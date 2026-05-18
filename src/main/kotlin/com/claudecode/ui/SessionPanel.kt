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
    private val sendStopButton: AccentButton
    private val statusLabel: JLabel
    private val thinkingLabel: JLabel
    private val debugArea: JTextArea
    private val debugToggle: JCheckBox
    private lateinit var modelChip: ChipDropdown
    private lateinit var permissionChip: ChipDropdown
    /** Mirrors the model the user has selected in the chip — used to detect divergence. */
    private var selectedModelForDivergenceCheck: String = ""
    /** Throttle: only warn about a given (selected, actual) pair once per session. */
    private val warnedModelPairs = mutableSetOf<Pair<String, String>>()
    /** One-shot: first time the user changes any chip in this session, drop a hint that it's session-local. */
    private var chipScopeHintShown = false
    private var thinkingTimer: Timer? = null
    private var dotCount = 0
    private var thinkingStartTime = 0L
    private var thinkingContent: String? = null
    private var activeToolName: String? = null
    private var hasAutoNamed = false
    private val changedFiles = mutableListOf<Pair<String, String>>() // (filePath, action)
    private val copyableCommands = mutableMapOf<String, String>()
    private val applyableCode = mutableMapOf<String, Pair<String, String>>() // key -> (code, lang)
    // Pending permission-grant offers keyed by the action URL token. Stores
    // (toolName, toolInputDetail) so the click handler knows what to allow.
    private val pendingGrants = mutableMapOf<String, Pair<String, String?>>()
    private var grantCounter = 0
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
                        href.contains("/action/grant-specific/") -> {
                            val key = href.substringAfter("/action/grant-specific/")
                            pendingGrants[key]?.let { applyGrant(key, broad = false) }
                        }
                        href.contains("/action/grant-broad/") -> {
                            val key = href.substringAfter("/action/grant-broad/")
                            pendingGrants[key]?.let { applyGrant(key, broad = true) }
                        }
                        href.contains("/action/grant-unrestricted/") -> {
                            val key = href.substringAfter("/action/grant-unrestricted/")
                            pendingGrants[key]?.let { switchToUnrestricted(key) }
                        }
                        href.contains("/action/swap-model/") -> {
                            val key = href.substringAfter("/action/swap-model/")
                            pendingGrants[key]?.let { applyModelSwap(key) }
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

        inputArea.border = JBUI.Borders.customLine(JBColor(0x3C3F41, 0x3C3F41), 1, 0, 0, 0)
        // Cursor-style auto-grow: starts at 3 lines, expands per line up to
        // 10, then scrolls. Reset to 3 happens automatically on clear() because
        // the document listener fires on text removal too.
        inputArea.enableAutoGrow(minLines = 3, maxLines = 10)
        // Keep the chat output pinned to the bottom across input-area resizes.
        // Without this, growing the input shifts the chat viewport upward,
        // making "Jump to latest" flicker on even though the user hasn't moved.
        inputArea.onBeforeAutoGrow = {
            val wasAtBottom = isOutputAtBottom()
            if (wasAtBottom) {
                // Two-step defer: revalidate is queued on the EDT, layout
                // happens on the *next* paint cycle, and only then can we
                // read the new scroll maximum. invokeLater→invokeLater puts
                // us safely after both.
                SwingUtilities.invokeLater {
                    SwingUtilities.invokeLater { scrollOutputToBottom() }
                }
            }
        }

        sendStopButton = AccentButton("Send").apply {
            font = smallFont
            addActionListener { onSendStopClick() }
            toolTipText = "Send message (Enter). Shift+Enter for new line."
        }

        statusLabel = JLabel("Ready").apply {
            border = JBUI.Borders.empty(2, 8)
            foreground = JBColor(0x808080, 0x808080)
            font = smallFont
        }

        val hintLabel = JLabel("Enter to send · Shift+Enter for newline · Esc to stop").apply {
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

        debugToggle = JCheckBox("Debug").apply {
            font = monoFont.deriveFont(10f)
            foreground = JBColor(0x606060, 0x606060)
            isOpaque = false
            isSelected = false
            toolTipText = "Show debug log panel"
            addActionListener {
                debugScrollPane.isVisible = isSelected
                this@SessionPanel.revalidate()
            }
        }

        // Input area takes the full width now — Send moved into the chip row.
        val inputPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4)
            add(inputArea, BorderLayout.CENTER)
        }

        // Single bottom row: status on the left (changes as Claude works),
        // keyboard hint on the right.
        val statusHintPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(statusLabel, BorderLayout.WEST)
            add(hintLabel, BorderLayout.EAST)
        }

        val chipsPanel = buildChipsPanel(smallFont, debugToggle)

        val bottomPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(thinkingLabel)
            add(inputPanel)
            add(chipsPanel)
            add(statusHintPanel)
            add(debugScrollPane)
        }

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

        if (looksLikeCliCommand(text)) {
            showCliCommandWarning(text)
            return
        }

        inputArea.clear()
        autoNameTab(text)
        appendUserMessage(text)
        permissionHintShown = false
        setBusyState(true)
        // Sending a new message means "I'm done reading history" — snap to
        // the bottom so the user's message and Claude's reply are in view,
        // even if they had scrolled up earlier.
        scrollOutputToBottom()
        session.sendMessage(text)
    }

    /**
     * Detects messages that look like Claude Code CLI invocations
     * (`--model …`, `/help`, `/clear`, etc.) rather than chat prompts.
     * The plugin runs claude in `-p` (non-interactive) mode, so neither
     * slash-commands nor extra CLI flags work — they'd cause a hard CLI
     * error or be silently ignored. Better to intercept and explain.
     *
     * Heuristic kept tight to avoid false positives on legitimate text:
     *   - `--word…` at the very start (with no space before)
     *   - `/word` as the first whitespace-separated token, where word is
     *     a short letter-only identifier (matches `/help`, `/clear`, `/model`
     *     but not `/Users/foo` or `/etc/hosts`)
     */
    private fun looksLikeCliCommand(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        if (Regex("^--[a-zA-Z]").containsMatchIn(trimmed)) return true
        // First token: `/<letters/dashes>` followed by space or end. Path-like
        // inputs (`/Users/...`, `/etc/passwd`) include slashes after the first
        // segment and are skipped by the `(\\s|\$)` boundary.
        val firstToken = trimmed.substringBefore(' ')
        if (firstToken.length in 2..20 && Regex("^/[a-zA-Z][a-zA-Z-]*$").matches(firstToken)) {
            return true
        }
        return false
    }

    private fun showCliCommandWarning(text: String) {
        val firstToken = text.trim().substringBefore(' ').take(40)
        appendHtml(
            "<div class='system-msg' style='margin: 6px 0; padding: 6px 10px; " +
                "border-left: 3px solid #D9B263; background-color: #2B2D30;'>" +
                "<span style='color: #D9B263;'>⚠ <code>${escapeHtml(firstToken)}</code> " +
                "looks like a Claude Code CLI command, which this plugin doesn't run.</span><br/>" +
                "<span style='color: #BCBEC4;'>Claude Code's interactive slash-commands " +
                "(<code>/help</code>, <code>/clear</code>, <code>/model</code>, …) and CLI flags " +
                "(<code>--model</code>, <code>--permission-mode</code>, …) aren't available in this chat.<br/>" +
                "Use the <b>gear icon</b> in the row below the input to open <b>Settings</b>, " +
                "or the <b>model / permission chips</b> next to it for per-session overrides.<br/>" +
                "Edit your message and send again to chat with Claude.</span>" +
                "</div>"
        )
        // Leave the text in the input area so the user can fix it and resend.
    }

    fun sendPrefilled(text: String) {
        if (session.isBusy) return
        autoNameTab(text)
        appendUserMessage(text)
        permissionHintShown = false
        setBusyState(true)
        scrollOutputToBottom()
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
            sendStopButton.setVariant(AccentButton.Variant.DANGER)
            sendStopButton.toolTipText = "Stop Claude (cancel current request)"
            statusLabel.text = "Claude is thinking..."
            statusLabel.foreground = JBColor(Color(0xD9, 0x77, 0x57), Color(0xD9, 0x77, 0x57))
            thinkingContent = null
            activeToolName = null
            startThinkingAnimation()
        } else {
            stopThinkingAnimation()
            sendStopButton.text = "Send"
            sendStopButton.setVariant(AccentButton.Variant.ACCENT)
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
            if (model.isBlank()) return@invokeLater

            val constants = com.claudecode.ClaudeConstants
            // `<synthetic>` and other `<…>` placeholders are CLI internals
            // (cached / interrupted / tool-only turns), not real models.
            // Skip the divergence check, chip update, and custom-model save.
            if (constants.isPlaceholderModel(model)) return@invokeLater
            val selected = selectedModelForDivergenceCheck
            val diverged = selected.isNotBlank() && selected != model

            if (diverged) {
                val pair = selected to model
                if (warnedModelPairs.add(pair)) {
                    // First time we see this (selected → actual) divergence in
                    // this session. Surface a one-line warn banner so the user
                    // sees Claude fell back to a different model — common when
                    // the selected model is rate-limited or out of credits.
                    appendHtml(
                        "<div class='system-msg' style='margin: 6px 0; padding: 6px 10px; " +
                            "border-left: 3px solid #D9B263; background-color: #2B2D30;'>" +
                            "<span style='color: #D9B263;'>⚠ Claude responded using " +
                            "<b>${escapeHtml(constants.shortModelLabel(model))}</b> " +
                            "(<code>${escapeHtml(model)}</code>) instead of your selected " +
                            "<b>${escapeHtml(constants.shortModelLabel(selected))}</b>.</span><br/>" +
                            "<span style='color: #808080;'>Likely a rate-limit or quota fallback. " +
                            "The model dropdown has been updated to reflect the actual model in use.</span>" +
                            "</div>"
                    )
                }
            }

            // Always: refresh chip to mirror the model actually serving requests,
            // and align the per-session override so the next message keeps
            // using it (rather than retrying the one that just fell back).
            ClaudeSettings.getInstance().addCustomModel(model)
            // The set of items may have grown (custom model added) — refresh.
            val allModels = ClaudeSettings.getInstance().getAllModels()
            modelChip.setItems(allModels.map { it to constants.shortModelLabel(it) })
            modelChip.updateLabel(constants.shortModelLabel(model))
            modelChip.toolTipText = "Model: $model"
            session.modelOverride = model
            selectedModelForDivergenceCheck = model
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

    override fun onPermissionBlocked(session: ClaudeSession, toolName: String?, toolInputDetail: String?) {
        ApplicationManager.getApplication().invokeLater {
            // Fire the hint immediately based on the structured tool_result \u2014
            // don't wait for the model to produce natural-language text. This
            // is the same UI as maybeShowPermissionHint but triggered earlier
            // and more reliably.
            if (permissionHintShown) return@invokeLater
            permissionHintShown = true
            renderPermissionBlockedBanner(toolName, toolInputDetail)
        }
    }

    /**
     * Renders the yellow "blocked by permission mode" banner with three
     * one-click remediations:
     *   1. Allow this exact command/path  \u2192 writes `.claude/settings.local.json`
     *   2. Allow all <Tool> calls         \u2192 ditto, broader pattern
     *   3. Change mode to Unrestricted    \u2192 flips the permission chip
     *
     * Each link carries a generated key so the click handler can look up
     * which (tool, input) pair this banner was for, even if multiple
     * banners stack in the same session.
     */
    private fun renderPermissionBlockedBanner(toolName: String?, toolInputDetail: String?) {
        val currentMode = ClaudeSettings.getInstance().state.permissionMode
        val currentModeLabel = com.claudecode.ClaudeConstants.shortPermissionModeLabel(currentMode)
        val safeToolName = toolName?.takeIf { it.isNotBlank() } ?: "tool"

        val key = "grant-${grantCounter++}"
        pendingGrants[key] = safeToolName to toolInputDetail

        val specificPattern = com.claudecode.project.ProjectAllowlist.patternFor(safeToolName, toolInputDetail)
        val broadPattern = com.claudecode.project.ProjectAllowlist.patternFor(safeToolName, null)
        val hasSpecific = !toolInputDetail.isNullOrBlank() && specificPattern != broadPattern

        val toolLabel = " (<code>${escapeHtml(safeToolName)}</code>)"
        val detailLabel = if (!toolInputDetail.isNullOrBlank())
            "<div style='color: #808080; margin-top: 4px;'>Attempted: <code>${escapeHtml(toolInputDetail.take(200))}</code></div>"
        else ""

        val actions = buildString {
            append("<div style='color: #BCBEC4; margin-top: 6px;'>Choose one:</div>")
            if (hasSpecific) {
                append("<div style='margin-top: 2px;'>\u2022 ")
                append("<a href=\"http://localhost/action/grant-specific/$key\">")
                append("Allow this exact ${escapeHtml(safeToolName.lowercase())}")
                append("</a></div>")
            }
            append("<div style='margin-top: 2px;'>\u2022 ")
            append("<a href=\"http://localhost/action/grant-broad/$key\">")
            append("Allow all <code>${escapeHtml(safeToolName)}</code> calls")
            append("</a></div>")
            append("<div style='margin-top: 2px;'>\u2022 ")
            append("<a href=\"http://localhost/action/grant-unrestricted/$key\">")
            append("Switch to Unrestricted mode")
            append("</a></div>")
            append("<div style='color: #707070; font-style: italic; margin-top: 6px;'>")
            append("Allow-list edits write to <code>.claude/settings.local.json</code> in this project; ")
            append("mode change is per-session via the permission chip.")
            append("</div>")
        }

        // The banner gets a stable id so we can swap its HTML in-place after
        // the user picks one of the actions \u2014 removes the "I can click again"
        // ambiguity without leaving the warning AND a separate confirmation
        // stacked in the chat.
        val bannerId = bannerIdFor(key)
        appendHtml(
            "<div id='$bannerId' class='system-msg' style='margin: 6px 0; padding: 6px 10px; " +
                "border-left: 3px solid #D9B263; background-color: #2B2D30;'>" +
                "<div style='color: #D9B263;'>\u26a0 A tool$toolLabel was blocked by your current permission mode " +
                "(<b>${escapeHtml(currentModeLabel)}</b>).</div>" +
                detailLabel +
                actions +
                "</div>"
        )
    }

    private fun bannerIdFor(key: String): String = "permblock-$key"

    /**
     * Replaces the banner's HTML in place. Used after the user picks an
     * action so the choice is final and the action links can't be re-clicked.
     */
    private fun replacePermissionBanner(key: String, newInnerHtml: String, accentColor: String) {
        val doc = outputPane.document as? HTMLDocument ?: return
        val element = doc.getElement(bannerIdFor(key)) ?: return
        val replacement = "<div id='${bannerIdFor(key)}' class='system-msg' " +
            "style='margin: 6px 0; padding: 6px 10px; " +
            "border-left: 3px solid $accentColor; background-color: #2B2D30;'>" +
            newInnerHtml +
            "</div>"
        try {
            doc.setOuterHTML(element, replacement)
        } catch (e: Exception) {
            // Fallback: just append below the banner \u2014 better than crashing.
            appendHtml(replacement)
        }
    }

    private fun applyGrant(key: String, broad: Boolean) {
        val (toolName, inputDetail) = pendingGrants[key] ?: return
        // Single-use: prevent the same banner from being applied twice.
        pendingGrants.remove(key)
        val pattern = com.claudecode.project.ProjectAllowlist.patternFor(
            toolName,
            if (broad) null else inputDetail
        )
        // File I/O off the EDT \u2014 JSON parse+write is fast but should never
        // block the event dispatch thread on principle.
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = com.claudecode.project.ProjectAllowlist.addAllow(
                session.workingDirectory,
                pattern
            )
            ApplicationManager.getApplication().invokeLater {
                renderGrantResult(key, result)
            }
        }
    }

    private fun renderGrantResult(key: String, result: com.claudecode.project.ProjectAllowlist.Result) {
        if (!result.success) {
            // Re-open the choice \u2014 write failed, user might want a different
            // action (or to retry after fixing perms).
            pendingGrants[key] = (pendingGrants[key] ?: ("tool" to null))
            replacePermissionBanner(
                key,
                "<div style='color: #FF6B68;'>\u2717 Could not update " +
                    "<code>${escapeHtml(result.filePath)}</code>: " +
                    "${escapeHtml(result.error ?: "unknown error")}</div>",
                accentColor = "#FF6B68",
            )
            return
        }
        // Tell IntelliJ's VFS about the on-disk change so the Project view
        // and any open editor reflect the new content immediately \u2014 without
        // this, the user has to manually right-click \u2192 Reload from Disk on
        // the .claude folder. Refresh both the parent dir (so .claude itself
        // appears the first time we create it) and the file itself.
        if (!result.alreadyPresent) {
            val targetFile = java.io.File(result.filePath)
            val toRefresh = listOfNotNull(targetFile.parentFile, targetFile)
            LocalFileSystem.getInstance().refreshIoFiles(toRefresh, true, true, null)
        }

        val verb = if (result.alreadyPresent) "Already in" else "Added to"
        replacePermissionBanner(
            key,
            "<div style='color: #6A8759;'>\u2713 $verb project allowlist: " +
                "<code>${escapeHtml(result.pattern)}</code></div>" +
                "<div style='color: #808080; margin-top: 4px;'>Resend your message \u2014 Claude will " +
                "now be allowed to run it under the current permission mode.</div>",
            accentColor = "#6A8759",
        )
        // Allow another blocked-banner to fire on the next denial.
        permissionHintShown = false
    }

    private fun switchToUnrestricted(key: String) {
        pendingGrants.remove(key)
        val mode = com.claudecode.ClaudeConstants.PERMISSION_MODE_BYPASS
        session.permissionModeOverride = mode
        permissionChip.updateLabel(com.claudecode.ClaudeConstants.shortPermissionModeLabel(mode))
        permissionChip.toolTipText = "Permission mode: " +
            com.claudecode.ClaudeConstants.describePermissionMode(mode)
        replacePermissionBanner(
            key,
            "<div style='color: #6A8759;'>\u2713 Switched permission mode to <b>Unrestricted</b> " +
                "for this session.</div>" +
                "<div style='color: #808080; margin-top: 4px;'>Resend your message to retry. " +
                "Global default is unchanged \u2014 open Settings to make it permanent.</div>",
            accentColor = "#6A8759",
        )
        permissionHintShown = false
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
            if (looksLikeClaudeNotFound(error)) {
                showCliNotFoundHint()
            }
            setBusyState(false)
        }
    }

    private fun looksLikeClaudeNotFound(error: String): Boolean {
        val lower = error.lowercase()
        // ProcessBuilder on Windows: "Cannot run program \"claude\" ... CreateProcess error=2"
        // ProcessBuilder on Unix: "Cannot run program \"claude\" ... No such file or directory"
        if (!lower.contains("cannot run program")) return false
        return lower.contains("claude") || lower.contains("createprocess error=2") ||
            lower.contains("no such file")
    }

    private fun showCliNotFoundHint() {
        appendHtml(
            "<div class='system-msg' style='margin: 6px 0; padding: 6px 10px; " +
                "border-left: 3px solid #D9B263; background-color: #2B2D30;'>" +
                "<span style='color: #D9B263;'>⚠ The Claude CLI couldn't be found.</span><br/>" +
                "Open <a href=\"http://localhost/action/open-settings\">Settings</a> and click " +
                "<b>Auto-detect</b> next to the CLI path field, or use <b>Browse…</b> to pick the executable. " +
                "On Windows it's usually <code>%APPDATA%\\npm\\claude.cmd</code>; on macOS/Linux check that " +
                "<code>claude</code> is on PATH or set the absolute path." +
                "</div>"
        )
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
    /**
     * Fires once per session, the first time the user changes the model or
     * permission chip. Surfaces the per-session-vs-global distinction so a
     * user who expected the change to stick across sessions knows where to
     * make it permanent. Subsequent chip changes are silent.
     */
    private fun maybeShowChipScopeHint() {
        if (chipScopeHintShown) return
        chipScopeHintShown = true
        appendHtml(
            "<div class='system-msg' style='margin: 4px 0; padding: 4px 10px; " +
                "color: #808080; font-style: italic;'>" +
                "ⓘ Chip changes apply to this session only. To set a new default, " +
                "open <a href=\"http://localhost/action/open-settings\">Settings</a> via the gear icon." +
                "</div>"
        )
    }

    /**
     * Returns the (value, displayLabel) list for the model dropdown.
     * Deprecated catalog entries get a "(deprecated)" suffix so users
     * notice them before clicking; the underlying value is unchanged so
     * selecting still works.
     */
    private fun buildModelMenuItems(): List<Pair<String, String>> {
        val constants = com.claudecode.ClaudeConstants
        val registry = com.claudecode.models.ModelsRegistry
        return ClaudeSettings.getInstance().getAllModels().map { id ->
            val base = constants.shortModelLabel(id)
            val label = if (registry.isDeprecated(id)) "$base (deprecated)" else base
            id to label
        }
    }

    private val warnedDeprecatedModels = mutableSetOf<String>()

    /**
     * If [modelId] is flagged deprecated by the live catalog, drop a one-time
     * per-(session, id) banner with a one-click swap to its replacement. Quiet
     * for active or unknown IDs.
     */
    private fun maybeWarnDeprecatedModel(modelId: String) {
        if (modelId.isBlank()) return
        val registry = com.claudecode.models.ModelsRegistry
        if (!registry.isDeprecated(modelId)) return
        if (!warnedDeprecatedModels.add(modelId)) return
        val replacement = registry.replacementFor(modelId)
        val note = registry.noteFor(modelId)
        val constants = com.claudecode.ClaudeConstants

        val swapLink = if (!replacement.isNullOrBlank()) {
            val key = "deprecated-swap-${grantCounter++}"
            pendingGrants[key] = replacement to null   // re-use the grant key plumbing
            "<br/><a href=\"http://localhost/action/swap-model/$key\">" +
                "Switch to ${escapeHtml(constants.shortModelLabel(replacement))}" +
                "</a>"
        } else ""

        val noteLine = if (!note.isNullOrBlank())
            "<div style='color: #808080; margin-top: 4px;'>${escapeHtml(note)}</div>" else ""

        appendHtml(
            "<div class='system-msg' style='margin: 6px 0; padding: 6px 10px; " +
                "border-left: 3px solid #D9B263; background-color: #2B2D30;'>" +
                "<span style='color: #D9B263;'>⚠ Model <b>${escapeHtml(constants.shortModelLabel(modelId))}</b> " +
                "(<code>${escapeHtml(modelId)}</code>) is marked deprecated in the live catalog.</span>" +
                noteLine +
                swapLink +
                "</div>"
        )
    }

    private fun applyModelSwap(key: String) {
        val (newModel, _) = pendingGrants.remove(key) ?: return
        session.modelOverride = newModel
        selectedModelForDivergenceCheck = newModel
        warnedModelPairs.clear()
        modelChip.setItems(buildModelMenuItems())
        modelChip.updateLabel(com.claudecode.ClaudeConstants.shortModelLabel(newModel))
        modelChip.toolTipText = "Model: " + if (newModel.isBlank()) "CLI default" else newModel
        appendHtml(
            "<div class='system-msg' style='margin: 4px 0; padding: 4px 10px; " +
                "color: #6A8759; font-style: italic;'>" +
                "✓ Switched to ${escapeHtml(com.claudecode.ClaudeConstants.shortModelLabel(newModel))} " +
                "for this session." +
                "</div>"
        )
    }

    private fun maybeShowPermissionHint(text: String) {
        if (permissionHintShown) return
        if (!looksLikePermissionBlocked(text)) return
        permissionHintShown = true

        val currentMode = ClaudeSettings.getInstance().state.permissionMode
        val currentModeLabel = com.claudecode.ClaudeConstants.shortPermissionModeLabel(currentMode)
        appendHtml(
            "<div class='system-msg' style='margin: 6px 0; padding: 6px 10px; " +
                "border-left: 3px solid #D9B263; background-color: #2B2D30;'>" +
                "<span style='color: #D9B263;'>⚠ Looks like a tool was blocked by your current permission mode " +
                "(<b>$currentModeLabel</b>).</span><br/>" +
                "Switch the permission chip above the input area to <b>Content Only</b> (lets file writes through) " +
                "or <b>Unrestricted</b> (allows everything including shell commands) — then re-run your request." +
                "</div>"
        )
    }

    internal fun looksLikePermissionBlocked(text: String): Boolean {
        val lower = text.lowercase()
        // The model's wording when a tool fails due to permission. Tuned for
        // false-negative tolerance: the hint is purely informational, so the
        // worst case of a false positive is one extra suggestion.
        // Phrase list curated from real -p mode responses where the CLI
        // silently blocked a Bash/Write/Edit call and the model started
        // asking the user to allow it.
        if (lower.contains("don't have permission") || lower.contains("do not have permission")) return true
        if (lower.contains("permission denied") || lower.contains("permission was denied")) return true
        if (lower.contains("permission to use the") || lower.contains("permission to use this")) return true
        if (lower.contains("requires approval")) return true
        if (lower.contains("needs your approval") || lower.contains("need your approval")) return true
        if (lower.contains("needs to be approved") || lower.contains("need to be approved")) return true
        if (lower.contains("permission prompt")) return true
        if (lower.contains("allowlist") || lower.contains("allow list")) return true
        if (lower.contains("could you allow") || lower.contains("can you allow") ||
            lower.contains("could you approve") || lower.contains("can you approve")) return true
        if (Regex("\\bapprove\\b.*\\btool\\b").containsMatchIn(lower)) return true
        if (Regex("\\b(blocked|denied)\\b.*\\b(tool|permission|approval|command)\\b")
                .containsMatchIn(lower)) return true
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

    /**
     * Builds the inline chip row (model + permission mode), Cursor-style:
     * flat borderless chips with a small caret, opening a popup menu on
     * click. Selections are per-session overrides on the [ClaudeSession];
     * the global defaults in [ClaudeSettings] remain the source of truth
     * for new sessions.
     */
    private fun buildChipsPanel(smallFont: Font, debugToggleComponent: JComponent): JComponent {
        val settings = ClaudeSettings.getInstance()
        val state = settings.state
        val constants = com.claudecode.ClaudeConstants

        // Model chip — built-in models + any custom IDs the user has used.
        // Empty string is shown as "Default" (CLI picks).
        val initialModel = if (state.model in settings.getAllModels()) state.model else ""
        selectedModelForDivergenceCheck = initialModel
        modelChip = ChipDropdown(constants.shortModelLabel(initialModel), smallFont).apply {
            toolTipText = "Model — click to switch (per-session)"
            // Use a provider so the dropdown re-reads the current catalog
            // each time the popup opens — Refresh in Settings (or a
            // background TTL refresh) lands automatically next click.
            setItemsProvider { buildModelMenuItems() }
            onPick { value ->
                session.modelOverride = value
                selectedModelForDivergenceCheck = value
                // Picking a model clears prior warnings for this session so the
                // user gets a fresh signal if it diverges again.
                warnedModelPairs.clear()
                updateLabel(constants.shortModelLabel(value))
                toolTipText = "Model: " + if (value.isBlank()) "CLI default" else value
                maybeShowChipScopeHint()
                maybeWarnDeprecatedModel(value)
            }
        }
        // If the user's persisted model is already deprecated, surface a
        // banner immediately so they don't get stuck on a sunset entry.
        maybeWarnDeprecatedModel(initialModel)

        // Permission chip — three modes that make sense in -p (non-interactive).
        val initialPerm = state.permissionMode
            .takeIf { it in constants.PERMISSION_MODES }
            ?: constants.PERMISSION_MODE_ACCEPT_EDITS
        permissionChip = ChipDropdown(constants.shortPermissionModeLabel(initialPerm), smallFont).apply {
            toolTipText = "Permission mode: ${constants.describePermissionMode(initialPerm)}"
            setItems(constants.PERMISSION_MODES.map { it to constants.shortPermissionModeLabel(it) })
            onPick { value ->
                session.permissionModeOverride = value
                updateLabel(constants.shortPermissionModeLabel(value))
                toolTipText = "Permission mode: ${constants.describePermissionMode(value)}"
                maybeShowChipScopeHint()
            }
        }

        // Gear → opens our Settings page. AllIcons.General.GearPlain exists in
        // 2024.1+; verified against AllIcons$General signatures.
        val gearButton = IconChipButton(
            com.intellij.icons.AllIcons.General.GearPlain,
            "Plugin settings"
        ).apply {
            addActionListener { openClaudeSettings() }
        }

        // Left strip — chips + debug toggle. Wrapped in a non-vertical scroll
        // pane so it can scroll horizontally when the tool window is too
        // narrow to fit everything alongside the pinned right-side actions.
        val leftStrip = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(modelChip)
            add(Box.createHorizontalStrut(4))
            add(permissionChip)
            add(Box.createHorizontalStrut(8))
            add(debugToggleComponent)
        }
        val leftScroll = JBScrollPane(leftStrip).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
            verticalScrollBarPolicy = javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
            isOpaque = false
            viewport.isOpaque = false
            // Match the chip row's natural height — let the layout decide the width.
            // BorderLayout.CENTER fills the available horizontal space anyway.
            preferredSize = Dimension(0, leftStrip.preferredSize.height)
        }

        // Right strip — gear + Send. Always visible: BorderLayout.EAST locks
        // it to its preferred width regardless of how narrow the panel gets.
        val rightActions = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(gearButton)
            add(Box.createHorizontalStrut(4))
            add(sendStopButton)
        }

        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 6)
            isOpaque = false
            add(leftScroll, BorderLayout.CENTER)
            add(rightActions, BorderLayout.EAST)
        }
    }

    fun dispose() {
        stopThinkingAnimation()
        session.removeListener(this)
    }
}
