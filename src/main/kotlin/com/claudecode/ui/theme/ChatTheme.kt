package com.claudecode.ui.theme

import com.claudecode.ClaudeConstants
import com.claudecode.settings.ClaudeSettings
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import java.awt.Color

/**
 * The chat UI's semantic color palette.
 *
 * Every color the chat renders — Swing component colors AND the hex literals
 * embedded in the transcript's inline HTML — comes from one [Palette] so the
 * whole surface stays internally consistent. Three sources drive it, chosen by
 * [ClaudeSettings.State.appearanceThemeMode]:
 *
 *  - [ClaudeConstants.THEME_DARK]  → [dark], the plugin's original hand-tuned look.
 *  - [ClaudeConstants.THEME_LIGHT] → [light], a hand-tuned light counterpart.
 *  - [ClaudeConstants.THEME_FOLLOW_IDE] → [followIde]: starts from whichever preset
 *    matches the IDE's light/dark state, then rebases the neutral colors
 *    (background, foreground, surfaces, borders) on the IDE's real editor/panel
 *    colors so the chat blends into the IDE instead of looking like a pasted-in
 *    terminal. Accent/diff colors stay from the preset so they remain legible on
 *    any theme.
 *
 * Palettes are resolved at panel-construction time; a theme change applies to
 * newly opened chats (matching the plugin's existing font behavior).
 */
object ChatTheme {

    /**
     * A fully-resolved set of chat colors. Each role is a plain [Color] for
     * Swing plus a `Hex` string ("#RRGGBB", no alpha) for inline HTML/CSS.
     */
    class Palette(
        /** Whether this palette reads as a dark theme (drives derived shading). */
        val isDark: Boolean,
        /** Main transcript background. */
        bg: Color,
        surface: Color,
        surfaceHi: Color,
        border: Color,
        debugBg: Color,
        fg: Color,
        fgCode: Color,
        fgMuted: Color,
        fgFaint: Color,
        accent: Color,
        accentText: Color,
        gold: Color,
        link: Color,
        error: Color,
        danger: Color,
        neutralButton: Color,
        diffAddBg: Color,
        diffAddFg: Color,
        diffRemoveBg: Color,
        diffRemoveFg: Color,
        synKeyword: Color,
        synString: Color,
        synNumber: Color,
        synComment: Color,
        synAnnotation: Color,
        mdHeader: Color,
    ) {
        // Every color is frozen into an immutable RGB snapshot at construction.
        // IDE-derived colors (JBColor / named theme colors) are *dynamic* — they
        // resolve against whatever theme is active at read time — so storing the
        // live object would make e.g. `surfaceHex` change value after a theme
        // switch, breaking the transcript re-color (whose "from" keys must match
        // the hexes actually baked into the transcript). See [followIde].
        /** Main transcript background. */
        val bg = snapColor(bg)
        /** Raised surfaces: code/pre blocks, user-message bubble, input box, chips. */
        val surface = snapColor(surface)
        /** Higher raised surfaces: buttons, chip hover fills, light borders. */
        val surfaceHi = snapColor(surfaceHi)
        /** Stronger border/divider lines. */
        val border = snapColor(border)
        /** Debug console background (deliberately dimmer than [bg]). */
        val debugBg = snapColor(debugBg)
        /** Primary body text. */
        val fg = snapColor(fg)
        /** Code / monospace text. */
        val fgCode = snapColor(fgCode)
        /** Muted secondary text (status, system messages). */
        val fgMuted = snapColor(fgMuted)
        /** Faint tertiary text (hints, counters, timestamps). */
        val fgFaint = snapColor(fgFaint)
        /** Claude accent (orange) — tool activity, primary button, status highlight. */
        val accent = snapColor(accent)
        /** Text drawn on top of an [accent] fill. */
        val accentText = snapColor(accentText)
        /** Gold marker (thinking/summary left border). */
        val gold = snapColor(gold)
        /** Links and user-message text. */
        val link = snapColor(link)
        /** Error text. */
        val error = snapColor(error)
        /** Danger button fill. */
        val danger = snapColor(danger)
        /** Neutral button fill. */
        val neutralButton = snapColor(neutralButton)
        /** Added-line diff background / foreground. */
        val diffAddBg = snapColor(diffAddBg)
        val diffAddFg = snapColor(diffAddFg)
        /** Removed-line diff background / foreground. */
        val diffRemoveBg = snapColor(diffRemoveBg)
        val diffRemoveFg = snapColor(diffRemoveFg)
        // ── Code syntax highlighting (MarkdownRenderer) ──
        val synKeyword = snapColor(synKeyword)
        val synString = snapColor(synString)
        val synNumber = snapColor(synNumber)
        val synComment = snapColor(synComment)
        val synAnnotation = snapColor(synAnnotation)
        /** Markdown heading color. */
        val mdHeader = snapColor(mdHeader)

        val bgHex get() = hex(bg)
        val surfaceHex get() = hex(surface)
        val surfaceHiHex get() = hex(surfaceHi)
        val borderHex get() = hex(border)
        val fgHex get() = hex(fg)
        val fgCodeHex get() = hex(fgCode)
        val fgMutedHex get() = hex(fgMuted)
        val fgFaintHex get() = hex(fgFaint)
        val accentHex get() = hex(accent)
        val goldHex get() = hex(gold)
        val linkHex get() = hex(link)
        val errorHex get() = hex(error)
        val diffAddBgHex get() = hex(diffAddBg)
        val diffAddFgHex get() = hex(diffAddFg)
        val diffRemoveBgHex get() = hex(diffRemoveBg)
        val diffRemoveFgHex get() = hex(diffRemoveFg)
        val synKeywordHex get() = hex(synKeyword)
        val synStringHex get() = hex(synString)
        val synNumberHex get() = hex(synNumber)
        val synCommentHex get() = hex(synComment)
        val synAnnotationHex get() = hex(synAnnotation)
        val mdHeaderHex get() = hex(mdHeader)
    }

