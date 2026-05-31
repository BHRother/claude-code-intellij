package com.claudecode.config

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.dsl.builder.*
import javax.swing.JComponent
import javax.swing.JTextField

/**
 * Add/edit a single top-level settings key. The value is entered as JSON — a
 * quoted string, number, boolean, array or object. Plain unquoted text is
 * accepted and stored as a string, so simple values stay easy.
 *
 * Returns the [resultKey] / [resultValue] on OK.
 */
class SettingsEntryDialog(
    project: Project,
    private val existingKey: String?,
    existingValueJson: String?,
    private val takenKeys: Set<String>,
) : DialogWrapper(project, true) {

    var resultKey: String? = null
        private set
    var resultValue: JsonElement? = null
        private set

    private lateinit var keyField: JTextField
    private lateinit var valueArea: javax.swing.JTextArea
    private val initialValue = existingValueJson ?: ""

    init {
        title = if (existingKey == null) "Add Setting" else "Edit Setting — $existingKey"
        setOKButtonText(if (existingKey == null) "Add" else "Save")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = panel {
            row("Key:") {
                comboBox(KNOWN_KEYS)
                    .applyToComponent {
                        isEditable = true
                        keyField = editor.editorComponent as JTextField
                        keyField.text = existingKey ?: ""
                    }
                    .comment("Top-level settings key. Pick a common one or type your own.")
            }
            row("Value (JSON):") {
                textArea().rows(8).align(Align.FILL).applyToComponent {
                    valueArea = this
                    text = initialValue
                }.comment(
                    "Examples: <code>\"acceptEdits\"</code>, <code>42</code>, <code>true</code>, " +
                        "<code>[\"Bash(git*)\"]</code>, <code>{\"allow\":[\"Read\"]}</code>. " +
                        "Unquoted text is saved as a string."
                )
            }
        }
        panel.preferredSize = java.awt.Dimension(com.intellij.util.ui.JBUI.scale(460), com.intellij.util.ui.JBUI.scale(260))
        return panel
    }

    override fun doValidate(): ValidationInfo? {
        val key = keyField.text.trim()
        if (key.isEmpty()) return ValidationInfo("Key is required.", keyField)
        if (existingKey != key && key in takenKeys) {
            return ValidationInfo("Key “$key” already exists. Edit it instead.", keyField)
        }
        // Value: if it looks like JSON (starts with a structural/JSON token) it
        // must parse; otherwise it's treated as a plain string and is always ok.
        if (looksLikeJson(valueArea.text) && parseJson(valueArea.text) == null) {
            return ValidationInfo("Value looks like JSON but doesn't parse. Fix it, or use plain text for a string.", valueArea)
        }
        return null
    }

    override fun doOKAction() {
        resultKey = keyField.text.trim()
        resultValue = toJsonElement(valueArea.text)
        super.doOKAction()
    }

    companion object {
        private val KNOWN_KEYS = listOf(
            "permissions", "env", "model", "hooks", "includeCoAuthoredBy",
            "cleanupPeriodDays", "apiKeyHelper", "statusLine", "outputStyle",
            "enableAllProjectMcpServers", "enabledMcpjsonServers", "disabledMcpjsonServers",
            "mcpServers", "forceLoginMethod",
        )

        private fun looksLikeJson(text: String): Boolean {
            val t = text.trim()
            if (t.isEmpty()) return false
            val c = t.first()
            return c == '{' || c == '[' || c == '"' || c == '-' || c.isDigit() ||
                t == "true" || t == "false" || t == "null"
        }

        private fun parseJson(text: String): JsonElement? =
            try { JsonParser.parseString(text.trim()) } catch (e: Exception) { null }

        /** Parse as JSON when possible; otherwise treat the raw text as a string. */
        fun toJsonElement(text: String): JsonElement {
            val t = text.trim()
            if (t.isEmpty()) return JsonPrimitive("")
            return if (looksLikeJson(t)) parseJson(t) ?: JsonPrimitive(t) else JsonPrimitive(t)
        }
    }
}
