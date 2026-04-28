package com.claudecode.actions

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ToggleClaudeActionTest {

    @Nested
    inner class ActionProperties {
        @Test
        fun `can be instantiated`() {
            val action = ToggleClaudeAction()
            assertNotNull(action)
        }

        @Test
        fun `uses BGT action update thread`() {
            val action = ToggleClaudeAction()
            assertEquals(
                com.intellij.openapi.actionSystem.ActionUpdateThread.BGT,
                action.actionUpdateThread
            )
        }

        @Test
        fun `has presentation text`() {
            val action = ToggleClaudeAction()
            assertEquals("Toggle Claude Code", action.templatePresentation.text)
        }

        @Test
        fun `has description`() {
            val action = ToggleClaudeAction()
            assertEquals("Open or focus the Claude Code panel", action.templatePresentation.description)
        }

        @Test
        fun `has no icon`() {
            val action = ToggleClaudeAction()
            assertNull(action.templatePresentation.icon)
        }
    }
}
