package com.claudecode.mcp

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import java.awt.datatransfer.StringSelection
import java.util.concurrent.TimeUnit

/**
 * Kicks off authentication for a remote (sse/http) MCP server.
 *
 * Why a terminal and not a button-driven browser flow: the OAuth dance (open
 * browser → localhost callback → token exchange → token storage) is owned by the
 * `claude` binary and is only reachable through the interactive `/mcp` menu —
 * there is no headless `claude mcp authenticate`, and `claude -p` can't initiate
 * OAuth. Our chat sessions just consume the stored tokens once auth is done.
 * (For servers that accept a static token, the server's "API token" field skips
 * this whole flow — see McpServerEditDialog.)
 *
 * The Terminal plugin API varies across IDE versions, so everything here is
 * reflective and defensive: we treat *opening* a terminal as success and do the
 * typing best-effort, trying multiple method names and falling back to raw TTY
 * writes — so a post-open API mismatch never strands the user with both a blank
 * terminal AND the manual dialog.
 */
object McpAuthLauncher {
    private val LOG = Logger.getInstance(McpAuthLauncher::class.java)
    private val scheduler get() = com.intellij.util.concurrency.AppExecutorUtil.getAppScheduledExecutorService()

    fun authenticate(project: Project, server: McpServer) {
        // Preferred path: drive an interactive `claude` in a PTY we own, paced
        // off its real output (McpOAuthFlow). It runs hidden — the user finishes
        // in the browser. We only fall back to the IDE terminal when that path
        // isn't available (Windows: no `script`) or the process can't even start.
        if (McpOAuthFlow.isSupported() && McpOAuthFlow.authenticate(project, server)) return

        val workDir = project.basePath ?: System.getProperty("user.home")
        if (openInIdeTerminal(project, server, workDir)) {
            notifyNextSteps(project, server)
        } else {
            showManualInstructions(project, server, workDir)
        }
    }

    private fun openInIdeTerminal(project: Project, server: McpServer, workDir: String): Boolean {
        val widget = try {
            createTerminal(project, workDir, "MCP Auth — ${server.name}")
        } catch (t: Throwable) {
            LOG.info("IDE terminal unavailable for MCP auth; using manual fallback", t)
            null
        } ?: return false

        // A terminal is open → this is success. Typing below is best-effort and
        // must never flip us back to the manual dialog.
        //
        // Timing matters because the newer (2026.1+) terminal has no usable
        // high-level send API, so we write raw to the TTY — which is dropped if
        // the shell isn't connected yet. So: launch `claude` only once the shell
        // is ready, then send `/mcp` and the filter as input to claude's REPL
        // (raw, NOT shell commands) once it has had time to start.
        scheduleAt(2000) { sendCommand(widget, "claude") }   // shell ready → launch claude
        scheduleAt(6500) { typeRaw(widget, "/mcp\n") }        // claude REPL up → open the MCP menu
        scheduleAt(8000) { typeRaw(widget, server.name) }     // menu rendered → filter to this server
        return true
    }

    /** Open a terminal tab via whichever factory this IDE exposes. */
    private fun createTerminal(project: Project, workDir: String, tab: String): Any? {
        val mgr = Class.forName("org.jetbrains.plugins.terminal.TerminalToolWindowManager")
            .getMethod("getInstance", Project::class.java).invoke(null, project) ?: return null
        val b = java.lang.Boolean.TYPE
        val attempts = listOf(
            Triple("createShellWidget", arrayOf(String::class.java, String::class.java, b, b), arrayOf<Any?>(workDir, tab, true, false)),
            Triple("createLocalShellWidget", arrayOf(String::class.java, String::class.java, b), arrayOf<Any?>(workDir, tab, true)),
            Triple("createLocalShellWidget", arrayOf(String::class.java, String::class.java), arrayOf<Any?>(workDir, tab)),
        )
        for ((name, types, args) in attempts) {
            try {
                val m = mgr.javaClass.getMethod(name, *types)
                return m.invoke(mgr, *args)
            } catch (_: NoSuchMethodException) {
                // try the next signature
            }
        }
        return null
    }

    /** Run a line in the terminal: prefer the high-level (queued) API, else raw TTY. */
    private fun sendCommand(widget: Any, line: String) {
        val sender = widget.javaClass.methods.firstOrNull {
            it.name in SEND_METHODS && it.parameterCount == 1 && it.parameterTypes[0] == String::class.java
        }
        try {
            if (sender != null) sender.invoke(widget, line) else typeRaw(widget, line + "\n")
        } catch (t: Throwable) {
            runCatching { typeRaw(widget, line + "\n") }
        }
    }

    /**
     * Write raw text to the terminal's TTY (no trailing Enter unless included),
     * via TerminalWidget.getTtyConnector().write(String). Used to filter the
     * /mcp list to a server without auto-selecting it. No-ops on any failure.
     */
    private fun typeRaw(widget: Any, text: String) {
        val tty = widget.javaClass.getMethod("getTtyConnector").invoke(widget) ?: return
        tty.javaClass.methods.firstOrNull {
            it.name == "write" && it.parameterCount == 1 && it.parameterTypes[0] == String::class.java
        }?.invoke(tty, text)
    }

    private fun scheduleAt(delayMs: Long, action: () -> Unit) {
        scheduler.schedule({
            ApplicationManager.getApplication().invokeLater { runCatching { action() } }
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun notifyNextSteps(project: Project, server: McpServer) {
        val group = NotificationGroupManager.getInstance().getNotificationGroup("Claude Code Tasks") ?: return
        group.createNotification(
            "Authenticate MCP server “${server.name}”",
            "A terminal is opening <code>claude</code> → <b>/mcp</b>, filtered to <b>${server.name}</b>. " +
                "Press <b>Enter</b> to select it, then <b>Authenticate</b> — your browser opens to finish " +
                "sign-in. Afterward, new chat sessions use it automatically.<br/><br/>" +
                "If nothing was typed, run <code>claude</code> then <code>/mcp</code> in the terminal yourself. " +
                "Tip: many servers accept a personal access token instead — set it as the <b>API token</b> in " +
                "the server's Edit dialog and skip OAuth entirely.",
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

    private val SEND_METHODS = setOf("sendCommandToExecute", "executeCommand")
}
