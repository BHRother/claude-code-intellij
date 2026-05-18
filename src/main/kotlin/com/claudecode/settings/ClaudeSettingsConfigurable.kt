package com.claudecode.settings

import com.claudecode.session.ClaudeSession
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.ui.dsl.builder.*
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JList
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
                    var modelComboRef: JComboBox<String>? = null
                    // Non-editable: users pick from catalog entries (or the
                    // empty "Default", which lets Claude Code choose). Typing
                    // arbitrary IDs has historically caused typos that fail
                    // silently in the CLI; selecting Default covers any
                    // "I want whatever Claude defaults to" case.
                    @Suppress("UNCHECKED_CAST")
                    comboBox(allModels, friendlyModelRenderer())
                        .bindItem(
                            { settings.state.model },
                            { settings.state.model = it ?: "" }
                        )
                        .applyToComponent {
                            isEditable = false
                            modelComboRef = this as JComboBox<String>
                        }
                    button("Refresh catalog") {
                        // Capture the Settings dialog's modality state NOW,
                        // while we're on the EDT inside the modal Settings
                        // dialog. Without this, invokeLater on the response
                        // would default to NON_MODAL — which lives BENEATH
                        // the Settings dialog in IntelliJ's modal stack, so
                        // the confirmation pops behind. Re-using the same
                        // modality keeps the Messages dialog *above* it.
                        val parent: java.awt.Component = modelComboRef ?: panel!!
                        val modality = com.intellij.openapi.application.ModalityState.stateForComponent(parent)
                        // Force a re-fetch of the remote models JSON. After
                        // success, rebuild the combo's model in place so the
                        // user sees new entries without reopening Settings.
                        com.claudecode.models.ModelsRegistry.invalidateCache()
                        com.claudecode.models.ModelsRegistry.refreshAsync { ok ->
                            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater({
                                if (ok) {
                                    val combo = modelComboRef
                                    if (combo != null) {
                                        val previouslySelected = combo.selectedItem as? String ?: ""
                                        val updated = settings.getAllModels()
                                        combo.model = DefaultComboBoxModel(updated.toTypedArray())
                                        if (previouslySelected in updated) {
                                            combo.selectedItem = previouslySelected
                                        }
                                    }
                                }
                                if (ok) {
                                    Messages.showInfoMessage(
                                        parent,
                                        "Model catalog refreshed from the remote source.",
                                        "Claude Code — Models"
                                    )
                                } else {
                                    Messages.showInfoMessage(
                                        parent,
                                        "Could not reach the remote catalog. Using the previously cached / bundled list.",
                                        "Claude Code — Models"
                                    )
                                }
                            }, modality)
                        }
                    }
                    button("Clear custom history") {
                        // Wipes the persisted list of model IDs Claude has
                        // returned that aren't in the catalog. Refreshes the
                        // combo in place so the user sees the change.
                        val parent: java.awt.Component = modelComboRef ?: panel!!
                        val count = settings.getCustomModelsList().size
                        if (count == 0) {
                            Messages.showInfoMessage(
                                parent,
                                "No custom model entries to clear.",
                                "Claude Code — Models"
                            )
                        } else {
                            val confirm = Messages.showYesNoDialog(
                                parent,
                                "Remove $count custom model entries from the dropdown?\n\n" +
                                    "Catalog models (Opus, Sonnet, Haiku, …) are unaffected. " +
                                    "Custom entries reappear automatically the next time Claude responds with them.",
                                "Claude Code — Clear Custom Models",
                                Messages.getQuestionIcon()
                            )
                            if (confirm == Messages.YES) {
                                settings.clearCustomModels()
                                val combo = modelComboRef
                                if (combo != null) {
                                    val previouslySelected = combo.selectedItem as? String ?: ""
                                    val updated = settings.getAllModels()
                                    combo.model = DefaultComboBoxModel(updated.toTypedArray())
                                    if (previouslySelected in updated) {
                                        combo.selectedItem = previouslySelected
                                    }
                                }
                                Messages.showInfoMessage(
                                    parent,
                                    "Cleared $count custom model entries.",
                                    "Claude Code — Models"
                                )
                            }
                        }
                    }
                }
                row("") {
                    comment(
                        "Pick a catalog model or <b>Default</b> (lets Claude Code choose). The list " +
                            "refreshes every 24h from the project's published catalog; deprecated entries " +
                            "appear with a '(deprecated)' suffix and a one-click swap in chat. Any model " +
                            "Claude has already responded with this session is also offered."
                    )
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
            group("Model & inference") {
                row("Extended thinking:") {
                    val levels = com.claudecode.ClaudeConstants.THINKING_BUDGETS
                    comboBox(levels, friendlyThinkingRenderer())
                        .bindItem(
                            {
                                settings.state.thinkingBudget.takeIf { it in levels }
                                    ?: com.claudecode.ClaudeConstants.THINKING_OFF
                            },
                            { settings.state.thinkingBudget = it ?: com.claudecode.ClaudeConstants.THINKING_OFF }
                        )
                        .comment(
                            "Sets <code>MAX_THINKING_TOKENS</code> on the spawned <code>claude</code> process. " +
                                "Only Opus / Sonnet 4.x families act on it; other models ignore it. " +
                                "Higher budgets give better reasoning on hard tasks but cost more and are slower."
                        )
                }
                row("Max agentic turns:") {
                    // 0 = unlimited; show as a spinner with a 0-allowed range
                    // and clarify in the comment. JetBrains DSL doesn't have a
                    // "blank-means-unset" int field, so we use 0 as the sentinel.
                    spinner(0..200)
                        .bindIntValue(settings.state::maxAgenticTurns)
                        .comment(
                            "Hard cap on the agentic tool-call loop per turn via " +
                                "<code>--max-turns</code>. Set to <b>0</b> for unlimited (CLI default). " +
                                "Useful for read-only chats where you don't want runaway tool loops."
                        )
                }
                row("Append system prompt:") {
                    textArea()
                        .rows(6)
                        .bindText(settings.state::appendSystemPrompt)
                        .resizableColumn()
                        .align(com.intellij.ui.dsl.builder.Align.FILL)
                        .comment(
                            "Persistent text appended to claude's system prompt every spawn via " +
                                "<code>--append-system-prompt</code>. Use this for project conventions, " +
                                "preferred tone (\"always be terse\"), or personal coding style notes. " +
                                "Leave blank for default behavior."
                        )
                }
            }
            group("Permissions") {
                row("Permission mode:") {
                    val modes = com.claudecode.ClaudeConstants.PERMISSION_MODES
                    comboBox(modes, friendlyPermissionRenderer())
                        .bindItem(
                            { settings.state.permissionMode.takeIf { it in modes }
                                ?: com.claudecode.ClaudeConstants.PERMISSION_MODE_ACCEPT_EDITS },
                            { settings.state.permissionMode = it ?: com.claudecode.ClaudeConstants.PERMISSION_MODE_ACCEPT_EDITS }
                        )
                        .comment(
                            "<b>Plan</b>: read-only — Read/Grep/Glob only, useful for exploratory chats.<br/>" +
                                "<b>Content Only</b> (recommended): file writes/edits go through, shell commands are blocked.<br/>" +
                                "<b>Unrestricted</b>: Claude can run any tool including shell commands.<br/>" +
                                "Maps to the CLI's <code>--permission-mode</code> flag " +
                                "(<code>plan</code> / <code>acceptEdits</code> / <code>bypassPermissions</code>)."
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

    private fun friendlyModelRenderer(): DefaultListCellRenderer = object : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?, value: Any?, index: Int,
            isSelected: Boolean, cellHasFocus: Boolean
        ): java.awt.Component {
            val raw = value?.toString() ?: ""
            val base = com.claudecode.ClaudeConstants.shortModelLabel(raw)
            val label = if (com.claudecode.models.ModelsRegistry.isDeprecated(raw))
                "$base (deprecated)" else base
            return super.getListCellRendererComponent(list, label, index, isSelected, cellHasFocus)
        }
    }

    private fun friendlyThinkingRenderer(): DefaultListCellRenderer = object : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?, value: Any?, index: Int,
            isSelected: Boolean, cellHasFocus: Boolean
        ): java.awt.Component {
            val raw = value?.toString() ?: ""
            val label = com.claudecode.ClaudeConstants.shortThinkingBudgetLabel(raw)
            return super.getListCellRendererComponent(list, label, index, isSelected, cellHasFocus)
        }
    }

    private fun friendlyPermissionRenderer(): DefaultListCellRenderer = object : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?, value: Any?, index: Int,
            isSelected: Boolean, cellHasFocus: Boolean
        ): java.awt.Component {
            val raw = value?.toString() ?: ""
            val label = com.claudecode.ClaudeConstants.shortPermissionModeLabel(raw)
            return super.getListCellRendererComponent(list, label, index, isSelected, cellHasFocus)
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
