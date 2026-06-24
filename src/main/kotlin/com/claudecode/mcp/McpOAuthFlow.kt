package com.claudecode.mcp

import com.claudecode.diagnostics.DebugLog
import com.claudecode.session.ClaudeSession
import com.claudecode.settings.ClaudeSettings
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * OAuth authentication for a remote (sse/http) MCP server, driven through an
 * interactive `claude` process we own end-to-end inside a pseudo-TTY.
 *
 * Why our own PTY instead of the IDE terminal: the OAuth dance (open browser →
 * localhost callback → token exchange → keychain storage) is owned by the
 * `claude` binary and is only reachable from its interactive `/mcp` menu — there
 * is no headless `claude mcp authenticate`, and `claude -p` can't initiate it.
 * Driving the *IDE's* terminal meant reflecting into a version-specific widget
 * API and firing keystrokes on fixed delays — unreliable. Here we spawn `claude`
 * ourselves (the same `script` PTY wrapper a chat session uses) and drive it as a
 * **content-driven state machine**: we read the actual screen and only act on what
 * it shows, so we don't fire keystrokes into the wrong place. The observed flow is:
 *
 *   startup → [trust-folder gate] → [approve new-MCP-server gate] → REPL ready
 *   → `/mcp` → a ↑/↓ list of servers (first pre-selected) → Enter on our server
 *   → detail view with "❯ 1. Authenticate" pre-selected → Enter → browser opens.
 *
 * We run hidden — the user finishes sign-in in the browser claude opens; claude
 * stores the token where `-p` sessions read it. Unix-only: it needs the `script`
 * PTY wrapper. On Windows the caller falls back to the IDE-terminal flow — see
 * [McpAuthLauncher].
 */
object McpOAuthFlow {
    private val LOG = Logger.getInstance(McpOAuthFlow::class.java)

    /** A wide PTY so long OAuth URLs don't get hard-wrapped in claude's TUI output. */
    private const val LAUNCH_PREFIX = "stty cols 220 rows 60 2>/dev/null; exec "

    /** True when this platform can host the own-PTY flow (needs `script`). */
    fun isSupported(): Boolean = !ClaudeSession.isWindows()

    /** Max time we wait for a single server's sign-in before giving up. */
    const val SIGN_IN_TIMEOUT_MS = 180_000L

    /** The result of one server's auth attempt — drives the feedback we show. */
    enum class AuthOutcome(val ok: Boolean, val summary: String) {
        SUCCESS(true, "authenticated"),
        NEEDS_TOKEN(false, "needs an API token (provider can’t do OAuth)"),
        MENU_FAILED(false, "couldn’t open claude’s /mcp menu"),
        NOT_IN_LIST(false, "not found in the /mcp list"),
        NO_AUTH_ACTION(false, "no Authenticate option offered"),
        TIMED_OUT(false, "sign-in wasn’t completed in time"),
        SKIPPED(false, "skipped"),
        ERROR(false, "unexpected error"),
    }

    /**
     * A running auth attempt. [cancel] stops it (Skip/Remove). [proceed] presses
     * Authenticate on a process that's been warmed up and parked at the menu —
     * used by the batch's warm-pool so activation is instant. In auto-proceed
     * mode (the single-server path) the driver authenticates on its own and
     * [proceed] is a no-op.
     */
    interface AuthHandle {
        fun cancel()
        fun proceed()
    }

