package com.claudecode.mcp

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel

/**
 * Lists every MCP server visible to this project (aggregated from `.mcp.json`,
 * `~/.claude.json` user + local scopes) and lets the user add/edit/remove them
 * and kick off authentication. Reads come from [McpConfigReader]; all writes go
 * through [McpCli] (`claude mcp …`) so we never edit `~/.claude.json` by hand.
 */
class McpServersDialog(private val project: Project) : DialogWrapper(project, true) {

    private val servers = mutableListOf<McpServer>()
    private val statusByName = HashMap<String, McpServerStatus>()
    private val tableModel = ServersTableModel()
    private val table = JBTable(tableModel)
    private val cli = McpCli(project.basePath)

    init {
        title = "MCP Servers — ${project.name}"
        setOKButtonText("Close")
        init()
        reload()
        refreshStatusAsync()
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)

    override fun createCenterPanel(): JComponent {
        table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        table.rowHeight = JBUI.scale(22)
        table.columnModel.getColumn(COL_NAME).preferredWidth = JBUI.scale(150)
        table.columnModel.getColumn(COL_SCOPE).preferredWidth = JBUI.scale(70)
        table.columnModel.getColumn(COL_TRANSPORT).preferredWidth = JBUI.scale(60)
        table.columnModel.getColumn(COL_DETAIL).preferredWidth = JBUI.scale(280)
        table.columnModel.getColumn(COL_STATUS).preferredWidth = JBUI.scale(150)
        object : com.intellij.ui.DoubleClickListener() {
            override fun onDoubleClick(event: java.awt.event.MouseEvent): Boolean {
                if (selectedServer() != null) { onEdit(); return true }
                return false
            }
        }.installOn(table)

        val decorator = ToolbarDecorator.createDecorator(table)
            .setAddAction { onAdd() }
            .setEditAction { onEdit() }
            .setRemoveAction { onRemove() }
            .setEditActionUpdater { selectedServer() != null }
            .setRemoveActionUpdater { selectedServer() != null }
            .addExtraAction(authAction())
            .addExtraAction(authAllAction())
            .addExtraAction(refreshAction())
            .disableUpDownActions()

        val panel = JPanel(BorderLayout())
        panel.add(decorator.createPanel(), BorderLayout.CENTER)
        val note = JBLabel(
            "<html>Changes apply to <b>new</b> sessions — restart open chats to pick them up. " +
                "Servers are managed via <code>claude mcp</code>; remote servers may need <b>Authenticate</b>.</html>"
        ).apply {
            border = JBUI.Borders.emptyTop(8)
            componentStyle = com.intellij.util.ui.UIUtil.ComponentStyle.SMALL
        }
        panel.add(note, BorderLayout.SOUTH)
        panel.preferredSize = Dimension(JBUI.scale(740), JBUI.scale(400))
        return panel
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    private fun authAction() = object : AnAction("Authenticate", "Authenticate the selected remote server", AllIcons.General.Web) {
        override fun actionPerformed(e: AnActionEvent) {
            val s = selectedServer() ?: return
            // Close this (application-modal) dialog first so the terminal can
            // take focus; authenticate on the next EDT cycle once it's gone.
            close(OK_EXIT_CODE)
            ApplicationManager.getApplication().invokeLater { McpAuthLauncher.authenticate(project, s) }
        }
        override fun update(e: AnActionEvent) {
            val s = selectedServer()
            // Offer Authenticate for any remote server that isn't token-based
            // (API Key) and isn't already connected — that covers OAuth servers
            // and bare/No-Auth servers that turn out to need OAuth (e.g. Sentry).
            e.presentation.isEnabled = s != null && canTryAuthenticate(s) &&
                statusByName[s.name] != McpServerStatus.CONNECTED
        }
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    /** A remote server we can attempt OAuth sign-in on: anything not configured with a token. */
    private fun canTryAuthenticate(s: McpServer): Boolean =
        s.isRemote && McpAuthType.infer(s) != McpAuthType.API_KEY

    private fun authAllAction() = object : AnAction(
        "Authenticate All",
        "Authenticate every remote server that needs it, one browser at a time",
        AllIcons.Actions.Execute,
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            val pending = serversToAuthenticate()
            if (pending.isEmpty()) {
                Messages.showInfoMessage(project, "No remote servers currently need authentication.", "Authenticate All")
                return
            }
            if (!McpOAuthFlow.isSupported()) {
                Messages.showWarningDialog(
                    project,
                    "Authenticate-all uses the macOS/Linux PTY flow. On Windows, authenticate servers individually.",
                    "Authenticate All",
                )
                return
            }
            val list = pending.joinToString("\n") { "• ${it.name}" }
            val ok = Messages.showYesNoDialog(
                project,
                "Authenticate these ${pending.size} server(s), one at a time? A browser tab opens for each — " +
                    "sign in as it appears and the next follows automatically:\n\n$list",
                "Authenticate All", "Start", "Cancel", Messages.getQuestionIcon(),
            )
            if (ok != Messages.YES) return
            // Close this (modal) dialog so the browser tabs/notifications aren't
            // blocked; run the sequential flow on the next EDT cycle.
            close(OK_EXIT_CODE)
            ApplicationManager.getApplication().invokeLater { McpOAuthFlow.authenticateAll(project, pending) }
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = serversToAuthenticate().isNotEmpty()
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    /**
     * Remote servers worth (re)authenticating: ones the health check flagged as
     * needing sign-in OR as failing to connect. `claude mcp list` reports an
     * unauthenticated OAuth server as "✗ Failed to connect" (not "needs auth"),
     * so FAILED must be included here or this would almost always be empty.
     * Servers still being checked (CHECKING/UNKNOWN) are excluded until a status
     * lands — hit Refresh and let the health check finish.
     */
    private fun serversToAuthenticate(): List<McpServer> =
        servers.filter {
            canTryAuthenticate(it) && statusByName[it.name] in setOf(McpServerStatus.NEEDS_AUTH, McpServerStatus.FAILED)
        }

    private fun refreshAction() = object : AnAction("Refresh", "Re-read config and re-check connection status", AllIcons.Actions.Refresh) {
        override fun actionPerformed(e: AnActionEvent) {
            reload()
            refreshStatusAsync()
        }
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    private fun onAdd() {
        val dialog = McpServerEditDialog(project, existing = null, takenNamesByScope = takenNames(excluding = null))
        if (dialog.showAndGet()) {
            val server = dialog.result ?: return
            applyMutation("Adding MCP server “${server.name}”") { cli.add(server) }
        }
    }

    private fun onEdit() {
        val selected = selectedServer() ?: return
        val status = statusByName[selected.name]
        val needsAuth = status == McpServerStatus.FAILED || status == McpServerStatus.NEEDS_AUTH
        val dialog = McpServerEditDialog(
            project, existing = selected,
            takenNamesByScope = takenNames(excluding = selected),
            needsAuthHint = needsAuth,
        )
        if (dialog.showAndGet()) {
            val updated = dialog.result ?: return
            applyMutation("Saving MCP server “${updated.name}”") { cli.edit(selected, updated) }
        }
    }

    private fun onRemove() {
        val selected = selectedServer() ?: return
        val ok = Messages.showYesNoDialog(
            project,
            "Remove MCP server “${selected.name}” from ${selected.scope.display}?",
            "Remove MCP Server",
            Messages.getQuestionIcon(),
        )
        if (ok == Messages.YES) {
            applyMutation("Removing MCP server “${selected.name}”") { cli.remove(selected.name, selected.scope) }
        }
    }

    /** Runs a CLI mutation under a modal progress, then refreshes on success or reports the error. */
    private fun applyMutation(progressTitle: String, op: () -> McpCli.Result) {
        val result = ProgressManager.getInstance().runProcessWithProgressSynchronously(
            ThrowableComputable { op() }, progressTitle, false, project,
        )
        if (result.success) {
            reload()
            refreshStatusAsync()
        } else {
            Messages.showErrorDialog(project, result.message().ifBlank { "The claude CLI reported a failure." }, progressTitle)
        }
    }

    // ------------------------------------------------------------------
    // Data
    // ------------------------------------------------------------------

    private fun reload() {
        servers.clear()
        servers.addAll(McpConfigReader.read(project.basePath))
        tableModel.fireTableDataChanged()
    }

    private fun refreshStatusAsync() {
        // Show "checking…" for everything, then fill from the health-checked list.
        statusByName.clear()
        servers.forEach { statusByName[it.name] = McpServerStatus.CHECKING }
        tableModel.fireTableDataChanged()
        val modality = ModalityState.stateForComponent(table)
        ApplicationManager.getApplication().executeOnPooledThread {
            val map = cli.list()
            ApplicationManager.getApplication().invokeLater({
                statusByName.clear()
                statusByName.putAll(map)
                tableModel.fireTableDataChanged()
            }, modality)
        }
    }

    private fun selectedServer(): McpServer? {
        val row = table.selectedRow
        if (row < 0 || row >= servers.size) return null
        return servers[table.convertRowIndexToModel(row)]
    }

    private fun takenNames(excluding: McpServer?): Map<McpScope, Set<String>> {
        val map = HashMap<McpScope, MutableSet<String>>()
        for (s in servers) {
            if (excluding != null && s.name == excluding.name && s.scope == excluding.scope) continue
            map.getOrPut(s.scope) { HashSet() }.add(s.name)
        }
        return map
    }

    // ------------------------------------------------------------------
    // Table model
    // ------------------------------------------------------------------

    private inner class ServersTableModel : AbstractTableModel() {
        override fun getRowCount(): Int = servers.size
        override fun getColumnCount(): Int = 5
        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false
        override fun getColumnName(column: Int): String = when (column) {
            COL_NAME -> "Name"
            COL_SCOPE -> "Scope"
            COL_TRANSPORT -> "Transport"
            COL_DETAIL -> "Command / URL"
            COL_STATUS -> "Status"
            else -> ""
        }

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val s = servers[rowIndex]
            return when (columnIndex) {
                COL_NAME -> s.name
                COL_SCOPE -> s.scope.badge
                COL_TRANSPORT -> s.transport.cliValue
                COL_DETAIL -> s.summary()
                COL_STATUS -> (statusByName[s.name] ?: McpServerStatus.UNKNOWN).label
                else -> ""
            }
        }
    }

    companion object {
        private const val COL_NAME = 0
        private const val COL_SCOPE = 1
        private const val COL_TRANSPORT = 2
        private const val COL_DETAIL = 3
        private const val COL_STATUS = 4
    }
}
