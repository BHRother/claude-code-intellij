package com.claudecode.config

import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import java.io.File

/**
 * Read/write helpers for Claude Code's own `settings.json` files. These are
 * user-facing config files (small, meant to be edited), so unlike the giant
 * live `~/.claude.json` we edit them directly.
 *
 * Three scopes:
 *   - **Global**:        `<CLAUDE_CONFIG_DIR or ~>/.claude/settings.json`
 *   - **Project**:       `<project>/.claude/settings.json`        (shared / checked in)
 *   - **Project-local**: `<project>/.claude/settings.local.json`  (gitignored, this machine)
 *
 * The structured editor reads the whole file, mutates the keys the user
 * touched, and writes the full object back — so unrelated keys (including the
 * `permissions` that the grant flow writes to settings.local.json) round-trip
 * safely as long as the JSON stays a valid object.
 */
object ClaudeSettingsFileStore {

    private val LOG = Logger.getInstance(ClaudeSettingsFileStore::class.java)

    data class WriteResult(
        val success: Boolean,
        val filePath: String,
        val error: String? = null,
    )

    fun globalFile(): File {
        val env = System.getenv("CLAUDE_CONFIG_DIR")
        val root = if (!env.isNullOrBlank()) File(env)
        else File(System.getProperty("user.home"), ".claude")
        return File(root, "settings.json")
    }

    fun projectFile(projectDir: String): File =
        File(projectDir, ".claude/settings.json")

    fun projectLocalFile(projectDir: String): File =
        File(projectDir, ".claude/settings.local.json")

    /** Returns the file's text, or "" if it doesn't exist / is empty / unreadable. */
    fun readText(file: File): String {
        if (!file.isFile) return ""
        return try {
            file.readText()
        } catch (t: Throwable) {
            LOG.warn("ClaudeSettingsFileStore: read failed for ${file.absolutePath}", t)
            ""
        }
    }

    /**
     * Validates [text] parses as a JSON object, then writes. Empty text writes
     * an empty `{}` (rather than deleting the file) so Claude Code keeps seeing
     * a valid config.
     */
    fun write(file: File, text: String): WriteResult {
        val normalized = text.trim().ifBlank { "{}" }
        return try {
            val parsed = JsonParser.parseString(normalized)
            if (!parsed.isJsonObject) {
                return WriteResult(
                    success = false,
                    filePath = file.absolutePath,
                    error = "Top-level value must be a JSON object (e.g. { … }).",
                )
            }
            file.parentFile?.mkdirs()
            file.writeText(normalized)
            WriteResult(success = true, filePath = file.absolutePath)
        } catch (t: Throwable) {
            WriteResult(
                success = false,
                filePath = file.absolutePath,
                error = "${t.javaClass.simpleName}: ${t.message}",
            )
        }
    }
}