    /**
     * Resolve the palette for the user's current appearance setting. Falls back
     * to [dark] (the original look) if the settings service or IDE colors are
     * unavailable — e.g. in a headless unit test with no Application.
     */
    fun current(): Palette = try {
        forMode(ClaudeSettings.getInstance().state.appearanceThemeMode)
    } catch (_: Throwable) {
        dark
    }

    /** Resolve the palette for an explicit mode (also the seam for tests). */
    fun forMode(mode: String): Palette = when (mode) {
        ClaudeConstants.THEME_DARK -> dark
        ClaudeConstants.THEME_LIGHT -> light
        else -> followIde()
    }

    /** The original plugin look. These are the exact colors used before the Appearance section existed. */
    val dark = Palette(
        isDark = true,
        bg = Color(0x1E, 0x1F, 0x22),
        surface = Color(0x2B, 0x2D, 0x30),
        surfaceHi = Color(0x3C, 0x3F, 0x41),
        border = Color(0x50, 0x53, 0x56),
        debugBg = Color(0x15, 0x15, 0x18),
        fg = Color(0xBC, 0xBE, 0xC4),
        fgCode = Color(0xA9, 0xB7, 0xC6),
        fgMuted = Color(0x80, 0x80, 0x80),
        fgFaint = Color(0x60, 0x60, 0x60),
        accent = Color(0xD9, 0x77, 0x57),
        accentText = Color.WHITE,
        gold = Color(0xD9, 0xB2, 0x63),
        link = Color(0x68, 0x97, 0xBB),
        error = Color(0xFF, 0x6B, 0x68),
        danger = Color(0xC7, 0x47, 0x47),
        neutralButton = Color(0x4B, 0x4E, 0x52),
        diffAddBg = Color(0x1E, 0x35, 0x20),
        diffAddFg = Color(0x6A, 0x87, 0x59),
        diffRemoveBg = Color(0x3D, 0x20, 0x20),
        diffRemoveFg = Color(0xFF, 0x6B, 0x68),
        synKeyword = Color(0xCC, 0x78, 0x32),
        synString = Color(0x6A, 0x87, 0x59),
        synNumber = Color(0x68, 0x97, 0xBB),
        synComment = Color(0x80, 0x80, 0x80),
        synAnnotation = Color(0xBB, 0xB5, 0x29),
        mdHeader = Color(0xFF, 0xC6, 0x6D),
    )

