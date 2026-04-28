package com.claudecode.actions

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class NewSessionActionTest {

    @Nested
    inner class ActionProperties {
        @Test
        fun `can be instantiated`() {
            val action = NewSessionAction()
            assertNotNull(action)
        }

        @Test
        fun `uses BGT action update thread`() {
            val action = NewSessionAction()
            assertEquals(
                com.intellij.openapi.actionSystem.ActionUpdateThread.BGT,
                action.actionUpdateThread
            )
        }

        @Test
        fun `has presentation text`() {
            val action = NewSessionAction()
            assertEquals("New Claude Session", action.templatePresentation.text)
        }

        @Test
        fun `has description`() {
            val action = NewSessionAction()
            assertEquals("Start a new Claude Code session", action.templatePresentation.description)
        }

        @Test
        fun `has icon`() {
            val action = NewSessionAction()
            assertNotNull(action.templatePresentation.icon)
        }
    }
}
