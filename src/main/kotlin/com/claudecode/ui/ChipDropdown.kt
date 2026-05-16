package com.claudecode.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JPopupMenu

/**
 * Cursor-style chip: borderless flat label with a tiny down arrow that
 * opens a popup menu on click. Designed to live inline beneath the input
 * area where a full [javax.swing.JComboBox] would dominate the layout.
 *
 * @param onPick called with the raw value (not the display label) when the
 *   user picks an item. Selection is rendered via [updateLabel] from the
 *   caller — the component itself only renders text.
 */
class ChipDropdown(
    initialLabel: String,
    chipFont: Font,
) : JLabel("$initialLabel  $ARROW") {

    private val pickHandlers = mutableListOf<(String) -> Unit>()
    // (value, displayLabel) — value goes back to onPick; displayLabel is what's drawn in the menu.
    private var items: List<Pair<String, String>> = emptyList()
    @Volatile private var hover = false

    init {
        font = chipFont
        foreground = JBColor(Color(0xBC, 0xBE, 0xC4), Color(0xBC, 0xBE, 0xC4))
        border = JBUI.Borders.empty(3, 8)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        isOpaque = false

        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
            override fun mouseExited(e: MouseEvent) { hover = false; repaint() }
            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger || e.button == MouseEvent.BUTTON1) showPopup()
            }
        })
    }

    /** Replace the visible label (call after the caller resolves the picked value to a display name). */
    fun updateLabel(text: String) {
        this.text = "$text  $ARROW"
    }

    /** Set menu contents. [pairs] = list of (value, displayLabel). */
    fun setItems(pairs: List<Pair<String, String>>) {
        items = pairs
    }

    fun onPick(handler: (String) -> Unit) {
        pickHandlers.add(handler)
    }

    override fun paintComponent(g: Graphics) {
        if (hover) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = JBColor(Color(0x3C, 0x3F, 0x41), Color(0x3C, 0x3F, 0x41))
            g2.fillRoundRect(0, 0, width, height, 6, 6)
            g2.dispose()
        }
        super.paintComponent(g)
    }

    override fun getPreferredSize(): Dimension {
        val base = super.getPreferredSize()
        // Keep height tight — Swing's default JLabel preferred height includes
        // descent slack; trim to match Cursor's compact row.
        return Dimension(base.width, (chipHeight()).coerceAtLeast(base.height))
    }

    private fun chipHeight(): Int {
        val fm = getFontMetrics(font)
        return fm.height + 6
    }

    private fun showPopup() {
        if (items.isEmpty()) return
        val popup = JPopupMenu()
        items.forEach { (value, label) ->
            popup.add(JMenuItem(label).apply {
                addActionListener { pickHandlers.forEach { it(value) } }
            })
        }
        // Open upward from the chip — the input panel sits above, so dropping
        // down would clip into the hint/status row. Show above the chip.
        popup.show(this, 0, -popup.preferredSize.height)
    }

    companion object {
        private const val ARROW = "▾" // ▾
    }
}
