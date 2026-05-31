package com.claudecode.mcp

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project

/**
 * Tool-window header action that opens the MCP server manager. Disabled when the
 * project has no base path (the CLI is cwd-relative, so we need one).
 */
class McpServersAction(private val project: Project) : AnAction(
    "MCP Servers",
    "Manage MCP servers for this project",
    AllIcons.Nodes.Plugin,
) {
    override fun actionPerformed(e: AnActionEvent) {
        McpServersDialog(project).show()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = !project.basePath.isNullOrBlank()
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT
}
