package com.claudecode

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ClaudeConstantsTest {

    @Nested
    inner class ToolWindowId {
        @Test
        fun `tool window id is Claude Code`() {
            assertEquals("Claude Code", ClaudeConstants.TOOL_WINDOW_ID)
        }
    }

    @Nested
    inner class ModelConstants {
        @Test
        fun `opus model id`() {
            assertEquals("claude-opus-4-6", ClaudeConstants.MODEL_OPUS)
        }

        @Test
        fun `sonnet model id`() {
            assertEquals("claude-sonnet-4-6", ClaudeConstants.MODEL_SONNET)
        }

        @Test
        fun `haiku model id`() {
            assertEquals("claude-haiku-4-5-20251001", ClaudeConstants.MODEL_HAIKU)
        }

        @Test
        fun `previous sonnet model id`() {
            assertEquals("claude-sonnet-4-5-20250514", ClaudeConstants.MODEL_SONNET_PREV)
        }

        @Test
        fun `completion model is haiku`() {
            assertEquals(ClaudeConstants.MODEL_HAIKU, ClaudeConstants.COMPLETION_MODEL)
        }
    }

    @Nested
    inner class DefaultPaths {
        @Test
        fun `default CLI path is claude`() {
            assertEquals("claude", ClaudeConstants.DEFAULT_CLI_PATH)
        }

        @Test
        fun `default shell is zsh`() {
            assertEquals("/bin/zsh", ClaudeConstants.DEFAULT_SHELL)
        }
    }

    @Nested
    inner class UIConstants {
        @Test
        fun `font family is JetBrains Mono`() {
            assertEquals("JetBrains Mono", ClaudeConstants.FONT_FAMILY)
        }

        @Test
        fun `env term value is dumb`() {
            assertEquals("dumb", ClaudeConstants.ENV_TERM_VALUE)
        }
    }

    @Nested
    inner class AvailableModels {
        @Test
        fun `available models contains empty string as first element`() {
            assertEquals("", ClaudeConstants.AVAILABLE_MODELS.first())
        }

        @Test
        fun `available models contains all model constants`() {
            assertTrue(ClaudeConstants.AVAILABLE_MODELS.contains(ClaudeConstants.MODEL_OPUS))
            assertTrue(ClaudeConstants.AVAILABLE_MODELS.contains(ClaudeConstants.MODEL_SONNET))
            assertTrue(ClaudeConstants.AVAILABLE_MODELS.contains(ClaudeConstants.MODEL_HAIKU))
            assertTrue(ClaudeConstants.AVAILABLE_MODELS.contains(ClaudeConstants.MODEL_SONNET_PREV))
        }

        @Test
        fun `available models has 5 entries`() {
            assertEquals(5, ClaudeConstants.AVAILABLE_MODELS.size)
        }

        @Test
        fun `available models is a list with correct order`() {
            assertEquals(
                listOf("", ClaudeConstants.MODEL_OPUS, ClaudeConstants.MODEL_SONNET, ClaudeConstants.MODEL_HAIKU, ClaudeConstants.MODEL_SONNET_PREV),
                ClaudeConstants.AVAILABLE_MODELS
            )
        }
    }
}
