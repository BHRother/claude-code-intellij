package com.claudecode.toolwindow

import com.claudecode.session.ClaudeSession
import com.claudecode.session.SessionManager
import com.claudecode.session.SessionManagerListener
import com.claudecode.ui.SessionPanel
import com.claudecode.ui.WelcomePanel
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import javax.swing.SwingUtilities

class ClaudeToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val manager = ClaudeToolWindowManager(project, toolWindow)
        Disposer.register(toolWindow.disposable, manager)
    }
}

class ClaudeToolWindowManager(
    private val project: Project,
    private val toolWindow: ToolWindow
) : Disposable, SessionManagerListener {

    private val sessionManager = SessionManager.getInstance(project)
    private val sessionPanels = mutableMapOf<String, SessionPanel>()
    private val contentToSession = mutableMapOf<Content, ClaudeSession>()
    private var welcomeContent: Content? = null

    init {
        sessionManager.addListener(this)
        setupToolbar()

        // Handle tab close
        toolWindow.contentManager.addContentManagerListener(object : ContentManagerListener {
            override fun contentRemoved(event: ContentManagerEvent) {
                if (event.content == welcomeContent) {
                    welcomeContent = null
                    return
                }
                val session = contentToSession.remove(event.content)
                if (session != null) {
                    sessionPanels.remove(session.id)?.dispose()
                    sessionManager.removeSession(session)
                }
                // Show welcome panel when all sessions are closed
                if (contentToSession.isEmpty()) {
                    showWelcome()
                }
            }
        })

        showWelcome()
    }

    private fun showWelcome() {
        if (welcomeContent != null) return
        val panel = WelcomePanel { createSessionWithCheck() }
        welcomeContent = ContentFactory.getInstance().createContent(
            panel, "Welcome", false
        ).apply { isCloseable = false }
        toolWindow.contentManager.addContent(welcomeContent!!)
    }

    private fun removeWelcome() {
        welcomeContent?.let {
            toolWindow.contentManager.removeContent(it, true)
            welcomeContent = null
        }
    }

    private fun setupToolbar() {
        val actionGroup = com.intellij.openapi.actionSystem.DefaultActionGroup().apply {
            add(object : com.intellij.openapi.actionSystem.AnAction(
                "New Session",
                "Start a new Claude Code session",
                com.intellij.icons.AllIcons.General.Add
            ) {
                override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                    createSessionWithCheck()
                }
            })
            add(object : com.intellij.openapi.actionSystem.AnAction(
                "Close Session",
                "Close the current Claude Code session",
                com.intellij.icons.AllIcons.Actions.Close
            ) {
                override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                    val content = toolWindow.contentManager.selectedContent ?: return
                    if (content == welcomeContent) return
                    toolWindow.contentManager.removeContent(content, true)
                }

                override fun update(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                    val content = toolWindow.contentManager.selectedContent
                    e.presentation.isEnabled = content != null && content != welcomeContent
                }

                override fun getActionUpdateThread() =
                    com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
            })
            add(object : com.intellij.openapi.actionSystem.AnAction(
                "Rename Session",
                "Rename the current session tab",
                com.intellij.icons.AllIcons.Actions.Edit
            ) {
                override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                    val content = toolWindow.contentManager.selectedContent ?: return
                    val currentName = content.displayName
                    val newName = Messages.showInputDialog(
                        project,
                        "Enter new session name:",
                        "Rename Session",
                        null,
                        currentName,
                        null
                    )
                    if (!newName.isNullOrBlank()) {
                        content.displayName = newName
                    }
                }

                override fun update(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                    val content = toolWindow.contentManager.selectedContent
                    e.presentation.isEnabled = content != null && content != welcomeContent
                }

                override fun getActionUpdateThread() =
                    com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
            })
        }
        toolWindow.setTitleActions(actionGroup.getChildActionsOrStubs().toList())
    }

    override fun onSessionAdded(session: ClaudeSession) {
        removeWelcome()

        val panel = SessionPanel(project, session) { newName ->
            updateTabName(session, newName)
        }
        sessionPanels[session.id] = panel

        val content = ContentFactory.getInstance().createContent(
            panel,
            session.name,
            false
        ).apply {
            isCloseable = true
            tabName = session.name
        }

        contentToSession[content] = session
        toolWindow.contentManager.addContent(content)
        toolWindow.contentManager.setSelectedContent(content)
        panel.focusInput()
    }

    override fun onSessionRemoved(session: ClaudeSession) {
        // Handled by contentRemoved listener
    }

    fun updateTabName(session: ClaudeSession, name: String) {
        for ((content, s) in contentToSession) {
            if (s.id == session.id) {
                content.displayName = name
                break
            }
        }
    }

    private fun createSessionWithCheck() {
        val session = sessionManager.createSession()
        if (session == null) {
            Messages.showWarningDialog(
                project,
                "Maximum number of sessions reached. Close an existing session first, or increase the limit in Settings → Tools → Claude Code.",
                com.claudecode.ClaudeConstants.TOOL_WINDOW_ID
            )
        }
    }

    override fun dispose() {
        sessionManager.removeListener(this)
        sessionPanels.values.forEach { it.dispose() }
        sessionPanels.clear()
        contentToSession.clear()
    }
}

fun sendToClaudeToolWindow(project: Project, message: String) {
    val toolWindowManager = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
    val toolWindow = toolWindowManager.getToolWindow(com.claudecode.ClaudeConstants.TOOL_WINDOW_ID) ?: return

    toolWindow.show {
        val content = toolWindow.contentManager.selectedContent
        val panel = content?.component as? SessionPanel

        if (panel != null && !panel.isBusy()) {
            panel.sendPrefilled(message)
        } else {
            val session = SessionManager.getInstance(project).createSession()
            if (session != null) {
                SwingUtilities.invokeLater {
                    val newContent = toolWindow.contentManager.selectedContent
                    val newPanel = newContent?.component as? SessionPanel
                    newPanel?.sendPrefilled(message)
                }
            }
        }
    }
}

fun sendContextToClaudeToolWindow(project: Project, context: String) {
    val toolWindowManager = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
    val toolWindow = toolWindowManager.getToolWindow(com.claudecode.ClaudeConstants.TOOL_WINDOW_ID) ?: return

    toolWindow.show {
        val content = toolWindow.contentManager.selectedContent
        val panel = content?.component as? SessionPanel

        if (panel != null) {
            panel.prefillInput(context)
        } else {
            val session = SessionManager.getInstance(project).createSession()
            if (session != null) {
                SwingUtilities.invokeLater {
                    val newContent = toolWindow.contentManager.selectedContent
                    val newPanel = newContent?.component as? SessionPanel
                    newPanel?.prefillInput(context)
                }
            }
        }
    }
}
