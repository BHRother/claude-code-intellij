package com.claudecode.config

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel

/**
 * Editor for Claude Code's `settings.json` at the project / project-local /
 * global scope. Each scope can be edited two ways, switchable via tabs:
 *   - **Form**: a structured key → value table (add / edit / remove top-level keys)
 *   - **JSON**: the raw file text, for power edits
 *
 * The two views share one in-memory [model]; switching tabs syncs between them.
 * Switching scope with unsaved edits prompts to save. Writes go through
 * [ClaudeSettingsFileStore] (validated, whole-object) so unrelated keys survive.
 */
class ClaudeSettingsFileDialog(
    private val project: Project,
    private val projectBasePath: String?,
) : DialogWrapper(project, true) {

    private val scopeCombo = com.intellij.openapi.ui.ComboBox(SettingsFileScope.entries.toTypedArray())
    private val pathLabel = JBLabel().apply { componentStyle = UIUtil.ComponentStyle.SMALL }
    private val tabs = JBTabbedPane()
    private val tableModel = EntriesTableModel()
    private val table = JBTable(tableModel)
    private val jsonArea = javax.swing.JTextArea()

    private var currentScope = SettingsFileScope.PROJECT
    private var model = JsonObject()
    private var originalSerialized = "{}"
    /** Guards listeners while we programmatically reload. */
    private var loading = false
    private var lastTabIndex = TAB_FORM

    init {
        title = "Claude Settings Files — ${project.name}"
        setOKButtonText("Save")
        init()
        loadScope(currentScope)
    }

    override fun createCenterPanel(): JComponent {
        scopeCombo.renderer = SimpleListCellRenderer.create("") { it.display }
        scopeCombo.addActionListener { if (!loading) onScopeChange() }

        // Form tab
        table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        table.rowHeight = JBUI.scale(22)
        object : com.intellij.ui.DoubleClickListener() {
            override fun onDoubleClick(event: java.awt.event.MouseEvent): Boolean {
                if (selectedKey() != null) { onEdit(); return true }
                return false
            }
        }.installOn(table)
        val formPanel = ToolbarDecorator.createDecorator(table)
            .setAddAction { onAdd() }
            .setEditAction { onEdit() }
            .setRemoveAction { onRemove() }
            .setEditActionUpdater { selectedKey() != null }
            .setRemoveActionUpdater { selectedKey() != null }
            .disableUpDownActions()
            .createPanel()

        // JSON tab
        jsonArea.font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scaleFontSize(12f))
        jsonArea.tabSize = 2

        tabs.addTab("Form", formPanel)
        tabs.addTab("JSON", JBScrollPane(jsonArea))
        tabs.addChangeListener { if (!loading) onTabChange() }

        val top = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            add(JBLabel("Scope:"), BorderLayout.WEST)
            add(scopeCombo, BorderLayout.CENTER)
        }
        val header = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            add(top, BorderLayout.NORTH)
            add(pathLabel, BorderLayout.SOUTH)
        }
        val note = JBLabel(
            "<html>Edits apply to <b>new</b> sessions. Project-local also holds the plugin's " +
                "permission grants — unrelated keys are preserved on save.</html>"
        ).apply {
            componentStyle = UIUtil.ComponentStyle.SMALL
            border = JBUI.Borders.emptyTop(8)
        }

        val panel = JPanel(BorderLayout(0, JBUI.scale(6)))
        panel.add(header, BorderLayout.NORTH)
        panel.add(tabs, BorderLayout.CENTER)
        panel.add(note, BorderLayout.SOUTH)
        panel.preferredSize = Dimension(JBUI.scale(640), JBUI.scale(460))
        return panel
    }

    // ------------------------------------------------------------------
    // Scope / tab orchestration
    // ------------------------------------------------------------------

    private fun loadScope(scope: SettingsFileScope) {
        loading = true
        try {
            val file = scope.file(projectBasePath)
            pathLabel.text = file.absolutePath
            val text = ClaudeSettingsFileStore.readText(file)
            model = parseObjectOrEmpty(text)
            originalSerialized = PRETTY.toJson(model)
            jsonArea.text = originalSerialized
            tableModel.refresh()
            lastTabIndex = tabs.selectedIndex
        } finally {
            loading = false
        }
    }

    private fun onScopeChange() {
        val target = scopeCombo.selectedItem as SettingsFileScope
        if (target == currentScope) return
        if (!leaveCurrentScope()) {
            // Revert the combo without retriggering.
            loading = true
            scopeCombo.selectedItem = currentScope
            loading = false
            return
        }
        currentScope = target
        loadScope(target)
    }

    /** Sync + offer to save the current scope before leaving it. False = stay. */
    private fun leaveCurrentScope(): Boolean {
        if (!syncActiveTabToModel()) return false
        if (!isDirty()) return true
        return when (
            Messages.showYesNoCancelDialog(
                project,
                "Save changes to ${currentScope.display}?",
                "Unsaved Settings Changes",
                "Save", "Discard", "Cancel", Messages.getQuestionIcon(),
            )
        ) {
            Messages.YES -> saveCurrent()
            Messages.NO -> true   // discard
            else -> false         // cancel → stay
        }
    }

    private fun onTabChange() {
        val newIndex = tabs.selectedIndex
        if (newIndex == lastTabIndex) return
        if (lastTabIndex == TAB_JSON) {
            // Leaving JSON: parse text into the model; refuse to leave if invalid.
            val parsed = parseObjectOrNull(jsonArea.text)
            if (parsed == null) {
                Messages.showErrorDialog(project, "The JSON isn't a valid object. Fix it before switching tabs.", "Invalid JSON")
                loading = true; tabs.selectedIndex = TAB_JSON; loading = false
                return
            }
            model = parsed
        }
        if (newIndex == TAB_FORM) tableModel.refresh() else jsonArea.text = PRETTY.toJson(model)
        lastTabIndex = newIndex
    }

    /**
     * Pull the active tab's content into [model]. False if the JSON tab doesn't
     * hold a valid JSON object. [showError] pops an error dialog on failure —
     * suppressed on Cancel, where we just want to know "is there unsaved work?".
     */
    private fun syncActiveTabToModel(showError: Boolean = true): Boolean {
        if (tabs.selectedIndex == TAB_JSON) {
            val parsed = parseObjectOrNull(jsonArea.text)
            if (parsed == null) {
                if (showError) Messages.showErrorDialog(project, "The JSON isn't a valid object.", "Invalid JSON")
                return false
            }
            model = parsed
        }
        return true
    }

    private fun isDirty(): Boolean = PRETTY.toJson(model) != originalSerialized

    private fun saveCurrent(): Boolean {
        val file = currentScope.file(projectBasePath)
        val result = ClaudeSettingsFileStore.write(file, PRETTY.toJson(model))
        if (!result.success) {
            Messages.showErrorDialog(project, result.error ?: "Failed to write ${file.absolutePath}", "Save Failed")
            return false
        }
        originalSerialized = PRETTY.toJson(model)
        com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
        return true
    }

    // ------------------------------------------------------------------
    // Form actions (operate on `model`)
    // ------------------------------------------------------------------

    private fun keys(): List<String> = model.keySet().toList()

    private fun selectedKey(): String? {
        val row = table.selectedRow
        val keys = keys()
        if (row < 0 || row >= keys.size) return null
        return keys[table.convertRowIndexToModel(row)]
    }

    private fun onAdd() {
        val dialog = SettingsEntryDialog(project, existingKey = null, existingValueJson = null, takenKeys = model.keySet())
        if (dialog.showAndGet()) {
            model.add(dialog.resultKey, dialog.resultValue)
            tableModel.refresh()
        }
    }

    private fun onEdit() {
        val key = selectedKey() ?: return
        val current = PRETTY.toJson(model.get(key))
        val dialog = SettingsEntryDialog(project, existingKey = key, existingValueJson = current, takenKeys = model.keySet())
        if (dialog.showAndGet()) {
            val newKey = dialog.resultKey!!
            if (newKey != key) model.remove(key)
            model.add(newKey, dialog.resultValue)
            tableModel.refresh()
        }
    }

    private fun onRemove() {
        val key = selectedKey() ?: return
        if (Messages.showYesNoDialog(project, "Remove setting “$key”?", "Remove Setting", Messages.getQuestionIcon()) == Messages.YES) {
            model.remove(key)
            tableModel.refresh()
        }
    }

    override fun doOKAction() {
        if (!syncActiveTabToModel()) return
        if (!saveCurrent()) return
        super.doOKAction()
    }

    override fun doCancelAction() {
        // Warn if there are unsaved edits — without erroring on invalid JSON
        // (invalid JSON in the raw tab simply counts as "has unsaved work").
        val hasUnsaved = !syncActiveTabToModel(showError = false) || isDirty()
        if (hasUnsaved) {
            if (Messages.showYesNoDialog(project, "Discard unsaved changes to ${currentScope.display}?", "Discard Changes", Messages.getQuestionIcon()) != Messages.YES) {
                return
            }
        }
        super.doCancelAction()
    }

    // ------------------------------------------------------------------

    private inner class EntriesTableModel : AbstractTableModel() {
        fun refresh() = fireTableDataChanged()
        override fun getRowCount(): Int = model.size()
        override fun getColumnCount(): Int = 2
        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false
        override fun getColumnName(column: Int): String = if (column == 0) "Key" else "Value"
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val key = keys()[rowIndex]
            return if (columnIndex == 0) key else COMPACT.toJson(model.get(key))
        }
    }

    companion object {
        private const val TAB_FORM = 0
        private const val TAB_JSON = 1
        private val PRETTY = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
        private val COMPACT = GsonBuilder().disableHtmlEscaping().create()

        private fun parseObjectOrNull(text: String): JsonObject? = try {
            val t = text.trim().ifBlank { "{}" }
            JsonParser.parseString(t).takeIf { it.isJsonObject }?.asJsonObject
        } catch (e: Exception) {
            null
        }

        private fun parseObjectOrEmpty(text: String): JsonObject = parseObjectOrNull(text) ?: JsonObject()
    }
}
