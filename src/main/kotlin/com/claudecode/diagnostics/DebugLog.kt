package com.claudecode.diagnostics

import com.claudecode.settings.ClaudeSettings
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date

/**
 * Small in-memory, opt-in diagnostics buffer. Components call [log]/[raw] to
 * record what they're doing; the Settings "Export debug log…" button (visible
 * only while debug is enabled) dumps [snapshot] to a file the user can attach to
 * a bug report.
 *
 * Privacy: capture is gated on the user's `debugMode` setting — when it's off,
 * nothing is stored (the MCP OAuth transcript can contain URLs/tokens, so we
 * never retain it silently). The buffer is capped and lives only in memory.
 */
object DebugLog {
    private const val MAX_LINES = 5000
    private val buffer = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS")

    fun isEnabled(): Boolean = try {
        ClaudeSettings.getInstance().state.debugMode
    } catch (_: Throwable) {
        false
    }

    /** Record a single tagged line, e.g. log("mcp-oauth", "spawned pid=123"). */
    @Synchronized
    fun log(tag: String, message: String) {
        if (!isEnabled()) return
        add("[${fmt.format(Date())}] [$tag] $message")
    }

    /** Record multi-line verbatim output (e.g. a PTY chunk), one buffer line per line. */
    @Synchronized
    fun raw(tag: String, text: String) {
        if (!isEnabled()) return
        for (line in text.split("\n")) {
            val trimmed = line.trimEnd('\r')
            if (trimmed.isNotEmpty()) add("[$tag] $trimmed")
        }
    }

    @Synchronized
    private fun add(line: String) {
        buffer.addLast(line)
        while (buffer.size > MAX_LINES) buffer.removeFirst()
    }

    @Synchronized
    fun snapshot(): String = buffer.joinToString("\n")

    @Synchronized
    fun isEmpty(): Boolean = buffer.isEmpty()

    @Synchronized
    fun clear() = buffer.clear()
}
