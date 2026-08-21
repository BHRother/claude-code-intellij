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
        fun `opus 4-7 model id`() {
            assertEquals("claude-opus-4-7", ClaudeConstants.MODEL_OPUS_47)
        }

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
    inner class CanonicalModelId {
        @Test
        fun `plain model id is unchanged`() {
            assertEquals("claude-opus-5", ClaudeConstants.canonicalModelId("claude-opus-5"))
        }

        @Test
        fun `blank is unchanged`() {
            assertEquals("", ClaudeConstants.canonicalModelId(""))
        }

        @Test
        fun `strips bedrock region prefix and vendor segment`() {
            assertEquals("claude-opus-5", ClaudeConstants.canonicalModelId("us.anthropic.claude-opus-5"))
            assertEquals("claude-opus-5", ClaudeConstants.canonicalModelId("eu.anthropic.claude-opus-5"))
            assertEquals("claude-opus-5", ClaudeConstants.canonicalModelId("apac.anthropic.claude-opus-5"))
        }

        @Test
        fun `strips context window suffix`() {
            assertEquals("claude-opus-5", ClaudeConstants.canonicalModelId("claude-opus-5[1m]"))
        }

        @Test
        fun `strips bedrock version suffix`() {
            assertEquals("claude-opus-5", ClaudeConstants.canonicalModelId("anthropic.claude-opus-5-v1:0"))
        }

        @Test
        fun `strips full bedrock inference profile id`() {
            assertEquals("claude-opus-5", ClaudeConstants.canonicalModelId("us.anthropic.claude-opus-5[1m]"))
        }

        @Test
        fun `strips longer region prefixes`() {
            assertEquals("claude-opus-5", ClaudeConstants.canonicalModelId("global.anthropic.claude-opus-5"))
        }

        @Test
        fun `plain anthropic model ids are fixed points`() {
            // Guards the regexes against mangling real IDs — dated suffixes
            // like -20251001 and version-looking segments must survive.
            listOf(
                ClaudeConstants.MODEL_OPUS_47,
                ClaudeConstants.MODEL_OPUS,
                ClaudeConstants.MODEL_SONNET,
                ClaudeConstants.MODEL_HAIKU,
                ClaudeConstants.MODEL_SONNET_PREV,
                "claude-opus-5",
                "claude-sonnet-5",
                "claude-fable-5",
            ).forEach {
                assertEquals(it, ClaudeConstants.canonicalModelId(it), "should be unchanged: $it")
            }
        }
    }

    @Nested
    inner class IsSameModel {
        @Test
        fun `bedrock profile id matches reported canonical id`() {
            assertTrue(ClaudeConstants.isSameModel("us.anthropic.claude-opus-5[1m]", "claude-opus-5"))
        }

        @Test
        fun `identical ids match`() {
            assertTrue(ClaudeConstants.isSameModel("claude-opus-5", "claude-opus-5"))
        }

        @Test
        fun `genuinely different models do not match`() {
            assertFalse(ClaudeConstants.isSameModel("us.anthropic.claude-opus-5[1m]", "claude-haiku-4-5-20251001"))
            assertFalse(ClaudeConstants.isSameModel("claude-opus-5", "claude-opus-4-6"))
        }

        @Test
        fun `blank selection does not match a real model`() {
            assertFalse(ClaudeConstants.isSameModel("", "claude-opus-5"))
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
            assertTrue(ClaudeConstants.AVAILABLE_MODELS.contains(ClaudeConstants.MODEL_OPUS_47))
            assertTrue(ClaudeConstants.AVAILABLE_MODELS.contains(ClaudeConstants.MODEL_OPUS))
            assertTrue(ClaudeConstants.AVAILABLE_MODELS.contains(ClaudeConstants.MODEL_SONNET))
            assertTrue(ClaudeConstants.AVAILABLE_MODELS.contains(ClaudeConstants.MODEL_HAIKU))
            assertTrue(ClaudeConstants.AVAILABLE_MODELS.contains(ClaudeConstants.MODEL_SONNET_PREV))
        }

        @Test
        fun `available models has 6 entries`() {
            assertEquals(6, ClaudeConstants.AVAILABLE_MODELS.size)
        }

        @Test
        fun `available models is a list with correct order`() {
            assertEquals(
                listOf("", ClaudeConstants.MODEL_OPUS_47, ClaudeConstants.MODEL_OPUS, ClaudeConstants.MODEL_SONNET, ClaudeConstants.MODEL_HAIKU, ClaudeConstants.MODEL_SONNET_PREV),
                ClaudeConstants.AVAILABLE_MODELS
            )
        }
    }
}
