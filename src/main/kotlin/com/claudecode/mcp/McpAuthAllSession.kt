package com.claudecode.mcp

import com.claudecode.settings.ClaudeSettings
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project

/**
 * Drives "Authenticate All" with a **warm-pool**: several servers are pre-warmed
 * in the background up to claude's "❯ 1. Authenticate" prompt and parked there,
 * so the cold start (spawn → /mcp → navigate) is paid *ahead* of the user. Then
 * exactly one server at a time is activated — its browser opens instantly — and
 * the moment that sign-in finishes (or is skipped) the next already-warmed server
 * is activated. The user perceives one initial wait, then rapid-fire sign-ins.
 *
 * Pool depth is [ClaudeSettings.mcpAuthWarmPoolSize] (configurable), and grows
 * adaptively up to [POOL_MAX] when the user finishes a sign-in before the next
 * server has warmed up — so a fast user isn't bottlenecked by warm-up. Bounding
 * the pool keeps concurrent claude processes (and the MCP connections each one
 * opens) in check; warming all N at once would be multiple GB for a big list.
 *
 * Activation is strictly sequential, so only one OAuth callback port is ever
 * bound at a time — no collision handling needed. Every state change is
 * marshalled onto the EDT so the dialog reads it without locking.
 *
 * FOLLOW-UP (not yet implemented) — recycle the PTY after a successful auth:
 * instead of killing a finished process, drive it Esc → /mcp list → a still
 * PENDING server → re-park, turning it into a warm slot without a fresh spawn.
 * Upside is narrow: warm-up is already hidden behind the previous sign-in, so
 * this saves spawn cost (CPU, not perceived time) and mainly helps a fast user
 * on a long list keep the pool full. Costs: driving a long-lived REPL back to
 * the list is more fragile than a clean process per server (stale scrollback,
 * the content-driven state machine reacting to old frames) and loses isolation
 * (one wedged worker would take down several servers). If built, do it as
 * best-effort: on any recycle-navigation hiccup, fall back to kill + spawn
 * fresh. Validate the base warm-pool in real use before adding this.
 */
class McpAuthAllSession(private val project: Project, servers: List<McpServer>) {

    enum class State(val display: String) {
        PENDING("⏳ queued"),
        WARMING("↻ warming up…"),
        READY("✓ ready — waiting to sign in"),
        RUNNING("▶ signing in…"),
        SUCCESS("✓ authenticated"),
        FAILED("✗ not authenticated"),
        SKIPPED("⊘ skipped"),
        REMOVED("— removed"),
    }

    inner class Item(val server: McpServer) {
        @Volatile var state: State = State.PENDING
        @Volatile var detail: String = ""
        /** Epoch-ms sign-in deadline while RUNNING (drives the countdown); null otherwise. */
        @Volatile var deadline: Long? = null
        @Volatile var handle: McpOAuthFlow.AuthHandle? = null
    }

    val items: List<Item> = servers.map { Item(it) }

    @Volatile var done: Boolean = false
        private set

    /** Called on the EDT whenever anything changes; the dialog re-renders. */
    var onChange: (() -> Unit)? = null

    /** One health-check source shared by drivers (off-EDT use only). */
    private val probe by lazy { McpStatusProbe(project.basePath) }

    /** Effective warm-pool depth; starts from settings, may grow adaptively. */
    private var poolSize: Int = runCatching { ClaudeSettings.getInstance().state.mcpAuthWarmPoolSize }
        .getOrDefault(DEFAULT_POOL).coerceIn(1, POOL_MAX)

    /** The server signing in right now (at most one), if any. */
    val running: Item? get() = items.firstOrNull { it.state == State.RUNNING }

    /** Sign-in deadline of the active server (for the header countdown). */
    val nextDeadline: Long? get() = running?.deadline

    /** Servers warmed-and-parked, ready to be activated instantly. */
    val readyCount: Int get() = items.count { it.state == State.READY }
    val warmingCount: Int get() = items.count { it.state == State.WARMING }
    val queuedCount: Int get() = items.count { it.state == State.PENDING }

    fun start() = onEdt { pump() }

    /**
     * Drop a server: removes it if not started, or stops its warm-up / sign-in
     * (killing the pre-warmed process) if it's live.
     */
    fun drop(item: Item) = onEdt {
        when (item.state) {
            State.PENDING -> { item.state = State.REMOVED; pump() }
            // cancel() ends the driver → onFinish(SKIPPED) → applyOutcome → pump.
            State.WARMING, State.READY, State.RUNNING -> item.handle?.cancel()
            else -> {}
        }
    }

    /** Drop everything still pending and stop every live attempt. */
    fun cancelAll() = onEdt {
        items.filter { it.state == State.PENDING }.forEach { it.state = State.REMOVED }
        val live = items.filter { it.handle != null }
        live.forEach { it.handle?.cancel() }
        if (live.isEmpty()) pump()
    }

    /**
     * Keep the warm pool topped up and one server activated. Re-runs on every
     * state change (warm-up ready, sign-in finished, skip, etc.).
     */
    private fun pump() {
        // 1. Top up the warm pool: spawn warm-ups until WARMING+READY hits poolSize.
        while (warmingCount + readyCount < poolSize) {
            val next = items.firstOrNull { it.state == State.PENDING } ?: break
            startWarmUp(next)
        }
        // 2. Activate the next ready server if none is signing in.
        if (running == null) {
            items.firstOrNull { it.state == State.READY }?.let { activate(it) }
        }
        // 3. Done when nothing is live and nothing is queued.
        val anyLive = items.any { it.state in LIVE_STATES }
        if (!anyLive && queuedCount == 0) {
            if (!done) { done = true; notifyDone() }
        }
        fire()
    }

    private fun startWarmUp(item: Item) {
        item.state = State.WARMING
        item.handle = McpOAuthFlow.spawnAuth(
            project,
            item.server,
            statusProbe = { name -> probe.status(name) },
            autoProceed = false,
            onReady = {
                onEdt { if (item.state == State.WARMING) { item.state = State.READY; pump() } }
            },
        ) { outcome ->
            onEdt {
                val wasRunning = item.state == State.RUNNING
                applyOutcome(item, outcome)
                // Adaptive: a sign-in finished but nothing was warmed up to take
                // its place → the user is out-pacing warm-up, so widen the pool.
                if (wasRunning && readyCount == 0 &&
                    items.any { it.state == State.PENDING || it.state == State.WARMING }
                ) {
                    poolSize = (poolSize + 1).coerceAtMost(POOL_MAX)
                }
                pump()
            }
        }
    }

    /** Activate a warmed-and-parked server: open its browser and start the sign-in. */
    private fun activate(item: Item) {
        item.state = State.RUNNING
        item.deadline = System.currentTimeMillis() + McpOAuthFlow.SIGN_IN_TIMEOUT_MS
        item.handle?.proceed()
    }

    private fun applyOutcome(item: Item, outcome: McpOAuthFlow.AuthOutcome) {
        item.state = when {
            outcome.ok -> State.SUCCESS
            outcome == McpOAuthFlow.AuthOutcome.SKIPPED -> State.SKIPPED
            else -> State.FAILED
        }
        item.detail = outcome.summary
        item.handle = null
        item.deadline = null
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
        /** Pool depth when settings can't be read. */
        const val DEFAULT_POOL = 4
        /** Hard ceiling on concurrent warm-ups (memory / MCP-connection storm guard). */
        const val POOL_MAX = 8
        private val LIVE_STATES = setOf(State.WARMING, State.READY, State.RUNNING)
    }
}
