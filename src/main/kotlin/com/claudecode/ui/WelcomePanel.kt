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
                </ul>
            </div>

            <h2>Right-Click Actions</h2>
            <div class='section'>
                Select code in the editor or a file in the project tree, then right-click:
                <ul>
                    <li><b>Explain with Claude</b> — understand what code does and why</li>
                    <li><b>Refactor with Claude</b> — get improvement suggestions</li>
                    <li><b>Generate Tests with Claude</b> — create unit tests for your code</li>
                    <li><b>Fix with Claude</b> — identify and fix bugs in selected code</li>
                </ul>
            </div>

            <h2>Keyboard Shortcuts</h2>
            <div class='section'>
                <ul>
                    <li><span class='shortcut'>Enter</span> — send message</li>
                    <li><span class='shortcut'>Shift+Enter</span> — new line</li>
                    <li><span class='shortcut'>Escape</span> — stop current request</li>
                    <li><span class='shortcut'>Ctrl+C / Cmd+C</span> twice — stop current request</li>
                </ul>
            </div>

            <h2>File Tracking</h2>
            <div class='section'>
                <ul>
                    <li>Files created or modified by Claude are shown as clickable links</li>
                    <li>A summary of all changed files appears after each response</li>
                    <li>The project tree refreshes automatically</li>
                </ul>
            </div>

            <h2>Settings</h2>
            <div class='section'>
                <a href="http://localhost/action/settings">Open Settings</a> &nbsp;<span class='dim'>(Settings &rarr; Tools &rarr; Claude Code)</span>
                <ul>
                    <li>Choose your preferred Claude model</li>
                    <li>Configure the CLI path</li>
                    <li>Toggle auto-accept file changes</li>
                    <li>Enable experimental code completion</li>
                </ul>
            </div>

            <h2>Prerequisites</h2>
            <div class='section'>
                <ul>
                    <li>Install the <code>claude</code> CLI: <span class='dim'>npm install -g @anthropic-ai/claude-code</span></li>
                    <li>Authenticate: run <code>claude</code> in a terminal and follow the prompts</li>
                </ul>
            </div>
            </body></html>
        """.trimIndent()
    }
}
