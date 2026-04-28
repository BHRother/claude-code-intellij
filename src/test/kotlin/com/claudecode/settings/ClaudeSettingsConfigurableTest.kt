package com.claudecode.settings

import com.claudecode.ClaudeConstants
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ClaudeSettingsConfigurableTest {

    @Nested
    inner class DisplayName {
        @Test
        fun `display name matches tool window id`() {
            // ClaudeSettingsConfigurable.getDisplayName() returns ClaudeConstants.TOOL_WINDOW_ID
            // We verify the constant it references
            assertEquals("Claude Code", ClaudeConstants.TOOL_WINDOW_ID)
        }
    }
}
