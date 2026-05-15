package com.claudecode.settings

import com.claudecode.session.ClaudeSession
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.ui.dsl.builder.*
import javax.swing.JComponent
import javax.swing.JTextField

class ClaudeSettingsConfigurable : Configurable {

    private var panel: DialogPanel? = null
    private val settings by lazy { ClaudeSettings.getInstance() }

    override fun getDisplayName(): String = com.claudecode.ClaudeConstants.TOOL_WINDOW_ID

    override fun createComponent(): JComponent {
        var pathTextField: JTextField? = null
        panel = panel {
            group("General") {
                row("Claude CLI path:") {
                    val tf = textField()
                        .bindText(settings.state::claudePath)
                        .columns(COLUMNS_LARGE)
                    pathTextField = tf.component
                    button("Auto-detect") {
                        autoDetectClaudePath(pathTextField)
                    }
                    button("Browse…") {
                        browseForClaudePath(pathTextField)
                    }
                }
                row("") {
                    comment(
                        "Bare name (resolved via PATH / npm) or an absolute path. " +
                            "On Windows, set the absolute path to <code>claude.cmd</code> " +
                            "(usually <code>%APPDATA%\\npm\\claude.cmd</code>) if auto-detection fails."
                    )
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

    private fun autoDetectClaudePath(field: JTextField?) {
        if (field == null) return
        ClaudeSession.clearResolutionCache()
        val configured = field.text.ifBlank { com.claudecode.ClaudeConstants.DEFAULT_CLI_PATH }
        val result = ClaudeSession.resolveClaudePathDiagnosed(configured)

        val traceText = if (result.trace.isEmpty()) "(no diagnostics)"
            else result.trace.joinToString("\n") { "• $it" }

        if (result.resolved) {
            field.text = result.resolvedPath
            Messages.showInfoMessage(
                "Found: ${result.resolvedPath}\n\nDiagnostic trace:\n$traceText",
                "Claude CLI Auto-Detect"
            )
        } else {
            Messages.showWarningDialog(
                "Could not auto-detect the Claude CLI.\n\n" +
                    "Diagnostic trace:\n$traceText\n\n" +
                    "Use Browse… to pick the executable manually. " +
                    "On Windows it's typically %APPDATA%\\npm\\claude.cmd.",
                "Claude CLI Auto-Detect"
            )
        }
    }

    private fun browseForClaudePath(field: JTextField?) {
        if (field == null) return
        // Constructor args: (chooseFiles, chooseFolders, chooseJars,
        // chooseJarsAsFiles, chooseJarContents, chooseMultiple).
        // FileChooserDescriptorFactory.createSingleFileDescriptor() was the
        // convenience for this same configuration but is deprecated as of
        // 2024.x; use the constructor directly.
        val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
            .withTitle("Select the Claude CLI executable")
            .withDescription(
                "Pick claude (Unix) or claude.cmd (Windows). On Windows you usually find it in " +
                    "%APPDATA%\\npm\\."
            )
        val picked = FileChooser.chooseFile(descriptor, null, null) ?: return
        field.text = picked.path
        ClaudeSession.clearResolutionCache()
    }
}
