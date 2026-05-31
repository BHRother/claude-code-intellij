package com.claudecode.mcp

/**
 * The three scopes Claude Code recognizes for MCP servers. Each maps 1:1 to a
 * `claude mcp add/remove --scope <value>` flag and to a physical storage
 * location (see [McpConfigReader]). We deliberately mirror the CLI's model
 * rather than inventing extra scopes — that keeps the mental model identical to
 * what `claude mcp` does on the command line.
 */
enum class McpScope(val cliValue: String, val display: String, val badge: String) {
    /** `<project>/.mcp.json` — checked into source control, shared with the team. */
    PROJECT("project", "Project — .mcp.json (shared)", "project"),

    /** `~/.claude.json` → `projects[<cwd>].mcpServers` — private to you, this project only. */
    LOCAL("local", "Local — this project, private to you", "local"),

    /** `~/.claude.json` → top-level `mcpServers` — available across all your projects. */
    USER("user", "User — all your projects", "user");

    companion object {
        fun fromCliValue(v: String?): McpScope? =
            entries.firstOrNull { it.cliValue.equals(v?.trim(), ignoreCase = true) }
    }
}
