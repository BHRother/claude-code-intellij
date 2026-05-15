package com.claudecode.ui

import com.claudecode.settings.ClaudeSettings
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.*
import javax.swing.event.HyperlinkEvent
import javax.swing.text.html.HTMLEditorKit

class WelcomePanel(
    private val onNewSession: () -> Unit
) : JPanel(BorderLayout()) {

    init {
        val settings = ClaudeSettings.getInstance().state
        val monoFont = Font(com.claudecode.ClaudeConstants.FONT_FAMILY, Font.PLAIN, settings.fontSize)
        background = JBColor(Color(0x1E, 0x1F, 0x22), Color(0x1E, 0x1F, 0x22))

        val content = JTextPane().apply {
            isEditable = false
            contentType = "text/html"
            background = JBColor(Color(0x1E, 0x1F, 0x22), Color(0x1E, 0x1F, 0x22))
            val kit = HTMLEditorKit()
            kit.styleSheet.addRule("""
                body {
                    font-family: 'JetBrains Mono', 'Menlo', 'Consolas', monospace;
                    font-size: ${settings.fontSize}px;
                    color: #BCBEC4;
                    background-color: #1E1F22;
                    padding: 20px;
                }
                h1 { color: #D97757; font-size: 18px; margin-bottom: 12px; }
                h2 { color: #FFC66D; font-size: 14px; margin-top: 16px; margin-bottom: 6px; }
                .section { margin-bottom: 14px; }
                .shortcut { color: #6897BB; }
                .dim { color: #808080; }
                ul { margin-top: 4px; margin-bottom: 4px; }
                li { margin-bottom: 3px; }
                code {
                    background-color: #2B2D30;
                    padding: 1px 4px;
                    color: #A9B7C6;
                }
            """.trimIndent())
            editorKit = kit
            text = buildWelcomeHtml()
            addHyperlinkListener { e ->
                if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                    val href = e.description ?: return@addHyperlinkListener
                    if (href.contains("/action/settings")) {
                        ShowSettingsUtil.getInstance().showSettingsDialog(null, com.claudecode.ClaudeConstants.TOOL_WINDOW_ID)
                    }
                }
            }
        }

        val scrollPane = com.intellij.ui.components.JBScrollPane(content).apply {
            border = JBUI.Borders.empty()
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        }

        val startButton = JButton("Start New Session").apply {
            font = monoFont.deriveFont(Font.BOLD, 14f)
            addActionListener { onNewSession() }
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }

        val buttonPanel = JPanel(FlowLayout(FlowLayout.CENTER, 0, 12)).apply {
            isOpaque = false
            add(startButton)
        }

        add(scrollPane, BorderLayout.CENTER)
        add(buttonPanel, BorderLayout.SOUTH)
    }

    private fun buildWelcomeHtml(): String {
        return """
            <html><body>
            <h1>Claude Code</h1>
            <div class='section'>
                AI pair programming powered by Claude, directly inside your IDE.
                Ask questions, generate code, refactor, debug, and more — all in context
                with your open project.
            </div>

            <h2>Chat Sessions</h2>
            <div class='section'>
                <ul>
                    <li>Click <b>Start New Session</b> or the <span class='shortcut'>+</span> button to begin</li>
                    <li>Run multiple sessions in parallel as separate tabs</li>
                    <li>Claude works in your project directory — it can read, edit, and create files</li>
                    <li>Session history is preserved so Claude remembers context</li>
                    <li>Drag/drop or paste files into the input — they become compact <code>📎</code> chips</li>
                    <li>Paste screenshots (or any clipboard image) — saved to a temp PNG and sent to Claude as a <code>🖼</code> chip on submit</li>
                    <li>Large pastes collapse into a chip — double-click to expand back into the input</li>
                    <li>Every code block in the chat has <span class='shortcut'>[copy]</span> and <span class='shortcut'>[apply]</span> links</li>
                </ul>
            </div>

            <h2>Inline AI Actions</h2>
            <div class='section'>
                Trigger Claude from anywhere you're working, no chat needed:
                <ul>
                    <li><b>Edit with Claude</b> <span class='shortcut'>Cmd+Alt+K</span> / <span class='shortcut'>Ctrl+Alt+K</span> (or the Claude icon in the floating code toolbar) — select code, describe the change, review the diff, accept or reject</li>
                    <li><b>Fix with Claude</b> <span class='shortcut'>Alt+Enter</span> — on any IDE-detected error or warning, pick "Fix with Claude" from the intentions menu</li>
                    <li><b>Generate Commit Message</b> — button in the VCS commit dialog turns your staged diff into a commit message matching your project's style</li>
                    <li><b>Apply</b> — click the <span class='shortcut'>[apply]</span> link on any chat code block to replace the active editor's selection with that code</li>
                </ul>
            </div>

            <h2>Right-Click Actions</h2>
            <div class='section'>
                Select code in the editor, or right-click a file/folder in the project tree:
                <ul>
                    <li><b>Explain with Claude</b> — understand what code does and why (works on folders too)</li>
                    <li><b>Refactor with Claude</b> — get improvement suggestions</li>
                    <li><b>Generate Tests with Claude</b> — for selected code, whole files, or every source file in a folder</li>
                    <li><b>Fix with Claude</b> — identify and fix bugs in selected code</li>
                    <li><b>Open Claude Session Here</b> — start a session rooted at any folder</li>
                    <li><b>Debug with Claude</b> — right-click in the Run/Debug console</li>
                </ul>
            </div>

            <h2>Keyboard Shortcuts</h2>
            <div class='section'>
                <ul>
                    <li><span class='shortcut'>Cmd+Shift+I / Ctrl+Shift+I</span> — toggle this Claude panel</li>
                    <li><span class='shortcut'>Cmd+Alt+K / Ctrl+Alt+K</span> — Edit with Claude (in any editor)</li>
                    <li><span class='shortcut'>Alt+Enter</span> — Fix with Claude (on errors/warnings)</li>
                    <li><span class='shortcut'>Enter</span> / <span class='shortcut'>Shift+Enter</span> — send / new line in chat input</li>
                    <li><span class='shortcut'>Escape</span> or <span class='shortcut'>Ctrl+C / Cmd+C</span> twice — stop current request</li>
                </ul>
            </div>

            <h2>File Tracking</h2>
            <div class='section'>
                <ul>
                    <li>Files created or modified by Claude are shown as clickable links</li>
                    <li>Inline diffs show old vs. new for every edit — click <span class='shortcut'>[▼ diff]</span> to expand</li>
                    <li>A summary of all changed files appears after each response, grouped by folder</li>
                    <li>The project tree refreshes automatically</li>
                </ul>
            </div>

            <h2>Settings</h2>
            <div class='section'>
                <a href="http://localhost/action/settings">Open Settings</a> &nbsp;<span class='dim'>(Settings &rarr; Tools &rarr; Claude Code)</span>
                <ul>
                    <li>Choose your preferred Claude model (editable — type any model ID)</li>
                    <li>Configure the CLI path</li>
                    <li>Pick a permission mode: <code>acceptEdits</code> (default — file edits allowed, shell blocked), <code>bypassPermissions</code> (allow everything), or <code>plan</code> (read-only)</li>
                </ul>
            </div>

            <h2>Prerequisites</h2>
            <div class='section'>
                <ul>
                    <li>Install the <code>claude</code> CLI: <span class='dim'>npm install -g @anthropic-ai/claude-code</span> (works on macOS, Linux, and Windows)</li>
                    <li>Authenticate: run <code>claude</code> in a terminal and follow the prompts</li>
                    <li>Make sure the CLI is on PATH. On Windows, npm typically installs <code>claude.cmd</code> under <code>%APPDATA%\\npm\\</code></li>
                </ul>
            </div>
            </body></html>
        """.trimIndent()
    }
}
