package com.claudecode.inline

import com.claudecode.cli.ClaudeOneShot
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.JLabel
import javax.swing.JPanel

class InlineEditAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible =
            editor != null && editor.selectionModel.hasSelection()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val selectedText = editor.selectionModel.selectedText ?: return
        if (selectedText.isBlank()) return

        val filePath = e.getData(CommonDataKeys.VIRTUAL_FILE)?.path ?: "unknown"
        showPromptPopup(project, editor, selectedText, filePath)
    }

    private fun showPromptPopup(
        project: Project,
        editor: Editor,
        selectedText: String,
        filePath: String,
    ) {
        val label = JLabel("Describe the edit (Enter to send, Esc to cancel)")
        val textField = JBTextField().apply {
            preferredSize = Dimension(520, preferredSize.height)
        }
        val panel = JPanel(BorderLayout(0, 6)).apply {
            border = JBUI.Borders.empty(8)
            add(label, BorderLayout.NORTH)
            add(textField, BorderLayout.CENTER)
        }

        val popup: JBPopup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, textField)
            .setTitle("Edit with Claude")
            .setMovable(true)
            .setRequestFocus(true)
            .setResizable(false)
            .setCancelOnClickOutside(true)
            .createPopup()

        textField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(ev: KeyEvent) {
                when (ev.keyCode) {
                    KeyEvent.VK_ENTER -> {
                        val instruction = textField.text?.trim().orEmpty()
                        if (instruction.isNotEmpty()) {
                            popup.closeOk(null)
                            runEdit(project, editor, selectedText, filePath, instruction)
                        }
                        ev.consume()
                    }
                    KeyEvent.VK_ESCAPE -> {
                        popup.cancel()
                        ev.consume()
                    }
                }
            }
        })

        popup.show(JBPopupFactory.getInstance().guessBestPopupLocation(editor))
    }

    private fun runEdit(
        project: Project,
        editor: Editor,
        selectedText: String,
        filePath: String,
        instruction: String,
    ) {
        val workingDir = project.basePath ?: System.getProperty("user.dir")
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project, "Claude: applying edit", true
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val prompt = buildPrompt(selectedText, filePath, instruction)
                val result = ClaudeOneShot.run(workingDir, prompt, timeoutSeconds = 120)
                val replacement = stripCodeFences(result.text)

                ApplicationManager.getApplication().invokeLater {
                    if (replacement.isBlank()) {
                        Messages.showWarningDialog(
                            project,
                            "Claude returned an empty response (exit ${result.exitCode}).",
                            "Edit with Claude",
                        )
                        return@invokeLater
                    }
                    showDiffAndApply(project, editor, selectedText, replacement, filePath)
                }
            }
        })
    }

    private fun buildPrompt(code: String, filePath: String, instruction: String): String {
        return """
            You are a focused code editor. The user selected this code from $filePath:

            <selection>
            $code
            </selection>

            Instruction: $instruction

            Return ONLY the replacement code, exactly as it should appear in the file.
            No markdown fences. No explanation. No preamble. Just the code.
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

    private fun showDiffAndApply(
        project: Project,
        editor: Editor,
        oldText: String,
        newText: String,
        filePath: String,
    ) {
        val dialog = InlineEditDiffDialog(project, oldText, newText, filePath) {
            val selectionModel = editor.selectionModel
            val start = selectionModel.selectionStart
            val end = selectionModel.selectionEnd
            WriteCommandAction.runWriteCommandAction(
                project, "Edit with Claude", null,
                { editor.document.replaceString(start, end, newText) },
            )
        }
        dialog.show()
    }
}
