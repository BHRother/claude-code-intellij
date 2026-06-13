package com.claudecode.memory

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea

/**
 * Editor for Claude Code's CLAUDE.md memory at the project / project-local / user
 * scope. Plain Markdown (no schema), so just a scoped text area + Save, plus an
 * "Open in editor" escape hatch for richer Markdown editing in the IDE.
 * Switching scope with unsaved edits prompts to save.
 */
class ClaudeMemoryDialog(
    private val project: Project,
    private val projectBasePath: String?,
) : DialogWrapper(project, true) {

    private val scopeCombo = ComboBox(MemoryFileScope.entries.toTypedArray())
    private val pathLabel = JBLabel().apply { componentStyle = UIUtil.ComponentStyle.SMALL }
    private val area = JTextArea()

    private var currentScope = MemoryFileScope.PROJECT
    private var originalText = ""
    private var loading = false

    init {
        title = "CLAUDE.md Memory — ${project.name}"
        setOKButtonText("Save")
        init()
        loadScope(currentScope)
    }

    override fun createCenterPanel(): JComponent {
        scopeCombo.renderer = com.claudecode.ui.comboTextRenderer<MemoryFileScope> { it.display }
        scopeCombo.addActionListener { if (!loading) onScopeChange() }

        area.font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scaleFontSize(12f))
        area.tabSize = 2
        area.lineWrap = true
        area.wrapStyleWord = true

        val openButton = JButton("Open in editor").apply {
            toolTipText = "Save and open this file in the IDE editor (Markdown preview, etc.)"
            addActionListener { openInEditor() }
        }
        val top = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            add(JBLabel("Scope:"), BorderLayout.WEST)
            add(scopeCombo, BorderLayout.CENTER)
            add(openButton, BorderLayout.EAST)
        }
        val header = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            add(top, BorderLayout.NORTH)
            add(pathLabel, BorderLayout.SOUTH)
        }
        val note = JBLabel(
            "<html>Standing instructions/context Claude reads every session — build/test commands, " +
                "architecture notes, conventions. Applies to <b>new</b> sessions. " +
                "Tip: run <code>/init</code> in a chat to have Claude generate this for you.</html>"
        ).apply {
            componentStyle = UIUtil.ComponentStyle.SMALL
            border = JBUI.Borders.emptyTop(8)
        }

        val panel = JPanel(BorderLayout(0, JBUI.scale(6)))
        panel.add(header, BorderLayout.NORTH)
        panel.add(JBScrollPane(area), BorderLayout.CENTER)
        panel.add(note, BorderLayout.SOUTH)
        panel.preferredSize = Dimension(JBUI.scale(640), JBUI.scale(460))
        return panel
    }

    private fun loadScope(scope: MemoryFileScope) {
        loading = true
        try {
            val file = scope.file(projectBasePath)
            pathLabel.text = file.absolutePath
            originalText = ClaudeMemoryStore.readText(file)
            area.text = originalText
            area.caretPosition = 0
        } finally {
            loading = false
        }
    }

    private fun onScopeChange() {
        val target = scopeCombo.selectedItem as MemoryFileScope
        if (target == currentScope) return
        if (!leaveCurrentScope()) {
            loading = true
            scopeCombo.selectedItem = currentScope
            loading = false
            return
        }
        currentScope = target
        loadScope(target)
    }

    /** Offer to save the current scope before leaving it. False = stay. */
    private fun leaveCurrentScope(): Boolean {
        if (!isDirty()) return true
        return when (
            Messages.showYesNoCancelDialog(
                project,
                "Save changes to ${currentScope.display}?",
                "Unsaved CLAUDE.md Changes",
                "Save", "Discard", "Cancel", Messages.getQuestionIcon(),
            )
        ) {
            Messages.YES -> saveCurrent()
            Messages.NO -> true
            else -> false
        }
    }

    private fun isDirty(): Boolean = area.text != originalText

    private fun saveCurrent(): Boolean {
        val file = currentScope.file(projectBasePath)
        val result = ClaudeMemoryStore.write(file, area.text)
        if (!result.success) {
            Messages.showErrorDialog(project, result.error ?: "Failed to write ${file.absolutePath}", "Save Failed")
            return false
        }
        originalText = area.text
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
        return true
    }

    private fun openInEditor() {
        if (!saveCurrent()) return
        val file = currentScope.file(projectBasePath)
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
        if (vf != null) {
            FileEditorManager.getInstance(project).openFile(vf, true)
            close(OK_EXIT_CODE)
        } else {
            Messages.showInfoMessage(project, "Saved to ${file.absolutePath}.", "CLAUDE.md")
        }
    }

    override fun doOKAction() {
        if (!saveCurrent()) return
        super.doOKAction()
    }

    override fun doCancelAction() {
        if (isDirty() &&
            Messages.showYesNoDialog(
                project, "Discard unsaved changes to ${currentScope.display}?",
                "Discard Changes", Messages.getQuestionIcon(),
            ) != Messages.YES
        ) {
            return
        }
        super.doCancelAction()
    }
}
