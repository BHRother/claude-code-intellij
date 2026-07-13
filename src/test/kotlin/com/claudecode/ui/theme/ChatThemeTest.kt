package com.claudecode.ui.theme

import com.claudecode.ClaudeConstants
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * A [java.awt.Color] whose RGB switches when [flipped] is toggled, mimicking a
 * JBColor that resolves to a different value after an IDE theme switch. If a
 * Palette stored the live object, [flipped] would change its hex after the fact —
 * exactly the bug that left code blocks dark after switching to a light theme.
 */
private class FlipColor : java.awt.Color(0x2B, 0x2D, 0x30) {
    var flipped = false
    override fun getRed() = if (flipped) 0xF2 else 0x2B
    override fun getGreen() = if (flipped) 0xF2 else 0x2D
    override fun getBlue() = if (flipped) 0xF2 else 0x30
}

/**
 * The DARK/LIGHT presets and the color math are pure (no IDE services), so they
 * can be verified headlessly. FOLLOW_IDE resolution needs a running Application
 * and is covered by manual verification in a live IDE.
 */
class ChatThemeTest {

    @Nested
    inner class ModeResolution {
        @Test
        fun `DARK mode resolves to the dark preset`() {
            assertSame(ChatTheme.dark, ChatTheme.forMode(ClaudeConstants.THEME_DARK))
        }

        @Test
        fun `LIGHT mode resolves to the light preset`() {
            assertSame(ChatTheme.light, ChatTheme.forMode(ClaudeConstants.THEME_LIGHT))
        }

        @Test
        fun `dark preset is flagged dark, light preset is flagged light`() {
            assertTrue(ChatTheme.dark.isDark)
            assertFalse(ChatTheme.light.isDark)
        }
    }

    @Nested
    inner class PaletteContrast {
        @Test
        fun `dark and light backgrounds differ`() {
            assertNotEquals(ChatTheme.dark.bg, ChatTheme.light.bg)
        }

        @Test
        fun `dark preset preserves the original background`() {
            // The dark preset must stay byte-for-byte the plugin's original look.
            assertEquals("#1E1F22", ChatTheme.dark.bgHex)
            assertEquals("#BCBEC4", ChatTheme.dark.fgHex)
        }

        @Test
        fun `light preset has a bright background and dark foreground`() {
            assertEquals("#FFFFFF", ChatTheme.light.bgHex)
            // foreground clearly darker than background
            assertTrue(ChatTheme.light.fg.red < 0x80)
        }
    }

    @Nested
    inner class HexFormatting {
        @Test
        fun `hex is uppercase six-digit with leading hash`() {
            assertEquals("#1E1F22", ChatTheme.hex(java.awt.Color(0x1E, 0x1F, 0x22)))
            assertEquals("#000000", ChatTheme.hex(java.awt.Color(0, 0, 0)))
            assertEquals("#FFFFFF", ChatTheme.hex(java.awt.Color(255, 255, 255)))
        }

        @Test
        fun `every hex accessor is a valid color literal`() {
            val hexes = listOf(
                ChatTheme.dark.surfaceHex, ChatTheme.dark.linkHex, ChatTheme.dark.accentHex,
                ChatTheme.dark.diffAddFgHex, ChatTheme.dark.synKeywordHex, ChatTheme.dark.mdHeaderHex,
                ChatTheme.light.surfaceHex, ChatTheme.light.linkHex, ChatTheme.light.mdHeaderHex,
            )
            hexes.forEach { assertTrue(it.matches(Regex("#[0-9A-F]{6}")), "bad hex: $it") }
        }
    }

    @Nested
    inner class SnapshotStability {
        private fun paletteWith(surface: java.awt.Color) = ChatTheme.Palette(
            isDark = true,
            bg = surface, surface = surface, surfaceHi = surface, border = surface, debugBg = surface,
            fg = surface, fgCode = surface, fgMuted = surface, fgFaint = surface,
            accent = surface, accentText = surface, gold = surface, link = surface, error = surface,
            danger = surface, neutralButton = surface,
            diffAddBg = surface, diffAddFg = surface, diffRemoveBg = surface, diffRemoveFg = surface,
            synKeyword = surface, synString = surface, synNumber = surface, synComment = surface,
            synAnnotation = surface, mdHeader = surface,
        )

        @Test
        fun `palette freezes a dynamic color at construction`() {
            val dynamic = FlipColor()
            val p = paletteWith(dynamic)       // snapshot taken under "theme A"
            val before = p.surfaceHex
            dynamic.flipped = true             // simulate an IDE theme switch
            val after = p.surfaceHex
            // A live (un-snapshotted) color would now read #F2F2F2; the snapshot must not.
            assertEquals("#2B2D30", before)
            assertEquals(before, after, "palette hex changed after theme flip — color not snapshotted")
        }

        @Test
        fun `preset palettes are stable across reads`() {
            assertEquals(ChatTheme.dark.surfaceHex, ChatTheme.dark.surfaceHex)
            assertEquals("#2B2D30", ChatTheme.dark.surfaceHex)
        }
    }

    @Nested
    inner class Remap {
        @Test
        fun `remaps a role hex from dark to light`() {
            val html = "<div style='background-color: ${ChatTheme.dark.bgHex}; color: ${ChatTheme.dark.fgHex};'>hi</div>"
            val out = ChatTheme.remap(html, ChatTheme.dark, ChatTheme.light)
            assertTrue(out.contains(ChatTheme.light.bgHex), "bg not remapped: $out")
            assertTrue(out.contains(ChatTheme.light.fgHex), "fg not remapped: $out")
            assertFalse(out.contains(ChatTheme.dark.bgHex), "old bg remained: $out")
        }

        @Test
        fun `is case-insensitive on the source hex`() {
            val html = "color:${ChatTheme.dark.linkHex.lowercase()};"
            val out = ChatTheme.remap(html, ChatTheme.dark, ChatTheme.light)
            assertTrue(out.contains(ChatTheme.light.linkHex))
        }

        @Test
        fun `same palette is a no-op`() {
            val html = "color:${ChatTheme.dark.accentHex};"
            assertEquals(html, ChatTheme.remap(html, ChatTheme.dark, ChatTheme.dark))
        }

        @Test
        fun `leaves unknown colors untouched`() {
            val html = "color:#123456;"
            assertEquals(html, ChatTheme.remap(html, ChatTheme.dark, ChatTheme.light))
        }

        @Test
        fun `dark round-trips back to dark`() {
            val html = "<pre style='background:${ChatTheme.dark.surfaceHex}'>" +
                "<span style='color:${ChatTheme.dark.diffAddFgHex}'>+ ok</span></pre>"
            val toLight = ChatTheme.remap(html, ChatTheme.dark, ChatTheme.light)
            val back = ChatTheme.remap(toLight, ChatTheme.light, ChatTheme.dark)
            assertEquals(html, back)
        }
    }
}