    /**
     * Spawn the PTY flow for one server and return a handle (or null if it
     * couldn't even start — in which case [onFinish] has already fired ERROR).
     * Shared by the single [authenticate] path and the batch session.
     *
     * [statusProbe] lets a batch caller share one `claude mcp list` health-check
     * across several in-flight drivers (see [McpStatusProbe]); when null each
     * driver polls its own (the single-server path).
     *
     * When [autoProceed] is false the driver warms up to the Authenticate prompt,
     * fires [onReady], then **parks** until [AuthHandle.proceed] (or cancel) —
     * this is what lets the batch pre-warm several servers and open each browser
     * the instant the prior sign-in finishes, paying the cold start only once.
     * When true (default) it authenticates straight through, as before.
     */
    fun spawnAuth(
        project: Project,
        server: McpServer,
        statusProbe: ((String) -> Boolean)? = null,
        autoProceed: Boolean = true,
        onReady: () -> Unit = {},
        onFinish: (AuthOutcome) -> Unit,
    ): AuthHandle? {
        if (!isSupported()) { onFinish(AuthOutcome.ERROR); return null }
        val workDir = project.basePath ?: System.getProperty("user.home")
        val configured = ClaudeSettings.getInstance().state.claudePath
            .ifBlank { com.claudecode.ClaudeConstants.DEFAULT_CLI_PATH }
        val claudePath = ClaudeSession.resolveClaudePathPublic(configured)
        val command = buildCommand(claudePath)
        DebugLog.log("mcp-oauth", "authenticate '${server.name}' (${server.transport.cliValue}) in $workDir")
        DebugLog.log("mcp-oauth", "command: ${command.joinToString(" ")}")

        val process = try {
            val pb = ProcessBuilder(command)
                .directory(File(workDir))
                .redirectErrorStream(true)
            ClaudeSession.resolveShellPathPublic()?.let { pb.environment()["PATH"] = it }
            pb.environment()["TERM"] = "xterm-256color"
            pb.start()
        } catch (t: Throwable) {
            LOG.info("MCP OAuth PTY failed to start; caller will fall back", t)
            DebugLog.log("mcp-oauth", "spawn failed: ${t.message}")
            onFinish(AuthOutcome.ERROR)
            return null
        }
        val driver = Driver(server, workDir, process, statusProbe, autoProceed, onReady, onFinish)
        driver.start()
        return object : AuthHandle {
            override fun cancel() = driver.cancel()
            override fun proceed() = driver.proceed()
        }
    }

    /**
     * Spawn `claude` in our PTY and drive it toward the OAuth flow for [server].
     * Returns true if the process started (= a flow is underway and the caller
     * should NOT fall back). Returns false only when we couldn't even spawn.
     *
     * [onFinish] is invoked once the flow ends with the [AuthOutcome]. When the
     * caller passes null (single, interactive use) we attach a default reporter
     * that notifies the user of success/failure; [authenticateAll] passes its own
     * to chain + summarize. The !supported / spawn-failure paths report ERROR only
     * for batch callers (so a single caller can fall back to the terminal flow).
     */
    fun authenticate(project: Project, server: McpServer, onFinish: ((AuthOutcome) -> Unit)? = null): Boolean {
        if (!isSupported()) { onFinish?.invoke(AuthOutcome.ERROR); return false }
        val isBatch = onFinish != null
        val reporter = onFinish ?: { outcome -> reportSingle(project, server, outcome) }
        val handle = spawnAuth(project, server, onFinish = reporter)
        // The batch shows its own per-step progress, so skip the per-server banner.
        if (handle != null && !isBatch) notifyStarted(project, server)
        return handle != null
    }

    /** Notify the user of a single (non-batch) auth attempt's result. */
    private fun reportSingle(project: Project, server: McpServer, outcome: AuthOutcome) {
        if (outcome.ok) {
            notify(project, "Authenticated “${server.name}”",
                "It’s connected — new chat sessions can use it now.", NotificationType.INFORMATION)
        } else {
            notify(project, "Couldn’t authenticate “${server.name}”",
                failureBody(outcome), NotificationType.WARNING)
        }
    }

    /** Actionable explanation for a failed [outcome]. */
    private fun failureBody(outcome: AuthOutcome): String = when (outcome) {
        AuthOutcome.NEEDS_TOKEN ->
            "Its provider doesn’t support automatic OAuth. If it uses a <b>personal access token</b> " +
                "(e.g. GitHub), set the <b>API token</b> in the server’s Edit dialog instead of Authenticate. " +
                "If it really is OAuth, add a pre-registered <b>OAuth client ID + secret</b> there."
        AuthOutcome.TIMED_OUT ->
            "No sign-in was completed in time. Run Authenticate again and finish in the browser tab that opens."
        AuthOutcome.ERROR ->
            "An unexpected error occurred. Enable <b>Settings → Debug</b> and export the log to see details."
        else ->
            "Couldn’t drive claude’s <code>/mcp</code> menu for it (${outcome.summary}). " +
                "Enable <b>Settings → Debug</b> and export the log if it keeps happening."
    }

    private fun notify(project: Project, title: String, body: String, type: NotificationType) {
        ApplicationManager.getApplication().invokeLater {
            NotificationGroupManager.getInstance().getNotificationGroup("Claude Code Tasks")
                ?.createNotification(title, body, type)?.notify(project)
        }
    }

