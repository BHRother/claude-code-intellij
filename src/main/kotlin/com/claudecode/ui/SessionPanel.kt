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
    private lateinit var outputScrollPane: JBScrollPane
    private lateinit var jumpToBottomButton: JButton
    private lateinit var inputArea: PasteAwareInputArea
    private val sendStopButton: JButton
    private val statusLabel: JLabel
    private val modelLabel: JLabel
    private val thinkingLabel: JLabel
    private val debugArea: JTextArea
    private val debugToggle: JCheckBox
    private var thinkingTimer: Timer? = null
    private var dotCount = 0
    private var thinkingStartTime = 0L
    private var thinkingContent: String? = null
    private var activeToolName: String? = null
    private var hasAutoNamed = false
    private var lastCtrlCTime = 0L
    private var ctrlCDispatcher: java.awt.KeyEventDispatcher? = null
    private val changedFiles = mutableListOf<Pair<String, String>>() // (filePath, action)
    private val copyableCommands = mutableMapOf<String, String>()
    private val applyableCode = mutableMapOf<String, Pair<String, String>>() // key -> (code, lang)
    private val pendingDiffs = mutableMapOf<String, String>() // diffId -> diff HTML
    private val expandedDiffs = mutableSetOf<String>()
    private var copyCommandCounter = 0
    private var toolUseCounter = 0
    private val toolUseIdToHtmlId = mutableMapOf<String, String>()
    private var permissionHintShown = false

    // Edit consolidation state: consecutive Edit calls on the same file update one UI entry
    private var lastEditFilePath: String? = null
    private var lastEditElementId: String? = null
    private var lastEditCount: Int = 0
    private var lastEditAddedLines: Int = 0
    private var lastEditRemovedLines: Int = 0
    private var lastEditChangedLines: Int = 0
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
            // Don't drag the viewport with the caret — appendHtml decides
            // whether to auto-scroll based on the user's current position.
            (caret as? DefaultCaret)?.updatePolicy = DefaultCaret.NEVER_UPDATE
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
                        href.contains("/action/apply/") -> {
                            val key = href.substringAfter("/action/apply/")
                            val payload = applyableCode[key] ?: return@addHyperlinkListener
                            applyCodeBlockToActiveEditor(payload.first, payload.second)
                        }
                        href.contains("/action/open-settings") -> {
                            openClaudeSettings()
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

        outputScrollPane = JBScrollPane(outputPane).apply {
            border = JBUI.Borders.empty()
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        }

        jumpToBottomButton = JButton("↓ Jump to latest").apply {
            font = monoFont.deriveFont(11f)
            toolTipText = "Scroll to the latest message"
            isFocusable = false
            isVisible = false
            isOpaque = true
            margin = JBUI.emptyInsets()
            background = JBColor(Color(0x3C, 0x3F, 0x41), Color(0x3C, 0x3F, 0x41))
            foreground = JBColor(Color(0xBC, 0xBE, 0xC4), Color(0xBC, 0xBE, 0xC4))
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor(Color(0x50, 0x53, 0x56), Color(0x50, 0x53, 0x56)), 1, true),
                JBUI.Borders.empty(4, 10)
            )
            addActionListener { scrollOutputToBottom() }
        }

        // Layered container places the floating "jump to latest" button on top
        // of the scroll pane. The button only appears when the user has
        // scrolled away from the bottom.
        val outputOverlay = object : JLayeredPane() {
            override fun doLayout() {
                outputScrollPane.setBounds(0, 0, width, height)
                if (jumpToBottomButton.isVisible) {
                    val pref = jumpToBottomButton.preferredSize
                    val margin = 12
                    val sbWidth = if (outputScrollPane.verticalScrollBar.isVisible)
                        outputScrollPane.verticalScrollBar.width else 0
                    jumpToBottomButton.setBounds(
                        width - pref.width - margin - sbWidth,
                        height - pref.height - margin,
                        pref.width,
                        pref.height
                    )
                }
            }
            override fun getPreferredSize(): Dimension = outputScrollPane.preferredSize
        }.apply {
            add(outputScrollPane, JLayeredPane.DEFAULT_LAYER, 0)
            add(jumpToBottomButton, JLayeredPane.PALETTE_LAYER, 0)
        }

        outputScrollPane.verticalScrollBar.addAdjustmentListener {
            updateJumpToBottomVisibility()
        }

        thinkingLabel = JLabel("").apply {
            font = monoFont.deriveFont(Font.ITALIC, 11f)
            foreground = JBColor(Color(0xD9, 0x77, 0x57), Color(0xD9, 0x77, 0x57))
            border = JBUI.Borders.empty(4, 8)
            isVisible = false
        }

        inputArea = PasteAwareInputArea(monoFont, project)

        // Key bindings via InputMap/ActionMap (works reliably on macOS)
        val inputMap = inputArea.getTextInputMap(JComponent.WHEN_FOCUSED)
        val actionMap = inputArea.getTextActionMap()

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

        inputArea.border = JBUI.Borders.customLine(JBColor(0x3C3F41, 0x3C3F41), 1, 0, 0, 0)
        inputArea.minimumSize = Dimension(0, 60)
        inputArea.preferredSize = Dimension(0, 100)

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
        val hintLabel = JLabel("Enter to send, Shift+Enter for newline, ${ctrlKey}+C×2 to stop, @file to reference").apply {
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
            isVisible = false
        }

        debugToggle = JCheckBox("Show debug log").apply {
            font = monoFont.deriveFont(10f)
            foreground = JBColor(0x606060, 0x606060)
            isOpaque = false
            isSelected = false
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
            add(inputArea, BorderLayout.CENTER)
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
        add(outputOverlay, BorderLayout.CENTER)
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
        val text = inputArea.getFullText()
        if (text.isEmpty() || session.isBusy) return

        inputArea.clear()
        autoNameTab(text)
        appendUserMessage(text)
        permissionHintShown = false
        setBusyState(true)
        session.sendMessage(text)
    }

    fun sendPrefilled(text: String) {
        if (session.isBusy) return
        autoNameTab(text)
        appendUserMessage(text)
        permissionHintShown = false
        setBusyState(true)
        session.sendMessage(text)
    }

    fun prefillInput(text: String) {
        inputArea.clear()
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
            statusLabel.foreground = JBColor(Color(0xD9, 0x77, 0x57), Color(0xD9, 0x77, 0x57))
            thinkingContent = null
            activeToolName = null
            startThinkingAnimation()
        } else {
            stopThinkingAnimation()
            sendStopButton.text = "Send"
            sendStopButton.toolTipText = "Send message (Enter). Shift+Enter for new line."
            statusLabel.text = "Ready"
            statusLabel.foreground = JBColor(Color(0x80, 0x80, 0x80), Color(0x80, 0x80, 0x80))
        }
    }

    override fun onText(session: ClaudeSession, text: String) {
        ApplicationManager.getApplication().invokeLater {
            resetEditConsolidation()
            stopThinkingAnimation()
            statusLabel.text = "Claude is responding..."
            val rendered = MarkdownRenderer.render(
                text,
                copyLinkGenerator = { codeContent ->
                    val key = "code-${copyCommandCounter++}"
                    copyableCommands[key] = codeContent
                    "<a href=\"http://localhost/action/copy/$key\">[copy]</a>"
                },
                applyLinkGenerator = { codeContent, lang ->
                    val key = "apply-${copyCommandCounter++}"
                    applyableCode[key] = codeContent to lang
                    "<a href=\"http://localhost/action/apply/$key\">[apply]</a>"
                },
            )
            appendHtml("<div class='claude-msg'>$rendered</div>")
            maybeShowPermissionHint(text)
        }
    }

    override fun onThinking(session: ClaudeSession, thinking: String?) {
        ApplicationManager.getApplication().invokeLater {
            activeToolName = null
            if (thinking != null) {
                thinkingContent = thinking.take(60)
            }
            startThinkingAnimation()
            statusLabel.text = "Claude is thinking..."
        }
    }

    override fun onToolUse(session: ClaudeSession, tool: String, detail: String?, diffSummary: String?, diffData: Pair<String, String>?, filePath: String?) {
        ApplicationManager.getApplication().invokeLater {
            thinkingContent = null
            activeToolName = detail?.take(50) ?: tool
            thinkingStartTime = System.currentTimeMillis()
            if (thinkingTimer == null) startThinkingAnimation()
            statusLabel.text = "Running: ${activeToolName}"

            val isEdit = tool == "Edit" && filePath != null
            val displayText = detail ?: tool

            // Consolidate consecutive Edit calls on the same file into one UI entry
            if (isEdit && filePath == lastEditFilePath && lastEditElementId != null) {
                lastEditCount++
                lastEditDisplayText = displayText
                if (diffData != null) {
                    lastEditDiffPairs.add(diffData)
                    val stats = computeDiffStats(lastEditDiffPairs)
                    lastEditAddedLines = stats.added
                    lastEditRemovedLines = stats.removed
                    lastEditChangedLines = stats.changed
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
                    lastEditChangedLines,
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
                lastEditChangedLines = 0
                lastEditDiffPairs.clear()

                if (diffData != null) {
                    lastEditDiffPairs.add(diffData)
                    val stats = computeDiffStats(lastEditDiffPairs)
                    lastEditAddedLines = stats.added
                    lastEditRemovedLines = stats.removed
                    lastEditChangedLines = stats.changed
                }

                val consolidatedDiffId = "diff-c-$elementId"
                val editLang = MarkdownRenderer.languageFromFilePath(filePath!!)
                val diffToggleHtml = if (diffData != null) {
                    pendingDiffs[consolidatedDiffId] = buildConsolidatedDiffHtml(lastEditDiffPairs, editLang, filePath)
                    expandedDiffs.add(consolidatedDiffId)
                    " <a href=\"http://localhost/action/inline-diff/$consolidatedDiffId\" style='color: #808080;'>[\u25BC diff]</a>"
                } else ""

                val editSummaryHtml = if (diffData != null) {
                    val parts = mutableListOf<String>()
                    if (lastEditChangedLines > 0) parts.add("~$lastEditChangedLines modified")
                    if (lastEditAddedLines > 0) parts.add("+$lastEditAddedLines added")
                    if (lastEditRemovedLines > 0) parts.add("-$lastEditRemovedLines removed")
                    "<br/>&nbsp;&nbsp;<span style='color: #808080;'>\u23BF ${parts.joinToString(", ")}</span>"
                } else diffHtml

                appendHtml("<div id='$elementId' class='tool-msg'><span style='color: $color;'>\u23FA</span> ${escapeHtml(displayText)}$diffToggleHtml$editSummaryHtml</div>")
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
        changedLines: Int,
        editCount: Int
    ): String {
        val color = "#D9B263"
        val arrow = if (expandedDiffs.contains(diffId)) "\u25BC" else "\u25B6"
        val diffToggleHtml = " <a href=\"http://localhost/action/inline-diff/$diffId\" style='color: #808080;'>[$arrow diff]</a>"

        val summaryParts = mutableListOf<String>()
        if (changedLines > 0) summaryParts.add("~$changedLines modified")
        if (addedLines > 0) summaryParts.add("+$addedLines added")
        if (removedLines > 0) summaryParts.add("-$removedLines removed")
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
        lastEditChangedLines = 0
        lastEditDiffPairs.clear()
        lastEditDisplayText = null
    }

    private data class DiffStats(val added: Int, val removed: Int, val changed: Int)

    private fun computeDiffStats(diffPairs: List<Pair<String, String>>): DiffStats {
        var added = 0
        var removed = 0
        var changed = 0
        for (pair in diffPairs) {
            val ops = computeDiff(pair.first.lines(), pair.second.lines())
            for (op in ops) {
                when (op.type) {
                    DiffOp.Type.ADDED -> added++
                    DiffOp.Type.REMOVED -> removed++
                    DiffOp.Type.MODIFIED_OLD -> changed++
                    else -> {}
                }
            }
        }
        return DiffStats(added, removed, changed)
    }

    override fun onFileChanged(session: ClaudeSession, filePath: String, action: String) {
        ApplicationManager.getApplication().invokeLater {
            changedFiles.add(filePath to action)
        }
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

            if (model.isNotBlank()) {
                ClaudeSettings.getInstance().addCustomModel(model)
            }
        }
    }

    override fun onToolResult(session: ClaudeSession, toolUseId: String, isError: Boolean) {
        ApplicationManager.getApplication().invokeLater {
            activeToolName = null
            thinkingContent = null
            thinkingStartTime = System.currentTimeMillis()
            statusLabel.text = "Claude is thinking..."
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
        thinkingStartTime = System.currentTimeMillis()
        thinkingLabel.isVisible = true
        thinkingTimer = Timer(500) {
            dotCount = (dotCount + 1) % 4
            val elapsed = (System.currentTimeMillis() - thinkingStartTime) / 1000
            val dots = ".".repeat(dotCount + 1)
            val timeStr = if (elapsed >= 2) " (${elapsed}s)" else ""
            val prefix = when {
                activeToolName != null -> "Running ${activeToolName}${dots}"
                thinkingContent != null -> "Thinking: ${thinkingContent}${dots}"
                else -> "Claude is thinking${dots}"
            }
            thinkingLabel.text = "  $prefix$timeStr"
        }
        thinkingTimer?.start()
    }

    private fun stopThinkingAnimation() {
        thinkingTimer?.stop()
        thinkingTimer = null
        thinkingLabel.isVisible = false
        thinkingContent = null
        activeToolName = null
    }

    private fun appendHtml(html: String) {
        val wasAtBottom = isOutputAtBottom()
        val doc = outputPane.document
        val kit = outputPane.editorKit as HTMLEditorKit
        try {
            kit.insertHTML(
                doc as HTMLDocument,
                doc.length,
                html,
                0, 0, null
            )
        } catch (e: Exception) {
            outputPane.text = (outputPane.text ?: "") + html
        }
        if (wasAtBottom) {
            scrollOutputToBottom()
        } else {
            // User has scrolled away — surface the jump button so they can
            // come back when they want.
            updateJumpToBottomVisibility()
        }
    }

    /**
     * True when the user is at (or within a small tolerance of) the bottom of
     * the output. The tolerance handles mouse-wheel imprecision and the few
     * pixels that incremental layout may leave between
     * `value + visibleAmount` and `maximum`.
     */
    private fun isOutputAtBottom(tolerancePx: Int = 40): Boolean {
        val sb = outputScrollPane.verticalScrollBar
        // Empty / not yet sized: treat as "at bottom" so initial appends scroll.
        if (sb.maximum == 0 || sb.visibleAmount >= sb.maximum) return true
        return sb.value + sb.visibleAmount >= sb.maximum - tolerancePx
    }

    private fun scrollOutputToBottom() {
        // Defer to after the document layout settles; HTML insertion updates
        // sizes asynchronously through the view hierarchy.
        SwingUtilities.invokeLater {
            val sb = outputScrollPane.verticalScrollBar
            sb.value = sb.maximum
        }
    }

    private fun updateJumpToBottomVisibility() {
        val show = !isOutputAtBottom()
        if (jumpToBottomButton.isVisible != show) {
            jumpToBottomButton.isVisible = show
            jumpToBottomButton.parent?.revalidate()
            jumpToBottomButton.parent?.repaint()
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

    /**
     * Detects responses where the model is asking the user to grant permission
     * (because the CLI silently blocked a tool in -p mode) and surfaces an
     * inline hint pointing to Settings. Only fires once per response so it
     * doesn't pile up across streaming chunks.
     */
    private fun maybeShowPermissionHint(text: String) {
        if (permissionHintShown) return
        if (!looksLikePermissionBlocked(text)) return
        permissionHintShown = true

        val currentMode = ClaudeSettings.getInstance().state.permissionMode
        appendHtml(
            "<div class='system-msg' style='margin: 6px 0; padding: 6px 10px; " +
                "border-left: 3px solid #D9B263; background-color: #2B2D30;'>" +
                "<span style='color: #D9B263;'>⚠ Looks like a tool was blocked by your current permission mode " +
                "(<code>$currentMode</code>).</span><br/>" +
                "Open <a href=\"http://localhost/action/open-settings\">Settings</a> and switch to " +
                "<b>acceptEdits</b> (lets file writes through) or <b>bypassPermissions</b> (allows everything " +
                "including shell commands) — then re-run your request." +
                "</div>"
        )
    }

    internal fun looksLikePermissionBlocked(text: String): Boolean {
        val lower = text.lowercase()
        // The model's wording when a tool fails due to permission. Tuned for
        // false-negative tolerance: the hint is purely informational, so the
        // worst case of a false positive is one extra suggestion.
        if (lower.contains("don't have permission") || lower.contains("do not have permission")) return true
        if (lower.contains("permission denied") || lower.contains("permission was denied")) return true
        if (lower.contains("permission to use the") || lower.contains("permission to use this")) return true
        if (Regex("\\bapprove\\b.*\\btool\\b").containsMatchIn(lower)) return true
        if (Regex("\\bblocked\\b.*\\b(tool|permission)\\b").containsMatchIn(lower)) return true
        return false
    }

    private fun openClaudeSettings() {
        com.intellij.openapi.options.ShowSettingsUtil.getInstance()
            .showSettingsDialog(project, com.claudecode.ClaudeConstants.TOOL_WINDOW_ID)
    }

    private fun applyCodeBlockToActiveEditor(code: String, lang: String) {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        if (editor == null) {
            com.intellij.openapi.ui.Messages.showInfoMessage(
                project,
                "Open a file in the editor first, then select the code you want to replace.",
                "Apply Code Block",
            )
            return
        }
        val selectionModel = editor.selectionModel
        if (!selectionModel.hasSelection()) {
            com.intellij.openapi.ui.Messages.showInfoMessage(
                project,
                "Select the target code in the active editor first. The code block will replace your selection.",
                "Apply Code Block",
            )
            return
        }
        val oldText = selectionModel.selectedText ?: return
        val vf = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(editor.document)
        val filePath = vf?.path ?: ("snippet.$lang".ifEmpty { "snippet.txt" })

        val dialog = com.claudecode.inline.InlineEditDiffDialog(project, oldText, code, filePath) {
            val start = selectionModel.selectionStart
            val end = selectionModel.selectionEnd
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(
                project, "Apply Code Block", null,
                { editor.document.replaceString(start, end, code) },
            )
        }
        dialog.show()
    }

    private fun buildInlineDiffHtml(oldStr: String, newStr: String, lang: String = "", filePath: String? = null): String {
        return buildConsolidatedDiffHtml(listOf(Pair(oldStr, newStr)), lang, filePath)
    }

    private fun preserveLeadingWhitespace(html: String): String {
        val leadingSpaces = html.length - html.trimStart().length
        if (leadingSpaces == 0) return html
        return "&nbsp;".repeat(leadingSpaces) + html.trimStart()
    }

    private fun buildConsolidatedDiffHtml(diffPairs: List<Pair<String, String>>, lang: String = "", filePath: String? = null): String {
        val keywords = MarkdownRenderer.keywordsForLanguage(lang)
        val sb = StringBuilder()
        sb.append("<div style='background-color: #2B2D30; padding: 0; margin: 2px 0 4px 16px; font-size: 11px;'>")

        var totalAdded = 0
        var totalRemoved = 0
        var totalChanged = 0

        val allHunks = StringBuilder()
        for ((index, pair) in diffPairs.withIndex()) {
            if (index > 0) {
                allHunks.append("<div style='border-top: 1px solid #3C3F41; margin: 0;'></div>")
            }
            val oldLines = pair.first.lines()
            val newLines = pair.second.lines()
            val ops = computeDiff(oldLines, newLines)

            allHunks.append("<div style='padding: 0;'>")
            for (op in ops) {
                val escaped = escapeHtml(op.line).replace("<br/>", "")
                val highlighted = preserveLeadingWhitespace(
                    if (keywords.isNotEmpty()) MarkdownRenderer.highlightLine(escaped, keywords) else escaped
                )
                when (op.type) {
                    DiffOp.Type.CONTEXT -> {
                        allHunks.append("<div style='white-space: pre; padding: 1px 8px;'><span style='color: #808080;'>  </span>$highlighted</div>")
                    }
                    DiffOp.Type.ADDED -> {
                        totalAdded++
                        allHunks.append("<div style='white-space: pre; background-color: #1E3520; padding: 1px 8px;'><span style='color: #6A8759;'>+ </span>$highlighted</div>")
                    }
                    DiffOp.Type.REMOVED -> {
                        totalRemoved++
                        allHunks.append("<div style='white-space: pre; background-color: #3D2020; padding: 1px 8px;'><span style='color: #FF6B68;'>- </span>$highlighted</div>")
                    }
                    DiffOp.Type.MODIFIED_OLD -> {
                        totalChanged++
                        allHunks.append("<div style='white-space: pre; background-color: #3D2020; padding: 1px 8px;'><span style='color: #FF6B68;'>~ </span>$highlighted</div>")
                    }
                    DiffOp.Type.MODIFIED_NEW -> {
                        allHunks.append("<div style='white-space: pre; background-color: #1E3520; padding: 1px 8px;'><span style='color: #6A8759;'>~ </span>$highlighted</div>")
                    }
                }
            }
            allHunks.append("</div>")
        }

        // File header with accurate counts
        val fileLabel = if (filePath != null) shortenPath(filePath) else ""
        val langLabel = if (lang.isNotEmpty()) "<span style='color: #808080;'>${lang.uppercase().take(4)}</span> " else ""
        sb.append("<div style='padding: 4px 8px; color: #808080; border-bottom: 1px solid #3C3F41;'>")
        sb.append("$langLabel<span style='color: #BCBEC4;'>$fileLabel</span> ")
        val parts = mutableListOf<String>()
        if (totalAdded > 0) parts.add("<span style='color: #6A8759;'>+$totalAdded</span>")
        if (totalRemoved > 0) parts.add("<span style='color: #FF6B68;'>-$totalRemoved</span>")
        if (totalChanged > 0) parts.add("<span style='color: #D9B263;'>~$totalChanged</span>")
        sb.append(parts.joinToString(" "))
        sb.append("</div>")
        sb.append(allHunks)
        sb.append("</div>")
        return sb.toString()
    }

    private data class DiffOp(val type: Type, val line: String) {
        enum class Type { CONTEXT, ADDED, REMOVED, MODIFIED_OLD, MODIFIED_NEW }
    }

    private fun computeDiff(oldLines: List<String>, newLines: List<String>): List<DiffOp> {
        val n = oldLines.size
        val m = newLines.size

        // Compute LCS table
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in 1..n) {
            for (j in 1..m) {
                dp[i][j] = if (oldLines[i - 1] == newLines[j - 1]) {
                    dp[i - 1][j - 1] + 1
                } else {
                    maxOf(dp[i - 1][j], dp[i][j - 1])
                }
            }
        }

        // Backtrack to build diff operations
        val ops = mutableListOf<DiffOp>()
        var i = n
        var j = m
        val pendingRemoved = mutableListOf<String>()
        val pendingAdded = mutableListOf<String>()

        fun flushPending() {
            if (pendingRemoved.isEmpty() && pendingAdded.isEmpty()) return

            // Pair up removed/added lines that are similar as "modified"
            val pairs = minOf(pendingRemoved.size, pendingAdded.size)
            for (k in 0 until pairs) {
                ops.add(DiffOp(DiffOp.Type.MODIFIED_OLD, pendingRemoved[k]))
                ops.add(DiffOp(DiffOp.Type.MODIFIED_NEW, pendingAdded[k]))
            }
            // Remaining unpaired lines
            for (k in pairs until pendingRemoved.size) {
                ops.add(DiffOp(DiffOp.Type.REMOVED, pendingRemoved[k]))
            }
            for (k in pairs until pendingAdded.size) {
                ops.add(DiffOp(DiffOp.Type.ADDED, pendingAdded[k]))
            }
            pendingRemoved.clear()
            pendingAdded.clear()
        }

        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && oldLines[i - 1] == newLines[j - 1]) {
                flushPending()
                ops.add(DiffOp(DiffOp.Type.CONTEXT, oldLines[i - 1]))
                i--
                j--
            } else if (j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j])) {
                pendingAdded.add(0, newLines[j - 1])
                j--
            } else {
                pendingRemoved.add(0, oldLines[i - 1])
                i--
            }
        }
        flushPending()

        return ops.reversed()
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
