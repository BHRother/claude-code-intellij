package com.claudecode.mcp

import com.claudecode.ClaudeConstants
import com.claudecode.session.ClaudeSession
import com.claudecode.settings.ClaudeSettings
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.diagnostic.Logger

/**
 * Thin wrapper over the `claude mcp` CLI. All **mutations** (add/remove) and the
 * **status** read go through here so we never hand-edit `~/.claude.json`.
 *
 * Every method blocks on a subprocess and must be called off the EDT (the
 * caller wraps these in a pooled-thread task). The working directory is the
 * project root so the CLI resolves the same local/project/user scopes the
 * embedded `claude -p` sessions see.
 */
class McpCli(private val projectBasePath: String?) {

    data class Result(val success: Boolean, val stdout: String, val stderr: String) {
        /** Combined, trimmed message suitable for an error dialog. */
        fun message(): String = (stderr.ifBlank { stdout }).trim()
    }

    /** `claude mcp list` parsed into name → status (health-checked by the CLI). */
    fun list(timeoutMs: Int = LIST_TIMEOUT_MS): Map<String, McpServerStatus> {
        val out = run(listOf("mcp", "list"), timeoutMs)
        if (!out.success) return emptyMap()
        return parseListOutput(out.stdout)
    }

    /**
     * Add a server. Prefers `claude mcp add-json` (the whole definition travels
     * as one JSON arg, so there's no variadic-flag ordering to get wrong). The
     * only thing add-json can't express is the OAuth `--client-id` /
     * `--callback-port` pre-registration, so for those we fall back to the
     * flag-based `claude mcp add`. Fails if the name already exists in scope.
     */
    fun add(server: McpServer, timeoutMs: Int = MUTATE_TIMEOUT_MS): Result {
        val needsOauthFlags = server.isRemote &&
            (server.clientId.isNotBlank() || server.callbackPort != null || server.clientSecret.isNotBlank())
        val args = if (needsOauthFlags) buildAddArgs(server) else buildAddJsonArgs(server)
        // The secret never travels on the command line — claude reads it from
        // MCP_CLIENT_SECRET when `--client-secret` is present (see buildAddArgs).
        val env = if (server.clientSecret.isNotBlank())
            mapOf("MCP_CLIENT_SECRET" to server.clientSecret) else emptyMap()
        return run(args, timeoutMs, env)
    }

    /** `claude mcp remove <name> -s <scope>`. */
    fun remove(name: String, scope: McpScope, timeoutMs: Int = MUTATE_TIMEOUT_MS): Result =
        run(listOf("mcp", "remove", name, "-s", scope.cliValue), timeoutMs)

    /**
     * Edit = remove + re-add (the CLI refuses to overwrite an existing name).
     * If the re-add fails we roll back by restoring [previous], so a failed edit
     * never silently drops the server.
     */
    fun edit(previous: McpServer, updated: McpServer): Result {
        val removed = remove(previous.name, previous.scope)
        if (!removed.success) return removed
        val added = add(updated)
        if (!added.success) {
            // Best-effort rollback to the prior definition.
            add(previous)
        }
        return added
    }

    // ------------------------------------------------------------------

    private fun run(args: List<String>, timeoutMs: Int, env: Map<String, String> = emptyMap()): Result {
        val bin = resolveClaudeBinary()
        return try {
            val cmd = GeneralCommandLine(listOf(bin) + args)
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            if (env.isNotEmpty()) cmd.withEnvironment(env)
            if (!projectBasePath.isNullOrBlank()) cmd.withWorkDirectory(projectBasePath)
            val output = CapturingProcessHandler(cmd).runProcess(timeoutMs)
            if (output.isTimeout) {
                Result(false, output.stdout, "Timed out after ${timeoutMs}ms running: claude ${args.joinToString(" ")}")
            } else {
                Result(output.exitCode == 0, output.stdout, output.stderr)
            }
        } catch (t: Throwable) {
            LOG.warn("claude mcp invocation failed: ${args.joinToString(" ")}", t)
            Result(false, "", t.message ?: "Failed to run claude")
        }
    }

    private fun resolveClaudeBinary(): String {
        val configured = ClaudeSettings.getInstance().state.claudePath.ifBlank { ClaudeConstants.DEFAULT_CLI_PATH }
        val res = ClaudeSession.resolveClaudePathDiagnosed(configured)
        return if (res.resolved) res.resolvedPath else configured
    }

