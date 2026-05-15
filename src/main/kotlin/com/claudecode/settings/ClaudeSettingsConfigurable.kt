package com.claudecode.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.*
import javax.swing.JComponent

class ClaudeSettingsConfigurable : Configurable {

    private var panel: DialogPanel? = null
    private val settings by lazy { ClaudeSettings.getInstance() }

    override fun getDisplayName(): String = com.claudecode.ClaudeConstants.TOOL_WINDOW_ID

    override fun createComponent(): JComponent {
        panel = panel {
            group("General") {
                row("Claude CLI path:") {
                    textField()
                        .bindText(settings.state::claudePath)
                        .columns(COLUMNS_LARGE)
                        .comment("Path to the 'claude' CLI executable")
                }
                row("Model:") {
                    val allModels = settings.getAllModels()
                    comboBox(allModels)
                        .bindItem(
                            { settings.state.model },
                            { settings.state.model = it ?: "" }
                        )
                        .applyToComponent { isEditable = true }
                        .validationOnApply {
                            val text = (it.editor.item as? String)?.trim() ?: ""
                            if (text.isNotBlank() && !text.startsWith("claude-")) {
                                warning("Model ID typically starts with 'claude-' (e.g., claude-sonnet-4-6). Save anyway?")
                            } else {
                                null
                            }
                        }
                        .comment("Leave empty for CLI default. You can type any model ID.")
                }
                row("Font size:") {
                    spinner(8..32)
                        .bindIntValue(settings.state::fontSize)
                        .comment("Applies to new sessions")
                }
                row("Max sessions:") {
                    spinner(1..20)
                        .bindIntValue(settings.state::maxSessions)
                }
            }
            group("Permissions") {
                row("Permission mode:") {
                    val modes = com.claudecode.ClaudeConstants.PERMISSION_MODES
                    comboBox(modes)
                        .bindItem(
                            { settings.state.permissionMode.takeIf { it in modes }
                                ?: com.claudecode.ClaudeConstants.PERMISSION_MODE_ACCEPT_EDITS },
                            { settings.state.permissionMode = it ?: com.claudecode.ClaudeConstants.PERMISSION_MODE_ACCEPT_EDITS }
                        )
                        .comment(
                            "<b>acceptEdits</b> (recommended): file edits go through, shell commands are blocked.<br/>" +
                                "<b>bypassPermissions</b>: Claude can run any tool including shell commands.<br/>" +
                                "<b>plan</b>: read-only — Read/Grep/Glob only, useful for exploratory chats.<br/>" +
                                "Maps to the CLI's <code>--permission-mode</code> flag."
                        )
                }
            }
            group("Context") {
                row {
                    checkBox("Send selected code as context")
                        .bindSelected(settings.state::sendSelectionContext)
                        .comment("When using right-click actions, send the selected code to Claude")
                }
            }
        }
        return panel!!
    }

    override fun isModified(): Boolean {
        return panel?.isModified() == true
    }

    override fun apply() {
        panel?.apply()
    }

    override fun reset() {
        panel?.reset()
    }

    override fun disposeUIResources() {
        panel = null
    }
}
