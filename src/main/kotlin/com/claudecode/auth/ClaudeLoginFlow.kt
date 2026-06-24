package com.claudecode.auth

import com.claudecode.ClaudeConstants
import com.claudecode.diagnostics.DebugLog
import com.claudecode.session.ClaudeSession
import com.claudecode.settings.ClaudeSettings
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * Drives `claude auth login` — the headless sign-in command behind the `/login`
 * REPL command. The flow is a paste-code OAuth dance, not a localhost callback:
 *
 *   spawn `claude auth login [--claudeai|--console] [--email …]`
 *   → claude opens the browser and prints the authorize URL
 *   → user approves, the browser shows an authorization code
 *   → user pastes the code back → we write it to the process's stdin
 *   → claude exchanges it, stores the token, and exits 0.
 *
 * Unlike the MCP OAuth flow this needs **no** `script` PTY wrapper — `claude auth
 * login` reads the code from a plain stdin pipe and writes the URL to stdout, so
 * it works the same on macOS, Linux, and Windows.
 *
 * Callbacks fire on a background thread; the dialog marshals them to the EDT.
 */
class ClaudeLoginFlow private constructor(
    private val process: Process,
    private val onUrl: (String) -> Unit,
    private val onFinish: (Boolean, String?) -> Unit,
) {
    private val out: OutputStream = process.outputStream
    private val transcript = StringBuilder()
    @Volatile private var urlSeen = false
    @Volatile private var finished = false

    private fun start() {
        Thread({ runCatching { pump() }.onFailure { finish(false) } }, "claude-login-read")
            .apply { isDaemon = true; start() }
        Thread({
            if (!process.waitFor(WATCHDOG_MS, TimeUnit.MILLISECONDS)) {
                DebugLog.log("login", "watchdog firing — login didn't complete")
                finish(false)
            }
        }, "claude-login-watchdog").apply { isDaemon = true; start() }
    }

    /** Read stdout, surface the authorize URL as soon as it appears. */
    private fun pump() {
        val buf = ByteArray(16384)
        val input = process.inputStream
        while (true) {
            val n = try { input.read(buf) } catch (_: Throwable) { -1 }
            if (n < 0) break
            val s = String(buf, 0, n, Charsets.UTF_8)
            transcript.append(s)
            DebugLog.raw("login", s)
            if (!urlSeen) URL_RE.find(transcript.toString())?.value
                ?.trimEnd('.', ',', ')', '"', '\'')?.let { urlSeen = true; onUrl(it) }
        }
        // Stream closed = process exiting. Success iff it exited 0 (token stored).
        val ok = runCatching { process.waitFor(4, TimeUnit.SECONDS) }.getOrDefault(false) &&
            process.exitValue() == 0
        finish(ok)
    }

    /** Submit the authorization code the user pasted from the browser. */
    fun submitCode(code: String) {
        try {
            out.write((code.trim() + "\n").toByteArray(Charsets.UTF_8))
            out.flush()
            DebugLog.log("login", "submitted pasted code (${code.trim().length} chars)")
        } catch (t: Throwable) {
            DebugLog.log("login", "submit failed: ${t.message}")
        }
    }

    /** Abort an in-progress login (dialog cancelled). */
    fun cancel() {
        runCatching { process.destroyForcibly() }
        finish(false)
    }

    @Synchronized
    private fun finish(ok: Boolean) {
        if (finished) return
        finished = true
        DebugLog.log("login", "finish ok=$ok")
        runCatching { process.destroyForcibly() }
        runCatching { onFinish(ok, null) }
    }

    companion object {
        private val LOG = Logger.getInstance(ClaudeLoginFlow::class.java)
        private const val WATCHDOG_MS = 300_000L
        private val URL_RE = Regex("https?://[^\\s'\"]+")

        /**
         * Start `claude auth login`. [useConsole] picks Anthropic Console (API
         * billing) over the Claude subscription; [email] pre-populates the login
         * page. [onUrl] fires with the authorize URL; [onFinish] with the result
         * (and the freshly-read email, looked up by the caller). Returns null if
         * the process couldn't even start.
         */
        fun start(
            workDir: String?,
            useConsole: Boolean,
            email: String?,
            onUrl: (String) -> Unit,
            onFinish: (Boolean, String?) -> Unit,
        ): ClaudeLoginFlow? {
            val args = mutableListOf("auth", "login", if (useConsole) "--console" else "--claudeai")
            if (!email.isNullOrBlank()) { args += "--email"; args += email }
            val bin = resolveClaudeBinary()
            val process = try {
                val pb = ProcessBuilder(listOf(bin) + args)
                    .directory(File(workDir ?: System.getProperty("user.home")))
                    .redirectErrorStream(true)
                ClaudeSession.resolveShellPathPublic()?.let { pb.environment()["PATH"] = it }
                pb.start()
            } catch (t: Throwable) {
                LOG.info("claude auth login failed to start", t)
                DebugLog.log("login", "spawn failed: ${t.message}")
                onFinish(false, null)
                return null
            }
            DebugLog.log("login", "started: claude ${args.joinToString(" ")}")
            return ClaudeLoginFlow(process, onUrl, onFinish).also { it.start() }
        }

        private fun resolveClaudeBinary(): String {
            val configured = ClaudeSettings.getInstance().state.claudePath
                .ifBlank { ClaudeConstants.DEFAULT_CLI_PATH }
            return ClaudeSession.resolveClaudePathPublic(configured)
        }
    }
}