    /** Hand-tuned light counterpart of [dark]; accents darkened for contrast on white. */
    val light = Palette(
        isDark = false,
        bg = Color(0xFF, 0xFF, 0xFF),
        surface = Color(0xF2, 0xF2, 0xF2),
        surfaceHi = Color(0xE2, 0xE2, 0xE2),
        border = Color(0xC4, 0xC4, 0xC4),
        debugBg = Color(0xEC, 0xEC, 0xEC),
        fg = Color(0x2B, 0x2B, 0x2B),
        fgCode = Color(0x1F, 0x1F, 0x1F),
        fgMuted = Color(0x6E, 0x6E, 0x6E),
        fgFaint = Color(0x9B, 0x9B, 0x9B),
        accent = Color(0xC4, 0x56, 0x2E),
        accentText = Color.WHITE,
        gold = Color(0xB8, 0x86, 0x0B),
        link = Color(0x2E, 0x6F, 0xBF),
        error = Color(0xC0, 0x39, 0x2B),
        danger = Color(0xC0, 0x39, 0x2B),
        neutralButton = Color(0xD0, 0xD0, 0xD0),
        diffAddBg = Color(0xE6, 0xF4, 0xE6),
        diffAddFg = Color(0x2E, 0x7D, 0x32),
        diffRemoveBg = Color(0xFB, 0xE9, 0xE9),
        diffRemoveFg = Color(0xC6, 0x28, 0x28),
        // IntelliJ Light–style syntax colors, legible on a white background.
        synKeyword = Color(0x00, 0x33, 0xB3),
        synString = Color(0x06, 0x7D, 0x17),
        synNumber = Color(0x17, 0x50, 0xEB),
        synComment = Color(0x8C, 0x8C, 0x8C),
        synAnnotation = Color(0x9E, 0x88, 0x0D),
        mdHeader = Color(0xB8, 0x86, 0x0B),
    )

    /**
     * The IDE-tracking palette: matching preset for accents, real IDE colors for
     * neutrals. Reads the active editor scheme + panel colors so the chat sits in
     * the IDE naturally. Falls back to the preset value whenever an IDE color is
     * unavailable (e.g. in a headless context).
     */
    fun followIde(): Palette {
        // JBColor.isBright() == the IDE is on a light theme. Default to a dark
        // base (the plugin's original look) when the state can't be read.
        val ideIsLight = safe({ JBColor.isBright() }, default = false)
        val base = if (ideIsLight) light else dark
        // These IDE colors are dynamic (JBColor / named theme colors); the Palette
        // constructor freezes them into immutable RGB snapshots so the palette is a
        // stable record of the theme it was created under. See Palette's comment.
        val ideBg = safe({ EditorColorsManager.getInstance().globalScheme.defaultBackground }, base.bg)
        val ideFg = safe({ EditorColorsManager.getInstance().globalScheme.defaultForeground }, base.fg)
        val panel = safe({ UIUtil.getPanelBackground() }, base.surface)
        val muted = safe({ UIUtil.getContextHelpForeground() }, base.fgMuted)

        // Raised surfaces: prefer a real panel color that's actually distinct from
        // the editor background; otherwise nudge the background toward the
        // foreground so code/pre blocks read as raised on any theme.
        val surface = if (distinct(panel, ideBg)) panel else shade(ideBg, base.isDark, 0.06)
        val surfaceHi = shade(surface, base.isDark, 0.10)
        val border = shade(surface, base.isDark, 0.18)
        val debugBg = shade(ideBg, base.isDark, -0.04)

        return Palette(
            isDark = base.isDark,
            bg = ideBg,
            surface = surface,
            surfaceHi = surfaceHi,
            border = border,
            debugBg = debugBg,
            fg = ideFg,
            fgCode = ideFg,
            fgMuted = muted,
            fgFaint = blend(muted, ideBg, 0.35),
            // Accents/diffs keep the preset's tuned, legible values.
            accent = base.accent,
            accentText = base.accentText,
            gold = base.gold,
            link = base.link,
            error = base.error,
            danger = base.danger,
            neutralButton = base.neutralButton,
            diffAddBg = base.diffAddBg,
            diffAddFg = base.diffAddFg,
            diffRemoveBg = base.diffRemoveBg,
            diffRemoveFg = base.diffRemoveFg,
            synKeyword = base.synKeyword,
            synString = base.synString,
            synNumber = base.synNumber,
            synComment = base.synComment,
            synAnnotation = base.synAnnotation,
            mdHeader = base.mdHeader,
        )
    }

