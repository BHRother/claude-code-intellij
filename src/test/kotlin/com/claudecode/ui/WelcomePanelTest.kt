package com.claudecode.ui

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * WelcomePanel is UI-dependent (requires ClaudeSettings.getInstance() which needs IntelliJ Application).
 * These tests validate the structural aspects that can be tested without the platform.
 */
class WelcomePanelTest {

    @Nested
    inner class WelcomeContent {
        @Test
        fun `tool window id used for settings link is correct`() {
            assertEquals("Claude Code", com.claudecode.ClaudeConstants.TOOL_WINDOW_ID)
        }

        @Test
        fun `font family constant is available`() {
            assertEquals("JetBrains Mono", com.claudecode.ClaudeConstants.FONT_FAMILY)
        }
    }
}
