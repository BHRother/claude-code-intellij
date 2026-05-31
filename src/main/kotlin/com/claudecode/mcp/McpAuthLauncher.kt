package com.claudecode.mcp

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import java.awt.datatransfer.StringSelection

/**
 * Kicks off authentication for a remote (sse/http) MCP server.
 *
 * Why a terminal and not a button-driven browser flow: the OAuth dance (open
 * browser → localhost callback → token exchange → token storage) is owned by the
 * `claude` binary and is only reachable through the interactive `/mcp` menu —
 * there is no headless `claude mcp authenticate`. Our chat sessions run
 * `claude -p` (non-interactive) and therefore cannot perform the login, but they
 * **do** consume the stored tokens once auth is done. So we drop the user into an
 * interactive `claude` session in the project directory; after they authenticate
 * once, every later session just works.
 *
 * Primary path opens the IDE's built-in Terminal (cross-platform); if the
 * Terminal plugin is unavailable we fall back to copyable instructions.
 */
object McpAuthLauncher {
    private val LOG = Logger.getInstance(McpAuthLauncher::class.java)

    fun authenticate(project: Project, server: McpServer) {
        val workDir = project.basePath ?: System.getProperty("user.home")
        if (openInIdeTerminal(project, server, workDir)) {
            notifyNextSteps(project, server)
        } else {
            showManualInstructions(project, server, workDir)
        }
    }

    private fun openInIdeTerminal(project: Project, server: McpServer, workDir: String): Boolean {
        return try {
            val mgr = org.jetbrains.plugins.terminal.TerminalToolWindowManager.getInstance(project)
            // requestFocus=true so the tab is shown; deferSessionStartUntilUiShown=false
            // so the shell starts now and sendCommandToExecute runs immediately.
            val widget = mgr.createShellWidget(workDir, "MCP Auth — ${server.name}", true, false)
            widget.sendCommandToExecute("claude")
            // Drop the user straight onto the /mcp screen. We send it after a
            // short delay so the `claude` REPL is up and reading stdin — by then
            // the shell has already exec'd claude, so this goes to claude's input
            // (where /mcp opens the MCP manager), not back to the shell.
            com.intellij.util.concurrency.AppExecutorUtil.getAppScheduledExecutorService().schedule({
                ApplicationManager.getApplication().invokeLater {
                    runCatching { widget.sendCommandToExecute("/mcp") }
                }
            }, 1500, java.util.concurrent.TimeUnit.MILLISECONDS)
            true
        } catch (t: Throwable) {
            // Terminal plugin disabled/absent, or API mismatch — degrade gracefully.
            LOG.info("IDE terminal unavailable for MCP auth; using manual fallback", t)
            false
        }
    }

    private fun notifyNextSteps(project: Project, server: McpServer) {
        val group = NotificationGroupManager.getInstance().getNotificationGroup("Claude Code Tasks") ?: return
        group.createNotification(
            "Authenticate MCP server “${server.name}”",
            "A terminal is opening <code>claude</code> and the <b>/mcp</b> menu. Select " +
                "<b>${server.name}</b>, then <b>Authenticate</b> — your browser opens to finish sign-in. " +
                "Afterward, new chat sessions use the server automatically. " +
                "(If the menu didn't open, type <b>/mcp</b> once <code>claude</code> is ready.)",
            NotificationType.INFORMATION,
        ).notify(project)
    }

    private fun showManualInstructions(project: Project, server: McpServer, workDir: String) {
        ApplicationManager.getApplication().invokeLater {
            val message = "To authenticate “${server.name}”, open a terminal in:\n  $workDir\n\n" +
                "run:  claude\n\nthen type /mcp, select ${server.name}, and choose Authenticate.\n" +
                "Your browser opens to finish sign-in; new chat sessions then use the server automatically."
            val choice = Messages.showYesNoDialog(
                project,
                message,
                "Authenticate MCP Server — ${server.name}",
                "Copy “claude”",
                "Close",
                Messages.getInformationIcon(),
            )
            if (choice == Messages.YES) {
                CopyPasteManager.getInstance().setContents(StringSelection("claude"))
            }
        }
    }
}
