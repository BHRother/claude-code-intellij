package com.claudecode.session

import com.claudecode.settings.ClaudeSettings
import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import java.io.*
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

data class ClaudeMessage(
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

interface SessionListener {
    fun onText(session: ClaudeSession, text: String)
    fun onThinking(session: ClaudeSession, thinking: String?)
    fun onToolUse(session: ClaudeSession, tool: String, detail: String?, diffSummary: String?, diffData: Pair<String, String>? = null, filePath: String? = null)
    fun onFileChanged(session: ClaudeSession, filePath: String, action: String)
    fun onTaskProgress(session: ClaudeSession, description: String)
    fun onModelInfo(session: ClaudeSession, model: String)
    /**
     * Tool finished. [resultContent] is the textual content from the
     * tool_result block (may be empty / null for some tools). The UI uses
     * it on error to show *what* went wrong rather than a bare "✗ failed".
     */
    fun onToolResult(session: ClaudeSession, toolUseId: String, isError: Boolean, resultContent: String?)
    /**
     * Fired when a tool call was denied by the active --permission-mode.
     * [toolInputDetail] is the most relevant single input field for the tool:
     * the bash command for Bash, the file_path for Edit/Write/Read, etc.
     * Used by the UI to offer a "grant exactly this" affordance via
     * `.claude/settings.local.json`.
     */
    fun onPermissionBlocked(session: ClaudeSession, toolName: String?, toolInputDetail: String?) {}
    fun onFinished(session: ClaudeSession, costUsd: Double?)
    fun onError(session: ClaudeSession, error: String)
    fun onDebug(session: ClaudeSession, message: String)
}

class ClaudeSession(
    val workingDirectory: String,
    val name: String = "Session",
    /**
     * Seed the conversation with an existing claude session ID, so the
     * first spawn is `--resume <id>` and Claude continues from where the
     * prior chat left off. Null = brand-new conversation. Used by the
     * "Recent" surface to revive past sessions across IDE restarts.
     */
    initialSessionId: String? = null,
) : Disposable {

    val id: String = UUID.randomUUID().toString().take(8)

    private val log = Logger.getInstance(ClaudeSession::class.java)
    private val listeners = CopyOnWriteArrayList<SessionListener>()
    private var process: Process? = null
    @Volatile private var sessionId: String? = initialSessionId

    /** The claude server-side session ID once known. Null until the first turn lands. */
    val claudeSessionId: String? get() = sessionId

    /** True when this session was constructed to resume a prior chat (vs starting fresh). */
    val isResumed: Boolean = initialSessionId != null

    val messages = mutableListOf<ClaudeMessage>()
    private val readFiles = mutableSetOf<String>()
    private var lastToolUseId: String? = null
    private var lastToolName: String? = null
    // Per-tool: the input field most useful as a permission-pattern argument
    // (bash command, file_path, etc.). See onPermissionBlocked.
    private var lastToolInputDetail: String? = null

    @Volatile
    var isBusy = false
        private set

    // Per-session overrides set from the chip row in SessionPanel.
    // null → fall back to ClaudeSettings (global default). Changes apply on
    // the next message — they don't affect a request that's already in flight.
    @Volatile var modelOverride: String? = null
    @Volatile var permissionModeOverride: String? = null

    fun addListener(listener: SessionListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: SessionListener) {
        listeners.remove(listener)
    }

    private fun debug(msg: String) {
        log.info("claude-session-$id: $msg")
        try {
            listeners.forEach { it.onDebug(this, msg) }
        } catch (_: Exception) {}
    }

    fun sendMessage(text: String) {
        if (isBusy) return

        isBusy = true
        messages.add(ClaudeMessage("user", text))
        listeners.forEach { it.onThinking(this, null) }

        Thread({
            try {
                runClaudeCommand(text)
            } catch (e: Exception) {
                debug("Uncaught exception in runClaudeCommand: ${e.stackTraceToString()}")
                listeners.forEach { it.onError(this, "Unexpected error: ${e.message}") }
                isBusy = false
                listeners.forEach { it.onFinished(this, null) }
            }
        }, "claude-session-$id-run").apply {
            isDaemon = true
            start()
        }
    }

    private fun runClaudeCommand(prompt: String) {
        val settings = ClaudeSettings.getInstance().state
        // Empty/blank persisted state should fall back to the default name,
        // not produce ProcessBuilder("") which fails with CreateProcess error=87.
        val configuredPath = settings.claudePath.ifBlank { com.claudecode.ClaudeConstants.DEFAULT_CLI_PATH }
        val resolution = resolveClaudePathDiagnosed(configuredPath)
        val claudePath = resolution.resolvedPath

        resolution.trace.forEach { debug("path-resolution: $it") }
        debug("Resolved claude path: $claudePath")
        debug("Working directory: $workingDirectory")

        // Build the claude command args
        val claudeArgs = mutableListOf(
            claudePath, "-p",
            "--output-format", "stream-json",
            "--verbose"
        )
        // Chip-row override (per-session) takes precedence over global settings.
        val model = (modelOverride ?: settings.model)
        if (model.isNotBlank()) {
            claudeArgs.add("--model")
            claudeArgs.add(model)
        }
        // Always pass --permission-mode explicitly. In -p mode the CLI silently
        // refuses tool calls that need permission and never emits an interactive
        // prompt, so the user picks one of the modes that fits their tolerance
        // (acceptEdits / bypassPermissions / plan) instead of an unreachable
        // "ask each time" flow.
        val permMode = (permissionModeOverride ?: settings.permissionMode)
            .ifBlank { com.claudecode.ClaudeConstants.PERMISSION_MODE_ACCEPT_EDITS }
        claudeArgs.add("--permission-mode")
        claudeArgs.add(permMode)
        // ── Model & inference settings ──
        // Persistent steering text appended to claude's system prompt every
        // spawn. Empty string skipped so we don't pass an empty arg.
        val appendSystem = settings.appendSystemPrompt
        if (appendSystem.isNotBlank()) {
            claudeArgs.add("--append-system-prompt")
            claudeArgs.add(appendSystem)
        }
        // Hard cap on agentic loop turns. 0 = unlimited (CLI default), skip.
        val maxTurns = settings.maxAgenticTurns
        if (maxTurns > 0) {
            claudeArgs.add("--max-turns")
            claudeArgs.add(maxTurns.toString())
        }
        if (sessionId != null) {
            claudeArgs.add("--resume")
            claudeArgs.add(sessionId!!)
        }
        claudeArgs.add(prompt)

        // On Unix, wrap with `script` to allocate a pseudo-TTY — Claude's Node
        // process buffers stdout when not connected to a TTY, which makes the
        // process hang with no output. Windows has no `script` equivalent,
        // and Node on Windows handles non-TTY pipes correctly for the CLI's
        // -p stream-json output, so we invoke directly there.
        val command = when {
            isWindows() -> claudeArgs.toMutableList()
            isMacOS() -> mutableListOf("script", "-q", "/dev/null").apply { addAll(claudeArgs) }
            else -> mutableListOf("script", "-q", "-c", claudeArgs.joinToString(" ") { shellQuote(it) }, "/dev/null")
        }

        debug("Command: ${command.joinToString(" ") { shellQuote(it) }}")

        val shellPath = resolveShellPath()
        val effectivePath = augmentedPath(claudePath, shellPath)
        val pb = ProcessBuilder(command)
            .directory(File(workingDirectory))
            .redirectErrorStream(true)

        if (effectivePath != null) {
            pb.environment()["PATH"] = effectivePath
        }
        pb.environment()["TERM"] = com.claudecode.ClaudeConstants.ENV_TERM_VALUE
        pb.environment()["NO_COLOR"] = "1"

        // Extended thinking budget — env var picked up by claude on Opus /
        // Sonnet 4.x families. We pass it only when the user has selected
        // a non-Off level, so the absence is a clean "no extended thinking".
        com.claudecode.ClaudeConstants.thinkingBudgetTokens(settings.thinkingBudget)?.let { tokens ->
            pb.environment()["MAX_THINKING_TOKENS"] = tokens.toString()
            debug("MAX_THINKING_TOKENS=$tokens (level=${settings.thinkingBudget})")
        }

        debug("Starting process...")
        process = pb.start()
        // Capture our process reference so the cleanup below can detect
        // the case where stop() killed us and a NEW run replaced us in
        // the `process` field — in which case we must NOT clobber the
        // shared isBusy / process / onFinished state belonging to the
        // newer run.
        val myProcess = process
        debug("Process started (pid=${myProcess?.pid()})")

        val responseText = StringBuilder()
        // Non-json lines from the CLI/pty wrapper — usually debug noise, BUT
        // when claude itself fails (bad flag, unknown model) it prints the
        // error here. We keep them so the error path can surface a useful
        // message instead of just "exited with code 1".
        val nonJsonOutput = StringBuilder()
        var costUsd: Double? = null

        // With redirectErrorStream(true) + script, everything comes on stdout
        try {
            debug("Reading output...")
            BufferedReader(InputStreamReader(process!!.inputStream)).use { reader ->
                var lineNum = 0
                reader.forEachLine { line ->
                    lineNum++

                    if (line.isBlank()) return@forEachLine

                    // Strip ANSI escape codes that `script` may inject
                    val cleaned = line.replace(Regex("\u001B\\[[^a-zA-Z]*[a-zA-Z]"), "")
                        .replace(Regex("\\]\\d+;[^\u0007]*\u0007"), "") // OSC sequences
                        .trim()

                    if (cleaned.isBlank()) return@forEachLine

                    // Only parse JSON lines. Non-JSON lines are debug noise from
                    // the CLI/pty wrapper — log and discard. (We used to scan
                    // these for interactive permission prompts, but -p mode
                    // never emits them; permission control happens via the
                    // --permission-mode flag.)
                    if (!cleaned.startsWith("{")) {
                        debug("non-json[$lineNum]: ${cleaned.take(200)}")
                        if (nonJsonOutput.length < 2000) {
                            if (nonJsonOutput.isNotEmpty()) nonJsonOutput.append('\n')
                            nonJsonOutput.append(cleaned)
                        }
                        return@forEachLine
                    }

                    debug("json[$lineNum]: ${cleaned.take(200)}${if (cleaned.length > 200) "..." else ""}")

                    try {
                        costUsd = parseStreamLine(cleaned, responseText) ?: costUsd
                    } catch (e: Exception) {
                        debug("Parse error on line $lineNum: ${e.message}")
                    }
                }
                debug("Stream closed after $lineNum lines")
            }
        } catch (e: Exception) {
            debug("Read error: ${e.stackTraceToString()}")
        }

        try {
            val finished = process?.waitFor(10, TimeUnit.SECONDS)
            val code = if (finished == true) process?.exitValue() else null
            debug("Process exited: finished=$finished code=$code")

            if ((code != null && code != 0) && responseText.isEmpty()) {
                val detail = extractClaudeErrorDetail(nonJsonOutput.toString())
                val msg = if (detail.isNullOrBlank()) {
                    "Claude exited with code $code"
                } else {
                    "Claude exited with code $code — $detail"
                }
                listeners.forEach { it.onError(this, msg) }
            }
        } catch (e: Exception) {
            debug("waitFor error: ${e.message}")
        }

        if (responseText.isNotEmpty()) {
            messages.add(ClaudeMessage("assistant", responseText.toString()))
        }

        // Identity guard: if a newer run has replaced us in the `process`
        // field (which happens when applyGrant / switchToUnrestricted
        // kills us and immediately spawns a retry), the newer run owns
        // the shared state — we must not flip isBusy back to false, null
        // out its process reference, or fire a misleading onFinished.
        val stillCurrent = process === myProcess
        if (stillCurrent) {
            isBusy = false
            process = null
            listeners.forEach { it.onFinished(this, costUsd) }
        } else {
            debug("Cleanup skipped — superseded by a newer run")
        }
    }

    internal fun parseStreamLine(line: String, responseText: StringBuilder): Double? {
        val json = JsonParser.parseString(line).asJsonObject
        val type = json.get("type")?.asString ?: return null

        when (type) {
            "system" -> {
                val subtype = json.get("subtype")?.asString
                val sid = json.get("session_id")?.asString
                if (sid != null) sessionId = sid

                when (subtype) {
                    "init" -> {
                        val model = json.get("model")?.asString
                        if (model != null) {
                            debug("Model: $model")
                            listeners.forEach { it.onModelInfo(this, model) }
                        }
                    }
                    "task_started" -> {
                        val desc = json.get("description")?.asString ?: ""
                        listeners.forEach { it.onTaskProgress(this, "Task started: $desc") }
                    }
                    "task_progress" -> {
                        val desc = json.get("description")?.asString ?: ""
                        listeners.forEach { it.onTaskProgress(this, desc) }
                    }
                }
            }
            "assistant" -> {
                val message = json.getAsJsonObject("message") ?: return null
                val msgModel = message.get("model")?.asString
                if (msgModel != null) {
                    listeners.forEach { it.onModelInfo(this, msgModel) }
                }
                val content = message.getAsJsonArray("content") ?: return null
                for (block in content) {
                    val obj = block.asJsonObject
                    when (obj.get("type")?.asString) {
                        "text" -> {
                            val text = obj.get("text")?.asString ?: continue
                            responseText.append(text)
                            listeners.forEach { it.onText(this, text) }
                        }
                        "thinking" -> {
                            val thinking = obj.get("thinking")?.asString
                            val summary = thinking
                                ?.lines()
                                ?.firstOrNull { it.isNotBlank() }
                                ?.take(100)
                            listeners.forEach { it.onThinking(this, summary) }
                        }
                        "tool_use" -> {
                            val toolName = obj.get("name")?.asString ?: "unknown"
                            val toolUseId = obj.get("id")?.asString
                            val input = obj.getAsJsonObject("input")
                            val detail = extractToolDetail(toolName, input)
                            val diffSummary = buildDiffSummary(toolName, input)
                            if (toolUseId != null) {
                                lastToolUseId = toolUseId
                                lastToolName = toolName
                            }
                            // Snapshot the input field most useful as a
                            // permission-allow pattern arg for this tool.
                            lastToolInputDetail = when (toolName) {
                                "Bash" -> input?.get("command")?.asString
                                "Edit", "Write", "Read", "NotebookEdit" -> input?.get("file_path")?.asString
                                "WebFetch" -> input?.get("url")?.asString
                                else -> null
                            }
                            val filePath = input?.get("file_path")?.asString
                            val diffData = if (toolName == "Edit") {
                                val oldStr = input?.get("old_string")?.asString
                                val newStr = input?.get("new_string")?.asString
                                if (oldStr != null && newStr != null) Pair(oldStr, newStr) else null
                            } else null
                            listeners.forEach { it.onToolUse(this, toolName, detail, diffSummary, diffData, filePath) }
                            if (toolName == "Read" && filePath != null) {
                                readFiles.add(filePath)
                            }
                            if (toolName in listOf("Edit", "Write") && filePath != null) {
                                val action = if (toolName == "Edit" || readFiles.contains(filePath)) "Modified" else "Created"
                                listeners.forEach { it.onFileChanged(this, filePath, action) }
                            }
                            // Track file deletions from Bash rm commands
                            if (toolName == "Bash") {
                                val cmd = input?.get("command")?.asString?.trim() ?: ""
                                // Only parse simple rm commands, stop at shell operators
                                val firstCmd = cmd.split(Regex("\\s*[;&|]")).firstOrNull()?.trim() ?: ""
                                if (firstCmd.startsWith("rm ")) {
                                    val argsStr = firstCmd.removePrefix("rm ").trim()
                                    // Extract file args, skipping flags
                                    val args = argsStr.split(" ").filter {
                                        it.isNotBlank() && !it.startsWith("-")
                                    }
                                    for (arg in args) {
                                        val fullPath = if (arg.startsWith("/")) arg else "$workingDirectory/$arg"
                                        listeners.forEach { it.onFileChanged(this, fullPath, "Deleted") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            "content_block_start" -> {
                val contentBlock = json.getAsJsonObject("content_block")
                if (contentBlock?.get("type")?.asString == "tool_result") {
                    val toolUseId = contentBlock.get("tool_use_id")?.asString
                    val isError = contentBlock.get("is_error")?.asBoolean ?: false
                    // content_block_start fires at the start of a streaming
                    // block — full content arrives via deltas. Best-effort
                    // extraction here; the user-message path below carries
                    // the canonical complete content for tool_result blocks.
                    val resultContent = contentBlock.get("content")?.let {
                        if (it.isJsonPrimitive) it.asString else null
                    }
                    if (toolUseId != null) {
                        listeners.forEach { it.onToolResult(this, toolUseId, isError, resultContent) }
                    }
                }
            }
            // The CLI emits user-type messages with embedded tool_result blocks
            // — that's where "This command requires approval" arrives when a
            // tool gets blocked by --permission-mode. We forward errors via
            // onToolResult and fire onPermissionBlocked for the specific
            // permission-denial wording so the UI can show its hint *before*
            // the model produces any natural-language response.
            "user" -> {
                val message = json.getAsJsonObject("message") ?: return null
                val content = message.getAsJsonArray("content") ?: return null
                for (block in content) {
                    val obj = block.asJsonObject
                    if (obj.get("type")?.asString != "tool_result") continue
                    val toolUseId = obj.get("tool_use_id")?.asString
                    val isError = obj.get("is_error")?.asBoolean ?: false
                    // tool_result.content can be a bare string OR an array
                    // of typed blocks. We just need the text for the UI badge,
                    // so handle the simple-string case and fall back to null
                    // (the array case carries non-text data we don't surface).
                    val resultContent = obj.get("content")?.let {
                        if (it.isJsonPrimitive) it.asString else null
                    }
                    if (toolUseId != null) {
                        listeners.forEach { it.onToolResult(this, toolUseId, isError, resultContent) }
                    }
                    if (isError && resultContent != null && looksLikePermissionDenial(resultContent)) {
                        listeners.forEach { it.onPermissionBlocked(this, lastToolName, lastToolInputDetail) }
                    }
                }
            }
            "result" -> {
                val sid = json.get("session_id")?.asString
                if (sid != null) sessionId = sid

                val resultText = json.get("result")?.asString
                if (resultText != null && responseText.isEmpty()) {
                    responseText.append(resultText)
                    listeners.forEach { it.onText(this, resultText) }
                }

                val cost = json.get("total_cost_usd")?.asDouble
                val durationMs = json.get("duration_ms")?.asLong
                val numTurns = json.get("num_turns")?.asInt
                if (durationMs != null) {
                    val secs = durationMs / 1000.0
                    val costStr = if (cost != null) " | cost: $${String.format("%.4f", cost)}" else ""
                    val turnsStr = if (numTurns != null) " | $numTurns turns" else ""
                    debug("Completed in ${String.format("%.1f", secs)}s$costStr$turnsStr")
                }
                return cost
            }
        }
        return null
    }

    /**
     * Sift non-json CLI output for something humans should see. Most lines are
     * pty noise; we want the first one that looks like an actual error/help
     * message from the `claude` binary itself.
     */
    internal fun extractClaudeErrorDetail(raw: String): String? {
        if (raw.isBlank()) return null
        // Match commander.js / yargs-style error lines (`error: ...`,
        // `Error:`, `unknown option`, `missing argument`) and the model-not-
        // available message Claude returns in -p mode.
        val errorPattern = Regex(
            "(?i)^(error:|err:|fatal:|warning:|unknown |missing |option |there's an issue|invalid )"
        )
        val candidates = raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            // Drop pty/script preambles like "Script started" / "Script done".
            .filterNot { it.startsWith("Script ", ignoreCase = true) }
            .toList()
        val first = candidates.firstOrNull { errorPattern.containsMatchIn(it) }
            ?: candidates.firstOrNull()
            ?: return null
        return first.take(300)
    }

    internal fun looksLikePermissionDenial(toolResultContent: String): Boolean {
        val lower = toolResultContent.lowercase()
        return lower.contains("requires approval") ||
            lower.contains("requires permission") ||
            lower.contains("permission denied") ||
            lower.contains("not allowed by the current permission") ||
            lower.contains("blocked by permission") ||
            // MCP-style: "Claude requested permissions to use mcp__foo, but
            // you haven't granted it yet." This wording is what Claude Code
            // returns when an MCP tool isn't on the allow list — previously
            // missed, so the in-chat grant banner never fired.
            lower.contains("haven't granted") ||
            lower.contains("requested permissions to use") ||
            lower.contains("permission to use")
    }

    internal fun buildDiffSummary(tool: String, input: com.google.gson.JsonObject?): String? {
        if (input == null) return null
        return when (tool) {
            "Edit" -> {
                val oldStr = input.get("old_string")?.asString ?: return null
                val newStr = input.get("new_string")?.asString ?: return null
                val oldLineCount = oldStr.lines().size
                val newLineCount = newStr.lines().size
                val parts = mutableListOf<String>()
                if (newLineCount > 0) parts.add("Added ${newLineCount} line${if (newLineCount > 1) "s" else ""}")
                if (oldLineCount > 0) parts.add("removed ${oldLineCount} line${if (oldLineCount > 1) "s" else ""}")
                parts.joinToString(", ")
            }
            "Write" -> {
                val content = input.get("content")?.asString ?: return null
                val lineCount = content.lines().size
                "$lineCount lines"
            }
            "Bash" -> {
                val cmd = input.get("command")?.asString ?: return null
                if (cmd.length > 80) "${cmd.take(77)}..." else null
            }
            else -> null
        }
    }

    internal fun extractToolDetail(tool: String, input: com.google.gson.JsonObject?): String? {
        if (input == null) return null
        return when (tool) {
            "Read" -> input.get("file_path")?.asString?.let { "Read(${shortenPath(it)})" }
            "Edit" -> input.get("file_path")?.asString?.let { "Update(${shortenPath(it)})" }
            "Write" -> input.get("file_path")?.asString?.let {
                val path = shortenPath(it)
                if (readFiles.contains(it)) "Update($path)" else "Create($path)"
            }
            "Glob" -> input.get("pattern")?.asString?.let { "Glob($it)" }
            "Grep" -> input.get("pattern")?.asString?.let { "Grep(\"$it\")" }
            "Bash" -> {
                val cmd = input.get("command")?.asString ?: return null
                val firstCmd = cmd.trimStart().split(Regex("\\s*[;&|]")).firstOrNull()?.trim() ?: ""
                if (firstCmd.startsWith("rm ")) {
                    val files = firstCmd.removePrefix("rm ").trim()
                        .split(" ").filter { it.isNotBlank() && !it.startsWith("-") }
                        .joinToString(", ") { shortenPath(it) }
                    "Delete($files)"
                } else {
                    cmd.take(80)
                }
            }
            "Task" -> input.get("description")?.asString?.take(80)
            "WebFetch" -> input.get("url")?.asString?.take(80)
            "WebSearch" -> input.get("query")?.asString
            else -> null
        }
    }

    internal fun shortenPath(path: String): String {
        val wdPrefix = "$workingDirectory/"
        return if (path.startsWith(wdPrefix)) path.removePrefix(wdPrefix) else path
    }

    fun stop() {
        debug("Stop requested")
        process?.destroyForcibly()
        process = null
        isBusy = false
    }

    /**
     * Drop the claude-side session ID and our in-memory message history so
     * the next [sendMessage] starts a brand-new conversation rather than
     * resuming this one. Used by the in-chat `/clear` slash command.
     *
     * Claude's own JSONL transcript under ~/.claude/projects is untouched;
     * we just stop referencing the old session_id.
     */
    fun resetConversation() {
        debug("Conversation reset (sessionId dropped, history cleared)")
        sessionId = null
        messages.clear()
    }

    override fun dispose() {
        stop()
        listeners.clear()
    }

    companion object {
        private val staticLog = Logger.getInstance(ClaudeSession::class.java)
        private var cachedResolvedPath: String? = null
        private var cachedShellPath: String? = null

        fun isMacOS(): Boolean = System.getProperty("os.name").lowercase().contains("mac")
        fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("win")

        internal fun shellQuote(s: String): String {
            return "'" + s.replace("'", "'\\''") + "'"
        }

        fun resolveShellPathPublic(): String? = resolveShellPath()

        fun resolveClaudePathPublic(configured: String): String = resolveClaudePath(configured)

        /**
         * Same resolution as [resolveClaudePathPublic] but also returns a
         * trace of every strategy attempted. Settings UI uses this to show
         * the user exactly what was tried; runClaudeCommand pipes the trace
         * through to the chat debug log so failures are diagnosable without
         * digging through idea.log.
         */
        data class CliResolution(val resolvedPath: String, val resolved: Boolean, val trace: List<String>)

        fun resolveClaudePathDiagnosed(configured: String): CliResolution {
            val trace = mutableListOf<String>()
            val resolved = resolveClaudePathInternal(configured, trace)
            val didFind = resolved != configured ||
                File(configured).isAbsolute && File(configured).isFile
            return CliResolution(resolved, didFind, trace)
        }

        fun clearResolutionCache() {
            cachedResolvedPath = null
        }

        private fun resolveShellPath(): String? {
            if (cachedShellPath != null) return cachedShellPath

            // Windows: read PATH straight from the JVM's inherited environment.
            // There's no "login shell" equivalent and System.getenv is
            // case-insensitive on Windows (handles PATH/Path/path).
            if (isWindows()) {
                cachedShellPath = System.getenv("PATH")
                return cachedShellPath
            }

            try {
                val shell = System.getenv("SHELL") ?: com.claudecode.ClaudeConstants.DEFAULT_SHELL
                val pb = ProcessBuilder(shell, "-l", "-c", "echo \$PATH")
                pb.redirectErrorStream(true)
                val proc = pb.start()
                val output = proc.inputStream.bufferedReader().readText().trim()
                val finished = proc.waitFor(5, TimeUnit.SECONDS)
                if (finished && proc.exitValue() == 0 && output.isNotBlank()) {
                    cachedShellPath = output
                    return output
                }
            } catch (_: Exception) {}
            return null
        }

        private fun resolveClaudePath(configured: String): String =
            resolveClaudePathInternal(configured, mutableListOf())

        private fun resolveClaudePathInternal(configured: String, trace: MutableList<String>): String {
            // Absolute paths: pass through unchanged.
            if (configured.startsWith("/")) {
                trace.add("Absolute Unix path; using as-is: $configured")
                return configured
            }
            if (isWindows() && configured.matches(Regex("^[A-Za-z]:[\\\\/].*"))) {
                trace.add("Absolute Windows path; using as-is: $configured")
                return configured
            }
            if (cachedResolvedPath != null) {
                trace.add("Cached resolution: ${cachedResolvedPath!!}")
                return cachedResolvedPath!!
            }

            if (isWindows()) {
                resolveOnWindows(configured, trace)?.let {
                    cachedResolvedPath = it
                    return it
                }
                val msg = "Could not resolve '$configured' on Windows via where, " +
                    "common npm locations, or `npm config get prefix`. " +
                    "Set an absolute path in Settings → Tools → Claude Code → Claude CLI path " +
                    "(usually %APPDATA%\\npm\\claude.cmd)."
                trace.add(msg)
                staticLog.warn(msg)
                return configured
            }

            try {
                val shell = System.getenv("SHELL") ?: com.claudecode.ClaudeConstants.DEFAULT_SHELL
                val pb = ProcessBuilder(shell, "-l", "-c", "which $configured")
                pb.redirectErrorStream(true)
                val proc = pb.start()
                val output = proc.inputStream.bufferedReader().readText().trim()
                val finished = proc.waitFor(5, TimeUnit.SECONDS)
                if (finished && proc.exitValue() == 0 && output.isNotBlank()) {
                    cachedResolvedPath = output
                    return output
                }
            } catch (_: Exception) {}
            return configured
        }

        /**
         * Layered Windows resolution. The IntelliJ JVM's PATH is whatever the
         * launcher inherited at startup, which often *omits* the user-PATH
         * entries added by the Node.js MSI installer (where %APPDATA%\npm\
         * normally sits). So a bare "claude" can be perfectly runnable from
         * cmd.exe yet invisible to ProcessBuilder. We try, in order:
         *
         *   1. `where <name>` against the inherited PATH (cheap, often works
         *      when the JVM did inherit the right PATH).
         *   2. Direct probes of the well-known npm install locations:
         *      %APPDATA%\npm\, %LOCALAPPDATA%\npm\, %ProgramFiles%\nodejs\.
         *   3. `npm config get prefix` (npm itself usually IS on PATH because
         *      Node's installer puts npm.cmd in C:\Program Files\nodejs\, a
         *      system-PATH location).
         */
        private fun resolveOnWindows(configured: String, trace: MutableList<String>): String? {
            fun trace(line: String) {
                trace.add(line)
                staticLog.info("resolveOnWindows: $line")
            }

            // 1. `where`
            try {
                val pb = ProcessBuilder("where", configured).redirectErrorStream(true)
                val proc = pb.start()
                val lines = proc.inputStream.bufferedReader().readLines()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                val finished = proc.waitFor(5, TimeUnit.SECONDS)
                if (finished && proc.exitValue() == 0 && lines.isNotEmpty()) {
                    val preferred = pickWindowsCandidate(lines)
                    trace("`where $configured` -> $preferred")
                    return preferred
                }
                trace("`where $configured` returned no matches " +
                    "(exit=${if (finished) proc.exitValue() else "timeout"})")
            } catch (e: Exception) {
                trace("`where` failed: ${e.message}")
            }

            // 2. Direct probes of common npm install dirs
            val nameCandidates = listOf("$configured.cmd", "$configured.exe", configured)
            val dirCandidates = listOfNotNull(
                System.getenv("APPDATA")?.let { "$it\\npm" },
                System.getenv("LOCALAPPDATA")?.let { "$it\\npm" },
                System.getenv("ProgramFiles")?.let { "$it\\nodejs" },
                System.getenv("ProgramFiles(x86)")?.let { "$it\\nodejs" },
                System.getenv("USERPROFILE")?.let { "$it\\AppData\\Roaming\\npm" },
                System.getenv("USERPROFILE")?.let { "$it\\AppData\\Local\\npm" },
            ).distinct()
            trace("Probing ${dirCandidates.size} directories: ${dirCandidates.joinToString(", ")}")
            for (dir in dirCandidates) {
                for (name in nameCandidates) {
                    val candidate = "$dir\\$name"
                    if (File(candidate).isFile) {
                        trace("Probe hit: $candidate")
                        return candidate
                    }
                }
            }
            trace("No probe directory contained $configured.cmd/.exe/bare")

            // 3. Ask npm for its global prefix
            try {
                val pb = ProcessBuilder("npm", "config", "get", "prefix").redirectErrorStream(true)
                val proc = pb.start()
                val output = proc.inputStream.bufferedReader().readText().trim()
                val finished = proc.waitFor(8, TimeUnit.SECONDS)
                if (finished && proc.exitValue() == 0 && output.isNotBlank()) {
                    val prefix = output.lines().firstOrNull { it.isNotBlank() }?.trim() ?: ""
                    if (prefix.isNotBlank()) {
                        trace("`npm config get prefix` -> $prefix")
                        for (name in nameCandidates) {
                            val candidate = "$prefix\\$name"
                            if (File(candidate).isFile) {
                                trace("npm-prefix probe hit: $candidate")
                                return candidate
                            }
                        }
                        trace("npm prefix '$prefix' has no $configured.cmd/.exe/bare")
                    } else {
                        trace("`npm config get prefix` returned blank")
                    }
                } else {
                    trace("`npm config get prefix` failed (exit=${if (finished) proc.exitValue() else "timeout"})")
                }
            } catch (e: Exception) {
                trace("`npm config get prefix` failed: ${e.message}")
            }

            // 4. Read the user PATH from the Windows registry. This is the PATH
            // cmd.exe / PowerShell get at login but the JVM may not have
            // inherited (e.g. if IntelliJ was launched before the user PATH
            // was last modified, or via a launcher that scrubs env). reg.exe
            // lives in C:\Windows\System32 which is on system PATH, so this
            // works even when the rest of PATH is degraded.
            val registryPaths = readUserPathFromRegistry(trace)
            for (dir in registryPaths) {
                for (name in nameCandidates) {
                    val candidate = "$dir\\$name"
                    if (File(candidate).isFile) {
                        trace("Registry-PATH probe hit: $candidate")
                        return candidate
                    }
                }
            }

            return null
        }

        private fun readUserPathFromRegistry(traceList: MutableList<String>): List<String> {
            fun log(line: String) {
                traceList.add(line)
                staticLog.info("resolveOnWindows: $line")
            }
            return try {
                val pb = ProcessBuilder("reg", "query", "HKCU\\Environment", "/v", "Path")
                    .redirectErrorStream(true)
                val proc = pb.start()
                val output = proc.inputStream.bufferedReader().readText()
                val finished = proc.waitFor(5, TimeUnit.SECONDS)
                if (!finished || proc.exitValue() != 0) {
                    log("`reg query HKCU\\Environment Path` failed " +
                        "(exit=${if (finished) proc.exitValue() else "timeout"})")
                    return emptyList()
                }
                // Output looks like:
                //   HKEY_CURRENT_USER\Environment
                //       Path    REG_EXPAND_SZ    C:\Foo;C:\Bar;%APPDATA%\npm
                val pathLine = output.lines().firstOrNull {
                    it.trim().startsWith("Path", ignoreCase = true)
                }
                if (pathLine == null) {
                    log("`reg query` output had no Path line")
                    return emptyList()
                }
                val pathValue = pathLine.substringAfter("REG_").substringAfter("    ").trim()
                val expanded = expandWindowsEnvVars(pathValue)
                val parts = expanded.split(";").map { it.trim() }.filter { it.isNotBlank() }
                log("Registry HKCU Path -> ${parts.size} entries")
                parts
            } catch (e: Exception) {
                log("`reg query` failed: ${e.message}")
                emptyList()
            }
        }

        private fun expandWindowsEnvVars(input: String): String {
            // Replace %FOO% with the JVM's view of the env var. Anything we
            // can't resolve we leave as-is so the directory simply won't exist
            // and gets skipped by the File.isFile check upstream.
            return Regex("%([^%]+)%").replace(input) { match ->
                System.getenv(match.groupValues[1]) ?: match.value
            }
        }

        private fun pickWindowsCandidate(lines: List<String>): String =
            lines.firstOrNull { it.endsWith(".cmd", true) }
                ?: lines.firstOrNull { it.endsWith(".exe", true) }
                ?: lines.first()

        /**
         * On Windows, prepend the resolved binary's directory to PATH so the
         * spawned process can find sibling tools (e.g. other npm-installed
         * binaries that claude.cmd may shell out to). No-op on Unix or when
         * the binary path doesn't have a directory component.
         */
        fun augmentedPath(binaryPath: String, basePath: String?): String? {
            if (!isWindows()) return basePath
            val binDir = File(binaryPath).parentFile?.absolutePath ?: return basePath
            if (basePath.isNullOrBlank()) return binDir
            val sep = ";"
            if (basePath.split(sep).any { it.equals(binDir, ignoreCase = true) }) return basePath
            return "$binDir$sep$basePath"
        }
    }
}
