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
    """Analyze the following console output. Focus on exceptions and errors.

1. **Execution Flow**: Trace the execution path that led to the error. For each step, show: `ClassName.methodName()` (line N). Present as a numbered sequence.

2. **Root Cause**: Explain what went wrong and why.

3. **Fix**: Suggest a concrete fix with code if possible."""
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
