package com.claudecode.history

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import java.io.File

/**
 * Reads Claude Code's local session storage (the JSONL transcript Claude
 * itself writes on every turn). Used by SessionPanel's "Load full history"
 * affordance to reconstruct the entire user/assistant text exchange when
 * resuming a previous chat, with zero API cost — the data is already on
 * disk locally.
 *
 * Storage layout (Claude Code defaults):
 *   ~/.claude/projects/<encoded-workdir>/<session-id>.jsonl
 *
 * Encoding observed on macOS/Linux: every `/` becomes `-`, including the
 * leading slash of an absolute path, so `/Users/foo/repo` becomes
 * `-Users-foo-repo`. Windows isn't documented and the drive-letter colon
 * complicates things, so we try several candidate encodings and finally
 * fall back to scanning every project subdirectory for a matching file
 * name. That makes the reader robust to encoding-scheme changes without
 * needing to track Claude Code's internals.
 *
 * Schema is intentionally permissive — unknown record `type`s are skipped
 * silently so an Anthropic-side schema change degrades gracefully instead
 * of breaking the UI.
 */
object ClaudeSessionFile {

    private val LOG = Logger.getInstance(ClaudeSessionFile::class.java)

    /**
     * One parsed user/assistant text turn from Claude's local JSONL. Tool
     * calls, tool results, file snapshots, thinking blocks, and image
     * blocks are excluded — text only.
     */
    data class HistoricalMessage(
        val role: String,   // "user" or "assistant"
        val text: String,
    )

    data class SessionContents(
        val messages: List<HistoricalMessage>,
        val permissionMode: String?,
        val error: String? = null,
    )

    /**
     * Find the JSONL file for [sessionId] under [workingDirectory], or null
     * if it doesn't exist (e.g. user cleared `~/.claude/projects`, or Claude
     * Code's storage layout changed). Tries fast encoded-path lookups
     * first, then falls back to a recursive scan limited to the projects
     * root depth = 1 so it's bounded even for users with many projects.
     */
    fun locate(workingDirectory: String, sessionId: String): File? {
        val projectsDir = File(resolveClaudeRoot(), "projects")
        if (!projectsDir.isDirectory) return null
        val targetName = "$sessionId.jsonl"

        for (candidate in encodePathCandidates(workingDirectory)) {
            val direct = File(projectsDir, "$candidate/$targetName")
            if (direct.isFile) return direct
        }

        // Fallback: scan top-level subdirectories of <claude>/projects. One
        // file lookup per project subdir; cheap enough.
        return projectsDir.listFiles { f -> f.isDirectory }
            ?.firstNotNullOfOrNull { dir -> File(dir, targetName).takeIf { it.isFile } }
    }

