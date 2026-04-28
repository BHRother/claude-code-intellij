package com.claudecode.ui

import com.claudecode.session.ClaudeSession
import com.claudecode.session.SessionListener
import com.claudecode.settings.ClaudeSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.*
import javax.swing.event.HyperlinkEvent
import javax.swing.text.DefaultCaret
import javax.swing.text.html.HTMLDocument
import javax.swing.text.html.HTMLEditorKit

class SessionPanel(
    private val project: Project,
    private val session: ClaudeSession,
    private val onTabRename: (String) -> Unit = {}
) : JPanel(BorderLayout()), SessionListener {

    private lateinit var outputPane: JTextPane
    private lateinit var inputArea: JTextArea
    private val sendStopButton: JButton
    private val statusLabel: JLabel
    private val modelLabel: JLabel
    private val thinkingLabel: JLabel
    private val debugArea: JTextArea
    private val debugToggle: JCheckBox
    private var thinkingTimer: Timer? = null
    private var dotCount = 0
    private var hasAutoNamed = false
    private var lastCtrlCTime = 0L
    private var ctrlCDispatcher: java.awt.KeyEventDispatcher? = null
    private val changedFiles = mutableListOf<Pair<String, String>>() // (filePath, action)
    private val copyableCommands = mutableMapOf<String, String>()
    private val pendingDiffs = mutableMapOf<String, String>() // diffId -> diff HTML
    private val expandedDiffs = mutableSetOf<String>()
    private var copyCommandCounter = 0
    private var toolUseCounter = 0
    private val toolUseIdToHtmlId = mutableMapOf<String, String>()
    private var permissionPanel: JPanel? = null

    // Edit consolidation state: consecutive Edit calls on the same file update one UI entry
    private var lastEditFilePath: String? = null
    private var lastEditElementId: String? = null
    private var lastEditCount: Int = 0
    private var lastEditAddedLines: Int = 0
    private var lastEditRemovedLines: Int = 0
    private var lastEditDiffPairs: MutableList<Pair<String, String>> = mutableListOf()
    private var lastEditDisplayText: String? = null

    init {
        val settings = ClaudeSettings.getInstance().state
        val monoFont = Font(com.claudecode.ClaudeConstants.FONT_FAMILY, Font.PLAIN, settings.fontSize)
        val smallFont = monoFont.deriveFont(11f)

        // Model info bar at top
        val configuredModel = settings.model.ifBlank { "default (send a message to detect)" }
        modelLabel = JLabel("Model: $configuredModel").apply {
            font = monoFont.deriveFont(11f)
            foreground = JBColor(Color(0xD9, 0x77, 0x57), Color(0xD9, 0x77, 0x57))
            border = JBUI.Borders.empty(4, 8)
            background = JBColor(Color(0x2B, 0x2D, 0x30), Color(0x2B, 0x2D, 0x30))
            isOpaque = true
        }

        outputPane = JTextPane().apply {
            isEditable = false
            contentType = "text/html"
            background = JBColor(Color(0x1E, 0x1F, 0x22), Color(0x1E, 0x1F, 0x22))
            val kit = HTMLEditorKit()
            val styleSheet = kit.styleSheet
            styleSheet.addRule("""
                body {
                    font-family: 'JetBrains Mono', 'Menlo', 'Consolas', monospace;
                    font-size: ${settings.fontSize}px;
                    padding: 8px;
                    color: #BCBEC4;
                    background-color: #1E1F22;
                }
                .user-msg {
                    color: #6897BB;
                    margin-top: 12px;
                    margin-bottom: 4px;
                    padding: 6px;
                    background-color: #2B2D30;
                }
                .claude-msg {
                    color: #BCBEC4;
                    margin-top: 4px;
                    margin-bottom: 4px;
                }
                .tool-msg {
                    color: #D97757;
                    font-style: italic;
                    margin-top: 2px;
                    margin-bottom: 2px;
                }
                .error-msg {
                    color: #FF6B68;
                    margin-top: 4px;
                    margin-bottom: 4px;
                }
                .system-msg {
                    color: #808080;
                    font-style: italic;
                    margin-top: 4px;
                    margin-bottom: 4px;
                }
                pre {
                    background-color: #2B2D30;
                    padding: 8px;
                }
                code {
                    font-family: 'JetBrains Mono', 'Menlo', monospace;
                    background-color: #2B2D30;
                    padding: 1px 4px;
                }
                a {
                    color: #6897BB;
                    text-decoration: underline;
                }
            """.trimIndent())
            editorKit = kit
            (caret as? DefaultCaret)?.updatePolicy = DefaultCaret.ALWAYS_UPDATE
            addHyperlinkListener { e ->
                if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                    val href = e.description ?: return@addHyperlinkListener
                    when {
                        href.startsWith("file://") -> {
                            val path = href.removePrefix("file://")
                            openFileInEditor(path)
                        }
                        href.contains("/action/copy/") -> {
                            val key = href.substringAfter("/action/copy/")
                            val text = copyableCommands[key] ?: return@addHyperlinkListener
                            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                            clipboard.setContents(java.awt.datatransfer.StringSelection(text), null)
                        }
                        href.contains("/action/reuse/") -> {
                            val key = href.substringAfter("/action/reuse/")
                            val text = copyableCommands[key] ?: return@addHyperlinkListener
                            this@SessionPanel.inputArea.text = text
                            this@SessionPanel.inputArea.requestFocusInWindow()
                        }
                        href.contains("/action/inline-diff/") -> {
                            val diffId = href.substringAfter("/action/inline-diff/")
                            toggleInlineDiff(diffId)
                        }
                        href.contains("/action/diff/") -> {
                            val path = href.substringAfter("/action/diff/")
                            showGitDiff(path)
                        }
                        href.startsWith("http://") || href.startsWith("https://") -> {
                            try {
                                java.awt.Desktop.getDesktop().browse(java.net.URI(href))
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
        }

        val scrollPane = JBScrollPane(outputPane).apply {
            border = JBUI.Borders.empty()
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        }

        thinkingLabel = JLabel("").apply {
            font = monoFont.deriveFont(Font.ITALIC)
            foreground = JBColor(Color(0xD9, 0x77, 0x57), Color(0xD9, 0x77, 0x57))
            border = JBUI.Borders.empty(4, 8)
            isVisible = false
        }

        inputArea = JTextArea(3, 40).apply {
            font = monoFont
            lineWrap = true
            wrapStyleWord = true
            border = JBUI.Borders.empty(8)
            background = JBColor(Color(0x2B, 0x2D, 0x30), Color(0x2B, 0x2D, 0x30))
            foreground = JBColor(Color(0xBC, 0xBE, 0xC4), Color(0xBC, 0xBE, 0xC4))
            caretColor = JBColor(Color(0xBC, 0xBE, 0xC4), Color(0xBC, 0xBE, 0xC4))
        }

        // Key bindings via InputMap/ActionMap (works reliably on macOS)
        val inputMap = inputArea.getInputMap(JComponent.WHEN_FOCUSED)
        val actionMap = inputArea.actionMap

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "send-message")
        actionMap.put("send-message", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                sendCurrentMessage()
            }
        })

        inputMap.put(
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK),
            "insert-newline"
        )
        actionMap.put("insert-newline", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                inputArea.insert("\n", inputArea.caretPosition)
            }
        })

        // Escape to stop current request
        val panelInputMap = this.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
        val panelActionMap = this.actionMap
        panelInputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "escape-stop")
        panelActionMap.put("escape-stop", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                if (session.isBusy) {
                    session.stop()
                    appendHtml("<div class='system-msg'>Stopped.</div>")
                    setBusyState(false)
                }
            }
        })

        // Ctrl+C / Cmd+C double-tap to stop (within 500ms)
        // Uses KeyEventPostProcessor to run AFTER IntelliJ's copy action
        ctrlCDispatcher = java.awt.KeyEventDispatcher { e ->
            if (e.id == KeyEvent.KEY_PRESSED && e.keyCode == KeyEvent.VK_C &&
                (e.isControlDown || e.isMetaDown)
            ) {
                // Only handle if this panel is showing
                if (!this@SessionPanel.isShowing) return@KeyEventDispatcher false
                val now = System.currentTimeMillis()
                if (now - lastCtrlCTime < 500 && session.isBusy) {
                    SwingUtilities.invokeLater {
                        session.stop()
                        appendHtml("<div class='system-msg'>Stopped by Ctrl+C.</div>")
                        setBusyState(false)
                    }
                    lastCtrlCTime = 0L
                    return@KeyEventDispatcher true
                }
                lastCtrlCTime = now
            }
            false
        }
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(ctrlCDispatcher)

        val inputScrollPane = JBScrollPane(inputArea).apply {
            border = JBUI.Borders.customLine(JBColor(0x3C3F41, 0x3C3F41), 1, 0, 0, 0)
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            minimumSize = Dimension(0, 60)
            preferredSize = Dimension(0, 80)
        }

        sendStopButton = JButton("Send").apply {
            addActionListener { onSendStopClick() }
            toolTipText = "Send message (Enter). Shift+Enter for new line."
        }

        statusLabel = JLabel("Ready").apply {
            border = JBUI.Borders.empty(2, 8)
            foreground = JBColor(0x808080, 0x808080)
            font = smallFont
        }

        val ctrlKey = if (System.getProperty("os.name").lowercase().contains("mac")) "Cmd" else "Ctrl"
        val hintLabel = JLabel("Enter to send, Shift+Enter for newline, ${ctrlKey}+C×2 to stop").apply {
            foreground = JBColor(0x606060, 0x606060)
            font = monoFont.deriveFont(10f)
            border = JBUI.Borders.empty(2, 8)
        }

        // Debug log area (collapsible)
        debugArea = JTextArea(5, 40).apply {
            isEditable = false
            font = monoFont.deriveFont(10f)
            background = JBColor(Color(0x15, 0x15, 0x18), Color(0x15, 0x15, 0x18))
            foreground = JBColor(Color(0x70, 0x70, 0x70), Color(0x70, 0x70, 0x70))
            lineWrap = true
            wrapStyleWord = true
        }

        val debugScrollPane = JBScrollPane(debugArea).apply {
            border = JBUI.Borders.customLine(JBColor(0x3C3F41, 0x3C3F41), 1, 0, 0, 0)
            preferredSize = Dimension(0, 120)
            isVisible = true
        }

        debugToggle = JCheckBox("Show debug log").apply {
            font = monoFont.deriveFont(10f)
            foreground = JBColor(0x606060, 0x606060)
            isOpaque = false
            isSelected = true
            addActionListener {
                debugScrollPane.isVisible = isSelected
                this@SessionPanel.revalidate()
            }
        }

        val buttonPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(sendStopButton, BorderLayout.NORTH)
        }

        val inputPanel = JPanel(BorderLayout(4, 0)).apply {
            border = JBUI.Borders.empty(4)
            add(inputScrollPane, BorderLayout.CENTER)
            add(buttonPanel, BorderLayout.EAST)
        }

        val hintsPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(hintLabel, BorderLayout.WEST)
            add(debugToggle, BorderLayout.EAST)
        }

        val statusPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(statusLabel, BorderLayout.WEST)
        }

        val bottomPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(thinkingLabel)
            add(inputPanel)
            add(hintsPanel)
            add(statusPanel)
            add(debugScrollPane)
        }

        add(modelLabel, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
        add(bottomPanel, BorderLayout.SOUTH)

        session.addListener(this)

        appendHtml("<div class='system-msg'>Claude Code session started. Working directory: ${escapeHtml(session.workingDirectory)}</div>")
    }

    private fun onSendStopClick() {
        if (session.isBusy) {
            session.stop()
            appendHtml("<div class='system-msg'>Stopped.</div>")
            setBusyState(false)
        } else {
            sendCurrentMessage()
        }
    }

    private fun sendCurrentMessage() {
        val text = inputArea.text.trim()
        if (text.isEmpty() || session.isBusy) return

        inputArea.text = ""
        autoNameTab(text)
        appendUserMessage(text)
        setBusyState(true)
        session.sendMessage(text)
    }

    fun sendPrefilled(text: String) {
        if (session.isBusy) return
        autoNameTab(text)
        appendUserMessage(text)
        setBusyState(true)
        session.sendMessage(text)
    }

    fun prefillInput(text: String) {
        inputArea.text = text
        inputArea.caretPosition = 0
        inputArea.requestFocusInWindow()
    }

    private fun appendUserMessage(text: String) {
        val key = "msg-${copyCommandCounter++}"
        copyableCommands[key] = text
        val copyLink = "<a href=\"http://localhost/action/copy/$key\">[copy]</a>"
        val reuseLink = "<a href=\"http://localhost/action/reuse/$key\">[edit]</a>"
        appendHtml("<div class='user-msg'>${escapeHtml(text)} $copyLink $reuseLink</div>")
    }

    private fun autoNameTab(message: String) {
        if (hasAutoNamed) return
        hasAutoNamed = true
        // Use the first line, stripped of markdown/file paths, truncated
        val name = message.lines()
            .first { it.isNotBlank() }
            .removePrefix("Explain ")
            .removePrefix("Generate ")
            .removePrefix("Refactor ")
            .take(30)
            .trim()
        onTabRename(name)
    }

    private fun setBusyState(busy: Boolean) {
        if (busy) {
            sendStopButton.text = "Stop"
            sendStopButton.toolTipText = "Stop Claude (cancel current request)"
            statusLabel.text = "Claude is thinking..."
            startThinkingAnimation()
        } else {
            stopThinkingAnimation()
            sendStopButton.text = "Send"
            sendStopButton.toolTipText = "Send message (Enter). Shift+Enter for new line."
            statusLabel.text = "Ready"
        }
    }

    override fun onText(session: ClaudeSession, text: String) {
        ApplicationManager.getApplication().invokeLater {
            resetEditConsolidation()
            stopThinkingAnimation()
            statusLabel.text = "Claude is responding..."
            val rendered = MarkdownRenderer.render(text) { codeContent ->
                val key = "code-${copyCommandCounter++}"
                copyableCommands[key] = codeContent
                "<a href=\"http://localhost/action/copy/$key\">[copy]</a>"
            }
            appendHtml("<div class='claude-msg'>$rendered</div>")
        }
    }

    override fun onThinking(session: ClaudeSession, thinking: String?) {
        ApplicationManager.getApplication().invokeLater {
            startThinkingAnimation()
            if (thinking != null) {
                thinkingLabel.text = "  Thinking: ${thinking.take(80)}..."
            }
            statusLabel.text = "Claude is thinking..."
        }
    }

    override fun onToolUse(session: ClaudeSession, tool: String, detail: String?, diffSummary: String?, diffData: Pair<String, String>?, filePath: String?) {
        ApplicationManager.getApplication().invokeLater {
            val isEdit = tool == "Edit" && filePath != null
            val displayText = detail ?: tool

            // Consolidate consecutive Edit calls on the same file into one UI entry
            if (isEdit && filePath == lastEditFilePath && lastEditElementId != null) {
                lastEditCount++
                lastEditDisplayText = displayText
                if (diffData != null) {
                    lastEditAddedLines += diffData.second.lines().size
                    lastEditRemovedLines += diffData.first.lines().size
                    lastEditDiffPairs.add(diffData)
                }

                val consolidatedDiffId = "diff-c-${lastEditElementId}"
                val lang = MarkdownRenderer.languageFromFilePath(filePath!!)
                pendingDiffs[consolidatedDiffId] = buildConsolidatedDiffHtml(lastEditDiffPairs, lang, filePath)
                expandedDiffs.remove(consolidatedDiffId)

                val newHtml = buildEditElementHtml(
                    lastEditElementId!!,
                    displayText,
                    consolidatedDiffId,
                    lastEditAddedLines,
                    lastEditRemovedLines,
                    lastEditCount
                )

                try {
                    val doc = outputPane.document as HTMLDocument
                    val element = doc.getElement(lastEditElementId!!)
                    if (element != null) {
                        doc.setOuterHTML(element, newHtml)
                        // Collapse the diff container since content was updated
                        val containerId = "container-$consolidatedDiffId"
                        val container = doc.getElement(containerId)
                        if (container != null) {
                            doc.setOuterHTML(container, "<div id='$containerId'></div>")
                        }
                        statusLabel.text = displayText.take(60)
                        return@invokeLater
                    }
                } catch (_: Exception) {
                    // Fall through to create a new entry
                }
            }

            // Not a consolidation — reset if tool changed or different file
            if (!isEdit || filePath != lastEditFilePath) {
                resetEditConsolidation()
            }

            val color = when (tool) {
                "Edit" -> "#D9B263"
                "Write" -> "#6A8759"
                "Bash" -> "#D97757"
                "Read", "Glob", "Grep" -> "#6897BB"
                else -> "#808080"
            }

            val diffHtml = if (diffSummary != null) {
                "<br/>&nbsp;&nbsp;<span style='color: #808080;'>\u23BF $diffSummary</span>"
            } else ""

            if (isEdit) {
                val elementId = "tool-edit-${toolUseCounter++}"
                lastEditFilePath = filePath
                lastEditElementId = elementId
                lastEditCount = 1
                lastEditDisplayText = displayText
                lastEditAddedLines = 0
                lastEditRemovedLines = 0
                lastEditDiffPairs.clear()

                if (diffData != null) {
                    lastEditAddedLines = diffData.second.lines().size
                    lastEditRemovedLines = diffData.first.lines().size
                    lastEditDiffPairs.add(diffData)
                }

                val consolidatedDiffId = "diff-c-$elementId"
                val editLang = MarkdownRenderer.languageFromFilePath(filePath!!)
                val diffToggleHtml = if (diffData != null) {
                    pendingDiffs[consolidatedDiffId] = buildConsolidatedDiffHtml(lastEditDiffPairs, editLang, filePath)
                    expandedDiffs.add(consolidatedDiffId)
                    " <a href=\"http://localhost/action/inline-diff/$consolidatedDiffId\" style='color: #808080;'>[\u25BC diff]</a>"
                } else ""

                appendHtml("<div id='$elementId' class='tool-msg'><span style='color: $color;'>\u23FA</span> ${escapeHtml(displayText)}$diffToggleHtml$diffHtml</div>")
                if (diffData != null) {
                    appendHtml("<div id='container-$consolidatedDiffId'>${pendingDiffs[consolidatedDiffId]}</div>")
                }
            } else if (tool == "Bash" && detail != null) {
                val cmdKey = "cmd-${copyCommandCounter++}"
                copyableCommands[cmdKey] = detail
                appendHtml("<div class='tool-msg'><span style='color: $color;'>\u23FA</span> ${escapeHtml(displayText)} <a href=\"http://localhost/action/copy/$cmdKey\">[copy]</a>$diffHtml</div>")
            } else {
                var inlineDiffId: String? = null
                val diffLang = if (filePath != null) MarkdownRenderer.languageFromFilePath(filePath) else ""
                val diffToggleHtml = if (diffData != null) {
                    inlineDiffId = "diff-${copyCommandCounter++}"
                    pendingDiffs[inlineDiffId!!] = buildInlineDiffHtml(diffData.first, diffData.second, diffLang, filePath)
                    expandedDiffs.add(inlineDiffId!!)
                    " <a href=\"http://localhost/action/inline-diff/$inlineDiffId\" style='color: #808080;'>[\u25BC diff]</a>"
                } else ""
                appendHtml("<div class='tool-msg'><span style='color: $color;'>\u23FA</span> ${escapeHtml(displayText)}$diffToggleHtml$diffHtml</div>")
                if (inlineDiffId != null) {
                    appendHtml("<div id='container-$inlineDiffId'>${pendingDiffs[inlineDiffId!!]}</div>")
                }
            }
            statusLabel.text = displayText.take(60)
        }
    }

    private fun buildEditElementHtml(
        elementId: String,
        displayText: String,
        diffId: String,
        addedLines: Int,
        removedLines: Int,
        editCount: Int
    ): String {
        val color = "#D9B263"
        val arrow = if (expandedDiffs.contains(diffId)) "\u25BC" else "\u25B6"
        val diffToggleHtml = " <a href=\"http://localhost/action/inline-diff/$diffId\" style='color: #808080;'>[$arrow diff]</a>"

        val summaryParts = mutableListOf<String>()
        if (addedLines > 0) summaryParts.add("Added $addedLines line${if (addedLines > 1) "s" else ""}")
        if (removedLines > 0) summaryParts.add("removed $removedLines line${if (removedLines > 1) "s" else ""}")
        val editsLabel = if (editCount > 1) " ($editCount edits)" else ""
        val summary = summaryParts.joinToString(", ") + editsLabel

        val diffHtml = "<br/>&nbsp;&nbsp;<span style='color: #808080;'>\u23BF $summary</span>"

        return "<div id='$elementId' class='tool-msg'><span style='color: $color;'>\u23FA</span> ${escapeHtml(displayText)}$diffToggleHtml$diffHtml</div>"
    }

    private fun resetEditConsolidation() {
        lastEditFilePath = null
        lastEditElementId = null
        lastEditCount = 0
        lastEditAddedLines = 0
        lastEditRemovedLines = 0
        lastEditDiffPairs.clear()
        lastEditDisplayText = null
    }

    override fun onFileChanged(session: ClaudeSession, filePath: String, action: String) {
        ApplicationManager.getApplication().invokeLater {
            changedFiles.add(filePath to action)
        }
    }

    override fun onPermissionRequest(session: ClaudeSession, prompt: String): Boolean {
        val latch = java.util.concurrent.CountDownLatch(1)
        val result = java.util.concurrent.atomic.AtomicBoolean(false)

        ApplicationManager.getApplication().invokeLater {
            appendHtml("<div class='system-msg'>Permission requested:</div>")
            appendHtml("<div class='tool-msg'>${escapeHtml(prompt)}</div>")

            val settings = ClaudeSettings.getInstance().state
            val monoFont = Font(com.claudecode.ClaudeConstants.FONT_FAMILY, Font.PLAIN, settings.fontSize)

            val bar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
                background = JBColor(Color(0x2B, 0x2D, 0x30), Color(0x2B, 0x2D, 0x30))
                border = JBUI.Borders.compound(
                    JBUI.Borders.customLine(JBColor(Color(0xD9, 0x77, 0x57), Color(0xD9, 0x77, 0x57)), 1, 0, 0, 0),
                    JBUI.Borders.empty(4, 8)
                )

                val label = JLabel("Allow this action?").apply {
                    font = monoFont.deriveFont(Font.BOLD, 12f)
                    foreground = JBColor(Color(0xD9, 0x77, 0x57), Color(0xD9, 0x77, 0x57))
                }

                val allowBtn = JButton("Allow").apply {
                    font = monoFont.deriveFont(12f)
                    addActionListener {
                        result.set(true)
                        appendHtml("<div class='system-msg'><span style='color: #6A8759;'>Allowed</span></div>")
                        removePermissionBar()
                        latch.countDown()
                    }
                }

                val denyBtn = JButton("Deny").apply {
                    font = monoFont.deriveFont(12f)
                    addActionListener {
                        result.set(false)
                        appendHtml("<div class='system-msg'><span style='color: #FF6B68;'>Denied</span></div>")
                        removePermissionBar()
                        latch.countDown()
                    }
                }

                add(label)
                add(allowBtn)
                add(denyBtn)
            }

            permissionPanel = bar
            // Insert above the input area
            val bottomPanel = inputArea.parent?.parent?.parent ?: return@invokeLater
            if (bottomPanel is JPanel) {
                bottomPanel.add(bar, 0)
                bottomPanel.revalidate()
                bottomPanel.repaint()
            }
        }

        latch.await()
        return result.get()
    }

    private fun removePermissionBar() {
        val bar = permissionPanel ?: return
        val parent = bar.parent
        if (parent != null) {
            parent.remove(bar)
            parent.revalidate()
            parent.repaint()
        }
        permissionPanel = null
    }

    override fun onTaskProgress(session: ClaudeSession, description: String) {
        ApplicationManager.getApplication().invokeLater {
            appendHtml("<div class='tool-msg'>${escapeHtml(description)}</div>")
            statusLabel.text = description.take(60)
        }
    }

    override fun onModelInfo(session: ClaudeSession, model: String) {
        ApplicationManager.getApplication().invokeLater {
            val displayName = model
                .replace("claude-", "")
                .replace("-2025", " (2025")
                .let { if (it.contains("(")) "$it)" else it }
            modelLabel.text = "Model: $displayName"
            modelLabel.toolTipText = model
        }
    }

    override fun onToolResult(session: ClaudeSession, toolUseId: String, isError: Boolean) {
        ApplicationManager.getApplication().invokeLater {
            val color = if (isError) "#FF6B68" else "#6A8759"
            val label = if (isError) "\u2717 failed" else "\u2713"
            appendHtml("<div class='tool-msg'>&nbsp;&nbsp;<span style='color: $color;'>$label</span></div>")
        }
    }

    override fun onFinished(session: ClaudeSession, costUsd: Double?) {
        ApplicationManager.getApplication().invokeLater {
            resetEditConsolidation()
            val costStr = if (costUsd != null) " | \$${String.format("%.4f", costUsd)}" else ""
            setBusyState(false)
            statusLabel.text = "Ready$costStr"

            if (changedFiles.isNotEmpty()) {
                refreshChangedFiles()
                appendHtml(buildFilesSummaryHtml())
                changedFiles.clear()
            }
        }
    }

    override fun onError(session: ClaudeSession, error: String) {
        ApplicationManager.getApplication().invokeLater {
            resetEditConsolidation()
            appendHtml("<div class='error-msg'>${escapeHtml(error)}</div>")
            setBusyState(false)
        }
    }

    override fun onDebug(session: ClaudeSession, message: String) {
        ApplicationManager.getApplication().invokeLater {
            debugArea.append("$message\n")
            debugArea.caretPosition = debugArea.document.length
        }
    }

    private fun startThinkingAnimation() {
        if (thinkingTimer != null) return
        dotCount = 0
        thinkingLabel.isVisible = true
        thinkingTimer = Timer(400) {
            dotCount = (dotCount + 1) % 4
            val dots = ".".repeat(dotCount)
            thinkingLabel.text = "  Claude is thinking$dots"
        }
        thinkingTimer?.start()
    }

    private fun stopThinkingAnimation() {
        thinkingTimer?.stop()
        thinkingTimer = null
        thinkingLabel.isVisible = false
    }

    private fun appendHtml(html: String) {
        val doc = outputPane.document
        val kit = outputPane.editorKit as HTMLEditorKit
        try {
            kit.insertHTML(
                doc as HTMLDocument,
                doc.length,
                html,
                0, 0, null
            )
            outputPane.caretPosition = doc.length
        } catch (e: Exception) {
            outputPane.text = (outputPane.text ?: "") + html
        }
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("\n", "<br/>")
    }

    private fun refreshChangedFiles() {
        val ioFiles = changedFiles.map { java.io.File(it.first) }
        // Collect parent dirs too so new files appear in the tree
        val allPaths = mutableSetOf<java.io.File>()
        for (f in ioFiles) {
            allPaths.add(f)
            f.parentFile?.let { allPaths.add(it) }
        }
        LocalFileSystem.getInstance().refreshIoFiles(allPaths, false, true, null)
    }

    private fun buildFilesSummaryHtml(): String {
        val grouped = changedFiles
            .distinctBy { it.first }
            .groupBy { java.io.File(it.first).parent ?: "" }

        val sb = StringBuilder("<div class='system-msg'><b>Changed files:</b><br/>")
        for ((folder, files) in grouped) {
            val displayFolder = shortenPath(folder)
            sb.append("<span style='color: #808080;'>$displayFolder/</span><br/>")
            for ((filePath, action) in files) {
                val fileName = java.io.File(filePath).name
                val actionColor = when (action) {
                    "Created" -> "#6A8759"
                    "Deleted" -> "#FF6B68"
                    else -> "#D9B263"
                }
                sb.append("&nbsp;&nbsp;<span style='color: $actionColor;'>[$action]</span> ")
                if (action == "Deleted") {
                    sb.append("<span style='color: #808080;'>$fileName</span><br/>")
                } else {
                    sb.append("<a href='file://$filePath' style='color: #6897BB;'>$fileName</a>")
                    if (action == "Modified") {
                        sb.append(" <a href='http://localhost/action/diff/$filePath' style='color: #6897BB;'>[diff]</a>")
                    }
                    sb.append("<br/>")
                }
            }
        }
        sb.append("</div>")
        return sb.toString()
    }

    private fun shortenPath(path: String): String {
        val wdPrefix = "${session.workingDirectory}/"
        return if (path.startsWith(wdPrefix)) path.removePrefix(wdPrefix) else path
    }

    private fun openFileInEditor(path: String) {
        ApplicationManager.getApplication().invokeLater {
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(path) ?: return@invokeLater
            FileEditorManager.getInstance(project).openFile(vf, true)
        }
    }

    private fun buildInlineDiffHtml(oldStr: String, newStr: String, lang: String = "", filePath: String? = null): String {
        val keywords = MarkdownRenderer.keywordsForLanguage(lang)
        val addedCount = newStr.lines().size
        val removedCount = oldStr.lines().size
        val sb = StringBuilder()
        sb.append("<div style='background-color: #2B2D30; padding: 0; margin: 2px 0 4px 16px; font-size: 11px;'>")
        // File header
        val fileLabel = if (filePath != null) shortenPath(filePath) else ""
        val langLabel = if (lang.isNotEmpty()) "<span style='color: #808080;'>${lang.uppercase().take(4)}</span> " else ""
        sb.append("<div style='padding: 4px 8px; color: #808080; border-bottom: 1px solid #3C3F41;'>")
        sb.append("$langLabel<span style='color: #BCBEC4;'>$fileLabel</span> ")
        sb.append("<span style='color: #6A8759;'>+$addedCount</span> <span style='color: #FF6B68;'>-$removedCount</span>")
        sb.append("</div>")
        // Diff lines
        sb.append("<div style='padding: 4px 0;'>")
        for (line in oldStr.lines()) {
            val escaped = escapeHtml(line).replace("<br/>", "")
            val highlighted = if (keywords.isNotEmpty()) MarkdownRenderer.highlightLine(escaped, keywords) else escaped
            sb.append("<div style='white-space: pre; background-color: #3D2020; padding: 1px 8px;'><span style='color: #FF6B68;'>- </span>$highlighted</div>")
        }
        for (line in newStr.lines()) {
            val escaped = escapeHtml(line).replace("<br/>", "")
            val highlighted = if (keywords.isNotEmpty()) MarkdownRenderer.highlightLine(escaped, keywords) else escaped
            sb.append("<div style='white-space: pre; background-color: #1E3520; padding: 1px 8px;'><span style='color: #6A8759;'>+ </span>$highlighted</div>")
        }
        sb.append("</div></div>")
        return sb.toString()
    }

    private fun buildConsolidatedDiffHtml(diffPairs: List<Pair<String, String>>, lang: String = "", filePath: String? = null): String {
        val keywords = MarkdownRenderer.keywordsForLanguage(lang)
        val totalAdded = diffPairs.sumOf { it.second.lines().size }
        val totalRemoved = diffPairs.sumOf { it.first.lines().size }
        val sb = StringBuilder()
        sb.append("<div style='background-color: #2B2D30; padding: 0; margin: 2px 0 4px 16px; font-size: 11px;'>")
        // File header
        val fileLabel = if (filePath != null) shortenPath(filePath) else ""
        val langLabel = if (lang.isNotEmpty()) "<span style='color: #808080;'>${lang.uppercase().take(4)}</span> " else ""
        sb.append("<div style='padding: 4px 8px; color: #808080; border-bottom: 1px solid #3C3F41;'>")
        sb.append("$langLabel<span style='color: #BCBEC4;'>$fileLabel</span> ")
        sb.append("<span style='color: #6A8759;'>+$totalAdded</span> <span style='color: #FF6B68;'>-$totalRemoved</span>")
        sb.append("</div>")
        for ((index, pair) in diffPairs.withIndex()) {
            if (index > 0) {
                sb.append("<div style='border-top: 1px solid #3C3F41; margin: 0;'></div>")
            }
            sb.append("<div style='padding: 0;'>")
            for (line in pair.first.lines()) {
                val escaped = escapeHtml(line).replace("<br/>", "")
                val highlighted = if (keywords.isNotEmpty()) MarkdownRenderer.highlightLine(escaped, keywords) else escaped
                sb.append("<div style='white-space: pre; background-color: #3D2020; padding: 1px 8px;'><span style='color: #FF6B68;'>- </span>$highlighted</div>")
            }
            for (line in pair.second.lines()) {
                val escaped = escapeHtml(line).replace("<br/>", "")
                val highlighted = if (keywords.isNotEmpty()) MarkdownRenderer.highlightLine(escaped, keywords) else escaped
                sb.append("<div style='white-space: pre; background-color: #1E3520; padding: 1px 8px;'><span style='color: #6A8759;'>+ </span>$highlighted</div>")
            }
            sb.append("</div>")
        }
        sb.append("</div>")
        return sb.toString()
    }

    private fun toggleInlineDiff(diffId: String) {
        val doc = outputPane.document as? HTMLDocument ?: return
        val containerId = "container-$diffId"
        val container = doc.getElement(containerId) ?: return

        try {
            if (expandedDiffs.contains(diffId)) {
                expandedDiffs.remove(diffId)
                doc.setOuterHTML(container, "<div id='$containerId'></div>")
            } else {
                val diffHtml = pendingDiffs[diffId] ?: return
                expandedDiffs.add(diffId)
                doc.setOuterHTML(container, "<div id='$containerId'>$diffHtml</div>")
            }
        } catch (_: Exception) {}
    }

    private fun showGitDiff(path: String) {
        ApplicationManager.getApplication().invokeLater {
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(path) ?: return@invokeLater
            try {
                val changeListManager = com.intellij.openapi.vcs.changes.ChangeListManager.getInstance(project)
                val change = changeListManager.getChange(vf)
                if (change != null) {
                    com.intellij.openapi.vcs.changes.actions.diff.ShowDiffAction.showDiffForChange(project, listOf(change))
                } else {
                    // No VCS change detected, just open the file
                    FileEditorManager.getInstance(project).openFile(vf, true)
                }
            } catch (e: Exception) {
                FileEditorManager.getInstance(project).openFile(vf, true)
            }
        }
    }

    fun isBusy(): Boolean = session.isBusy

    fun focusInput() {
        inputArea.requestFocusInWindow()
    }

    fun dispose() {
        stopThinkingAnimation()
        ctrlCDispatcher?.let {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(it)
        }
        session.removeListener(this)
    }
}
