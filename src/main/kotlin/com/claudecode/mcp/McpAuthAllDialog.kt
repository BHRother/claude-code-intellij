package com.claudecode.mcp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.Action
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.Timer
import javax.swing.table.AbstractTableModel

/**
 * Non-modal progress panel for "Authenticate All". Shows the queue (waiting /
 * authenticating / done / failed / skipped), the current server with a live
 * countdown of how long we'll wait, and lets the user <b>Skip current</b> or
 * <b>Remove</b> a pending server to avoid waiting. Closing it stops the run.
 */
class McpAuthAllDialog(project: Project, private val session: McpAuthAllSession) : DialogWrapper(project, false) {

    private val model = QueueModel()
    private val table = JBTable(model)
    private val header = JBLabel()
    private lateinit var skipButton: JButton
    private lateinit var removeButton: JButton
    private val ticker = Timer(1000) { updateHeader() }

    init {
        title = "Authenticate MCP Servers"
        isModal = false
        init()
        setOKButtonText("Close")
        session.onChange = { ApplicationManager.getApplication().invokeLater({ refresh() }, ModalityState.any()) }
        ticker.start()
        refresh()
    }

    override fun createCenterPanel(): JComponent {
        table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        table.rowHeight = JBUI.scale(22)
        table.columnModel.getColumn(0).preferredWidth = JBUI.scale(160)
        table.columnModel.getColumn(1).preferredWidth = JBUI.scale(300)
        table.selectionModel.addListSelectionListener { updateButtons() }

        skipButton = JButton("Skip selected").apply { addActionListener { skipSelected() } }
        removeButton = JButton("Remove selected").apply { addActionListener { removeSelected() } }
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            add(skipButton); add(removeButton)
        }

        val panel = JPanel(BorderLayout(0, JBUI.scale(8)))
        header.border = JBUI.Borders.emptyBottom(4)
        panel.add(header, BorderLayout.NORTH)
        panel.add(JBScrollPane(table), BorderLayout.CENTER)
        panel.add(buttons, BorderLayout.SOUTH)
        panel.preferredSize = Dimension(JBUI.scale(500), JBUI.scale(340))
        return panel
    }

    // Close-only; the run is stopped on dispose().
    override fun createActions(): Array<Action> = arrayOf(okAction)

    private fun removeSelected() {
        val row = table.selectedRow
        if (row in session.items.indices) session.removePending(session.items[row])
    }

    private fun skipSelected() {
        val row = table.selectedRow
        if (row in session.items.indices) session.skipRunning(session.items[row])
    }

    private fun refresh() {
        model.fireTableDataChanged()
        updateHeader()
        updateButtons()
    }

    private fun updateHeader() {
        val running = session.running
        header.text = when {
            session.done -> {
                val ok = session.items.count { it.state == McpAuthAllSession.State.SUCCESS }
                val tried = session.items.count {
                    it.state != McpAuthAllSession.State.REMOVED && it.state != McpAuthAllSession.State.PENDING
                }
                "<html>Done — <b>$ok of $tried</b> authenticated. You can close this.</html>"
            }
            running.isNotEmpty() -> {
                val names = running.joinToString(", ") { it.server.name }
                val remaining = (session.nextDeadline ?: System.currentTimeMillis()) - System.currentTimeMillis()
                val waiting = session.items.count { it.state == McpAuthAllSession.State.PENDING }
                val more = if (waiting > 0) " <i>($waiting more queued)</i>" else ""
                "<html>Signing in to <b>$names</b> — finish each in your browser.$more<br/>" +
                    "Up to <b>${fmt(remaining)}</b> left for the next one, or select a row and " +
                    "<b>Skip selected</b> to move on.</html>"
            }
            else -> "Preparing…"
        }
    }

    private fun updateButtons() {
        val row = table.selectedRow
        val selected = session.items.getOrNull(row)
        skipButton.isEnabled = selected?.state == McpAuthAllSession.State.RUNNING
        removeButton.isEnabled = selected?.state == McpAuthAllSession.State.PENDING
    }

    override fun dispose() {
        ticker.stop()
        session.onChange = null
        if (!session.done) session.cancelAll()   // closing the window stops the run
        super.dispose()
    }

    private fun fmt(ms: Long): String {
        val s = (ms.coerceAtLeast(0L) / 1000L)
        return "%d:%02d".format(s / 60, s % 60)
    }

    private inner class QueueModel : AbstractTableModel() {
        override fun getRowCount(): Int = session.items.size
        override fun getColumnCount(): Int = 2
        override fun getColumnName(column: Int): String = if (column == 0) "Server" else "Status"
        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val item = session.items[rowIndex]
            if (columnIndex == 0) return item.server.name
            val extra = if (item.state == McpAuthAllSession.State.FAILED && item.detail.isNotBlank())
                " — ${item.detail}" else ""
            return item.state.display + extra
        }
    }
}
