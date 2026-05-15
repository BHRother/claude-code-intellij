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
    fun onToolResult(session: ClaudeSession, toolUseId: String, isError: Boolean)
    fun onFinished(session: ClaudeSession, costUsd: Double?)
    fun onError(session: ClaudeSession, error: String)
    fun onDebug(session: ClaudeSession, message: String)
}

class ClaudeSession(
    val workingDirectory: String,
    val name: String = "Session"
) : Disposable {

    val id: String = UUID.randomUUID().toString().take(8)

    private val log = Logger.getInstance(ClaudeSession::class.java)
    private val listeners = CopyOnWriteArrayList<SessionListener>()
    private var process: Process? = null
    private var sessionId: String? = null

    val messages = mutableListOf<ClaudeMessage>()
    private val readFiles = mutableSetOf<String>()
    private var lastToolUseId: String? = null
    private var lastToolName: String? = null

    @Volatile
    var isBusy = false
        private set

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
        val claudePath = resolveClaudePath(settings.claudePath)

        debug("Resolved claude path: $claudePath")
        debug("Working directory: $workingDirectory")

        // Build the claude command args
        val claudeArgs = mutableListOf(
            claudePath, "-p",
            "--output-format", "stream-json",
            "--verbose"
        )
        val model = settings.model
        if (model.isNotBlank()) {
            claudeArgs.add("--model")
            claudeArgs.add(model)
        }
        // Always pass --permission-mode explicitly. In -p mode the CLI silently
        // refuses tool calls that need permission and never emits an interactive
        // prompt, so the user picks one of the modes that fits their tolerance
        // (acceptEdits / bypassPermissions / plan) instead of an unreachable
        // "ask each time" flow.
        val permMode = settings.permissionMode.ifBlank { com.claudecode.ClaudeConstants.PERMISSION_MODE_ACCEPT_EDITS }
        claudeArgs.add("--permission-mode")
        claudeArgs.add(permMode)
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

        debug("Starting process...")
        process = pb.start()
        debug("Process started (pid=${process?.pid()})")

        val responseText = StringBuilder()
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
                listeners.forEach { it.onError(this, "Claude exited with code $code") }
            }
        } catch (e: Exception) {
            debug("waitFor error: ${e.message}")
        }

        if (responseText.isNotEmpty()) {
            messages.add(ClaudeMessage("assistant", responseText.toString()))
        }

        isBusy = false
        listeners.forEach { it.onFinished(this, costUsd) }
        process = null
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
                    if (toolUseId != null) {
                        listeners.forEach { it.onToolResult(this, toolUseId, isError) }
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

        private fun resolveClaudePath(configured: String): String {
            // Absolute paths: pass through unchanged.
            if (configured.startsWith("/")) return configured
            if (isWindows() && configured.matches(Regex("^[A-Za-z]:[\\\\/].*"))) return configured
            if (cachedResolvedPath != null) return cachedResolvedPath!!

            if (isWindows()) {
                resolveOnWindows(configured)?.let {
                    cachedResolvedPath = it
                    return it
                }
                staticLog.warn("Could not resolve '$configured' on Windows via where, " +
                    "common npm locations, or `npm config get prefix`. " +
                    "Falling back to bare name; ProcessBuilder will likely fail. " +
                    "Set an absolute path in Settings → Tools → Claude Code → Claude CLI path.")
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
        private fun resolveOnWindows(configured: String): String? {
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
                    staticLog.info("resolveOnWindows: `where $configured` -> $preferred")
                    return preferred
                }
                staticLog.info("resolveOnWindows: `where $configured` returned no matches " +
                    "(exit=${if (finished) proc.exitValue() else "timeout"})")
            } catch (e: Exception) {
                staticLog.info("resolveOnWindows: `where` failed: ${e.message}")
            }

            // 2. Direct probes of common npm install dirs
            val nameCandidates = listOf("$configured.cmd", "$configured.exe", configured)
            val dirCandidates = listOfNotNull(
                System.getenv("APPDATA")?.let { "$it\\npm" },
                System.getenv("LOCALAPPDATA")?.let { "$it\\npm" },
                System.getenv("ProgramFiles")?.let { "$it\\nodejs" },
                System.getenv("ProgramFiles(x86)")?.let { "$it\\nodejs" },
            )
            for (dir in dirCandidates) {
                for (name in nameCandidates) {
                    val candidate = "$dir\\$name"
                    if (File(candidate).isFile) {
                        staticLog.info("resolveOnWindows: probed candidate exists: $candidate")
                        return candidate
                    }
                }
            }

            // 3. Ask npm for its global prefix
            try {
                val pb = ProcessBuilder("npm", "config", "get", "prefix").redirectErrorStream(true)
                val proc = pb.start()
                val output = proc.inputStream.bufferedReader().readText().trim()
                val finished = proc.waitFor(8, TimeUnit.SECONDS)
                if (finished && proc.exitValue() == 0 && output.isNotBlank()) {
                    val prefix = output.lines().firstOrNull { it.isNotBlank() }?.trim() ?: ""
                    if (prefix.isNotBlank()) {
                        for (name in nameCandidates) {
                            val candidate = "$prefix\\$name"
                            if (File(candidate).isFile) {
                                staticLog.info("resolveOnWindows: npm prefix '$prefix' -> $candidate")
                                return candidate
                            }
                        }
                        staticLog.info("resolveOnWindows: npm prefix '$prefix' has no $configured.cmd/exe/bare")
                    }
                }
            } catch (e: Exception) {
                staticLog.info("resolveOnWindows: `npm config get prefix` failed: ${e.message}")
            }

            return null
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
