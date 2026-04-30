package com.claudecode.actions

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class FileContextActionsInstantiationTest {

    @Nested
    inner class ExplainFileAction {
        @Test
        fun `can be instantiated`() {
            assertNotNull(ExplainFileWithClaudeAction())
        }

        @Test
        fun `uses BGT`() {
            assertEquals(
                com.intellij.openapi.actionSystem.ActionUpdateThread.BGT,
                ExplainFileWithClaudeAction().actionUpdateThread
            )
        }

        @Test
        fun `prompt contains explain`() {
            val action = ExplainFileWithClaudeAction()
            val field = action.javaClass.superclass.getDeclaredField("prompt")
            field.isAccessible = true
            val prompt = field.get(action) as String
            assertTrue(prompt.lowercase().contains("explain"))
        }
    }

    @Nested
    inner class GenerateTestsFileAction {
        @Test
        fun `can be instantiated`() {
            assertNotNull(GenerateTestsFileWithClaudeAction())
        }

        @Test
        fun `uses BGT`() {
            assertEquals(
                com.intellij.openapi.actionSystem.ActionUpdateThread.BGT,
                GenerateTestsFileWithClaudeAction().actionUpdateThread
            )
        }

        @Test
        fun `prompt contains tests`() {
            val action = GenerateTestsFileWithClaudeAction()
            val field = action.javaClass.superclass.getDeclaredField("prompt")
            field.isAccessible = true
            val prompt = field.get(action) as String
            assertTrue(prompt.lowercase().contains("test"))
        }
    }

    @Nested
    inner class RefactorFileAction {
        @Test
        fun `can be instantiated`() {
            assertNotNull(RefactorFileWithClaudeAction())
        }

        @Test
        fun `uses BGT`() {
            assertEquals(
                com.intellij.openapi.actionSystem.ActionUpdateThread.BGT,
                RefactorFileWithClaudeAction().actionUpdateThread
            )
        }

        @Test
        fun `prompt contains refactor`() {
            val action = RefactorFileWithClaudeAction()
            val field = action.javaClass.superclass.getDeclaredField("prompt")
            field.isAccessible = true
            val prompt = field.get(action) as String
            assertTrue(prompt.lowercase().contains("refactor"))
        }
    }

    @Nested
    inner class SendFileAction {
        @Test
        fun `can be instantiated`() {
            assertNotNull(SendFileToClaudeAction())
        }

        @Test
        fun `uses BGT`() {
            assertEquals(
                com.intellij.openapi.actionSystem.ActionUpdateThread.BGT,
                SendFileToClaudeAction().actionUpdateThread
            )
        }
    }

    @Nested
    inner class ExplainFolderAction {
        @Test
        fun `can be instantiated`() {
            assertNotNull(ExplainFolderWithClaudeAction())
        }

        @Test
        fun `uses BGT`() {
            assertEquals(
                com.intellij.openapi.actionSystem.ActionUpdateThread.BGT,
                ExplainFolderWithClaudeAction().actionUpdateThread
            )
        }
    }

    @Nested
    inner class GenerateTestsFolderAction {
        @Test
        fun `can be instantiated`() {
            assertNotNull(GenerateTestsFolderWithClaudeAction())
        }

        @Test
        fun `uses BGT`() {
            assertEquals(
                com.intellij.openapi.actionSystem.ActionUpdateThread.BGT,
                GenerateTestsFolderWithClaudeAction().actionUpdateThread
            )
        }
    }
}
