package com.claudecode.completion

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ClaudeCompletionContributorTest {

    @Nested
    inner class Instantiation {
        @Test
        fun `can be instantiated`() {
            val contributor = ClaudeCompletionContributor()
            assertNotNull(contributor)
        }

        @Test
        fun `extends CompletionContributor`() {
            val contributor = ClaudeCompletionContributor()
            assertTrue(contributor is com.intellij.codeInsight.completion.CompletionContributor)
        }
    }
}
