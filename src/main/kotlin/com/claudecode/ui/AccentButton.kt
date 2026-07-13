package com.claudecode.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton

/**
 * Compact accent-color button used for the inline chip row Send/Stop control.
 *
 * Renders a rounded background fill with no platform L&F decoration so the
 * appearance is identical on macOS, Linux, and Windows. The fill color is
 * driven by [setVariant] — "accent" for Send, "danger" for Stop.
 */
class AccentButton(text: String) : JButton(text) {

    enum class Variant { ACCENT, DANGER, NEUTRAL }

    private var variant: Variant = Variant.ACCENT
    private var hover = false
    private var pressed = false
    private var palette = com.claudecode.ui.theme.ChatTheme.current()

    /** Re-theme in place after an IDE-theme / Appearance change. */
    fun reapplyPalette(newPalette: com.claudecode.ui.theme.ChatTheme.Palette) {
        palette = newPalette
        foreground = newPalette.accentText
        repaint()
    }

    init {
        isBorderPainted = false
        isContentAreaFilled = false
        isFocusPainted = false
        isOpaque = false
        foreground = palette.accentText
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        margin = JBUI.emptyInsets()
        border = JBUI.Borders.empty(2, 12)

        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
            override fun mouseExited(e: MouseEvent) { hover = false; pressed = false; repaint() }
            override fun mousePressed(e: MouseEvent) { pressed = true; repaint() }
            override fun mouseReleased(e: MouseEvent) { pressed = false; repaint() }
        })
    }

    fun setVariant(v: Variant) {
        variant = v
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = currentFill()
            g2.fillRoundRect(0, 0, width, height, 8, 8)
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }

    private fun currentFill(): Color {
        val base = when (variant) {
            Variant.ACCENT -> palette.accent     // Claude orange
            Variant.DANGER -> palette.danger
            Variant.NEUTRAL -> palette.neutralButton
        }
        return when {
            pressed -> base.darker()
            hover -> base.brighter()
            else -> base
        }
    }

    override fun getPreferredSize(): Dimension {
        val base = super.getPreferredSize()
        // Tighten height — Swing default adds slack. Target a thin chip-row
        // height that matches ChipDropdown's footprint.
        return Dimension(base.width, (base.height - 8).coerceAtLeast(20))
    }

    @Suppress("unused") // referenced via JBColor variant if we want dark/light split later
    private fun jbAccent() = JBColor(currentFill(), currentFill())
}
