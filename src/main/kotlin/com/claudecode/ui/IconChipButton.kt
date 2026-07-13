package com.claudecode.ui

import com.intellij.util.ui.JBUI
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JButton

/**
 * Borderless icon-only button matching the [ChipDropdown] visual style.
 * Used for the inline gear / quick-action affordances next to the chips.
 */
class IconChipButton(icon: Icon, tooltip: String? = null) : JButton(icon) {

    private var hover = false
    private val palette = com.claudecode.ui.theme.ChatTheme.current()

    init {
        isBorderPainted = false
        isContentAreaFilled = false
        isFocusPainted = false
        isOpaque = false
        margin = JBUI.emptyInsets()
        border = JBUI.Borders.empty(4)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = tooltip

        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
            override fun mouseExited(e: MouseEvent) { hover = false; repaint() }
        })
    }

    override fun paintComponent(g: Graphics) {
        if (hover) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = palette.surfaceHi
                g2.fillRoundRect(0, 0, width, height, 6, 6)
            } finally {
                g2.dispose()
            }
        }
        super.paintComponent(g)
    }

    override fun getPreferredSize(): Dimension {
        val base = super.getPreferredSize()
        val side = (base.height).coerceAtLeast(22)
        return Dimension(side, side)
    }
}
