package com.claudecode.actions

import com.claudecode.ui.SessionPanel
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager
import javax.swing.SwingUtilities

class ToggleClaudeAction : AnAction(
    "Toggle Claude Code",
    "Open or focus the Claude Code panel",
    null
) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(com.claudecode.ClaudeConstants.TOOL_WINDOW_ID) ?: return

        if (toolWindow.isVisible) {
            toolWindow.hide()
        } else {
            toolWindow.show {
                SwingUtilities.invokeLater {
                    val panel = toolWindow.contentManager.selectedContent?.component as? SessionPanel
                    panel?.focusInput()
                }
            }
        }
    }
}
