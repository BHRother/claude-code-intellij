package com.claudecode.mcp

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project

/**
 * Drives "Authenticate All" sequentially and exposes live state for the dialog:
 * each server's progress, the current sign-in deadline (for a countdown), and
 * controls to skip the current attempt or drop pending ones. Every state change
 * is marshalled onto the EDT so the dialog reads it without extra locking, and a
 * summary notification is posted when the run ends (so the result is visible even
 * if the dialog was closed).
 */
class McpAuthAllSession(private val project: Project, servers: List<McpServer>) {

    enum class State(val display: String) {
        PENDING("⏳ waiting"),
        RUNNING("▶ authenticating…"),
        SUCCESS("✓ authenticated"),
        FAILED("✗ not authenticated"),
        SKIPPED("⊘ skipped"),
        REMOVED("— removed"),
    }

    inner class Item(val server: McpServer) {
        @Volatile var state: State = State.PENDING
        @Volatile var detail: String = ""
    }

    val items: List<Item> = servers.map { Item(it) }

    /** Epoch-ms deadline of the current sign-in (for the countdown); null when idle. */
    @Volatile var currentDeadline: Long? = null
        private set

    @Volatile var done: Boolean = false
        private set

    /** Called on the EDT whenever anything changes; the dialog re-renders. */
    var onChange: (() -> Unit)? = null

    private var handle: McpOAuthFlow.AuthHandle? = null

    val current: Item? get() = items.firstOrNull { it.state == State.RUNNING }

    fun start() = onEdt { runNext() }

    /** Skip the server currently signing in (advances to the next). */
    fun skipCurrent() = onEdt { handle?.cancel() }

    /** Drop a not-yet-started server from the queue. */
    fun removePending(item: Item) = onEdt {
        if (item.state == State.PENDING) {
            item.state = State.REMOVED
            fire()
        }
    }

    /** Drop everything still pending and stop the current attempt. */
    fun cancelAll() = onEdt {
        items.filter { it.state == State.PENDING }.forEach { it.state = State.REMOVED }
        handle?.cancel()   // the current attempt (if any) finishes SKIPPED → runNext → done
        if (current == null) runNext()
    }

    private fun runNext() {
        val item = items.firstOrNull { it.state == State.PENDING }
        if (item == null) {
            currentDeadline = null
            handle = null
            if (!done) {
                done = true
                notifyDone()
            }
            fire()
            return
        }
        item.state = State.RUNNING
        currentDeadline = System.currentTimeMillis() + McpOAuthFlow.SIGN_IN_TIMEOUT_MS
        fire()
        handle = McpOAuthFlow.spawnAuth(project, item.server) { outcome ->
            onEdt {
                item.state = when {
                    outcome.ok -> State.SUCCESS
                    outcome == McpOAuthFlow.AuthOutcome.SKIPPED -> State.SKIPPED
                    else -> State.FAILED
                }
                item.detail = outcome.summary
                handle = null
                currentDeadline = null
                fire()
                runNext()
            }
        }
        // spawnAuth(...) == null means it already reported ERROR via the callback
        // above (which advances), so there's nothing extra to do here.
    }

    private fun notifyDone() {
        val ok = items.filter { it.state == State.SUCCESS }.map { it.server.name }
        val failed = items.filter { it.state == State.FAILED || it.state == State.SKIPPED }
        val tried = ok.size + failed.size
        val body = buildString {
            if (ok.isNotEmpty()) append("✓ Authenticated: ${ok.joinToString(", ")}<br/>")
            if (failed.isNotEmpty()) {
                append("Not authenticated:<br/>")
                failed.forEach { append("&nbsp;&nbsp;• <b>${it.server.name}</b> — ${it.state.display.removePrefix("✗ ").removePrefix("⊘ ")}${if (it.detail.isNotBlank()) " (${it.detail})" else ""}<br/>") }
                append("<br/>Token-based servers (e.g. GitHub) need an <b>API token</b> set in Edit.")
            }
        }
        NotificationGroupManager.getInstance().getNotificationGroup("Claude Code Tasks")
            ?.createNotification("Authenticate all — ${ok.size}/$tried authenticated", body,
                if (failed.isEmpty()) NotificationType.INFORMATION else NotificationType.WARNING)
            ?.notify(project)
    }

    private fun fire() {
        onChange?.invoke()
    }

    /** Always hop to the EDT (even if already on it) so callbacks never re-enter. */
    private fun onEdt(block: () -> Unit) =
        ApplicationManager.getApplication().invokeLater(block, ModalityState.any())
}
