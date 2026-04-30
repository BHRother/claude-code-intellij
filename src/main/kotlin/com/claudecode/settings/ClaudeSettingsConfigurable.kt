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
                    val models = com.claudecode.ClaudeConstants.AVAILABLE_MODELS
                    comboBox(models)
                        .bindItem(
                            { settings.state.model },
                            { settings.state.model = it ?: "" }
                        )
                        .comment("Leave empty to use the default model configured in Claude CLI")
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
            collapsibleGroup("Code Completion (Experimental)") {
                row {
                    checkBox("Enable Claude code completion")
                        .bindSelected(settings.state::enableCompletion)
                        .comment("Uses Haiku for inline suggestions. Slow — spawns a CLI process per request. Not recommended for production use yet.")
                }
                row("Debounce (ms):") {
                    spinner(100..5000, 100)
                        .bindIntValue(
                            { settings.state.completionDebounceMs.toInt() },
                            { settings.state.completionDebounceMs = it.toLong() }
                        )
                }
            }
            group("Permissions") {
                row {
                    checkBox("Auto-accept file changes")
                        .bindSelected(settings.state::autoAcceptPermissions)
                        .comment("Allow Claude to create, edit, and delete files without prompting. Disable to require manual approval for each change.")
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
