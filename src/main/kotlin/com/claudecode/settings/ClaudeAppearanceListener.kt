package com.claudecode.settings

import com.intellij.util.messages.Topic

/**
 * Application-level signal fired when the user changes the plugin's Appearance
 * settings (theme mode / fonts) in Settings. Open chat panels subscribe and
 * re-theme themselves in place, so a settings change reloads the UI without
 * reopening chats. IDE-theme changes are handled separately via
 * [com.intellij.ide.ui.LafManagerListener] / editor-scheme listeners.
 */
interface ClaudeAppearanceListener {
    fun appearanceChanged()

    companion object {
        @JvmField
        val TOPIC: Topic<ClaudeAppearanceListener> =
            Topic.create("Claude Code appearance", ClaudeAppearanceListener::class.java)
    }
}
