package com.claudecode.memory

import com.intellij.openapi.diagnostic.Logger
import java.io.File

/**
 * Read/write helpers for Claude Code's CLAUDE.md "memory" files — plain Markdown
 * the agent reads as standing instructions/context. Unlike settings.json there's
 * no schema to validate; we just read and write the text.
 *
 *   - **Project**:       `<project>/CLAUDE.md`        (shared / checked in)
 *   - **Project-local**: `<project>/CLAUDE.local.md`  (gitignored, this machine)
 *   - **User**:          `<CLAUDE_CONFIG_DIR or ~>/.claude/CLAUDE.md` (all projects)
 */
object ClaudeMemoryStore {

    private val LOG = Logger.getInstance(ClaudeMemoryStore::class.java)

    data class WriteResult(val success: Boolean, val filePath: String, val error: String? = null)

    fun userFile(): File {
        val env = System.getenv("CLAUDE_CONFIG_DIR")
        val root = if (!env.isNullOrBlank()) File(env)
        else File(System.getProperty("user.home"), ".claude")
        return File(root, "CLAUDE.md")
    }

    /** The file's text, or "" if it doesn't exist / is unreadable. */
    fun readText(file: File): String {
        if (!file.isFile) return ""
        return try {
            file.readText()
        } catch (t: Throwable) {
            LOG.warn("ClaudeMemoryStore: read failed for ${file.absolutePath}", t)
            ""
        }
    }

    /** Writes [text]; empty text deletes the file (no point keeping an empty memory). */
    fun write(file: File, text: String): WriteResult {
        return try {
            if (text.isBlank()) {
                if (file.isFile) file.delete()
                return WriteResult(true, file.absolutePath)
            }
            file.parentFile?.mkdirs()
            file.writeText(text)
            WriteResult(true, file.absolutePath)
        } catch (t: Throwable) {
            WriteResult(false, file.absolutePath, "${t.javaClass.simpleName}: ${t.message}")
        }
    }
}
