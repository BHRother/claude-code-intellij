package com.claudecode.toolwindow

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ClaudeToolWindowFactoryTest {

    @Nested
    inner class FactoryInstantiation {
        @Test
        fun `can be instantiated`() {
            val factory = ClaudeToolWindowFactory()
            assertNotNull(factory)
        }

        @Test
        fun `implements DumbAware`() {
            val factory = ClaudeToolWindowFactory()
            assertTrue(factory is com.intellij.openapi.project.DumbAware)
        }

        @Test
        fun `implements ToolWindowFactory`() {
            val factory = ClaudeToolWindowFactory()
            assertTrue(factory is com.intellij.openapi.wm.ToolWindowFactory)
        }
    }
}