    companion object {
        private val LOG = Logger.getInstance(McpCli::class.java)
        private const val LIST_TIMEOUT_MS = 30_000
        private const val MUTATE_TIMEOUT_MS = 20_000
        private val GSON = com.google.gson.Gson()

        /** `claude mcp add-json <name> <json> -s <scope>` — robust, order-free. */
        fun buildAddJsonArgs(server: McpServer): List<String> =
            listOf("mcp", "add-json", server.name, buildServerJson(server), "-s", server.scope.cliValue)

        /**
         * Serialize a server to the JSON shape `claude mcp add-json` expects:
         *   stdio → { "type": "stdio", "command", "args"?, "env"? }
         *   http/sse → { "type": "http"|"sse", "url", "headers"? }
         * Empty collections are omitted to keep the definition clean.
         */
        fun buildServerJson(server: McpServer): String {
            val obj = com.google.gson.JsonObject()
            obj.addProperty("type", server.transport.cliValue)
            when (server.transport) {
                McpTransport.STDIO -> {
                    obj.addProperty("command", server.command)
                    if (server.args.isNotEmpty()) {
                        val arr = com.google.gson.JsonArray()
                        server.args.forEach { arr.add(it) }
                        obj.add("args", arr)
                    }
                    if (server.env.isNotEmpty()) obj.add("env", toJsonObject(server.env))
                }
                McpTransport.SSE, McpTransport.HTTP -> {
                    obj.addProperty("url", server.url)
                    if (server.headers.isNotEmpty()) obj.add("headers", toJsonObject(server.headers))
                }
            }
            return GSON.toJson(obj)
        }

        private fun toJsonObject(map: Map<String, String>): com.google.gson.JsonObject {
            val o = com.google.gson.JsonObject()
            map.forEach { (k, v) -> o.addProperty(k, v) }
            return o
        }

        /**
         * Build the argument list for `claude mcp add`, excluding the binary.
         *
         * Ordering is load-bearing because `--header`/`--env` are **variadic**
         * (`<header...>` in the CLI help) — they swallow every following token.
         * So the positional `<commandOrUrl>` must never sit *after* a variadic:
         *   - remote (http/sse): put the URL right after the name, before `-H`
         *     (matches the CLI's own examples). Otherwise `-H` eats the URL and
         *     the CLI reports `missing required argument 'commandOrUrl'`.
         *   - stdio: terminate option parsing with `--`, then command + args, so
         *     a variadic `-e` and a command starting with `-` are both safe.
         */
        fun buildAddArgs(server: McpServer): List<String> {
            val args = mutableListOf("mcp", "add", server.name)
            when (server.transport) {
                McpTransport.STDIO -> {
                    args += listOf("-s", server.scope.cliValue, "-t", server.transport.cliValue)
                    server.env.forEach { (k, v) -> args += listOf("-e", "$k=$v") }
                    args += "--"
                    args += server.command
                    args += server.args
                }
                McpTransport.SSE, McpTransport.HTTP -> {
                    args += server.url // 2nd positional — must precede variadic -H
                    args += listOf("-s", server.scope.cliValue, "-t", server.transport.cliValue)
                    server.headers.forEach { (k, v) -> args += listOf("-H", "$k: $v") }
                    if (server.clientId.isNotBlank()) args += listOf("--client-id", server.clientId)
                    // Boolean flag: claude takes the value from MCP_CLIENT_SECRET
                    // (set in add()). Only emitted when we actually have a secret,
                    // otherwise it would block on an interactive prompt.
                    if (server.clientSecret.isNotBlank()) args += "--client-secret"
                    server.callbackPort?.let { args += listOf("--callback-port", it.toString()) }
                }
            }
            return args
        }

        /**
         * Parse `claude mcp list` output. Each server line looks like:
         *   `name: npx -y pkg@latest - ✓ Connected`
         * i.e. `<name>: <commandOrUrl> - <status>`. Header/footer lines
         * ("Checking MCP server health…", "No MCP servers configured.") are
         * skipped. Status is taken from the LAST " - " so a command containing
         * " - " doesn't confuse it.
         */
        fun parseListOutput(stdout: String): Map<String, McpServerStatus> {
            val result = LinkedHashMap<String, McpServerStatus>()
            for (rawLine in stdout.lines()) {
                val line = rawLine.trim()
                if (line.isEmpty()) continue
                val colon = line.indexOf(": ")
                if (colon <= 0) continue
                val name = line.substring(0, colon).trim()
                if (name.isEmpty() || name.contains(' ')) continue // skip prose lines
                val rest = line.substring(colon + 2)
                val dash = rest.lastIndexOf(" - ")
                val statusText = if (dash >= 0) rest.substring(dash + 3) else rest
                result[name] = McpServerStatus.fromListStatusText(statusText)
            }
            return result
        }
    }
}
