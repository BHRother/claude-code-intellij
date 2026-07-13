package com.claudecode.ui.theme

import javax.swing.text.html.HTMLEditorKit
import javax.swing.text.html.StyleSheet

/**
 * Builds an [HTMLEditorKit] backed by a **private** [StyleSheet] containing only
 * the given rules.
 *
 * `HTMLEditorKit().styleSheet` returns a process-wide *shared* default stylesheet.
 * Adding rules there leaks across every HTML pane and — worse for live re-theming —
 * accumulates duplicate `code {}` / `pre {}` rules whose cascade keeps the *first*
 * (stale) one, so a re-themed pane keeps its old code-block background. Overriding
 * [HTMLEditorKit.getStyleSheet] with our own sheet isolates each pane, so building
 * a fresh kit on theme change yields a clean cascade with only the new colors.
 */
object ChatHtmlKit {
    fun create(css: String): HTMLEditorKit {
        val sheet = StyleSheet()
        val kit = object : HTMLEditorKit() {
            override fun getStyleSheet(): StyleSheet = sheet
        }
        sheet.addRule(css)
        return kit
    }
}
