package com.claudecode.ui

import com.claudecode.history.RecentSessionsStore
import com.claudecode.session.SessionManager
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import javax.swing.Icon

/**
 * Toolbar action surfacing recent Claude chats for the current project.
 * Clicking shows a popup list of recently-used sessions; picking one
 * spawns a new tab that resumes that conversation via
 * `claude -p --resume <id>`.
 *
 * Intentionally thin: all storage and retention live in
 * [com.claudecode.history.RecentSessionsStore]. If we later move the
 * "Recent" surface to a side panel or a status-bar widget, that consumer
 * calls the same store APIs — this file becomes optional.
 */
class RecentSessionsAction(private val project: Project) : AnAction(
    "Recent Sessions",
    "Resume a previous chat for this project",
    AllIcons.Vcs.History,
) {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val projectPath = project.basePath
        e.presentation.isEnabled = !projectPath.isNullOrBlank() &&
            RecentSessionsStore.recentForProject(projectPath).isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val projectPath = project.basePath ?: return
        val recents = RecentSessionsStore.recentForProject(projectPath)
        if (recents.isEmpty()) return

        val items = recents.map { entry ->
            // The label combines name + age + turn count so the user can
            // disambiguate quickly without a custom renderer. Kept inside
            // the data class as a precomputed string for simplicity.
            RecentItem(
                id = entry.id,
                label = "${entry.name}  —  ${buildSubtitle(entry.lastUsedAt, entry.messageCount)}",
            )
        }

        val popup = JBPopupFactory.getInstance().createListPopup(
            object : BaseListPopupStep<RecentItem>("Recent Chats", items) {
                override fun getTextFor(value: RecentItem): String = value.label
                override fun getIconFor(value: RecentItem): Icon = AllIcons.Vcs.History
                override fun onChosen(selectedValue: RecentItem, finalChoice: Boolean): PopupStep<*>? {
                    if (finalChoice) resumeSession(selectedValue.id, selectedValue.label)
                    return PopupStep.FINAL_CHOICE
                }
            }
        )
        // Show under the toolbar button when invoked from the UI; falls
        // back to the project frame center for keyboard-triggered cases.
        val component = e.inputEvent?.component
        if (component != null) popup.showUnderneathOf(component) else popup.showCenteredInCurrentWindow(project)
    }

    private fun resumeSession(claudeSessionId: String, @Suppress("UNUSED_PARAMETER") fallbackLabel: String) {
        val recent = project.basePath?.let { path ->
            RecentSessionsStore.recentForProject(path).firstOrNull { it.id == claudeSessionId }
        }
        // Prefer the original tab name (without the "— Nh ago · Mturns"
        // suffix we add for the popup label) so the resumed tab title
        // matches what the user originally named the chat.
        val name = recent?.name ?: fallbackLabel
        val workDir = recent?.workingDirectory ?: project.basePath ?: System.getProperty("user.home")
        SessionManager.getInstance(project).createSession(
            name = name,
            workDir = workDir,
            initialSessionId = claudeSessionId,
        )
    }

    private fun buildSubtitle(lastUsedAt: Long, messageCount: Int): String {
        val ago = humanizeAgo(System.currentTimeMillis() - lastUsedAt)
        val turns = "$messageCount turn${if (messageCount == 1) "" else "s"}"
        return "$ago · $turns"
    }

    private fun humanizeAgo(deltaMs: Long): String {
        val mins = deltaMs / 60_000L
        val hours = mins / 60L
        val days = hours / 24L
        return when {
            days >= 2 -> "${days}d ago"
            days == 1L -> "yesterday"
            hours >= 1 -> "${hours}h ago"
            mins >= 1 -> "${mins}m ago"
            else -> "just now"
        }
    }

    private data class RecentItem(
        val id: String,
        val label: String,
    )
}
