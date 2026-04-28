package com.claudecode.completion

import com.claudecode.session.ClaudeSession
import com.claudecode.settings.ClaudeSettings
import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.diagnostic.Logger
import com.intellij.patterns.PlatformPatterns
import com.intellij.util.ProcessingContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.*

class ClaudeCompletionContributor : CompletionContributor() {

    private val log = Logger.getInstance(ClaudeCompletionContributor::class.java)
    private val executor = Executors.newSingleThreadExecutor()

    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement(),
            ClaudeCompletionProvider()
        )
    }

    private inner class ClaudeCompletionProvider : CompletionProvider<CompletionParameters>() {

        override fun addCompletions(
            parameters: CompletionParameters,
            context: ProcessingContext,
            result: CompletionResultSet
        ) {
            val settings = ClaudeSettings.getInstance().state
            if (!settings.enableCompletion) return

            val editor = parameters.editor
            val document = editor.document
            val offset = parameters.offset

            val lineNumber = document.getLineNumber(offset)
            val lineStart = document.getLineStartOffset(lineNumber)
            val prefix = document.getText(com.intellij.openapi.util.TextRange(lineStart, offset))

            if (prefix.isBlank()) return

            // Get surrounding context (up to 20 lines before and 10 after)
            val contextStart = maxOf(0, lineNumber - 20)
            val contextEnd = minOf(document.lineCount - 1, lineNumber + 10)
            val contextStartOffset = document.getLineStartOffset(contextStart)
            val contextEndOffset = document.getLineEndOffset(contextEnd)
            val surroundingCode = document.getText(
                com.intellij.openapi.util.TextRange(contextStartOffset, contextEndOffset)
            )

            val file = parameters.originalFile
            val fileName = file.name

            try {
                val suggestions = getCompletions(surroundingCode, prefix, fileName, settings)
                for (suggestion in suggestions) {
                    result.addElement(
                        LookupElementBuilder.create(suggestion)
                            .withIcon(AllIcons.Actions.Lightning)
                            .withTypeText("Claude", true)
                            .withBoldness(true)
                    )
                }
            } catch (e: Exception) {
                log.debug("Completion failed: ${e.message}")
            }
        }

        private fun getCompletions(
            context: String,
            prefix: String,
            fileName: String,
            settings: ClaudeSettings.State
        ): List<String> {
            // Keep prompt short for speed
            val trimmedContext = context.lines().takeLast(15).joinToString("\n")
            val prompt = "Complete this code. ONLY return the completion, no explanation. 1-3 short completions, one per line.\n" +
                    "File: $fileName\n```\n$trimmedContext\n```\nComplete after: `$prefix`"

            val future = executor.submit(Callable {
                val claudePath = ClaudeSession.resolveClaudePathPublic(settings.claudePath)
                val shellPath = ClaudeSession.resolveShellPathPublic()

                val claudeArgs = listOf(
                    claudePath, "-p",
                    "--model", com.claudecode.ClaudeConstants.COMPLETION_MODEL,
                    "--max-tokens", "200",
                    prompt
                )
                val command = if (ClaudeSession.isMacOS()) {
                    mutableListOf("script", "-q", "/dev/null").apply { addAll(claudeArgs) }
                } else {
                    mutableListOf("script", "-q", "-c", claudeArgs.joinToString(" ") { "'${it.replace("'", "'\\''")}'"},  "/dev/null")
                }

                val pb = ProcessBuilder(command)
                    .redirectErrorStream(true)

                if (shellPath != null) {
                    pb.environment()["PATH"] = shellPath
                }
                pb.environment()["TERM"] = com.claudecode.ClaudeConstants.ENV_TERM_VALUE
                pb.environment()["NO_COLOR"] = "1"

                val process = pb.start()
                val output = BufferedReader(InputStreamReader(process.inputStream)).readText()

                val completed = process.waitFor(6, TimeUnit.SECONDS)
                if (!completed) {
                    process.destroyForcibly()
                    return@Callable emptyList<String>()
                }

                // Strip ANSI codes and filter to non-empty lines
                output.trim()
                    .replace(Regex("\u001B\\[[^a-zA-Z]*[a-zA-Z]"), "")
                    .lines()
                    .filter { it.isNotBlank() && !it.startsWith("{") }
                    .map { it.trim().removePrefix("```").removeSuffix("```").trim() }
                    .filter { it.isNotBlank() }
                    .take(3)
            })

            return try {
                future.get(8, TimeUnit.SECONDS)
            } catch (e: TimeoutException) {
                emptyList()
            }
        }
    }
}
