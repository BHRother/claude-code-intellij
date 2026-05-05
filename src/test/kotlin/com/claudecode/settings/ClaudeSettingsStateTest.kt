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
        fun `completion is disabled by default`() {
            val state = ClaudeSettings.State()
            assertFalse(state.enableCompletion)
        }

        @Test
        fun `default completion debounce is 500ms`() {
            val state = ClaudeSettings.State()
            assertEquals(500L, state.completionDebounceMs)
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
        fun `autoAcceptPermissions is enabled by default`() {
            val state = ClaudeSettings.State()
            assertTrue(state.autoAcceptPermissions)
        }

        @Test
        fun `customModels is empty by default`() {
            val state = ClaudeSettings.State()
            assertEquals("", state.customModels)
        }
    }

    @Nested
    inner class Mutability {
        @Test
        fun `state fields are mutable`() {
            val state = ClaudeSettings.State()
            state.claudePath = "/usr/local/bin/claude"
            state.model = "claude-opus-4-6"
            state.enableCompletion = true
            state.maxSessions = 5

            assertEquals("/usr/local/bin/claude", state.claudePath)
            assertEquals("claude-opus-4-6", state.model)
            assertTrue(state.enableCompletion)
            assertEquals(5, state.maxSessions)
        }

        @Test
        fun `fontSize is mutable`() {
            val state = ClaudeSettings.State()
            state.fontSize = 18
            assertEquals(18, state.fontSize)
        }

        @Test
        fun `completionDebounceMs is mutable`() {
            val state = ClaudeSettings.State()
            state.completionDebounceMs = 1000L
            assertEquals(1000L, state.completionDebounceMs)
        }

        @Test
        fun `sendSelectionContext is mutable`() {
            val state = ClaudeSettings.State()
            state.sendSelectionContext = false
            assertFalse(state.sendSelectionContext)
        }

        @Test
        fun `autoAcceptPermissions is mutable`() {
            val state = ClaudeSettings.State()
            state.autoAcceptPermissions = false
            assertFalse(state.autoAcceptPermissions)
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
                enableCompletion = true,
                completionDebounceMs = 1000L,
                maxSessions = 5,
                fontSize = 16,
                sendSelectionContext = false,
                autoAcceptPermissions = false,
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
        fun `inequality on different enableCompletion`() {
            val a = ClaudeSettings.State(enableCompletion = false)
            val b = ClaudeSettings.State(enableCompletion = true)
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
        fun `inequality on different autoAcceptPermissions`() {
            val a = ClaudeSettings.State(autoAcceptPermissions = true)
            val b = ClaudeSettings.State(autoAcceptPermissions = false)
            assertNotEquals(a, b)
        }

        @Test
        fun `inequality on different completionDebounceMs`() {
            val a = ClaudeSettings.State(completionDebounceMs = 500L)
            val b = ClaudeSettings.State(completionDebounceMs = 1000L)
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
                enableCompletion = true,
                completionDebounceMs = 200L,
                maxSessions = 3,
                fontSize = 16,
                sendSelectionContext = false,
                autoAcceptPermissions = false,
                customModels = "claude-custom-1,claude-custom-2"
            )
            assertEquals("/opt/claude", state.claudePath)
            assertEquals(ClaudeConstants.MODEL_SONNET, state.model)
            assertTrue(state.enableCompletion)
            assertEquals(200L, state.completionDebounceMs)
            assertEquals(3, state.maxSessions)
            assertEquals(16, state.fontSize)
            assertFalse(state.sendSelectionContext)
            assertFalse(state.autoAcceptPermissions)
            assertEquals("claude-custom-1,claude-custom-2", state.customModels)
        }
    }
}