    /**
     * Authenticate a batch of remote servers via a **warm-pool** (see
     * [McpAuthAllSession]): several servers are pre-warmed to claude's
     * Authenticate prompt and parked in the background, then activated one at a
     * time so each browser opens instantly — the cold start is paid ahead of the
     * user, not per server. Activation is sequential, so only one OAuth callback
     * port is bound at a time. Unix-only (see [isSupported]).
     */
    fun authenticateAll(project: Project, servers: List<McpServer>) {
        val queue = servers.filter { it.isRemote }
        if (queue.isEmpty() || !isSupported()) return
        DebugLog.log("mcp-oauth", "authenticate-all: ${queue.size} servers: ${queue.joinToString { it.name }}")
        // A live, non-modal panel drives the queue and shows progress / lets the
        // user skip or drop servers — see McpAuthAllSession / McpAuthAllDialog.
        val session = McpAuthAllSession(project, queue)
        McpAuthAllDialog(project, session).show()
        session.start()
    }

    private fun buildCommand(claudePath: String): List<String> {
        val launch = LAUNCH_PREFIX + ClaudeSession.shellQuote(claudePath)
        // BSD (macOS): `script -q file command...`; util-linux: `script -q -c "cmd" file`.
        return if (ClaudeSession.isMacOS())
            listOf("script", "-q", "/dev/null", "/bin/sh", "-c", launch)
        else
            listOf("script", "-q", "-c", launch, "/dev/null")
    }

    private fun notifyStarted(project: Project, server: McpServer) {
        val group = NotificationGroupManager.getInstance().getNotificationGroup("Claude Code Tasks") ?: return
        group.createNotification(
            "Authenticating MCP server “${server.name}”",
            "Signing in to <b>${server.name}</b> — your browser will open to finish (this can take a few " +
                "seconds to start). Once you approve access, new chat sessions use the server automatically. " +
                "No terminal needed; you can keep working.",
            NotificationType.INFORMATION,
        ).notify(project)
    }

