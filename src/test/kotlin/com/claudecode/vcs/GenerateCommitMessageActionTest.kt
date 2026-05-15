package com.claudecode.vcs

import com.intellij.openapi.actionSystem.ActionUpdateThread
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class GenerateCommitMessageActionTest {

    private val action = GenerateCommitMessageAction()

    @Suppress("UNCHECKED_CAST")
    private fun <T> invokePrivate(name: String, vararg args: Any?): T {
        val method = GenerateCommitMessageAction::class.java
            .declaredMethods.first { it.name == name }
        method.isAccessible = true
        return method.invoke(action, *args) as T
    }

    private fun cleanMessage(text: String): String = invokePrivate("cleanMessage", text)
    private fun truncateForPrompt(diff: String, maxChars: Int): String =
        invokePrivate("truncateForPrompt", diff, maxChars)
    private fun buildPrompt(diff: String, stat: String, recentCommits: String): String =
        invokePrivate("buildPrompt", diff, stat, recentCommits)

    @Nested
    inner class ActionProperties {
        @Test
        fun `can be instantiated`() {
            assertNotNull(GenerateCommitMessageAction())
        }

        @Test
        fun `uses BGT action update thread`() {
            assertEquals(ActionUpdateThread.BGT, GenerateCommitMessageAction().actionUpdateThread)
        }
    }

    @Nested
    inner class CleanMessage {
        @Test
        fun `trims surrounding whitespace`() {
            assertEquals("add feature", cleanMessage("   add feature   \n\n"))
        }

        @Test
        fun `strips triple-backtick code fences`() {
            val fenced = """
                ```
                add login flow

                Wire up the new endpoint.
                ```
            """.trimIndent()
            assertEquals("add login flow\n\nWire up the new endpoint.", cleanMessage(fenced))
        }

        @Test
        fun `strips fenced block with language tag`() {
            val fenced = """
                ```text
                fix off-by-one
                ```
            """.trimIndent()
            assertEquals("fix off-by-one", cleanMessage(fenced))
        }

        @Test
        fun `strips straight double quotes wrapping the message`() {
            assertEquals("add tests", cleanMessage("\"add tests\""))
        }

        @Test
        fun `strips smart quotes wrapping the message`() {
            assertEquals("add tests", cleanMessage("“add tests”"))
        }

        @Test
        fun `leaves message untouched when only one side is quoted`() {
            assertEquals("\"add tests", cleanMessage("\"add tests"))
        }

        @Test
        fun `drops Here is the commit message preamble`() {
            val raw = "Here is the commit message:\nadd retry policy"
            assertEquals("add retry policy", cleanMessage(raw))
        }

        @Test
        fun `drops Heres preamble case-insensitively`() {
            val raw = "Here's the commit message:\nfix flaky test"
            assertEquals("fix flaky test", cleanMessage(raw))
        }

        @Test
        fun `drops first line ending in commit message colon`() {
            val raw = "Below is the commit message:\nrefactor session manager"
            assertEquals("refactor session manager", cleanMessage(raw))
        }

        @Test
        fun `collapses three or more blank lines to a single blank line`() {
            val raw = "subject\n\n\n\nbody paragraph"
            assertEquals("subject\n\nbody paragraph", cleanMessage(raw))
        }

        @Test
        fun `leaves normal subject and body untouched`() {
            val raw = "add commit message generator\n\nUse Claude to draft a message from the staged diff."
            assertEquals(raw, cleanMessage(raw))
        }
    }

    @Nested
    inner class TruncateForPrompt {
        @Test
        fun `returns input unchanged when under the limit`() {
            val diff = "diff --git a/foo b/foo\n+hello"
            assertEquals(diff, truncateForPrompt(diff, 1000))
        }

        @Test
        fun `appends truncation marker when over the limit`() {
            val diff = "x".repeat(100)
            val result = truncateForPrompt(diff, 40)
            assertTrue(result.startsWith("x".repeat(40)))
            assertTrue(result.contains("[…diff truncated, 60 chars omitted]"))
        }

        @Test
        fun `respects custom maxChars at exact boundary`() {
            val diff = "x".repeat(10)
            assertEquals(diff, truncateForPrompt(diff, 10))
        }
    }

    @Nested
    inner class BuildPrompt {
        private val diff = "diff --git a/Foo.kt b/Foo.kt\n+println(\"hi\")"
        private val stat = " Foo.kt | 1 +\n 1 file changed, 1 insertion(+)"

        @Test
        fun `includes recent commits block when commits are provided`() {
            val commits = "add login\n\n---\n\nfix retry"
            val prompt = buildPrompt(diff, stat, commits)
            assertTrue(prompt.contains("<recent-commits>"))
            assertTrue(prompt.contains("</recent-commits>"))
            assertTrue(prompt.contains("add login"))
            assertTrue(prompt.contains("fix retry"))
            assertTrue(prompt.contains("Match their style"))
        }

        @Test
        fun `falls back to default style when no commits are provided`() {
            val prompt = buildPrompt(diff, stat, "")
            assertFalse(prompt.contains("<recent-commits>"))
            assertTrue(prompt.contains("No recent commits available"))
        }

        @Test
        fun `always contains the diff and shared rules`() {
            val prompt = buildPrompt(diff, stat, "")
            assertTrue(prompt.contains(diff))
            assertTrue(prompt.contains("Imperative mood"))
            assertTrue(prompt.contains("Return ONLY the commit message text"))
        }

        @Test
        fun `includes the stat summary when provided`() {
            val prompt = buildPrompt(diff, stat, "")
            assertTrue(prompt.contains("<files-changed>"))
            assertTrue(prompt.contains("</files-changed>"))
            assertTrue(prompt.contains("Foo.kt"))
        }

        @Test
        fun `omits files-changed block when stat is blank`() {
            val prompt = buildPrompt(diff, "", "")
            // The closing tag only appears in the actual block — the opening
            // tag is also referenced in the natural-language instructions.
            assertFalse(prompt.contains("</files-changed>"))
        }

        @Test
        fun `tells claude to find the unifying intent`() {
            val prompt = buildPrompt(diff, stat, "")
            assertTrue(prompt.contains("unifying intent"))
        }
    }
}
