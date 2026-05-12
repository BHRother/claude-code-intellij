package com.claudecode.inline

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel

class InlineEditDiffDialog(
    private val project: Project,
    oldText: String,
    newText: String,
    filePath: String,
    private val onAccept: () -> Unit,
) : DialogWrapper(project, true) {

    private val request: SimpleDiffRequest

    init {
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(filePath)
        val factory = DiffContentFactory.getInstance()
        val left = factory.create(project, oldText, fileType)
        val right = factory.create(project, newText, fileType)
        request = SimpleDiffRequest(
            "Claude edit",
            left, right,
            "Original", "Suggested by Claude",
        )
        title = "Claude Edit — review changes"
        setOKButtonText("Accept")
        setCancelButtonText("Reject")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        val diffPanel = DiffManager.getInstance().createRequestPanel(project, disposable, null)
        diffPanel.setRequest(request)
        panel.add(diffPanel.component, BorderLayout.CENTER)
        panel.preferredSize = Dimension(900, 520)
        return panel
    }

    override fun doOKAction() {
        onAccept()
        super.doOKAction()
    }
}
