package com.claudecode.ui

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.ScrollPaneConstants

/** One selectable choice. [id] is a host-meaningful identifier the host switches on. */
data class Choice(
    val label: String,
    val description: String = "",
    val id: String = label,
    val isCustom: Boolean = false,
)

/**
 * Persistent, keyboard-navigable inline picker, sitting just above the input
 * area (NOT a transient popup — it survives typing and focus loss). One reusable
 * surface for any in-chat decision: Claude's AskUserQuestion options AND
 * permission-grant prompts.
 *
 * The input keeps focus throughout; the host ([SessionPanel]) drives navigation
 * from the input's key listener (↑/↓/Space) while the input is empty, so the
 * user can pick OR just start typing. When an [onCustom] handler is supplied, a
 * trailing "Something else…" row is added (used by AskUserQuestion's "Other").
 */
class ChoiceBar : JPanel(BorderLayout()) {

    private var palette = com.claudecode.ui.theme.ChatTheme.current()

    /** Re-theme in place after an IDE-theme / Appearance change. */
    fun reapplyPalette(newPalette: com.claudecode.ui.theme.ChatTheme.Palette) {
        palette = newPalette
        titleLabel.foreground = newPalette.link
        hintLabel.foreground = newPalette.fgMuted
        list.background = newPalette.surface
        background = newPalette.surface
        border = JBUI.Borders.customLine(newPalette.surfaceHi, 1)
        revalidate()
        repaint()
    }

    private val titleLabel = JBLabel().apply {
        border = JBUI.Borders.empty(6, 10, 2, 10)
        foreground = palette.link
    }
    private val hintLabel = JBLabel().apply {
        border = JBUI.Borders.empty(2, 10, 6, 10)
        componentStyle = UIUtil.ComponentStyle.SMALL
        foreground = palette.fgMuted
    }
    private val model = DefaultListModel<Choice>()
    private val list = JBList(model).apply {
        cellRenderer = RowRenderer()
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        isFocusable = false // input keeps focus; the host drives selection
        background = palette.surface
        border = JBUI.Borders.empty()
    }

    private var multiSelect = false
    private val checked = linkedSetOf<Int>()
    private var onSubmit: ((List<Choice>) -> Unit)? = null
    private var onCustom: (() -> Unit)? = null

    init {
        isVisible = false
        isOpaque = true
        background = palette.surface
        border = JBUI.Borders.customLine(palette.surfaceHi, 1)
        add(titleLabel, BorderLayout.NORTH)
        add(
            JBScrollPane(list).apply {
                border = JBUI.Borders.empty()
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            },
            BorderLayout.CENTER,
        )
        add(hintLabel, BorderLayout.SOUTH)
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent) {
                if (e.button != MouseEvent.BUTTON1) return
                list.selectedIndex = list.locationToIndex(e.point).takeIf { it >= 0 } ?: return
                if (multiSelect && !current().isCustom) toggleSelected() else submitSelected()
            }
        })
    }

    val isActive: Boolean get() = isVisible

    fun present(
        title: String,
        choices: List<Choice>,
        multiSelect: Boolean = false,
        hint: String? = null,
        onSubmit: (List<Choice>) -> Unit,
        onCustom: (() -> Unit)? = null,
    ) {
        this.multiSelect = multiSelect
        this.onSubmit = onSubmit
        this.onCustom = onCustom
        checked.clear()
        model.clear()
        choices.forEach { model.addElement(it) }
        if (onCustom != null) {
            model.addElement(Choice("Something else…", "Type your own answer below and press Enter", id = "__custom__", isCustom = true))
        }
        titleLabel.text = "<html>${escape(title)}</html>"
        hintLabel.text = hint ?: if (multiSelect)
            "Space toggles · Enter submits · or type your own answer below"
        else
            "↑/↓ to choose · Enter to select" + (if (onCustom != null) " · or just type your own answer below" else "")
        list.selectedIndex = 0
        list.visibleRowCount = model.size().coerceAtMost(8)
        isVisible = true
        revalidate()
        repaint()
    }

    fun clear() {
        isVisible = false
        onSubmit = null
        onCustom = null
        model.clear()
    }

    fun moveSelection(delta: Int) {
        if (model.isEmpty) return
        val idx = (list.selectedIndex + delta).coerceIn(0, model.size() - 1)
        list.selectedIndex = idx
        list.ensureIndexIsVisible(idx)
    }

    /** Multi-select: toggle the highlighted option. Returns true if it acted
     *  (so the host only swallows Space when it's meaningful). */
    fun toggleSelected(): Boolean {
        if (!multiSelect || current().isCustom) return false
        val idx = list.selectedIndex.takeIf { it >= 0 } ?: return false
        if (!checked.add(idx)) checked.remove(idx)
        list.repaint()
        return true
    }

    fun submitSelected() {
        val row = current()
        if (row.isCustom) { onCustom?.invoke(); return }
        val choices = if (multiSelect && checked.isNotEmpty()) {
            checked.sorted().mapNotNull { model.getElementAt(it) }
        } else {
            listOf(row)
        }
        onSubmit?.invoke(choices)
    }

    private fun current(): Choice =
        list.selectedIndex.takeIf { it in 0 until model.size() }?.let { model.getElementAt(it) }
            ?: Choice("", isCustom = true)

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, super.getPreferredSize().height)

    private inner class RowRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean,
        ): Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            val row = value as? Choice ?: return this
            // On selection the row background is the IDE's saturated selection
            // color, so keep near-white text; otherwise track the palette.
            val labelColor = if (isSelected) "#FFFFFF" else palette.fgCodeHex
            val descColor = if (isSelected) "#E0E0E0" else palette.fgMutedHex
            val mark = when {
                row.isCustom -> "✎ "
                multiSelect -> if (checked.contains(index)) "☑ " else "☐ "
                else -> ""
            }
            val desc = if (row.description.isBlank()) "" else
                "&nbsp;&nbsp;<span style='color:$descColor;'>${escape(row.description)}</span>"
            text = "<html><span style='color:$labelColor;'>$mark<b>${escape(row.label)}</b></span>$desc</html>"
            border = JBUI.Borders.empty(5, 10)
            return this
        }
    }

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
