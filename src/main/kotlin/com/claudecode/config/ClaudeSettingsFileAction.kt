package com.claudecode.config

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project

/**
 * Toolbar action: opens the structured + raw editor for Claude Code's own
 * settings.json files (project / project-local / global). Distinct from the
 * gear icon, which opens the plugin's own Settings dialog.
 */
class ClaudeSettingsFileAction(private val project: Project) : AnAction(
    "Claude Settings Files",
    "Edit Claude Code's settings.json (project / local / global)",
    AllIcons.FileTypes.Json,
) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = !project.basePath.isNullOrBlank()
    }

    override fun actionPerformed(e: AnActionEvent) {
        ClaudeSettingsFileDialog(project, project.basePath).show()
    }
}
