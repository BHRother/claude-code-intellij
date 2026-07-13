package com.claudecode.settings

import com.claudecode.ClaudeConstants
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ClaudeSettingsStateTest {

    @Nested
    inner class Defaults {
        @Test
        fun `default claudePath is claude`() {
            val state = ClaudeSettings.State()
            assertEquals(ClaudeConstants.DEFAULT_CLI_PATH, state.claudePath)
        }

        @Test
        fun `default model is empty`() {
            val state = ClaudeSettings.State()
            assertEquals("", state.model)
        }

        @Test
        fun `default max sessions is 10`() {
            val state = ClaudeSettings.State()
            assertEquals(10, state.maxSessions)
        }

        @Test
        fun `default font size is 13`() {
            val state = ClaudeSettings.State()
            assertEquals(13, state.fontSize)
        }

        @Test
        fun `sendSelectionContext is enabled by default`() {
            val state = ClaudeSettings.State()
            assertTrue(state.sendSelectionContext)
        }

        @Test
        fun `permissionMode defaults to acceptEdits`() {
            val state = ClaudeSettings.State()
            assertEquals(ClaudeConstants.PERMISSION_MODE_ACCEPT_EDITS, state.permissionMode)
        }

        @Test
        fun `customModels is empty by default`() {
            val state = ClaudeSettings.State()
            assertEquals("", state.customModels)
        }

        @Test
        fun `appearance theme defaults to follow IDE`() {
            val state = ClaudeSettings.State()
            assertEquals(ClaudeConstants.THEME_FOLLOW_IDE, state.appearanceThemeMode)
        }

        @Test
        fun `chat and input font families default to blank (bundled default)`() {
            val state = ClaudeSettings.State()
            assertEquals("", state.chatFontFamily)
            assertEquals("", state.inputFontFamily)
        }

        @Test
        fun `input font size defaults to 0 (inherit chat size)`() {
            val state = ClaudeSettings.State()
            assertEquals(0, state.inputFontSize)
        }
    }

    @Nested
    inner class FontResolution {
        @Test
        fun `chat family falls back to bundled default when blank`() {
            val settings = ClaudeSettings()
            settings.loadState(ClaudeSettings.State(chatFontFamily = ""))
            assertEquals(ClaudeConstants.FONT_FAMILY, settings.chatFontFamily())
        }

        @Test
        fun `chat family honors an override`() {
            val settings = ClaudeSettings()
            settings.loadState(ClaudeSettings.State(chatFontFamily = "Fira Code"))
            assertEquals("Fira Code", settings.chatFontFamily())
        }

        @Test
        fun `input size inherits chat size when unset`() {
            val settings = ClaudeSettings()
            settings.loadState(ClaudeSettings.State(fontSize = 17, inputFontSize = 0))
            assertEquals(17, settings.effectiveInputFontSize())
        }

        @Test
        fun `input size uses its own value when set`() {
            val settings = ClaudeSettings()
            settings.loadState(ClaudeSettings.State(fontSize = 17, inputFontSize = 11))
            assertEquals(11, settings.effectiveInputFontSize())
        }
    }

    @Nested
    inner class Mutability {
        @Test
        fun `state fields are mutable`() {
            val state = ClaudeSettings.State()
            state.claudePath = "/usr/local/bin/claude"
            state.model = "claude-opus-4-6"
            state.maxSessions = 5

            assertEquals("/usr/local/bin/claude", state.claudePath)
            assertEquals("claude-opus-4-6", state.model)
            assertEquals(5, state.maxSessions)
        }

        @Test
        fun `fontSize is mutable`() {
            val state = ClaudeSettings.State()
            state.fontSize = 18
            assertEquals(18, state.fontSize)
        }

        @Test
        fun `sendSelectionContext is mutable`() {
            val state = ClaudeSettings.State()
            state.sendSelectionContext = false
            assertFalse(state.sendSelectionContext)
        }

        @Test
        fun `permissionMode is mutable`() {
            val state = ClaudeSettings.State()
            state.permissionMode = ClaudeConstants.PERMISSION_MODE_BYPASS
            assertEquals(ClaudeConstants.PERMISSION_MODE_BYPASS, state.permissionMode)
        }

        @Test
        fun `customModels is mutable`() {
            val state = ClaudeSettings.State()
            state.customModels = "claude-test-1,claude-test-2"
            assertEquals("claude-test-1,claude-test-2", state.customModels)
        }
    }

    @Nested
    inner class DataClassBehavior {
        @Test
        fun `data class copy preserves values`() {
            val original = ClaudeSettings.State(
                claudePath = "/custom/path",
                model = "claude-opus-4-6",
                maxSessions = 3
            )
            val copy = original.copy(model = "claude-sonnet-4-6")
            assertEquals("/custom/path", copy.claudePath)
            assertEquals("claude-sonnet-4-6", copy.model)
            assertEquals(3, copy.maxSessions)
        }

        @Test
        fun `data class equality works`() {
            val a = ClaudeSettings.State()
            val b = ClaudeSettings.State()
            assertEquals(a, b)
        }

        @Test
        fun `data class inequality on different values`() {
            val a = ClaudeSettings.State()
            val b = ClaudeSettings.State(maxSessions = 5)
            assertNotEquals(a, b)
        }

        @Test
        fun `hashCode is consistent for equal objects`() {
            val a = ClaudeSettings.State()
            val b = ClaudeSettings.State()
            assertEquals(a.hashCode(), b.hashCode())
        }

        @Test
        fun `toString contains field values`() {
            val state = ClaudeSettings.State(model = "claude-opus-4-6")
            val str = state.toString()
            assertTrue(str.contains("claude-opus-4-6"))
        }

        @Test
        fun `copy all fields`() {
            val original = ClaudeSettings.State(
                claudePath = "/custom",
                model = "opus",
                maxSessions = 5,
                fontSize = 16,
                sendSelectionContext = false,
                permissionMode = ClaudeConstants.PERMISSION_MODE_BYPASS,
                customModels = "claude-test-1"
            )
            val copy = original.copy()
            assertEquals(original, copy)
        }

        @Test
        fun `inequality on different claudePath`() {
            val a = ClaudeSettings.State(claudePath = "/a")
            val b = ClaudeSettings.State(claudePath = "/b")
            assertNotEquals(a, b)
        }

        @Test
        fun `inequality on different fontSize`() {
            val a = ClaudeSettings.State(fontSize = 13)
            val b = ClaudeSettings.State(fontSize = 16)
            assertNotEquals(a, b)
        }

        @Test
        fun `inequality on different sendSelectionContext`() {
            val a = ClaudeSettings.State(sendSelectionContext = true)
            val b = ClaudeSettings.State(sendSelectionContext = false)
            assertNotEquals(a, b)
        }

        @Test
        fun `inequality on different permissionMode`() {
            val a = ClaudeSettings.State(permissionMode = ClaudeConstants.PERMISSION_MODE_ACCEPT_EDITS)
            val b = ClaudeSettings.State(permissionMode = ClaudeConstants.PERMISSION_MODE_BYPASS)
            assertNotEquals(a, b)
        }
    }

    @Nested
    inner class ConstructorWithParams {
        @Test
        fun `constructs with custom claudePath`() {
            val state = ClaudeSettings.State(claudePath = "/opt/claude")
            assertEquals("/opt/claude", state.claudePath)
        }

        @Test
        fun `constructs with custom model`() {
            val state = ClaudeSettings.State(model = ClaudeConstants.MODEL_OPUS)
            assertEquals(ClaudeConstants.MODEL_OPUS, state.model)
        }

        @Test
        fun `constructs with all custom values`() {
            val state = ClaudeSettings.State(
                claudePath = "/opt/claude",
                model = ClaudeConstants.MODEL_SONNET,
                maxSessions = 3,
                fontSize = 16,
                sendSelectionContext = false,
                permissionMode = ClaudeConstants.PERMISSION_MODE_PLAN,
                customModels = "claude-custom-1,claude-custom-2"
            )
            assertEquals("/opt/claude", state.claudePath)
            assertEquals(ClaudeConstants.MODEL_SONNET, state.model)
            assertEquals(3, state.maxSessions)
            assertEquals(16, state.fontSize)
            assertFalse(state.sendSelectionContext)
            assertEquals(ClaudeConstants.PERMISSION_MODE_PLAN, state.permissionMode)
            assertEquals("claude-custom-1,claude-custom-2", state.customModels)
        }
    }
}
