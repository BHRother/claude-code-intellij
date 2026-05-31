package com.claudecode.ui

import java.awt.Component
import javax.swing.DefaultListCellRenderer
import javax.swing.JList

/**
 * A combo-box / list cell renderer that displays each item via [textOf].
 *
 * Built on plain Swing [DefaultListCellRenderer] on purpose: the platform's
 * `SimpleListCellRenderer.create(...)` factory family (both the
 * `(String, Function)` and `(Customizer)` overloads) is scheduled for removal,
 * so we avoid it entirely. This mirrors the renderer pattern already used in
 * ClaudeSettingsConfigurable.
 */
fun <T : Any> comboTextRenderer(textOf: (T) -> String): DefaultListCellRenderer =
    object : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            @Suppress("UNCHECKED_CAST")
            val text = (value as? T)?.let(textOf) ?: ""
            return super.getListCellRendererComponent(list, text, index, isSelected, cellHasFocus)
        }
    }
