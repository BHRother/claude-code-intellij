package com.claudecode.actions

import com.claudecode.session.SessionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.icons.AllIcons

class NewSessionAction : AnAction(
    "New Claude Session",
    "Start a new Claude Code session",
    AllIcons.General.Add
) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        if (SessionManager.getInstance(project).createSession() == null) {
            com.intellij.openapi.ui.Messages.showWarningDialog(
                project,
                "Maximum number of sessions reached. Close an existing session first, or increase the limit in Settings → Tools → Claude Code.",
                com.claudecode.ClaudeConstants.TOOL_WINDOW_ID
            )
        }
    }
}
