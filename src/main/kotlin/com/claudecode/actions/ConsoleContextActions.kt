package com.claudecode.actions

import com.claudecode.toolwindow.sendContextToClaudeToolWindow
import com.claudecode.toolwindow.sendToClaudeToolWindow
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys

abstract class BaseClaudeConsoleAction(
    private val prompt: String
) : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = editor != null &&
            editor.selectionModel.hasSelection()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val selectedText = editor.selectionModel.selectedText ?: return

        val message = buildString {
            append(prompt)
            append("\n\n```\n")
            append(selectedText)
            append("\n```")
        }
        sendToClaudeToolWindow(project, message)
    }
}

class DebugExceptionWithClaudeAction : BaseClaudeConsoleAction(
    """Analyze this console output. Focus on exceptions and errors.

1. **Execution Flow** — trace the path that led to the error as a numbered sequence: `ClassName.methodName()` (line N). Skip framework internals that don't add insight.
2. **Root Cause** — explain what went wrong, why, and under what conditions it triggers.
3. **Fix** — show the corrected code. If the root cause is ambiguous, list the most likely candidates with a fix for each."""
)

class ExplainConsoleWithClaudeAction : BaseClaudeConsoleAction(
    """Analyze the following log/console output. Your goal is to help me understand what the application did.

## Instructions

### 1. Group by thread or request flow
If the log lines contain thread names, request IDs, correlation IDs, or any identifier that ties lines together, group them by that identifier. If there is only one flow, no grouping header is needed.

### 2. For each group, build an execution flow
Parse each log line and extract the class, method, and line number when available. Present the flow as a numbered sequence like:

1. `ClassName.methodName()` (line 42) — Brief explanation of what this step did
2. `AnotherClass.process()` (line 118) — What happened here
   ↳ Detail or sub-step if relevant

Collapse consecutive lines from the same class/method into one entry when they represent the same logical operation. Do not list every single log line individually if they are part of the same step.

### 3. Exceptions (only if present)
If any stack traces or exceptions appear in the output, add a separate **Exceptions** section at the end of the relevant flow group:
- State the exception type and message
- Identify the root cause from the stack trace
- Point to the originating `ClassName.methodName()` (line N)
- Suggest a fix if possible

### 4. Format
- Use the file path and line number format `path/to/File.java:42` so I can navigate to the code
- Keep explanations concise — one line per flow step unless something unusual needs more detail
- If a step is a standard framework call (Spring, Hibernate, etc.) that doesn't add insight, skip it or summarize the framework block as one entry"""
)

class SendConsoleToClaudeAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = editor != null &&
            editor.selectionModel.hasSelection()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val selectedText = editor.selectionModel.selectedText ?: return

        val context = buildString {
            append("Console output:\n\n```\n")
            append(selectedText)
            append("\n```\n\n")
        }
        sendContextToClaudeToolWindow(project, context)
    }
}
