package com.claudecode.mcp

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/**
 * Aggregates MCP server definitions from every place Claude Code stores them,
 * for **display only**. We never write these files — all mutations go through
 * the `claude mcp` CLI (see McpCli) so a parse hiccup can never truncate the
 * user's large, live `~/.claude.json`.
 *
 * Sources, in display order (project → local → user), matching Claude's scopes:
 *   - PROJECT: `<project>/.mcp.json` → `mcpServers`
 *   - LOCAL:   `~/.claude.json` → `projects[<cwd|canonical>].mcpServers`
 *   - USER:    `~/.claude.json` → top-level `mcpServers`
 *
 * Every file read is individually fault-tolerant: a missing or malformed file
 * contributes no servers rather than throwing.
 */
object McpConfigReader {

    fun read(projectBasePath: String?): List<McpServer> {
        val dotMcp = if (!projectBasePath.isNullOrBlank()) File(projectBasePath, ".mcp.json") else null
        return readFrom(projectBasePath, dotMcp, globalConfigFile())
    }

    /**
     * Testable core: aggregate from explicit files. [dotMcpFile] is the project
     * `.mcp.json`; [globalFile] is `~/.claude.json`. Either may be null/missing.
     */
    internal fun readFrom(projectBasePath: String?, dotMcpFile: File?, globalFile: File?): List<McpServer> {
        val out = mutableListOf<McpServer>()

        // PROJECT scope — <project>/.mcp.json
        if (dotMcpFile != null) {
            out += parseServerMap(mcpServersFromDotMcp(readJsonObject(dotMcpFile)), McpScope.PROJECT)
        }

        // LOCAL + USER scopes — both live in ~/.claude.json
        val root = if (globalFile != null) readJsonObject(globalFile) else null
        if (root != null) {
            if (!projectBasePath.isNullOrBlank()) {
                val projectEntry = root.getObject("projects")?.let { matchProjectEntry(it, projectBasePath) }
                out += parseServerMap(projectEntry?.getObject("mcpServers"), McpScope.LOCAL)
            }
            out += parseServerMap(root.getObject("mcpServers"), McpScope.USER)
        }

        return out
    }

    /**
     * Locate the user-global config. Honors `CLAUDE_CONFIG_DIR` (some users
     * relocate it); otherwise `~/.claude.json`. Both candidates are probed so we
     * read whichever actually exists.
     */
    fun globalConfigFile(): File {
        val custom = System.getenv("CLAUDE_CONFIG_DIR")?.takeIf { it.isNotBlank() }
        if (custom != null) {
            val inDir = File(custom, ".claude.json")
            if (inDir.exists()) return inDir
        }
        return File(System.getProperty("user.home"), ".claude.json")
    }

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    /** `.mcp.json` is normally `{ "mcpServers": {…} }`; tolerate a bare server map too. */
    private fun mcpServersFromDotMcp(root: JsonObject?): JsonObject? {
        if (root == null) return null
        root.getObject("mcpServers")?.let { return it }
        // Fallback: treat the whole object as a server map if its values look
        // like server definitions (object with command/url/type).
        val looksLikeServerMap = root.entrySet().any { (_, v) ->
            v.isJsonObject && v.asJsonObject.let { it.has("command") || it.has("url") || it.has("type") }
        }
        return if (looksLikeServerMap) root else null
    }

    private fun parseServerMap(map: JsonObject?, scope: McpScope): List<McpServer> {
        if (map == null) return emptyList()
        val list = mutableListOf<McpServer>()
        for ((name, el) in map.entrySet()) {
            if (!el.isJsonObject) continue
            runCatching { parseServer(name, el.asJsonObject, scope) }.getOrNull()?.let { list += it }
        }
        return list
    }

    private fun parseServer(name: String, obj: JsonObject, scope: McpScope): McpServer {
        val typeHint = obj.getString("type") ?: if (obj.has("url")) "http" else "stdio"
        val transport = McpTransport.fromTypeString(typeHint)
        return McpServer(
            name = name,
            scope = scope,
            transport = transport,
            command = obj.getString("command").orEmpty(),
            args = obj.getStringArray("args"),
            env = obj.getStringMap("env"),
            url = obj.getString("url").orEmpty(),
            headers = obj.getStringMap("headers"),
            clientId = obj.getString("clientId") ?: obj.getString("client_id").orEmpty(),
            callbackPort = obj.getString("callbackPort")?.toIntOrNull()
                ?: obj.getString("callback_port")?.toIntOrNull(),
            raw = obj,
        )
    }

    /**
     * Match the project's entry in `~/.claude.json`'s `projects` map. Claude
     * keys these by the **canonical** working directory (e.g. macOS resolves
     * `/var/...` → `/private/var/...`), so an exact string match against
     * `project.basePath` is not enough — we also compare canonical paths. This
     * is the bug the earlier attempt had, which hid CLI-added local servers.
     */
    private fun matchProjectEntry(projects: JsonObject, basePath: String): JsonObject? {
        projects.getObject(basePath)?.let { return it }
        val canonical = runCatching { File(basePath).canonicalPath }.getOrNull() ?: return null
        projects.getObject(canonical)?.let { return it }
        for ((key, value) in projects.entrySet()) {
            if (!value.isJsonObject) continue
            val keyCanonical = runCatching { File(key).canonicalPath }.getOrNull()
            if (keyCanonical == canonical) return value.asJsonObject
        }
        return null
    }

    // ------------------------------------------------------------------
    // Safe Gson access
    // ------------------------------------------------------------------

    private fun readJsonObject(file: File): JsonObject? {
        if (!file.isFile) return null
        return runCatching {
            JsonParser.parseString(file.readText()).asJsonObject
        }.getOrNull()
    }

    private fun JsonObject.getObject(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.getString(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.getStringArray(key: String): List<String> {
        val arr = get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return arr.mapNotNull { el: JsonElement -> el.takeIf { it.isJsonPrimitive }?.asString }
    }

    private fun JsonObject.getStringMap(key: String): Map<String, String> {
        val o = getObject(key) ?: return emptyMap()
        val m = LinkedHashMap<String, String>()
        for ((k, v) in o.entrySet()) {
            if (v.isJsonPrimitive) m[k] = v.asString
        }
        return m
    }
}
