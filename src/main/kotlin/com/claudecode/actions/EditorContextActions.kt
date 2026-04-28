package com.claudecode.actions

import com.claudecode.settings.ClaudeSettings
import com.claudecode.toolwindow.sendContextToClaudeToolWindow
import com.claudecode.toolwindow.sendToClaudeToolWindow
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys

abstract class BaseClaudeEditorAction(
    private val selectionPrompt: String,
    private val filePrompt: String?,
    private val requiresSelection: Boolean = filePrompt == null
) : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        if (editor == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val hasSelection = editor.selectionModel.hasSelection()
        e.presentation.isEnabledAndVisible = hasSelection || !requiresSelection
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val filePath = file?.path ?: "unknown"
        val sendContext = ClaudeSettings.getInstance().state.sendSelectionContext

        val selectedText = editor.selectionModel.selectedText
        val hasSelection = !selectedText.isNullOrBlank()

        val message = if (hasSelection && sendContext) {
            buildString {
                append(selectionPrompt)
                append("\n\nFile: $filePath\n")
                append("```\n")
                append(selectedText)
                append("\n```")
            }
        } else if (hasSelection) {
            "$selectionPrompt\n\nFile: $filePath"
        } else {
            buildString {
                append(filePrompt ?: return)
                append("\n\nFile: $filePath")
            }
        }

        sendToClaudeToolWindow(project, message)
    }
}

class RefactorWithClaudeAction : BaseClaudeEditorAction(
    selectionPrompt = "Refactor the following code. Suggest improvements for readability, performance, and best practices.",
    filePrompt = null
)

class ExplainWithClaudeAction : BaseClaudeEditorAction(
    selectionPrompt = "Explain the following code in detail. What does it do, and why?",
    filePrompt = "Explain this file in detail. What does it do, what are the key classes/methods, and why is it structured this way?"
)

class AddTestsWithClaudeAction : BaseClaudeEditorAction(
    selectionPrompt = "Generate comprehensive unit tests for the following code.",
    filePrompt = "Generate comprehensive unit tests for all the public methods in this file."
)

class FixErrorWithClaudeAction : BaseClaudeEditorAction(
    selectionPrompt = "Identify and fix any bugs or errors in the following code.",
    filePrompt = null
)

class SendToClaudeAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val filePath = file?.path ?: "unknown"

        val selectedText = editor.selectionModel.selectedText
        val hasSelection = !selectedText.isNullOrBlank()

        val context = if (hasSelection) {
            "File: $filePath\n```\n$selectedText\n```\n\n"
        } else {
            "File: $filePath\n\n"
        }

        sendContextToClaudeToolWindow(project, context)
    }
}
