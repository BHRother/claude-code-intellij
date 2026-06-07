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
    // Failure-dedupe state: consecutive failures of the same tool name
    // collapse into a single badge with a (Nx) suffix instead of spamming
    // a new "✗ failed" line per attempt.
    private var lastFailedToolName: String? = null
    private var lastFailedElementId: String? = null
    private var lastFailedCount: Int = 0
    private var toolFailCounter: Int = 0
    /** Mirrors the current tab display name. Updated on auto-name and manual rename. */
    private var currentDisplayName: String = session.name
    /** Slash-command autocomplete popup; null until init wires it on top of the input area. */
    private var slashPopup: SlashCommandPopup? = null
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

    // Reusable keyboard-navigable picker (AskUserQuestion options + permission grants).
    private val choiceBar = ChoiceBar()
    private var pendingAsk: PendingAsk? = null
    private var askCounter = 0

    /** One in-flight AskUserQuestion: its questions and the answers gathered so far. */
    private inner class PendingAsk(
        val askId: String,
        val questions: List<com.claudecode.session.AskQuestion>,
    ) {
        val answers = HashMap<Int, List<String>>()
        fun nextUnanswered(): Int? = questions.indices.firstOrNull { it !in answers }
    }
    private var toolUseCounter = 0
    private val toolUseIdToHtmlId = mutableMapOf<String, String>()
    private var permissionHintShown = false
    /** While a permission-grant prompt is showing, swallow Claude's redundant
     *  "could you approve…" text and further failure badges this turn. */
    private var permissionPromptActive = false
    /** Running total of usage cost (USD) for this panel's session. Surfaced by `/cost`. */
    private var totalCostUsd: Double = 0.0
    /** Last turn's cost (USD). Surfaced by `/cost` alongside the total. */
    private var lastTurnCostUsd: Double = 0.0
    /** Epoch-millis the most recent request started. Used by the long-task notification heuristic. */
    private var lastTurnStartedAt: Long = 0L

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

        // Escape to stop current request (or dismiss slash popup if showing)
        val panelInputMap = this.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
        val panelActionMap = this.actionMap
        panelInputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "escape-stop")
        panelActionMap.put("escape-stop", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                if (slashPopup?.isShowing == true) {
                    slashPopup!!.hide()
                    return
                }
                // Dismiss the picker (question or permission grant).
                if (choiceBar.isActive) {
                    choiceBar.clear()
                    return
                }
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

        installSlashCommandPopup()

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
            add(choiceBar) // persistent picker (questions + permission grants), hidden until needed
            add(inputPanel)
            add(chipsPanel)
            add(statusHintPanel)
            add(debugScrollPane)
        }

        add(outputOverlay, BorderLayout.CENTER)
        add(bottomPanel, BorderLayout.SOUTH)

        session.addListener(this)

        if (session.isResumed) {
            // Initial header — kick the async JSONL load right after so the
            // full transcript replaces this stub once parsed.
            appendHtml(buildResumeHeaderHtml(loading = true))
            loadAndRenderHistory()
        } else {
            appendHtml("<div class='system-msg'>Claude Code session started. Working directory: ${escapeHtml(session.workingDirectory)}</div>")
        }
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
        // If the slash-command popup is showing, Enter picks the highlighted
        // command instead of sending. The popup's onSelect callback rewrites
        // the input to the command string; the next Enter sends it normally.
        if (slashPopup?.isShowing == true) {
            slashPopup!!.pickSelected()
            return
        }
        // AskUserQuestion picker is open and the user hasn't typed anything:
        // Enter submits the highlighted option. If they DID type, fall through
        // and send their custom answer (clearing the pending question below).
        if (choiceBar.isActive && inputArea.getFullText().isEmpty()) {
            choiceBar.submitSelected()
            return
        }

        val text = inputArea.getFullText()
        if (text.isEmpty() || session.isBusy) return
        // A typed message supersedes any pending question.
        clearPendingAsk()

        val trimmed = text.trim()

        // `--flag` style input: claude -p won't parse it, surface the warning
        // unchanged. Slash commands are handled separately below.
        if (Regex("^--[a-zA-Z]").containsMatchIn(trimmed)) {
            showCliFlagWarning(text)
            return
        }

        // Slash command: handle known ones locally (so they work in -p
        // mode without depending on Claude's interactive shell). Unknown
        // slash inputs are blocked with a warning — `claude -p "/foo"`
        // tries to resolve "foo" as a session ID and fails with
        // "No conversation found with session ID: ..." rather than
        // treating it as chat text.
        if (looksLikeSlashCommand(trimmed)) {
            if (handleSlashCommand(trimmed)) {
                inputArea.clear()
                return
            }
            showUnknownSlashWarning(trimmed)
            return
        }

        inputArea.clear()
        autoNameTab(text)
        appendUserMessage(text)
        permissionHintShown = false
        resetFailureDedupe()
        setBusyState(true)
        // Sending a new message means "I'm done reading history" — snap to
        // the bottom so the user's message and Claude's reply are in view,
        // even if they had scrolled up earlier.
        scrollOutputToBottom()
        session.sendMessage(text)
    }

    /**
     * Tightly-bounded "looks like /command" check that excludes path-like
     * inputs (`/Users/foo`, `/etc/hosts`). First token must be `/word`
     * with letters/dashes only, 2-20 chars.
     */
    private fun looksLikeSlashCommand(trimmed: String): Boolean {
        val firstToken = trimmed.substringBefore(' ')
        return firstToken.length in 2..20 &&
            Regex("^/[a-zA-Z][a-zA-Z-]*$").matches(firstToken)
    }

    /**
     * Intercepts the well-known slash commands. Returns true if the input
     * was fully handled and consumed; false if the command is unknown and
     * the caller should send it through as plain text.
     */
    private fun handleSlashCommand(trimmed: String): Boolean {
        val firstToken = trimmed.substringBefore(' ').lowercase()
        return when (firstToken) {
            "/clear" -> { runClearCommand(); true }
            "/help", "/?" -> { runHelpCommand(); true }
            "/cost" -> { runCostCommand(); true }
            "/model", "/models" -> { runModelInfoCommand(); true }
            "/settings", "/config" -> { runSettingsCommand(); true }
            else -> false
        }
    }

    private fun runClearCommand() {
        if (session.isBusy) session.stop()
        session.resetConversation()
        // Wipe the visible chat. The HTMLEditorKit accepts an empty body
        // by setting text to a minimal document; the existing CSS still
        // applies via the editor kit's stylesheet.
        outputPane.text = "<html><body></body></html>"
        permissionHintShown = false
        resetFailureDedupe()
        appendHtml(
            "<div class='system-msg'>↻ Session cleared — starting a fresh conversation. " +
                "Claude no longer has the previous context.</div>"
        )
    }

    private fun runHelpCommand() {
        appendUserMessage("/help")
        appendHtml(
            "<div class='system-msg' style='color: #BCBEC4;'>" +
                "<b>Supported slash commands:</b><br/>" +
                "• <code>/clear</code> — drop current conversation and start fresh<br/>" +
                "• <code>/help</code> — this list<br/>" +
                "• <code>/cost</code> — total cost across this session's responses<br/>" +
                "• <code>/model</code> — currently selected model<br/>" +
                "• <code>/settings</code> — open the plugin Settings page<br/>" +
                "<br/><i>Only these commands are supported at the moment. Anything else " +
                "starting with <code>/</code> is blocked — Claude's CLI " +
                "(<code>-p</code> mode) doesn't run interactive slash commands, so we " +
                "warn instead of sending them through.</i>" +
                "</div>"
        )
    }

    /**
     * Surfaces a friendly warning when the user types an unsupported
     * slash command. The text is kept in the input area so they can
     * edit and resend.
     */
    private fun showUnknownSlashWarning(trimmed: String) {
        val firstToken = trimmed.substringBefore(' ').take(40)
        appendHtml(
            "<div class='system-msg' style='margin: 6px 0; padding: 6px 10px; " +
                "border-left: 3px solid #D9B263; background-color: #2B2D30;'>" +
                "<span style='color: #D9B263;'>⚠ <code>${escapeHtml(firstToken)}</code> " +
                "isn't a supported slash command.</span><br/>" +
                "<span style='color: #BCBEC4;'>Currently supported: " +
                "<code>/clear</code>, <code>/help</code>, <code>/cost</code>, " +
                "<code>/model</code>, <code>/settings</code>. " +
                "Other slash commands aren't sent to Claude — its <code>-p</code> mode " +
                "treats them as session-ID lookups, not chat input. " +
                "Edit your message to start with something other than <code>/</code>, " +
                "or pick from the list above." +
                "</span></div>"
        )
    }

    private fun runSettingsCommand() {
        appendUserMessage("/settings")
        openClaudeSettings()
    }

    /**
     * Wires the slash-command autocomplete popup to the input area.
     *
     * Trigger condition is intentionally narrow: the input must be exactly
     * "/" with the caret at position 1. This avoids the popup nagging
     * users typing `/Users/foo` paths or `/api/v1/...` URLs.
     *
     * Navigation is handled by:
     *   - Arrow up/down on the text input → moves popup selection (we
     *     consume the key when the popup is showing).
     *   - Enter → picked up by [sendCurrentMessage]'s existing intercept
     *     (it calls `slashPopup.pickSelected()` before any send logic).
     *   - Escape → handled by the panel's existing escape-stop action,
     *     which dismisses the popup first if it's showing.
     *   - Focus loss → dismisses the popup (deferred so a popup mouse
     *     click can fire pickSelected first).
     */
    private fun installSlashCommandPopup() {
        val textComp = inputArea.getTextComponent()
        val popup = SlashCommandPopup(inputArea) { command ->
            // Use PasteAwareInputArea's wrapper, NOT the bare textPane. The
            // wrapper flips isProgrammaticInsert so the DocumentFilter skips
            // chip-creation logic — otherwise `/settings` matches the
            // filename detector (settings.svg in docs/) and gets turned
            // into a file chip mid-set, leaving the doc length out of sync
            // with `command.length` → IllegalArgumentException on
            // caret-position set.
            inputArea.text = command
            inputArea.caretPosition = command.length
            inputArea.requestFocusInWindow()
        }
        slashPopup = popup

        textComp.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = recheck()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = recheck()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) { /* attr changes */ }

            private fun recheck() {
                // Defer to invokeLater so the document's text is settled
                // and caretPosition reflects the new state.
                SwingUtilities.invokeLater {
                    val text = try {
                        textComp.document.getText(0, textComp.document.length)
                    } catch (_: Exception) {
                        return@invokeLater
                    }
                    val shouldShow = text == "/" && textComp.caretPosition == 1
                    when {
                        shouldShow && !popup.isShowing -> popup.show()
                        !shouldShow && popup.isShowing -> popup.hide()
                    }
                }
            }
        })

        textComp.addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (!popup.isShowing) return
                when (e.keyCode) {
                    KeyEvent.VK_UP -> { popup.moveSelection(-1); e.consume() }
                    KeyEvent.VK_DOWN -> { popup.moveSelection(1); e.consume() }
                    KeyEvent.VK_TAB -> { popup.pickSelected(); e.consume() }
                    // VK_ENTER falls through to the existing Enter handler,
                    // which calls sendCurrentMessage → picks the popup item.
                    // VK_ESCAPE falls through to the panel's escape-stop
                    // action, which dismisses the popup before stop logic.
                }
            }
        })

        // AskUserQuestion picker navigation. Only active while the input is
        // empty, so typing a custom answer (and caret movement) still works.
        // Enter is handled by sendCurrentMessage's guard; Esc by escape-stop.
        textComp.addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (!choiceBar.isActive || inputArea.getFullText().isNotEmpty()) return
                when (e.keyCode) {
                    KeyEvent.VK_UP -> { choiceBar.moveSelection(-1); e.consume() }
                    KeyEvent.VK_DOWN -> { choiceBar.moveSelection(1); e.consume() }
                    KeyEvent.VK_SPACE -> { if (choiceBar.toggleSelected()) e.consume() }
                }
            }
        })

        textComp.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusLost(e: java.awt.event.FocusEvent) {
                // Defer so a popup mouse click (which steals focus briefly)
                // can fire pickSelected before we tear the popup down.
                SwingUtilities.invokeLater {
                    if (popup.isShowing) popup.hide()
                }
            }
        })
    }

    private fun runCostCommand() {
        appendUserMessage("/cost")
        val sb = StringBuilder("<div class='system-msg'>")
        if (totalCostUsd > 0) {
            sb.append("Total cost for this session: <b>$").append(String.format("%.4f", totalCostUsd)).append("</b>")
            if (lastTurnCostUsd > 0) {
                sb.append(" <span style='color: #808080;'>(last turn: $")
                sb.append(String.format("%.4f", lastTurnCostUsd))
                sb.append(")</span>")
            }
        } else {
            sb.append("<span style='color: #808080;'>No cost recorded yet — send a message first.</span>")
        }
        sb.append("</div>")
        appendHtml(sb.toString())
    }

    private fun runModelInfoCommand() {
        appendUserMessage("/model")
        val constants = com.claudecode.ClaudeConstants
        val effective = session.modelOverride?.takeIf { it.isNotBlank() }
            ?: ClaudeSettings.getInstance().state.model
        val label = if (effective.isBlank()) "Default (CLI choice)" else constants.shortModelLabel(effective)
        val rawHint = if (effective.isBlank()) "" else " (<code>${escapeHtml(effective)}</code>)"
        appendHtml(
            "<div class='system-msg'>" +
                "Currently using <b>${escapeHtml(label)}</b>$rawHint." +
                " Click the model chip below the input to switch (per-session)." +
                "</div>"
        )
    }

    private fun showCliFlagWarning(text: String) {
        val firstToken = text.trim().substringBefore(' ').take(40)
        appendHtml(
            "<div class='system-msg' style='margin: 6px 0; padding: 6px 10px; " +
                "border-left: 3px solid #D9B263; background-color: #2B2D30;'>" +
                "<span style='color: #D9B263;'>⚠ <code>${escapeHtml(firstToken)}</code> " +
                "looks like a CLI flag, which this plugin doesn't run.</span><br/>" +
                "<span style='color: #BCBEC4;'>Use the <b>gear icon</b> below the input to open " +
                "<b>Settings</b>, or the <b>model / permission chips</b> next to it for " +
                "per-session overrides. Edit your message and send again to chat with Claude." +
                "</span></div>"
        )
    }

    fun sendPrefilled(text: String) {
        if (session.isBusy) return
        clearPendingAsk()
        autoNameTab(text)
        appendUserMessage(text)
        permissionHintShown = false
        resetFailureDedupe()
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
        currentDisplayName = name
        onTabRename(name)
    }

    /**
     * Called from outside (e.g. ClaudeToolWindowFactory's manual Rename
     * action) so SessionPanel can keep its tracked name in sync. Persists
     * the new name to the recent-chats store opportunistically.
     */
    fun updateDisplayName(name: String) {
        if (name.isBlank()) return
        currentDisplayName = name
        // Refresh the persisted recent entry so the rename shows up in
        // the "Recent" surface immediately, not just on the next turn.
        if (session.claudeSessionId != null) {
            persistRecentSnapshot()
        }
    }

    private fun setBusyState(busy: Boolean) {
        if (busy) {
            permissionPromptActive = false // new turn — stop suppressing text
            sendStopButton.text = "Stop"
            sendStopButton.setVariant(AccentButton.Variant.DANGER)
            sendStopButton.toolTipText = "Stop Claude (cancel current request)"
            statusLabel.text = "Claude is thinking..."
            statusLabel.foreground = JBColor(Color(0xD9, 0x77, 0x57), Color(0xD9, 0x77, 0x57))
            thinkingContent = null
            activeToolName = null
            startThinkingAnimation()
            lastTurnStartedAt = System.currentTimeMillis()
            // Block system sleep while Claude is working so a long agentic
            // run isn't interrupted by the user's screen lock policy.
            com.claudecode.platform.SleepInhibitor.start()
        } else {
            stopThinkingAnimation()
            sendStopButton.text = "Send"
            sendStopButton.setVariant(AccentButton.Variant.ACCENT)
            sendStopButton.toolTipText = "Send message (Enter). Shift+Enter for new line."
            statusLabel.text = "Ready"
            statusLabel.foreground = JBColor(Color(0x80, 0x80, 0x80), Color(0x80, 0x80, 0x80))
            com.claudecode.platform.SleepInhibitor.stop()
        }
    }

    override fun onText(session: ClaudeSession, text: String) {
        ApplicationManager.getApplication().invokeLater {
            // A permission prompt is showing → swallow Claude's redundant
            // "it needs your approval / could you approve…" narration.
            if (permissionPromptActive) return@invokeLater
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

    override fun onAskUserQuestion(
        session: ClaudeSession,
        toolUseId: String,
        questions: List<com.claudecode.session.AskQuestion>,
    ) {
        ApplicationManager.getApplication().invokeLater {
            stopThinkingAnimation()
            statusLabel.text = ""
            clearPendingAsk()
            val pending = PendingAsk("ask-${askCounter++}", questions)
            pendingAsk = pending
            renderAskQuestions(pending)
            pending.nextUnanswered()?.let { showAskBarFor(it) }
        }
    }

    /** Records the question(s) in the chat for history; the picker bar below the
     *  input handles the interaction (keyboard + click + custom answer). */
    private fun renderAskQuestions(pending: PendingAsk) {
        val sb = StringBuilder("<div class='system-msg'>")
        sb.append("<div style='color:#6897BB;'><b>Claude needs your input</b></div>")
        pending.questions.forEach { q ->
            sb.append("<div style='margin-top:4px;'>${escapeHtml(q.question)}</div>")
        }
        sb.append("</div>")
        appendHtml(sb.toString())
        scrollOutputToBottom()
    }

    private fun showAskBarFor(qIdx: Int) {
        val pending = pendingAsk ?: return
        val q = pending.questions.getOrNull(qIdx) ?: return
        choiceBar.present(
            title = q.question,
            choices = q.options.map { Choice(it.label, it.description) },
            multiSelect = q.multiSelect,
            onSubmit = { choices -> onAskAnswered(qIdx, choices.map { it.label }) },
            onCustom = {
                // User chose "Something else" → focus the input so they can type
                // a custom answer; type + Enter sends it (see sendCurrentMessage).
                inputArea.requestFocusInWindow()
            },
        )
        inputArea.requestFocusInWindow()
    }

    /** Records the answer for one question, then advances or sends the composed answer. */
    private fun onAskAnswered(qIdx: Int, labels: List<String>) {
        val pending = pendingAsk ?: return
        pending.answers[qIdx] = labels
        val next = pending.nextUnanswered()
        if (next != null) {
            showAskBarFor(next)
            return
        }
        val msg = pending.questions.indices.joinToString("\n") { i ->
            val q = pending.questions[i]
            val head = q.header.ifBlank { q.question }
            "$head: ${pending.answers[i]?.joinToString(", ").orEmpty()}"
        }
        clearPendingAsk()
        appendUserMessage(msg)
        sendAnswerWhenReady(msg)
    }

    /** Sends the user's answer once the (just-finishing) turn is idle. */
    private fun sendAnswerWhenReady(text: String, attempt: Int = 0) {
        if (!session.isBusy) {
            permissionHintShown = false
            resetFailureDedupe()
            setBusyState(true)
            scrollOutputToBottom()
            session.sendMessage(text)
            return
        }
        if (attempt >= MAX_RETRY_POLL_ATTEMPTS) {
            appendHtml("<div class='system-msg' style='color:#D9B263;'>" +
                "⚠ Couldn't send your answer automatically — send it manually.</div>")
            return
        }
        ApplicationManager.getApplication().invokeLater { sendAnswerWhenReady(text, attempt + 1) }
    }

    /** Dismiss the choice bar (questions or permission grants) and any pending question. */
    private fun clearPendingAsk() {
        choiceBar.clear()
        pendingAsk = null
    }

    override fun onToolUse(session: ClaudeSession, tool: String, detail: String?, diffSummary: String?, diffData: Pair<String, String>?, filePath: String?) {
        ApplicationManager.getApplication().invokeLater {
            // After a permission block we're stopping the turn; don't render
            // chips for the further (also-blocked) commands Claude tries.
            if (permissionPromptActive) return@invokeLater
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

    override fun onToolResult(session: ClaudeSession, toolUseId: String, isError: Boolean, resultContent: String?) {
        ApplicationManager.getApplication().invokeLater {
            val justFinishedTool = activeToolName
            activeToolName = null
            thinkingContent = null
            thinkingStartTime = System.currentTimeMillis()
            statusLabel.text = "Claude is thinking..."

            if (!isError) {
                // Successful tool finishes the failure run \u2014 reset dedupe state.
                lastFailedToolName = null
                lastFailedElementId = null
                lastFailedCount = 0
                appendHtml("<div class='tool-msg'>&nbsp;&nbsp;<span style='color: #6A8759;'>\u2713</span></div>")
                return@invokeLater
            }

            // Permission denials are surfaced by the grant ChoiceBar (and we stop
            // the turn), so don't also render a redundant "\u2717 failed: requires
            // approval" badge \u2014 nor any further failure badges while the prompt
            // is up.
            if (permissionPromptActive ||
                (resultContent != null && session.looksLikePermissionDenial(resultContent))
            ) {
                return@invokeLater
            }

            // Failure path: collapse consecutive failures of the same tool
            // into a single badge with a "(Nx)" count, and surface a snippet
            // of the actual error message so the user knows *why* it failed.
            val snippet = errorSnippet(resultContent)
            if (justFinishedTool != null && justFinishedTool == lastFailedToolName && lastFailedElementId != null) {
                lastFailedCount += 1
                updateFailureElement(lastFailedElementId!!, justFinishedTool, snippet, lastFailedCount)
            } else {
                lastFailedToolName = justFinishedTool
                lastFailedCount = 1
                lastFailedElementId = "tool-fail-${toolFailCounter++}"
                appendHtml(buildFailureHtml(lastFailedElementId!!, justFinishedTool, snippet, 1))
            }
        }
    }

    /**
     * Trim and collapse the raw tool_result content into a single line for
     * the failure badge. Strips ANSI/PowerShell category cruft and clamps
     * to FAILURE_SNIPPET_MAX so the badge stays one line.
     */
    private fun errorSnippet(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        // Take the first non-blank line \u2014 most CLI errors lead with the
        // useful summary and follow with stack/category noise.
        val firstLine = raw.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: return null
        return if (firstLine.length <= FAILURE_SNIPPET_MAX) firstLine
        else firstLine.take(FAILURE_SNIPPET_MAX - 1) + "\u2026"
    }

    private fun resetFailureDedupe() {
        lastFailedToolName = null
        lastFailedElementId = null
        lastFailedCount = 0
    }

    private fun buildFailureHtml(elementId: String, toolName: String?, snippet: String?, count: Int): String {
        val tail = buildString {
            if (toolName != null) append(" ${escapeHtml(toolName)}")
            if (snippet != null) append(": ${escapeHtml(snippet)}")
            if (count > 1) append(" <span style='color: #707070;'>(${count}x)</span>")
        }
        return "<div id='$elementId' class='tool-msg'>&nbsp;&nbsp;" +
            "<span style='color: #FF6B68;'>\u2717 failed</span>" +
            "<span style='color: #BCBEC4;'>$tail</span>" +
            "</div>"
    }

    private fun updateFailureElement(elementId: String, toolName: String?, snippet: String?, count: Int) {
        val doc = outputPane.document as? javax.swing.text.html.HTMLDocument ?: return
        val element = doc.getElement(elementId) ?: return
        try {
            doc.setOuterHTML(element, buildFailureHtml(elementId, toolName, snippet, count))
        } catch (_: Exception) {
            // Fallback to a fresh badge if the in-place swap fails for any reason.
            appendHtml(buildFailureHtml(elementId, toolName, snippet, count))
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
            permissionPromptActive = true
            presentPermissionChoices(toolName, toolInputDetail)
            // Stop the turn: in -p mode Claude would otherwise keep trying more
            // (also-blocked) commands and asking in text. Granting re-runs the
            // original prompt from scratch anyway, so nothing is lost by stopping.
            if (session.isBusy) {
                session.stop()
                setBusyState(false)
            }
        }
    }

    /**
     * Presents the "blocked by permission mode" remediations on the keyboard
     * [choiceBar]: allow this exact pattern, allow the broad pattern, switch to
     * Unrestricted, or deny. Same actions as the old banner, now keyboard-first.
     */
    private fun presentPermissionChoices(toolName: String?, toolInputDetail: String?) {
        val currentMode = ClaudeSettings.getInstance().state.permissionMode
        val currentModeLabel = com.claudecode.ClaudeConstants.shortPermissionModeLabel(currentMode)
        val safeToolName = toolName?.takeIf { it.isNotBlank() } ?: "tool"

        val specificPattern = com.claudecode.project.ProjectAllowlist.patternFor(safeToolName, toolInputDetail)
        val broadPattern = com.claudecode.project.ProjectAllowlist.patternFor(safeToolName, null)
        // "Specific" is meaningful when it differs from broad \u2014 either because we
        // have a tool input that wraps into a (X) pattern, or because the tool is
        // MCP (mcp__server__tool vs mcp__server).
        val hasSpecific = specificPattern != broadPattern

        val detail = if (!toolInputDetail.isNullOrBlank())
            " \u00b7 attempted <code>${escapeHtml(toolInputDetail.take(160))}</code>" else ""
        appendHtml(
            "<div class='system-msg' style='border-left:3px solid #D9B263; padding:4px 8px; margin:6px 0;'>" +
                "<span style='color:#D9B263;'>\u26a0 <code>${escapeHtml(safeToolName)}</code> was blocked by your " +
                "permission mode (<b>${escapeHtml(currentModeLabel)}</b>)$detail</span></div>"
        )

        val choices = buildList {
            if (hasSpecific) add(Choice("Allow this exact pattern: $specificPattern", id = "specific"))
            add(Choice("Allow broad pattern: $broadPattern", id = "broad"))
            add(Choice("Switch to Unrestricted mode", id = "unrestricted"))
            add(Choice("Deny / keep blocked", id = "deny"))
        }
        choiceBar.present(
            title = "Allow this tool to run?",
            choices = choices,
            hint = "Allow-list writes to .claude/settings.local.json \u00b7 mode change is per-session",
            onSubmit = { picked ->
                when (picked.firstOrNull()?.id) {
                    "specific" -> applyGrant(safeToolName, toolInputDetail, broad = false)
                    "broad" -> applyGrant(safeToolName, toolInputDetail, broad = true)
                    "unrestricted" -> switchSessionMode(com.claudecode.ClaudeConstants.PERMISSION_MODE_BYPASS)
                    "deny" -> {
                        choiceBar.clear()
                        appendHtml("<div class='system-msg' style='color:#808080;'>Kept blocked.</div>")
                        permissionHintShown = false
                    }
                }
            },
        )
        inputArea.requestFocusInWindow()
    }

    private fun applyGrant(toolName: String, inputDetail: String?, broad: Boolean) {
        choiceBar.clear()
        val pattern = com.claudecode.project.ProjectAllowlist.patternFor(
            toolName,
            if (broad) null else inputDetail
        )

        // The in-flight claude process won't pick up .claude/settings.local.json
        // changes \u2014 that file is read at spawn time. Snapshot the last user
        // prompt, kill any runaway request, then auto-resend after the
        // grant write completes so Claude retries with the new permission.
        //
        // We retry whether or not the session is currently busy: the user
        // clicked Allow on this denial banner, which is an explicit "redo
        // this with the permission granted" signal. If they clicked after
        // the original request already finished/gave up (very common),
        // they still want it to run again with the new permission.
        val lastPrompt = session.messages.lastOrNull { it.role == "user" }?.content
        if (session.isBusy) {
            session.stop()
        }

        // File I/O off the EDT \u2014 JSON parse+write is fast but should never
        // block the event dispatch thread on principle.
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = com.claudecode.project.ProjectAllowlist.addAllow(
                session.workingDirectory,
                pattern
            )
            ApplicationManager.getApplication().invokeLater {
                renderGrantResult(result)
                if (result.success && lastPrompt != null) {
                    scheduleRetryAfterGrant(lastPrompt)
                }
            }
        }
    }

    /**
     * After applying a permission grant or mode switch, re-spawn the
     * last user prompt so Claude retries from the same conversation
     * context with the new permission in place.
     *
     * Stop is asynchronous (kills the process; onFinished resets isBusy
     * via setBusyState). To make sure we don't try to send while the
     * previous process is still tearing down, we poll isBusy with a
     * cheap invokeLater chain, then send. Bounded to ~30 retries so a
     * stuck stop can't loop forever.
     */
    private fun scheduleRetryAfterGrant(prompt: String, attempt: Int = 0) {
        if (!session.isBusy) {
            appendHtml(
                "<div class='system-msg' style='color: #6A8759; font-style: italic;'>" +
                    "↻ Retrying with new permission…" +
                    "</div>"
            )
            permissionHintShown = false
            resetFailureDedupe()
            setBusyState(true)
            scrollOutputToBottom()
            session.sendMessage(prompt)
            return
        }
        if (attempt >= MAX_RETRY_POLL_ATTEMPTS) {
            appendHtml(
                "<div class='system-msg' style='color: #D9B263;'>" +
                    "⚠ Could not retry automatically — the previous request is still stopping. " +
                    "Send your message again manually." +
                    "</div>"
            )
            return
        }
        ApplicationManager.getApplication().invokeLater {
            scheduleRetryAfterGrant(prompt, attempt + 1)
        }
    }

    private fun renderGrantResult(result: com.claudecode.project.ProjectAllowlist.Result) {
        if (!result.success) {
            appendHtml(
                "<div class='system-msg' style='color:#FF6B68;'>\u2717 Could not update " +
                    "<code>${escapeHtml(result.filePath)}</code>: " +
                    "${escapeHtml(result.error ?: "unknown error")}</div>"
            )
            permissionHintShown = false
            return
        }
        // Tell IntelliJ's VFS about the on-disk change so the Project view and
        // any open editor reflect it immediately. Refresh both the parent dir
        // (so .claude itself appears the first time) and the file.
        if (!result.alreadyPresent) {
            val targetFile = java.io.File(result.filePath)
            val toRefresh = listOfNotNull(targetFile.parentFile, targetFile)
            LocalFileSystem.getInstance().refreshIoFiles(toRefresh, true, true, null)
        }
        val verb = if (result.alreadyPresent) "Already in" else "Added to"
        val willRetry = session.messages.any { it.role == "user" }
        val followUp = if (willRetry) "Retrying your message\u2026" else "Send your message again."
        appendHtml(
            "<div class='system-msg' style='color:#6A8759;'>\u2713 $verb allowlist: " +
                "<code>${escapeHtml(result.pattern)}</code> \u00b7 <span style='color:#808080;'>$followUp</span></div>"
        )
        permissionHintShown = false
    }

    /** Override the session's permission mode (per-session), announce it, and retry the last prompt. */
    private fun switchSessionMode(mode: String) {
        choiceBar.clear()
        // Explicit retry signal regardless of whether the request is in flight.
        val lastPrompt = session.messages.lastOrNull { it.role == "user" }?.content
        if (session.isBusy) {
            session.stop()
        }
        session.permissionModeOverride = mode
        val label = com.claudecode.ClaudeConstants.shortPermissionModeLabel(mode)
        permissionChip.updateLabel(label)
        permissionChip.toolTipText = "Permission mode: " +
            com.claudecode.ClaudeConstants.describePermissionMode(mode)
        appendHtml(
            "<div class='system-msg' style='color:#6A8759;'>\u2713 Switched permission mode to <b>${escapeHtml(label)}</b> " +
                "for this session. <span style='color:#808080;'>" +
                (if (lastPrompt != null) "Retrying your message\u2026" else "Send a new message to use it.") +
                " Global default unchanged \u2014 open Settings to make it permanent.</span></div>"
        )
        permissionHintShown = false
        if (lastPrompt != null) {
            scheduleRetryAfterGrant(lastPrompt)
        }
    }

    override fun onFinished(session: ClaudeSession, costUsd: Double?) {
        val elapsedMs = if (lastTurnStartedAt > 0) System.currentTimeMillis() - lastTurnStartedAt else 0L
        ApplicationManager.getApplication().invokeLater {
            resetEditConsolidation()
            if (costUsd != null) {
                lastTurnCostUsd = costUsd
                totalCostUsd += costUsd
            }
            val costStr = if (costUsd != null) " | \$${String.format("%.4f", costUsd)}" else ""
            setBusyState(false)
            statusLabel.text = "Ready$costStr"

            if (changedFiles.isNotEmpty()) {
                refreshChangedFiles()
                appendHtml(buildFilesSummaryHtml())
                changedFiles.clear()
            }

            // Persist the recent-chats entry so it survives IDE restart.
            // Only after a turn finished and Claude assigned a server-side
            // session_id — without that, --resume can't reconnect.
            persistRecentSnapshot()

            // If the turn took a while and the user isn't watching the chat
            // panel, ping them with an IntelliJ balloon so they can swing
            // back to the IDE. Short-running turns and active-window cases
            // are silent to avoid notification fatigue.
            maybeNotifyLongTaskComplete(elapsedMs, costUsd)
        }
    }

    /**
     * Surfaces a non-modal IntelliJ notification when a slow request
     * completes while the tool window isn't the active one. Stays in
     * the Event Log either way.
     */
    private fun maybeNotifyLongTaskComplete(elapsedMs: Long, costUsd: Double?) {
        if (elapsedMs < LONG_TASK_NOTIFY_THRESHOLD_MS) return
        if (isToolWindowVisibleAndActive()) return
        val seconds = elapsedMs / 1000
        val displayCost = if (costUsd != null) " · $${String.format("%.4f", costUsd)}" else ""
        val group = com.intellij.notification.NotificationGroupManager
            .getInstance()
            .getNotificationGroup("Claude Code Tasks") ?: return
        group.createNotification(
            "Claude finished — ${escapeHtml(currentDisplayName)}",
            "Took ${seconds}s$displayCost",
            com.intellij.notification.NotificationType.INFORMATION,
        ).notify(project)
    }

    private fun isToolWindowVisibleAndActive(): Boolean {
        val tw = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
            .getToolWindow(com.claudecode.ClaudeConstants.TOOL_WINDOW_ID) ?: return false
        return tw.isVisible && tw.isActive
    }

    /** Look up the persisted recent-chats entry for the current Claude session, if any. */
    private fun recentEntryForCurrentSession(): com.claudecode.history.RecentSession? {
        val claudeId = session.claudeSessionId ?: return null
        return com.claudecode.history.RecentSessionsStore
            .recentForProject(session.workingDirectory)
            .firstOrNull { it.id == claudeId }
    }

    /**
     * Builds the resume header (one line). Used both as the initial stub
     * during the async JSONL load and as the prefix above the rendered
     * full transcript. The single wrapper div carries [RESUME_PREAMBLE_ID]
     * so the async result can swap header + transcript together via
     * HTMLDocument.setOuterHTML.
     */
    private fun buildResumeHeaderHtml(
        loading: Boolean = false,
        loadedCount: Int? = null,
        unavailableReason: String? = null,
    ): String {
        val entry = recentEntryForCurrentSession()
        val ageStr = entry?.let { humanizeAgo(System.currentTimeMillis() - it.lastUsedAt) }
        val countStr = entry?.let { "${it.messageCount} prior message${if (it.messageCount == 1) "" else "s"}" }

        val sb = StringBuilder()
        sb.append("<div id='$RESUME_PREAMBLE_ID'>")
        sb.append("<div class='system-msg' style='color: #808080; font-style: italic;'>")
        sb.append("↻ Resumed session")
        if (ageStr != null) sb.append(" from $ageStr")
        if (countStr != null) sb.append(" — $countStr")
        sb.append(".")
        when {
            loading -> sb.append(" <span style='color: #606060;'>Loading transcript…</span>")
            loadedCount != null -> sb.append(" <span style='color: #606060;'>$loadedCount turn")
                .append(if (loadedCount == 1) "" else "s").append(" rendered below.</span>")
            unavailableReason != null -> sb.append(" <span style='color: #606060;'>")
                .append(escapeHtml(unavailableReason)).append("</span>")
        }
        sb.append("</div>")
        sb.append("</div>")
        return sb.toString()
    }

    /**
     * Async-loads the full transcript from Claude Code's local JSONL the
     * first time a resumed chat opens. No "Load" button — we always load
     * eagerly (matches Cursor's behavior). On failure (file missing /
     * unreadable) the header swaps to a "history unavailable" line and
     * the chat keeps working normally.
     */
    private fun loadAndRenderHistory() {
        val claudeId = session.claudeSessionId
        if (claudeId.isNullOrBlank()) {
            replaceResumePreamble(buildResumeHeaderHtml(unavailableReason = "No session ID — cannot resume."))
            return
        }
        val workDir = session.workingDirectory

        ApplicationManager.getApplication().executeOnPooledThread {
            val file = com.claudecode.history.ClaudeSessionFile.locate(workDir, claudeId)
            val contents = if (file != null) {
                com.claudecode.history.ClaudeSessionFile.readTextOnly(file)
            } else {
                com.claudecode.history.ClaudeSessionFile.SessionContents(
                    emptyList(), null, "Local transcript not found under ~/.claude/projects/."
                )
            }
            ApplicationManager.getApplication().invokeLater {
                if (contents.error != null || contents.messages.isEmpty()) {
                    replaceResumePreamble(
                        buildResumeHeaderHtml(unavailableReason = "Full history unavailable.")
                    )
                    return@invokeLater
                }
                replaceResumePreamble(renderFullHistoryHtml(contents.messages))

                // Apply the historical permission mode for this session only.
                // Doesn't touch global settings — chip override scope.
                val mode = contents.permissionMode
                if (!mode.isNullOrBlank() &&
                    mode in com.claudecode.ClaudeConstants.PERMISSION_MODES
                ) {
                    session.permissionModeOverride = mode
                    permissionChip.updateLabel(com.claudecode.ClaudeConstants.shortPermissionModeLabel(mode))
                    permissionChip.toolTipText = "Permission mode: " +
                        com.claudecode.ClaudeConstants.describePermissionMode(mode)
                }
            }
        }
    }

    /**
     * Builds the full-history replacement HTML: short header div, then one
     * proper chat block per message (user → `.user-msg`, assistant →
     * markdown-rendered same as live chat), then an explicit
     * "End of previous history" separator so the boundary with any new
     * turn is visually clear. Historical messages don't get copy/edit
     * action links — they're context, not actionable.
     */
    private fun renderFullHistoryHtml(messages: List<com.claudecode.history.ClaudeSessionFile.HistoricalMessage>): String {
        val sb = StringBuilder()
        // Same single-wrapper pattern as buildResumeHeaderHtml so the
        // async load (or any re-render) swaps everything via one setOuterHTML.
        sb.append("<div id='$RESUME_PREAMBLE_ID'>")

        // Header is the same shape as the loading stub, with a final count.
        sb.append(buildResumeHeaderHtml(loadedCount = messages.size)
            .removePrefix("<div id='$RESUME_PREAMBLE_ID'>")
            .removeSuffix("</div>"))

        messages.forEach { m ->
            when (m.role) {
                "user" -> {
                    sb.append("<div class='user-msg'>")
                    sb.append(escapeHtml(m.text))
                    sb.append("</div>")
                }
                "assistant" -> {
                    // Reuse the same markdown pipeline live chat uses, but
                    // suppress copy/apply links — these are historical and
                    // wiring them up would require seeding the action maps
                    // with new keys for every old code block.
                    sb.append(MarkdownRenderer.render(m.text))
                }
                // Defensive: unknown role → render as plain dimmed text
                // rather than dropping silently.
                else -> {
                    sb.append("<div class='system-msg' style='color: #707070;'>")
                    sb.append(escapeHtml(m.text))
                    sb.append("</div>")
                }
            }
        }

        sb.append("<div class='system-msg' style='margin: 14px 0 6px 0; padding: 6px 0; ")
        sb.append("color: #707070; border-top: 1px dashed #3C3F41; border-bottom: 1px dashed #3C3F41; ")
        sb.append("text-align: center; font-style: italic; font-size: 11px;'>")
        sb.append("─── End of previous history ───")
        sb.append("</div>")

        sb.append("</div>")  // close outer wrapper
        return sb.toString()
    }

    /** Replace the resume-preamble div in place via HTMLDocument.setOuterHTML. */
    private fun replaceResumePreamble(newHtml: String) {
        val doc = outputPane.document as? javax.swing.text.html.HTMLDocument ?: return
        val element = doc.getElement(RESUME_PREAMBLE_ID)
        if (element != null) {
            try {
                doc.setOuterHTML(element, newHtml)
            } catch (_: Exception) {
                // Fallback: append at end. Worst-case duplicates the preamble
                // but never crashes.
                appendHtml(newHtml)
            }
        } else {
            appendHtml(newHtml)
        }
    }

    companion object {
        private const val RESUME_PREAMBLE_ID = "resume-preamble"
        /** Max length of the inline error snippet on a "✗ failed" badge. */
        private const val FAILURE_SNIPPET_MAX = 140
        /** Bound on the post-grant retry-poll loop — ~30 invokeLater ticks. */
        private const val MAX_RETRY_POLL_ATTEMPTS = 30
        /** A turn that took at least this long fires a desktop notification when the panel isn't focused. */
        private const val LONG_TASK_NOTIFY_THRESHOLD_MS = 20_000L
    }

    private fun humanizeAgo(deltaMs: Long): String {
        val mins = deltaMs / 60_000L
        val hours = mins / 60L
        val days = hours / 24L
        return when {
            days >= 2 -> "$days days ago"
            days == 1L -> "1 day ago"
            hours >= 2 -> "$hours hours ago"
            hours == 1L -> "1 hour ago"
            mins >= 2 -> "$mins minutes ago"
            else -> "just now"
        }
    }

    /**
     * Snapshot the current session into [com.claudecode.history.RecentSessionsStore]
     * so it appears in the "Recent" dropdown across IDE restarts. Metadata
     * only — message content is sourced on demand from Claude's own JSONL
     * transcript (see [com.claudecode.history.ClaudeSessionFile]).
     */
    private fun persistRecentSnapshot() {
        val claudeId = session.claudeSessionId ?: return
        val now = System.currentTimeMillis()
        // Preserve createdAt across touches by looking up any existing entry.
        val existing = com.claudecode.history.RecentSessionsStore
            .recentForProject(session.workingDirectory)
            .firstOrNull { it.id == claudeId }
        val createdAt = existing?.createdAt ?: now

        com.claudecode.history.RecentSessionsStore.touch(
            session.workingDirectory,
            com.claudecode.history.RecentSession(
                id = claudeId,
                name = currentDisplayName,
                workingDirectory = session.workingDirectory,
                createdAt = createdAt,
                lastUsedAt = now,
                messageCount = session.messages.size,
            )
        )
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

        // Text-only fallback (no structured tool info, so no allow-list patterns):
        // offer the mode switches on the keyboard bar.
        val currentModeLabel = com.claudecode.ClaudeConstants.shortPermissionModeLabel(
            ClaudeSettings.getInstance().state.permissionMode
        )
        appendHtml(
            "<div class='system-msg' style='border-left:3px solid #D9B263; padding:4px 8px; margin:6px 0;'>" +
                "<span style='color:#D9B263;'>⚠ Looks like a tool was blocked by your permission mode " +
                "(<b>${escapeHtml(currentModeLabel)}</b>).</span></div>"
        )
        choiceBar.present(
            title = "Change permission mode to continue?",
            choices = listOf(
                Choice("Switch to Content Only (file edits allowed, shell blocked)", id = "content"),
                Choice("Switch to Unrestricted (allow everything)", id = "unrestricted"),
                Choice("Dismiss", id = "dismiss"),
            ),
            hint = "Per-session via the permission chip · global default unchanged",
            onSubmit = { picked ->
                when (picked.firstOrNull()?.id) {
                    "content" -> switchSessionMode(com.claudecode.ClaudeConstants.PERMISSION_MODE_ACCEPT_EDITS)
                    "unrestricted" -> switchSessionMode(com.claudecode.ClaudeConstants.PERMISSION_MODE_BYPASS)
                    "dismiss" -> { choiceBar.clear(); permissionHintShown = false }
                }
            },
        )
        inputArea.requestFocusInWindow()
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
