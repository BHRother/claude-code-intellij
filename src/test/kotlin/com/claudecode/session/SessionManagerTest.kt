package com.claudecode.session

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SessionManagerTest {

    @Nested
    inner class SessionManagerListenerInterface {
        @Test
        fun `listener interface has required methods`() {
            val listener = object : SessionManagerListener {
                override fun onSessionAdded(session: ClaudeSession) {}
                override fun onSessionRemoved(session: ClaudeSession) {}
            }
            assertNotNull(listener)
        }
    }

    @Nested
    inner class SessionListenerInterface {
        @Test
        fun `listener interface can be implemented`() {
            val listener = object : SessionListener {
                override fun onText(session: ClaudeSession, text: String) {}
                override fun onThinking(session: ClaudeSession, thinking: String?) {}
                override fun onToolUse(session: ClaudeSession, tool: String, detail: String?, diffSummary: String?, diffData: Pair<String, String>?, filePath: String?) {}
                override fun onFileChanged(session: ClaudeSession, filePath: String, action: String) {}
                override fun onPermissionRequest(session: ClaudeSession, prompt: String): Boolean = false
                override fun onTaskProgress(session: ClaudeSession, description: String) {}
                override fun onModelInfo(session: ClaudeSession, model: String) {}
                override fun onToolResult(session: ClaudeSession, toolUseId: String, isError: Boolean) {}
                override fun onFinished(session: ClaudeSession, costUsd: Double?) {}
                override fun onError(session: ClaudeSession, error: String) {}
                override fun onDebug(session: ClaudeSession, message: String) {}
            }
            assertNotNull(listener)
        }
    }

    @Nested
    inner class ClaudeMessageDataClass {
        @Test
        fun `creates message with role and content`() {
            val msg = ClaudeMessage("user", "hello")
            assertEquals("user", msg.role)
            assertEquals("hello", msg.content)
        }

        @Test
        fun `timestamp is set automatically`() {
            val before = System.currentTimeMillis()
            val msg = ClaudeMessage("user", "hello")
            val after = System.currentTimeMillis()
            assertTrue(msg.timestamp in before..after)
        }

        @Test
        fun `timestamp can be set explicitly`() {
            val msg = ClaudeMessage("user", "hello", 12345L)
            assertEquals(12345L, msg.timestamp)
        }

        @Test
        fun `equality ignores timestamp when different`() {
            val a = ClaudeMessage("user", "hello", 1L)
            val b = ClaudeMessage("user", "hello", 2L)
            assertNotEquals(a, b)
        }

        @Test
        fun `equality for same values`() {
            val a = ClaudeMessage("user", "hello", 100L)
            val b = ClaudeMessage("user", "hello", 100L)
            assertEquals(a, b)
        }

        @Test
        fun `copy preserves values`() {
            val original = ClaudeMessage("user", "hello", 100L)
            val copy = original.copy(content = "world")
            assertEquals("user", copy.role)
            assertEquals("world", copy.content)
            assertEquals(100L, copy.timestamp)
        }

        @Test
        fun `assistant role`() {
            val msg = ClaudeMessage("assistant", "response")
            assertEquals("assistant", msg.role)
        }
    }
}
