package com.claudecode.inspection

import com.claudecode.cli.ClaudeOneShot
import com.claudecode.inline.InlineEditDiffDialog
import com.claudecode.ui.MarkdownRenderer
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Iconable
import com.intellij.openapi.util.IconLoader
import com.intellij.psi.PsiFile
import javax.swing.Icon

class ClaudeFixIntentionAction : IntentionAction, Iconable {

    override fun getText(): String = "Fix with Claude"
    override fun getFamilyName(): String = "Claude Code"
    override fun startInWriteAction(): Boolean = false
    override fun getIcon(flags: Int): Icon = CLAUDE_ICON

    private companion object {
        private val CLAUDE_ICON: Icon =
            IconLoader.getIcon("/icons/claude.svg", ClaudeFixIntentionAction::class.java)
    }

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        if (editor == null || file == null) return false
        return findHighlightAtCaret(project, editor) != null
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null || file == null) return
        val info = findHighlightAtCaret(project, editor) ?: return

        val document = editor.document
        val lineStart = document.getLineStartOffset(document.getLineNumber(info.startOffset))
        val lineEnd = document.getLineEndOffset(document.getLineNumber(info.endOffset))
        val regionText = document.getText(com.intellij.openapi.util.TextRange(lineStart, lineEnd))

        val filePath = file.virtualFile?.path ?: file.name
        val lineNumber = document.getLineNumber(info.startOffset) + 1
        val language = MarkdownRenderer.languageFromFilePath(filePath)
        val message = info.description ?: info.toolTip ?: "code issue"
        val workingDir = project.basePath ?: System.getProperty("user.dir")

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project, "Claude: fixing $message", true
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val prompt = buildPrompt(language, filePath, lineNumber, message, regionText)
                val result = ClaudeOneShot.run(workingDir, prompt, timeoutSeconds = 90)
                val replacement = stripCodeFences(result.text)

                ApplicationManager.getApplication().invokeLater {
                    if (replacement.isBlank()) {
                        Messages.showWarningDialog(
                            project,
                            "Claude returned an empty response (exit ${result.exitCode}).",
                            "Fix with Claude",
                        )
                        return@invokeLater
                    }

                    val dialog = InlineEditDiffDialog(project, regionText, replacement, filePath) {
                        WriteCommandAction.runWriteCommandAction(
                            project, "Fix with Claude", null,
                            { document.replaceString(lineStart, lineEnd, replacement) },
                        )
                    }
                    dialog.show()
                }
            }
        })
    }

    private fun findHighlightAtCaret(project: Project, editor: Editor): HighlightInfo? {
        val offset = editor.caretModel.offset
        val document = editor.document
        val markup = DocumentMarkupModel.forDocument(document, project, false) ?: return null
        var best: HighlightInfo? = null
        for (highlighter in markup.allHighlighters) {
            if (offset < highlighter.startOffset || offset > highlighter.endOffset) continue
            val info = HighlightInfo.fromRangeHighlighter(highlighter) ?: continue
            if (info.severity.compareTo(HighlightSeverity.WARNING) < 0) continue
            val current = best
            if (current == null || info.severity.compareTo(current.severity) > 0) {
                best = info
            }
        }
        return best
    }

    private fun buildPrompt(
        language: String,
        filePath: String,
        lineNumber: Int,
        message: String,
        code: String,
    ): String {
        val cleanedMessage = message
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .trim()
        return """
            Fix this $language issue: "$cleanedMessage"

            File: $filePath (line $lineNumber)

            Code with the issue:
            $code

            Return ONLY the fixed version of the exact lines above. No markdown fences, no explanation, no extra blank lines around the code. The result will replace the original lines verbatim.
        """.trimIndent()
    }

    private fun stripCodeFences(text: String): String {
        val trimmed = text.trim()
        val lines = trimmed.lines()
        if (lines.size >= 2 &&
            lines.first().trimStart().startsWith("```") &&
            lines.last().trim() == "```"
        ) {
            return lines.subList(1, lines.size - 1).joinToString("\n")
        }
        return trimmed
    }
}
