package com.claudecode.auth

import com.claudecode.ClaudeConstants
import com.claudecode.session.ClaudeSession
import com.claudecode.settings.ClaudeSettings
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.diagnostic.Logger

/**
 * Thin wrapper over the `claude auth` CLI — the same command surface the `/login`
 * REPL command drives, but reachable headlessly: `claude auth status --json`,
 * `claude auth logout`. The interactive sign-in (browser + paste-code) is driven
 * separately by [ClaudeLoginFlow] because it needs to stream output and feed the
 * pasted code back to stdin.
 *
 * Every call blocks on a subprocess, so invoke off the EDT. Cross-platform: these
 * are plain `claude` subcommands, no PTY required (unlike the MCP OAuth flow).
 */
class ClaudeAuthCli(private val workDir: String?) {

    /** Parsed `claude auth status --json`. [loggedIn] is the only always-present field. */
    data class AuthStatus(
        val loggedIn: Boolean,
        val authMethod: String? = null,
        val email: String? = null,
        val orgName: String? = null,
        val subscriptionType: String? = null,
    ) {
        /** One-line human summary, e.g. "Claude Max · bruno@…". */
        fun describe(): String {
            if (!loggedIn) return "Not signed in"
            val plan = when (subscriptionType?.lowercase()) {
                "max" -> "Claude Max"
                "pro" -> "Claude Pro"
                "team", "enterprise" -> "Claude ${subscriptionType.replaceFirstChar { it.uppercase() }}"
                else -> authMethod ?: "Signed in"
            }
            return listOfNotNull(plan, email).joinToString(" · ")
        }
    }

    /** `claude auth status --json`; null if the command failed (e.g. claude missing). */
    fun status(timeoutMs: Int = STATUS_TIMEOUT_MS): AuthStatus? {
        val out = run(listOf("auth", "status", "--json"), timeoutMs)
        // `claude auth status` exits non-zero when logged out but still prints JSON.
        val json = out.stdout.trim().ifBlank { return null }
        return parseStatus(json)
    }

    /** `claude auth logout`. */
    fun logout(timeoutMs: Int = MUTATE_TIMEOUT_MS): Result =
        run(listOf("auth", "logout"), timeoutMs)

    data class Result(val success: Boolean, val stdout: String, val stderr: String) {
        fun message(): String = (stderr.ifBlank { stdout }).trim()
    }

    private fun run(args: List<String>, timeoutMs: Int): Result {
        val bin = resolveClaudeBinary()
        return try {
            val cmd = GeneralCommandLine(listOf(bin) + args)
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            if (!workDir.isNullOrBlank()) cmd.withWorkDirectory(workDir)
            val output = CapturingProcessHandler(cmd).runProcess(timeoutMs)
            if (output.isTimeout)
                Result(false, output.stdout, "Timed out after ${timeoutMs}ms: claude ${args.joinToString(" ")}")
            else
                Result(output.exitCode == 0, output.stdout, output.stderr)
        } catch (t: Throwable) {
            LOG.warn("claude auth invocation failed: ${args.joinToString(" ")}", t)
            Result(false, "", t.message ?: "Failed to run claude")
        }
    }

    private fun resolveClaudeBinary(): String {
        val configured = ClaudeSettings.getInstance().state.claudePath.ifBlank { ClaudeConstants.DEFAULT_CLI_PATH }
        val res = ClaudeSession.resolveClaudePathDiagnosed(configured)
        return if (res.resolved) res.resolvedPath else configured
    }

    companion object {
        private val LOG = Logger.getInstance(ClaudeAuthCli::class.java)
        private const val STATUS_TIMEOUT_MS = 12_000
        private const val MUTATE_TIMEOUT_MS = 15_000

        /** Parse the `claude auth status --json` object; tolerant of missing fields. */
        fun parseStatus(json: String): AuthStatus? = try {
            val o = com.google.gson.JsonParser.parseString(json).asJsonObject
            fun str(k: String) = o.get(k)?.takeIf { !it.isJsonNull }?.asString
            AuthStatus(
                loggedIn = o.get("loggedIn")?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
                authMethod = str("authMethod"),
                email = str("email"),
                orgName = str("orgName"),
                subscriptionType = str("subscriptionType"),
            )
        } catch (_: Exception) {
            null
        }
    }
}