    /**
     * Recolor already-emitted transcript HTML for a theme change, mapping every
     * [from]-palette hex to the corresponding [to]-palette hex for the same role.
     *
     * The transcript is an opaque accumulated HTML string, so there's no message
     * model to re-render from — but every color we ever emit comes from the
     * palette, so a hex→hex substitution is exact per role. When two roles share
     * a hex in [from] (e.g. dark `error` and `diffRemoveFg` are both #FF6B68), the
     * role inserted first wins; they're always same-family colors (red↔red,
     * green↔green, blue↔blue), so the fallback stays visually correct.
     */
    fun remap(html: String, from: Palette, to: Palette): String {
        if (from === to) return html
        val map = LinkedHashMap<String, String>()
        fun add(f: String, t: String) { map.putIfAbsent(f.uppercase(), t) }
        // Primary (structural/foreground) roles first so they win on collisions.
        add(from.bgHex, to.bgHex)
        add(from.surfaceHex, to.surfaceHex)
        add(from.surfaceHiHex, to.surfaceHiHex)
        add(from.borderHex, to.borderHex)
        add(from.fgHex, to.fgHex)
        add(from.fgCodeHex, to.fgCodeHex)
        add(from.fgMutedHex, to.fgMutedHex)
        add(from.fgFaintHex, to.fgFaintHex)
        add(from.accentHex, to.accentHex)
        add(from.goldHex, to.goldHex)
        add(from.linkHex, to.linkHex)
        add(from.errorHex, to.errorHex)
        add(from.diffAddBgHex, to.diffAddBgHex)
        add(from.diffAddFgHex, to.diffAddFgHex)
        add(from.diffRemoveBgHex, to.diffRemoveBgHex)
        add(from.diffRemoveFgHex, to.diffRemoveFgHex)
        // Syntax roles last — they defer to the primary role on any shared hex.
        add(from.synKeywordHex, to.synKeywordHex)
        add(from.synStringHex, to.synStringHex)
        add(from.synNumberHex, to.synNumberHex)
        add(from.synCommentHex, to.synCommentHex)
        add(from.synAnnotationHex, to.synAnnotationHex)
        add(from.mdHeaderHex, to.mdHeaderHex)
        return Regex("#[0-9A-Fa-f]{6}").replace(html) { m -> map[m.value.uppercase()] ?: m.value }
    }

    // ── color math ───────────────────────────────────────────────────────────

    fun hex(c: Color): String = String.format("#%02X%02X%02X", c.red, c.green, c.blue)

    /** Lighten (dark themes) or darken (light themes) by [amount] of the 0..255 range. */
    private fun shade(c: Color, isDark: Boolean, amount: Double): Color {
        val delta = (255 * amount).toInt() * (if (isDark) 1 else -1)
        fun ch(v: Int) = (v + delta).coerceIn(0, 255)
        return Color(ch(c.red), ch(c.green), ch(c.blue))
    }

    /** Linear blend: [ratio] of [b] mixed into [a]. */
    private fun blend(a: Color, b: Color, ratio: Double): Color {
        fun ch(x: Int, y: Int) = (x + (y - x) * ratio).toInt().coerceIn(0, 255)
        return Color(ch(a.red, b.red), ch(a.green, b.green), ch(a.blue, b.blue))
    }

    /** True when two colors differ enough to read as separate surfaces. */
    private fun distinct(a: Color, b: Color): Boolean {
        val d = Math.abs(a.red - b.red) + Math.abs(a.green - b.green) + Math.abs(a.blue - b.blue)
        return d >= 12
    }

    private fun <T> safe(block: () -> T, default: T): T = try {
        block() ?: default
    } catch (_: Throwable) {
        default
    }
}

/**
 * Freeze a possibly-dynamic color (JBColor / named theme color) into a plain,
 * immutable [Color] holding the RGB it has *right now*. File-private so both
 * [ChatTheme] and its nested [ChatTheme.Palette] can use it.
 */
private fun snapColor(c: Color): Color = Color(c.red, c.green, c.blue)
