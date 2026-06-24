package com.claudecode.mcp

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project

/**
 * Drives "Authenticate All" as a **bounded-concurrency pipeline** and exposes
 * live state for the dialog. Up to [MAX_CONCURRENT] servers sign in at once: as
 * soon as one finishes, the next pending server's flow starts — so the spawn /
 * navigate overhead of later servers overlaps the browser sign-in wait of the
 * earlier ones, instead of running strictly one-after-another.
 *
 * Concurrency is capped (not unbounded) so the user gets a steady cadence of
 * browser tabs rather than all of them at once. Two servers that pin the **same
 * fixed OAuth callback port** are never run together (they'd collide on the
 * loopback redirect); everything else — claude's default dynamic per-process
 * callback port — is safe to overlap. All in-flight drivers share one
 * [McpStatusProbe] so the success poll is a single `claude mcp list` per window.
 *
 * Every state change is marshalled onto the EDT so the dialog reads it without
 * extra locking, and a summary notification is posted when the run ends.
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
        /** Epoch-ms sign-in deadline while RUNNING (drives this row's countdown); null otherwise. */
        @Volatile var deadline: Long? = null
        @Volatile var handle: McpOAuthFlow.AuthHandle? = null
    }

    val items: List<Item> = servers.map { Item(it) }

    @Volatile var done: Boolean = false
        private set

    /** Called on the EDT whenever anything changes; the dialog re-renders. */
    var onChange: (() -> Unit)? = null

    /** One health-check source shared by all in-flight drivers (off-EDT use only). */
    private val probe by lazy { McpStatusProbe(project.basePath) }

    /** Servers currently signing in (0..[MAX_CONCURRENT]). */
    val running: List<Item> get() = items.filter { it.state == State.RUNNING }

    /** The soonest sign-in deadline among in-flight servers (for the header countdown). */
    val nextDeadline: Long? get() = running.mapNotNull { it.deadline }.minOrNull()

    fun start() = onEdt { pump() }

    /** Stop a server that's currently signing in (advances the pipeline). */
    fun skipRunning(item: Item) = onEdt {
        if (item.state == State.RUNNING) item.handle?.cancel()
    }

    /** Drop a not-yet-started server from the queue. */
    fun removePending(item: Item) = onEdt {
        if (item.state == State.PENDING) {
            item.state = State.REMOVED
            pump()
        }
    }

    /** Drop everything still pending and stop every in-flight attempt. */
    fun cancelAll() = onEdt {
        items.filter { it.state == State.PENDING }.forEach { it.state = State.REMOVED }
        val live = running
        live.forEach { it.handle?.cancel() }   // each finishes SKIPPED → pump → done
        if (live.isEmpty()) pump()
    }

    /**
     * Start as many pending servers as the concurrency cap and port-collision
     * rules allow, then report completion when nothing is left in flight.
     */
    private fun pump() {
        while (running.size < MAX_CONCURRENT) {
            val next = nextStartable() ?: break
            startItem(next)
        }
        if (running.isEmpty() && items.none { it.state == State.PENDING }) {
            if (!done) {
                done = true
                notifyDone()
            }
        }
        fire()
    }

    /**
     * The next pending server we may start now: skipped if it pins a fixed
     * callback port already held by an in-flight server (they'd collide on the
     * loopback redirect). Such a server starts once the holder finishes.
     */
    private fun nextStartable(): Item? {
        val busyPorts = running.mapNotNull { it.server.callbackPort }.toSet()
        return items.firstOrNull {
            it.state == State.PENDING &&
                (it.server.callbackPort == null || it.server.callbackPort !in busyPorts)
        }
    }

    private fun startItem(item: Item) {
        item.state = State.RUNNING
        item.deadline = System.currentTimeMillis() + McpOAuthFlow.SIGN_IN_TIMEOUT_MS
        // onFinish always re-posts via onEdt → invokeLater, so even a synchronous
        // spawn failure can't re-enter pump() inside this loop.
        item.handle = McpOAuthFlow.spawnAuth(
            project,
            item.server,
            statusProbe = { name -> probe.isConnected(name) },
        ) { outcome ->
            onEdt {
                item.state = when {
                    outcome.ok -> State.SUCCESS
                    outcome == McpOAuthFlow.AuthOutcome.SKIPPED -> State.SKIPPED
                    else -> State.FAILED
                }
                item.detail = outcome.summary
                item.handle = null
                item.deadline = null
                pump()   // fill the freed slot and/or finish
            }
        }
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

    companion object {
        /** Max servers signing in at once — a steady tab cadence, not all-at-once. */
        const val MAX_CONCURRENT = 3
    }
}