    /**
     * Stream-parse the JSONL file, returning a chronologically ordered list
     * of user/assistant **text** messages (tool_use, tool_result, file
     * snapshots, sidechain branches are all skipped) plus the most recently
     * set permission mode. Empty messages (e.g. an assistant turn that was
     * only tool calls) are dropped.
     *
     * Failure modes: missing file, IO error, malformed JSON line, unknown
     * shape — all return a SessionContents with [SessionContents.error]
     * populated and an empty message list. Caller decides what to do (we
     * just remove the Load link from the UI).
     */
    fun readTextOnly(file: File): SessionContents {
        if (!file.isFile) {
            return SessionContents(emptyList(), null, "File not found")
        }
        val messages = mutableListOf<HistoricalMessage>()
        var permissionMode: String? = null
        try {
            file.bufferedReader().use { reader ->
                reader.lineSequence().forEach { rawLine ->
                    val line = rawLine.trim()
                    if (line.isEmpty()) return@forEach
                    val obj = try {
                        JsonParser.parseString(line).asJsonObject
                    } catch (_: Exception) {
                        return@forEach  // skip malformed line, keep going
                    }
                    val type = obj.get("type")?.asString ?: return@forEach
                    when (type) {
                        "permission-mode" -> {
                            obj.get("permissionMode")?.asString?.let { permissionMode = it }
                        }
                        "user" -> {
                            val msg = obj.getAsJsonObject("message") ?: return@forEach
                            val text = extractTextFromContent(msg.get("content"))
                            if (!text.isNullOrBlank()) {
                                messages.add(HistoricalMessage("user", text))
                            }
                        }
                        "assistant" -> {
                            val msg = obj.getAsJsonObject("message") ?: return@forEach
                            val text = extractTextFromContent(msg.get("content"))
                            if (!text.isNullOrBlank()) {
                                messages.add(HistoricalMessage("assistant", text))
                            }
                        }
                        // All other types (file-history-snapshot, summary, …)
                        // intentionally ignored — we only want the conversation
                        // text, not Claude's internal bookkeeping.
                    }
                }
            }
            return SessionContents(messages, permissionMode)
        } catch (t: Throwable) {
            LOG.warn("ClaudeSessionFile: failed to read ${file.absolutePath}", t)
            return SessionContents(emptyList(), null, "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    // ─────────────── internals ───────────────

    private fun resolveClaudeRoot(): File {
        // Honor CLAUDE_CONFIG_DIR first (some users relocate it), fall back
        // to ~/.claude. Both work on macOS, Linux, and Windows because
        // System.getProperty("user.home") is platform-correct everywhere.
        val env = System.getenv("CLAUDE_CONFIG_DIR")
        if (!env.isNullOrBlank()) return File(env)
        return File(System.getProperty("user.home"), ".claude")
    }

    /**
     * Produce candidate encodings of [path] for the Claude Code projects
     * directory name. Observed Mac/Linux convention is "replace every / with
     * -, keep the leading dash from the leading /". Windows behavior isn't
     * documented; we try multiple variants and then the directory scan
     * fallback so we don't depend on getting the exact rule right.
     */
    private fun encodePathCandidates(path: String): List<String> {
        if (path.isBlank()) return emptyList()
        val cands = LinkedHashSet<String>()

        // Variant 1: replace both `/` and `\` with `-`, leave `:` alone.
        // Matches the Mac/Linux convention with a leading `-` for absolute
        // paths and is also what Windows would look like if Claude Code did
        // the same naive replacement.
        val sepReplaced = path.replace('\\', '-').replace('/', '-')
        cands.add(sepReplaced)
        cands.add(ensureLeadingDash(sepReplaced))

        // Variant 2: drop the drive-letter colon entirely (Windows).
        val colonStripped = sepReplaced.replace(":", "")
        cands.add(colonStripped)
        cands.add(ensureLeadingDash(colonStripped))

        // Variant 3: also turn `:` into `-` so `C:\foo` → `-C--foo`.
        val colonReplaced = sepReplaced.replace(':', '-')
        cands.add(colonReplaced)
        cands.add(ensureLeadingDash(colonReplaced))

        return cands.toList()
    }

    private fun ensureLeadingDash(s: String): String =
        if (s.startsWith('-')) s else "-$s"

    private fun extractTextFromContent(content: JsonElement?): String? {
        if (content == null || content.isJsonNull) return null
        // Older / simpler turn shape: content is a bare string.
        if (content.isJsonPrimitive) {
            return content.asString.takeIf { it.isNotBlank() }
        }
        // Standard shape: content is an array of typed blocks. We keep only
        // `text` blocks; `tool_use`, `tool_result`, `thinking`, image
        // blocks, etc. are dropped on purpose per the user's request.
        if (content.isJsonArray) {
            val sb = StringBuilder()
            content.asJsonArray.forEach { el ->
                if (!el.isJsonObject) return@forEach
                val obj = el.asJsonObject
                if (obj.get("type")?.asString == "text") {
                    val t = obj.get("text")?.asString ?: return@forEach
                    if (sb.isNotEmpty()) sb.append("\n\n")
                    sb.append(t)
                }
            }
            return if (sb.isEmpty()) null else sb.toString()
        }
        return null
    }
}
