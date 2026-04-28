package com.claudecode.actions

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class EditorContextActionsTest {

    @Nested
    inner class ActionInstantiation {
        @Test
        fun `RefactorWithClaudeAction can be instantiated`() {
            val action = RefactorWithClaudeAction()
            assertNotNull(action)
        }

        @Test
        fun `ExplainWithClaudeAction can be instantiated`() {
            val action = ExplainWithClaudeAction()
            assertNotNull(action)
        }

        @Test
        fun `AddTestsWithClaudeAction can be instantiated`() {
            val action = AddTestsWithClaudeAction()
            assertNotNull(action)
        }

        @Test
        fun `FixErrorWithClaudeAction can be instantiated`() {
            val action = FixErrorWithClaudeAction()
            assertNotNull(action)
        }

        @Test
        fun `SendToClaudeAction can be instantiated`() {
            val action = SendToClaudeAction()
            assertNotNull(action)
        }
    }

    @Nested
    inner class ActionUpdateThread {
        @Test
        fun `RefactorWithClaudeAction uses BGT`() {
            val action = RefactorWithClaudeAction()
            assertEquals(
                com.intellij.openapi.actionSystem.ActionUpdateThread.BGT,
                action.actionUpdateThread
            )
        }

        @Test
        fun `ExplainWithClaudeAction uses BGT`() {
            val action = ExplainWithClaudeAction()
            assertEquals(
                com.intellij.openapi.actionSystem.ActionUpdateThread.BGT,
                action.actionUpdateThread
            )
        }

        @Test
        fun `AddTestsWithClaudeAction uses BGT`() {
            val action = AddTestsWithClaudeAction()
            assertEquals(
                com.intellij.openapi.actionSystem.ActionUpdateThread.BGT,
                action.actionUpdateThread
            )
        }

        @Test
        fun `FixErrorWithClaudeAction uses BGT`() {
            val action = FixErrorWithClaudeAction()
            assertEquals(
                com.intellij.openapi.actionSystem.ActionUpdateThread.BGT,
                action.actionUpdateThread
            )
        }

        @Test
        fun `SendToClaudeAction uses BGT`() {
            val action = SendToClaudeAction()
            assertEquals(
                com.intellij.openapi.actionSystem.ActionUpdateThread.BGT,
                action.actionUpdateThread
            )
        }
    }

    @Nested
    inner class BaseClaudeEditorActionFields {
        @Test
        fun `RefactorWithClaudeAction requires selection`() {
            val action = RefactorWithClaudeAction()
            val field = action.javaClass.superclass.getDeclaredField("requiresSelection")
            field.isAccessible = true
            assertTrue(field.getBoolean(action))
        }

        @Test
        fun `ExplainWithClaudeAction does not require selection`() {
            val action = ExplainWithClaudeAction()
            val field = action.javaClass.superclass.getDeclaredField("requiresSelection")
            field.isAccessible = true
            assertFalse(field.getBoolean(action))
        }

        @Test
        fun `AddTestsWithClaudeAction does not require selection`() {
            val action = AddTestsWithClaudeAction()
            val field = action.javaClass.superclass.getDeclaredField("requiresSelection")
            field.isAccessible = true
            assertFalse(field.getBoolean(action))
        }

        @Test
        fun `FixErrorWithClaudeAction requires selection`() {
            val action = FixErrorWithClaudeAction()
            val field = action.javaClass.superclass.getDeclaredField("requiresSelection")
            field.isAccessible = true
            assertTrue(field.getBoolean(action))
        }

        @Test
        fun `RefactorWithClaudeAction has no filePrompt`() {
            val action = RefactorWithClaudeAction()
            val field = action.javaClass.superclass.getDeclaredField("filePrompt")
            field.isAccessible = true
            assertNull(field.get(action))
        }

        @Test
        fun `ExplainWithClaudeAction has filePrompt`() {
            val action = ExplainWithClaudeAction()
            val field = action.javaClass.superclass.getDeclaredField("filePrompt")
            field.isAccessible = true
            assertNotNull(field.get(action))
        }

        @Test
        fun `AddTestsWithClaudeAction has filePrompt`() {
            val action = AddTestsWithClaudeAction()
            val field = action.javaClass.superclass.getDeclaredField("filePrompt")
            field.isAccessible = true
            assertNotNull(field.get(action))
        }

        @Test
        fun `FixErrorWithClaudeAction has no filePrompt`() {
            val action = FixErrorWithClaudeAction()
            val field = action.javaClass.superclass.getDeclaredField("filePrompt")
            field.isAccessible = true
            assertNull(field.get(action))
        }

        @Test
        fun `RefactorWithClaudeAction selectionPrompt contains refactor`() {
            val action = RefactorWithClaudeAction()
            val field = action.javaClass.superclass.getDeclaredField("selectionPrompt")
            field.isAccessible = true
            val prompt = field.get(action) as String
            assertTrue(prompt.lowercase().contains("refactor"))
        }

        @Test
        fun `ExplainWithClaudeAction selectionPrompt contains explain`() {
            val action = ExplainWithClaudeAction()
            val field = action.javaClass.superclass.getDeclaredField("selectionPrompt")
            field.isAccessible = true
            val prompt = field.get(action) as String
            assertTrue(prompt.lowercase().contains("explain"))
        }

        @Test
        fun `AddTestsWithClaudeAction selectionPrompt contains tests`() {
            val action = AddTestsWithClaudeAction()
            val field = action.javaClass.superclass.getDeclaredField("selectionPrompt")
            field.isAccessible = true
            val prompt = field.get(action) as String
            assertTrue(prompt.lowercase().contains("test"))
        }

        @Test
        fun `FixErrorWithClaudeAction selectionPrompt contains fix`() {
            val action = FixErrorWithClaudeAction()
            val field = action.javaClass.superclass.getDeclaredField("selectionPrompt")
            field.isAccessible = true
            val prompt = field.get(action) as String
            assertTrue(prompt.lowercase().contains("fix"))
        }
    }
}