    /**
     * Reads the PTY frame-by-frame and walks claude through the interactive flow,
     * pacing every step off what the screen actually shows.
     */
    private class Driver(
        private val server: McpServer,
        private val workDir: String,
        private val process: Process,
        private val statusProbe: ((String) -> Boolean)?,
        private val autoProceed: Boolean,
        private val onReady: () -> Unit,
        private val onFinish: (AuthOutcome) -> Unit,
    ) {
        private val input = process.inputStream
        private val out: OutputStream = process.outputStream
        private val transcript = StringBuilder()   // all stripped output (success scan + debug)
        private val cli by lazy { McpCli(workDir) }
        @Volatile private var browserOpened = false
        @Volatile private var finished = false
        @Volatile private var skipped = false
        // Set when claude reports the provider can't do OAuth (e.g. GitHub, which
        // is token-based) — surfaced to the user so they don't just see nothing.
        @Volatile private var authError: String? = null
        // Park gate: opened by proceed() (authenticate now) or cancel() (skip).
        // `proceeded` distinguishes the two once the latch releases.
        private val gate = java.util.concurrent.CountDownLatch(1)
        @Volatile private var proceeded = false
        @Volatile private var parked = false

        fun start() {
            Thread({
                runCatching { drive() }.onFailure {
                    DebugLog.log("mcp-oauth", "driver error: ${it.message}")
                    finish(AuthOutcome.ERROR)
                }
            }, "mcp-oauth-drive").apply { isDaemon = true; start() }
            // Warm-up watchdog: if we never even reach the parked/authenticating
            // state within the cap, give up. Once parked we wait indefinitely for
            // the user (no timeout); the sign-in clock starts at proceed().
            Thread({
                sleep(WARMUP_WATCHDOG_MS)
                if (!finished && !parked && !proceeded) {
                    DebugLog.log("mcp-oauth", "warm-up watchdog firing for '${server.name}'")
                    finish(AuthOutcome.MENU_FAILED)
                }
            }, "mcp-oauth-warm-watchdog").apply { isDaemon = true; start() }
        }

        /** Skip this server: stop now and report SKIPPED (advances the batch). */
        fun cancel() {
            skipped = true
            gate.countDown()   // release a parked warm-up so it can unwind
            finish(AuthOutcome.SKIPPED)
        }

        /** Activate a parked (warmed-up) server: press Authenticate and sign in. */
        fun proceed() {
            if (finished || skipped) return
            proceeded = true
            gate.countDown()
        }

        private fun drive() {
            warmUp() ?: return          // null = warm-up already finished (failure)
            if (autoProceed) {
                proceeded = true        // single-server path authenticates straight through
            } else {
                parked = true
                onReady()               // tell the batch this server is ready to activate
                gate.await()            // park until proceed()/cancel()
            }
            if (skipped || finished) return finish(AuthOutcome.SKIPPED)
            authenticate()
        }

        /**
         * Steps 1–4: drive claude to the server's detail view with
         * "❯ 1. Authenticate" pre-selected. Returns Unit on success, or null when
         * it already terminated via [finish] (gate/onboarding/menu/list failure).
         */
        private fun warmUp(): Unit? {
            // 1. Wait for the REPL, clearing onboarding gates (trust folder /
            //    approve new MCP server) as they appear. Each decision is made on
            //    a FRESH settled frame so we never react to stale scrollback.
            var frame = settle(quietMs = 900, capMs = 14_000)
            var ready = false
            for (i in 0 until 10) {
                if (has(frame, "for shortcuts")) { ready = true; break }
                frame = when {
                    has(frame, "trust this folder") -> { send(ENTER); settle(900, 8_000) }
                    has(frame, "new mcp server")    -> { send(ENTER); settle(900, 8_000) }
                    else                            -> settle(900, 6_000)
                }
            }
            DebugLog.log("mcp-oauth", "repl ready=$ready; tail=${tail(frame)}")

            // 2. Open the MCP menu (one retry).
            var menu = ""
            for (i in 0 until 2) {
                send("/mcp"); sleep(150); send(ENTER)
                menu = settle(800, 9_000)
                if (has(menu, "to navigate", "manage mcp server")) break
            }
            if (!has(menu, "to navigate", "manage mcp server")) {
                DebugLog.log("mcp-oauth", "MCP menu did not open; tail=${tail(menu)}")
                finish(AuthOutcome.MENU_FAILED); return null
            }

            // 3. Step-and-verify navigate the ↑/↓ list to our server (first item is
            //    pre-selected; ↓ moves between entries). Re-read after each step.
            var cur = menu
            var reached = false
            for (i in 0 until 14) {
                if (cursorOn(cur, server.name)) { reached = true; break }
                send(DOWN); cur = settle(450, 4_000)
            }
            DebugLog.log("mcp-oauth", "navigated to '${server.name}'=$reached")
            if (!reached) { finish(AuthOutcome.NOT_IN_LIST); return null }

            // 4. Open the server detail; "❯ 1. Authenticate" is pre-selected there.
            send(ENTER)
            val detail = settle(800, 8_000)
            // If claude already reported it can't OAuth this provider (e.g. a
            // misconfigured token-based server), bail now instead of waiting.
            if (authError != null) { finish(AuthOutcome.NEEDS_TOKEN); return null }
            if (!has(detail, "authenticate")) {
                DebugLog.log("mcp-oauth", "no Authenticate action; tail=${tail(detail)}")
                finish(AuthOutcome.NO_AUTH_ACTION); return null
            }
            DebugLog.log("mcp-oauth", "warmed up & parked at Authenticate for '${server.name}'")
            return Unit
        }

        /**
         * Steps 5–6: press Authenticate (claude opens the browser), then wait for
         * sign-in. A sign-in watchdog bounds this phase — distinct from warm-up,
         * since a server may sit parked for a long time before the user gets to it.
         */
        private fun authenticate() {
            Thread({
                sleep(WATCHDOG_MS)
                if (!finished) {
                    DebugLog.log("mcp-oauth", "sign-in watchdog firing for '${server.name}'")
                    finish(AuthOutcome.TIMED_OUT)
                }
            }, "mcp-oauth-signin-watchdog").apply { isDaemon = true; start() }

            // 5. Trigger it — claude opens the browser to finish OAuth.
            send(ENTER)
            DebugLog.log("mcp-oauth", "authenticate triggered for '${server.name}'")

            // 6. Wait for sign-in. Success is confirmed authoritatively by polling
            //    `claude mcp list` (the same health check the UI's Refresh uses) —
            //    the moment the server flips to Connected we advance, instead of
            //    waiting out a timeout while scraping the TUI for a success string.
            //    Because that poll proves the token is already persisted, we can
            //    then tear down promptly.
            val ok = awaitOutcome(SIGN_IN_TIMEOUT_MS)
            finish(when {
                ok -> AuthOutcome.SUCCESS
                authError != null -> AuthOutcome.NEEDS_TOKEN   // provider can't OAuth
                else -> AuthOutcome.TIMED_OUT                  // user didn't finish sign-in
            })
        }

        // ── PTY plumbing ──────────────────────────────────────────────────

        /** Read until the screen is quiet for [quietMs] (or [capMs] elapses); return the stripped frame. */
        private fun settle(quietMs: Long, capMs: Long): String {
            val frame = StringBuilder()
            val buf = ByteArray(65536)
            var lastData = now()
            val deadline = now() + capMs
            while (now() < deadline) {
                val avail = try { input.available() } catch (_: Throwable) { 0 }
                if (avail > 0) {
                    val n = try { input.read(buf, 0, minOf(buf.size, avail)) } catch (_: Throwable) { -1 }
                    if (n < 0) break
                    val s = stripAnsi(String(buf, 0, n, Charsets.UTF_8))
                    frame.append(s)
                    transcript.append(s)
                    DebugLog.raw("pty", s)
                    maybeOpenBrowser()
                    checkFailure()
                    lastData = now()
                } else {
                    if (now() - lastData >= quietMs) break
                    sleep(80)
                }
            }
            return frame.toString()
        }

        private fun send(keys: String) {
            try {
                DebugLog.log("mcp-oauth", "send ${keys.replace("\r", "\\r").replace("\u001B", "\\e")}")
                out.write(keys.toByteArray(Charsets.UTF_8))
                out.flush()
            } catch (t: Throwable) {
                DebugLog.log("mcp-oauth", "send failed: ${t.message}")
            }
        }

        /** Set [authError] as soon as a provider-can't-OAuth message appears anywhere. */
        private fun checkFailure() {
            if (authError != null) return
            val t = transcript.toString().lowercase()
            FAILURE_MARKERS.firstOrNull { it in t }?.let {
                authError = it
                DebugLog.log("mcp-oauth", "detected auth-incompatible (${server.name}): $it")
            }
        }

        private fun awaitOutcome(timeoutMs: Long): Boolean {
            val deadline = now() + timeoutMs
            // First poll a bit in so the user has time to start sign-in; the TUI
            // markers below also trigger an immediate poll when they appear.
            var nextPoll = now() + FIRST_POLL_MS
            while (now() < deadline && !finished) {
                if (authError != null) return false   // provider can't OAuth — stop now
                val frame = settle(700, 3_000).lowercase()
                // A TUI success hint → poll right away to confirm authoritatively.
                val hint = SUCCESS_MARKERS.any { it in frame } ||
                    ("authenticated" in frame && "not authenticated" !in frame)
                FAILURE_MARKERS.firstOrNull { it in frame }?.let { authError = it; return false }
                if (hint || now() >= nextPoll) {
                    if (pollConnected()) return true
                    nextPoll = now() + POLL_INTERVAL_MS
                }
            }
            // Timed out: accept a TUI success string as a best-effort fallback.
            return sawSuccess()
        }

        /** Authoritative success check: does `claude mcp list` now report Connected? */
        private fun pollConnected(): Boolean {
            // A shared probe (batch) coalesces the health-check across drivers;
            // the single-server path falls back to this driver's own CLI.
            val connected = statusProbe?.invoke(server.name)
                ?: runCatching { cli.list()[server.name] == McpServerStatus.CONNECTED }.getOrDefault(false)
            if (connected) DebugLog.log("mcp-oauth", "poll: '${server.name}' is connected")
            return connected
        }

        private fun sawSuccess(): Boolean {
            val t = transcript.toString().lowercase()
            return SUCCESS_MARKERS.any { it in t }
        }

        private fun maybeOpenBrowser() {
            if (browserOpened) return
            val text = transcript.toString()
            val url = URL_RE.find(text)?.value?.trimEnd('.', ',', ')', '"', '\'') ?: return
            val lower = text.lowercase()
            val authish = listOf("auth", "oauth", "authorize", "callback").any { it in url.lowercase() }
            val wantsManual = MANUAL_HINTS.any { it in lower }
            // Only open it ourselves when claude couldn't (it normally shells out
            // to the OS opener). Avoids a duplicate browser tab in the common case.
            if (authish && wantsManual) {
                browserOpened = true
                DebugLog.log("mcp-oauth", "opening browser manually for $url")
                runCatching { BrowserUtil.browse(url) }
            }
        }

        @Synchronized
        private fun finish(outcome: AuthOutcome) {
            if (finished) return
            finished = true
            DebugLog.log("mcp-oauth", "finish: ${server.name} → $outcome")
            if (skipped) {
                // User hit Skip — tear down immediately, no graceful dance.
                runCatching { process.destroyForcibly() }
            } else {
                // Quit claude cleanly so it flushes state. Success is already
                // poll-confirmed (token persisted), so the waits are short. Esc
                // leaves any menu; double Ctrl-C is claude's quit; Ctrl-D is a
                // backstop; destroyForcibly only as a last resort.
                runCatching {
                    send(ESC); sleep(150); send(ESC); sleep(250)
                    send(CTRL_C); sleep(300); send(CTRL_C)
                }
                if (!waitExit(6)) {
                    runCatching { send(CTRL_D) }
                    if (!waitExit(2)) runCatching { process.destroyForcibly() }
                }
            }
            DebugLog.log("mcp-oauth", "process alive after quit=${process.isAlive}")
            runCatching { onFinish(outcome) }
        }

        private fun waitExit(seconds: Long): Boolean =
            runCatching { process.waitFor(seconds, TimeUnit.SECONDS) }.getOrDefault(false)

        /** Does some "❯"-cursor line name our target server? */
        private fun cursorOn(frame: String, name: String): Boolean {
            val target = name.lowercase()
            return frame.split("\n").any { it.contains("❯") && it.lowercase().contains(target) }
        }

        private fun tail(frame: String): String =
            frame.replace(Regex("\\s+"), " ").takeLast(280)

        companion object {
            // Hard per-server cap (a bit above the 180s sign-in wait + graceful
            // quit) so one slow/stuck server can never block the rest of a batch.
            private const val WATCHDOG_MS = 210_000L
            // Warm-up cap: time to reach the parked-at-Authenticate state. Once
            // parked we wait indefinitely for the user, so this only guards the
            // spawn → /mcp → navigate phase, which is normally a few seconds.
            private const val WARMUP_WATCHDOG_MS = 45_000L
            // Status polling: first check after a short head-start, then steadily.
            // Kept tight so an instant sign-in is confirmed quickly; the shared
            // probe (batch) coalesces overlapping polls into one CLI call, so a
            // lower floor here doesn't multiply subprocess cost across drivers.
            private const val FIRST_POLL_MS = 2_500L
            private const val POLL_INTERVAL_MS = 3_000L

            private const val ENTER = "\r"
            private const val DOWN = "\u001B[B"   // ANSI cursor-down
            private const val ESC = "\u001B"
            private const val CTRL_C = "\u0003"
            private const val CTRL_D = "\u0004"

            private val URL_RE = Regex("https?://[^\\s'\"]+")
            // Unambiguous only — must NOT match "not authenticated".
            private val SUCCESS_MARKERS = listOf(
                "authentication successful", "successfully authenticated", "auth successful",
            )
            // claude's wording when the provider can't do automatic OAuth.
            private val FAILURE_MARKERS = listOf(
                "does not support dynamic client registration",
                "incompatible auth server",
                "sdk auth failed",
            )
            private val MANUAL_HINTS = listOf(
                "couldn't open", "could not open", "open the following", "open this url",
                "paste this url", "visit the following", "manually open",
            )

            // CSI (ESC [ … final), OSC (ESC ] … BEL/ST), then any stray ESC/BEL.
            private val ANSI_CSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")
            private val ANSI_OSC = Regex("\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)")

            private fun now() = System.currentTimeMillis()
            private fun sleep(ms: Long) = Thread.sleep(ms)

            private fun stripAnsi(s: String): String = s
                .replace(ANSI_OSC, "")
                .replace(ANSI_CSI, "")
                .replace("\u001B", "")
                .replace("\u0007", "")

            /** True if [frame] contains any of [subs] (whitespace- and case-insensitive). */
            private fun has(frame: String, vararg subs: String): Boolean {
                val v = frame.replace(" ", "").lowercase()
                return subs.any { v.contains(it.replace(" ", "").lowercase()) }
            }
        }
    }
}
