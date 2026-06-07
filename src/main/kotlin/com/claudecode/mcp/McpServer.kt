package com.claudecode.mcp

import com.google.gson.JsonObject

/**
 * How a remote MCP server authenticates. Not stored separately — it's *inferred*
 * from the saved config (the source of truth) via [infer], so the Edit dialog and
 * the auth buttons always reflect what's actually configured.
 */
enum class McpAuthType(val display: String) {
    NONE("No Auth"),
    API_KEY("API Key / Token"),
    OAUTH("OAuth");

    companion object {
        /**
         * Derive the type from what's actually configured — nothing more:
         *   - an `Authorization` header → API Key (a token is set),
         *   - an OAuth client config (client id / callback port) → OAuth,
         *   - neither → No Auth.
         * We do NOT guess OAuth from a bare config: a server that needs auth but
         * has none configured reads as No Auth, and the UI flags "looks like it
         * needs authentication" from its connection status instead.
         */
        fun infer(server: McpServer): McpAuthType = when {
            server.headers.keys.any { it.equals("Authorization", ignoreCase = true) } -> API_KEY
            server.clientId.isNotBlank() || server.callbackPort != null -> OAUTH
            else -> NONE
        }
    }
}

/** MCP transport types, matching `claude mcp add --transport <value>`. */
enum class McpTransport(val cliValue: String) {
    STDIO("stdio"),
    SSE("sse"),
    HTTP("http");

    companion object {
        /**
         * Resolve a transport from a server definition's `type` field. When the
         * field is absent (common in hand-written `.mcp.json`), the caller infers
         * it from the shape (presence of `url` vs `command`) and passes that in.
         */
        fun fromTypeString(s: String?): McpTransport = when (s?.trim()?.lowercase()) {
            "sse" -> SSE
            "http" -> HTTP
            else -> STDIO
        }
    }
}

/**
 * A single MCP server definition as displayed in the management UI. Built by
 * [McpConfigReader] from the on-disk JSON (read-only); mutations are performed
 * through the `claude mcp` CLI (see McpCli), never by writing this back out.
 *
 * [raw] preserves the original JsonObject so we never lose fields we don't model.
 */
data class McpServer(
    val name: String,
    val scope: McpScope,
    val transport: McpTransport,
    // stdio
    val command: String = "",
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    // sse / http
    val url: String = "",
    val headers: Map<String, String> = emptyMap(),
    // optional OAuth client config. The secret is NEVER read from config (claude
    // stores it securely); it's only populated from the Add/Edit dialog and handed
    // to `claude mcp add` via the MCP_CLIENT_SECRET env var + --client-secret flag.
    val clientId: String = "",
    val clientSecret: String = "",
    val callbackPort: Int? = null,
    val raw: JsonObject? = null,
) {
    val isRemote: Boolean get() = transport == McpTransport.SSE || transport == McpTransport.HTTP

    /** Compact one-line summary for the list's detail column. */
    fun summary(): String = when (transport) {
        McpTransport.STDIO -> (listOf(command) + args).joinToString(" ").trim()
        McpTransport.SSE, McpTransport.HTTP -> url
    }
}

/**
 * Normalize an args text area into individual arguments. Each line is one
 * argument, but because users naturally type several space-separated tokens on
 * a single line (e.g. `-y @upstash/context7-mcp@latest`), any whitespace within
 * a line is also split — so the result is always one token per argument. Blank
 * lines / extra whitespace are dropped.
 */
fun splitMcpArgs(text: String): List<String> =
    text.lines().flatMap { line -> line.trim().split(Regex("\\s+")) }.filter { it.isNotEmpty() }
