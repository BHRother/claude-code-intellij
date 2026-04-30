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
    selectionPrompt = """Refactor this code. For each change:
1. Show the refactored code
2. Explain what changed and why it's better

Focus on: readability, reducing complexity, eliminating duplication, and following idiomatic conventions for the language. Don't change external behavior. Prioritize impactful improvements over cosmetic ones.""",
    filePrompt = null
)

class ExplainWithClaudeAction : BaseClaudeEditorAction(
    selectionPrompt = """Explain this code:
1. **Purpose** — what problem it solves and when it runs
2. **How it works** — walk through the logic step by step
3. **Key decisions** — any non-obvious patterns, trade-offs, or edge cases handled

Be concise. Skip obvious details a developer reading the code would already understand.""",
    filePrompt = """Read this file and explain it:
1. **Purpose** — what this file is responsible for in the project
2. **Key components** — the main classes, functions, configurations, or resources it defines
3. **How it fits** — how it connects to or is used by other parts of the codebase
4. **Notable details** — any non-obvious patterns, edge cases, or important design decisions

Adapt the explanation to the file type (source code, config, infrastructure, build file, etc.)."""
)

class AddTestsWithClaudeAction : BaseClaudeEditorAction(
    selectionPrompt = """Generate unit tests for this code:
1. Use the same test framework and conventions already used in this project
2. Cover: happy path, edge cases, error conditions, and boundary values
3. Use descriptive test names that explain the scenario being tested
4. Each test should be independent and test one behavior""",
    filePrompt = """Read this file and generate unit tests for its public API:
1. Detect and use the same test framework and conventions already used in this project
2. Cover: happy path, edge cases, error conditions, and boundary values
3. Use descriptive test names that explain the scenario being tested
4. Each test should be independent and test one behavior
5. Group tests logically by the method or behavior they cover"""
)

class FixErrorWithClaudeAction : BaseClaudeEditorAction(
    selectionPrompt = """Analyze this code for bugs, errors, and potential issues. For each problem found:
1. **Bug** — describe the issue and how it manifests
2. **Cause** — explain why it happens
3. **Fix** — show the corrected code

If no bugs are found, note any fragile patterns that could break under edge cases.""",
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
