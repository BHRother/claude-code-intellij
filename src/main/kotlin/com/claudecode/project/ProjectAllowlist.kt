package com.claudecode.project

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/**
 * Reads and writes `<project>/.claude/settings.local.json`, which Claude
 * Code reads to apply per-project tool permissions in addition to the
 * --permission-mode flag.
 *
 * Schema (only the parts we touch):
 * ```
 * {
 *   "permissions": {
 *     "allow": ["Bash(npm install)", "Edit", ...],
 *     "deny":  []
 *   }
 * }
 * ```
 *
 * Other top-level keys (e.g. `env`, `mcpServers`, project user config) are
 * preserved verbatim — we only mutate `permissions.allow`.
 */
object ProjectAllowlist {

    data class Result(
        val success: Boolean,
        val pattern: String,
        val filePath: String,
        val alreadyPresent: Boolean = false,
        val error: String? = null,
    )

    /**
     * Adds [pattern] to the project's `permissions.allow` array. Creates the
     * `.claude` directory and `settings.local.json` file if missing. Returns
     * a [Result] describing what happened — caller should report to the user.
     */
    fun addAllow(projectDir: String, pattern: String): Result {
        val settingsFile = File(projectDir, ".claude/settings.local.json")
        return try {
            val root = readOrEmpty(settingsFile)
            val permissions = root.getAsJsonObject("permissions")
                ?: JsonObject().also { root.add("permissions", it) }
            val allow = permissions.getAsJsonArray("allow")
                ?: JsonArray().also { permissions.add("allow", it) }

            // Check for an exact duplicate so re-clicks don't bloat the file.
            val existsAlready = (0 until allow.size())
                .any { allow.get(it).asString == pattern }
            if (existsAlready) {
                return Result(
                    success = true,
                    pattern = pattern,
                    filePath = settingsFile.absolutePath,
                    alreadyPresent = true,
                )
            }
            allow.add(pattern)
            settingsFile.parentFile?.mkdirs()
            settingsFile.writeText(GSON.toJson(root))
            Result(success = true, pattern = pattern, filePath = settingsFile.absolutePath)
        } catch (e: Exception) {
            Result(
                success = false,
                pattern = pattern,
                filePath = settingsFile.absolutePath,
                error = e.message ?: e.javaClass.simpleName,
            )
        }
    }

    /**
     * Build the pattern string Claude Code expects for a given tool + input.
     *
     * For **MCP tools** (`mcp__<server>__<tool>`), the two scopes are:
     *   - broad   → `mcp__<server>`           (allow every tool in that server)
     *   - specific → `mcp__<server>__<tool>`  (this one tool only)
     *
     * Built-in tools follow the existing `Bash(cmd)` / `Edit(path)` etc.
     * scheme — broad is the bare tool name, specific wraps the input.
     */
    fun patternFor(toolName: String, exactInput: String?): String {
        // MCP tools have their own scope-narrowing convention: drop the
        // trailing `__<tool>` segment to allow every tool from the same
        // MCP server. The `exactInput` field doesn't fit Claude's
        // `Tool(arg)` syntax for MCP, so we only use it as a "specific
        // vs broad" toggle here — broad → server, specific → full name.
        if (toolName.startsWith("mcp__")) {
            val parts = toolName.split("__")
            if (parts.size >= 3 && exactInput.isNullOrBlank()) {
                return parts.take(2).joinToString("__")
            }
            return toolName
        }
        // Tools without a useful sub-pattern (or when we lack input) just use
        // the bare tool name — that allows every invocation of the tool.
        if (exactInput.isNullOrBlank()) return toolName
        return when (toolName) {
            // Bash patterns wrap the command verbatim; the CLI matches against
            // the exact `command` field. Pre-validated input from the model
            // is safe to include verbatim — Claude already constructed it.
            "Bash" -> "Bash($exactInput)"
            // File-targeting tools wrap the path.
            "Edit", "Write", "Read", "NotebookEdit" -> "$toolName($exactInput)"
            "WebFetch" -> "WebFetch($exactInput)"
            else -> toolName
        }
    }

    private fun readOrEmpty(file: File): JsonObject {
        if (!file.exists()) return JsonObject()
        return try {
            val text = file.readText().trim()
            if (text.isEmpty()) JsonObject()
            else JsonParser.parseString(text).asJsonObject
        } catch (e: Exception) {
            // Malformed JSON — bail out by treating as empty rather than
            // clobbering. The caller surfaces this via Result.error.
            throw IllegalStateException(
                "Could not parse existing ${file.name}: ${e.message}", e
            )
        }
    }

    private val GSON = GsonBuilder().setPrettyPrinting().create()
}
