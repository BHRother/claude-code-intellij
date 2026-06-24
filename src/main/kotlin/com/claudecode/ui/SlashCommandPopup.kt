package com.claudecode.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListCellRenderer
import javax.swing.JList
import javax.swing.JPopupMenu
import javax.swing.ListSelectionModel
import javax.swing.ScrollPaneConstants

/**
 * Tiny autocomplete popup that surfaces the slash commands we handle
 * locally (`/clear`, `/help`, `/cost`, `/model`). Triggered when the
 * user types `/` as the first and only character of the input.
 *
 * Designed to be unobtrusive:
 *   - Does NOT request focus (popup is non-focusable). Caret stays on
 *     the input, so the document keeps receiving keystrokes normally.
 *   - Arrow up/down + Enter navigation is wired by the host
 *     ([SessionPanel.installSlashTriggerListener]) via key consumption
 *     on the text component, since we don't want a popup grabbing the
 *     keymap surprise.
 *   - Anchored above the input area so it doesn't get clipped against
 *     the bottom of the tool window (JPopupMenu auto-flips otherwise).
 *
 * Picking an item triggers [onSelect] with the chosen command string.
 * Host replaces the input's text with that string.
 */
class SlashCommandPopup(
    private val anchor: Component,
    private val onSelect: (String) -> Unit,
) {

    data class Item(val command: String, val description: String)

    private val items = listOf(
        Item("/clear", "Reset conversation — start fresh"),
        Item("/help", "Show available slash commands"),
        Item("/cost", "Show session cost so far"),
        Item("/model", "Show currently selected model"),
        Item("/mcp", "List MCP servers + connection status"),
        Item("/login", "Sign in to / out of your Anthropic account"),
        Item("/settings", "Open plugin Settings"),
        Item("/btw", "Queue a message to send after the current turn"),
        Item("/init", "Analyze the project and write/refresh CLAUDE.md"),
        Item("/memory", "Edit CLAUDE.md (project / local / user)"),
    )

    private val list = JBList(items).apply {
        cellRenderer = ItemRenderer()
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        selectedIndex = 0
        background = JBColor(Color(0x2B, 0x2D, 0x30), Color(0x2B, 0x2D, 0x30))
        border = JBUI.Borders.empty()
        visibleRowCount = items.size
    }

    private val popup: JPopupMenu = JPopupMenu().apply {
        layout = BorderLayout()
        isFocusable = false
        border = JBUI.Borders.customLine(JBColor(Color(0x3C, 0x3F, 0x41), Color(0x3C, 0x3F, 0x41)), 1)
        add(
            JBScrollPane(list).apply {
                border = JBUI.Borders.empty()
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
                preferredSize = Dimension(320, list.preferredSize.height + 6)
            },
            BorderLayout.CENTER,
        )
    }

    init {
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON1) pickSelected()
            }
        })
    }

    val isShowing: Boolean get() = popup.isVisible

    /** Shows the popup anchored just above the input area. */
    fun show() {
        list.selectedIndex = 0
        // y = -prefHeight → above the anchor's top edge. Swing's popup
        // logic re-flips this if there isn't enough screen space.
        popup.show(anchor, 0, -popup.preferredSize.height)
    }

    fun hide() {
        popup.isVisible = false
    }

    fun moveSelection(delta: Int) {
        if (items.isEmpty()) return
        val newIdx = (list.selectedIndex + delta).coerceIn(0, items.size - 1)
        list.selectedIndex = newIdx
        list.ensureIndexIsVisible(newIdx)
    }

    fun pickSelected() {
        val item = list.selectedValue ?: return
        onSelect(item.command)
        hide()
    }

    /** HTML-rendered row so the command and the description can be styled differently. */
    private class ItemRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?, value: Any?, index: Int,
            isSelected: Boolean, cellHasFocus: Boolean,
        ): Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            val item = value as? Item ?: return this
            val cmdColor = if (isSelected) "#FFFFFF" else "#6897BB"
            val descColor = if (isSelected) "#E0E0E0" else "#808080"
            text = "<html><span style='color: $cmdColor;'>" +
                "<code>${item.command}</code></span>" +
                "&nbsp;&nbsp;<span style='color: $descColor;'>${item.description}</span></html>"
            border = JBUI.Borders.empty(5, 10)
            return this
        }
    }
}
