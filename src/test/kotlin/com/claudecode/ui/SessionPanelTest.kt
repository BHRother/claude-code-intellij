package com.claudecode.ui

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * SessionPanel is heavily UI/platform-dependent (requires Project, ClaudeSession, Swing EDT).
 * These tests validate the testable pure logic aspects.
 * Full integration tests would require IntelliJ platform test fixtures.
 */
class SessionPanelTest {

    @Nested
    inner class MarkdownRendererIntegration {
        @Test
        fun `markdown renderer is available for SessionPanel rendering`() {
            // SessionPanel uses MarkdownRenderer.render() for message display
            val result = MarkdownRenderer.render("**test**")
            assertTrue(result.contains("<b>test</b>"))
        }

        @Test
        fun `markdown renderer handles code blocks used in SessionPanel`() {
            val result = MarkdownRenderer.render("```kotlin\nval x = 1\n```", copyLinkGenerator = { code ->
                "<a>copy</a>"
            })
            assertTrue(result.contains("<a>copy</a>"))
        }
    }

    @Nested
    inner class LanguageDetection {
        @Test
        fun `language detection works for diff display`() {
            assertEquals("kotlin", MarkdownRenderer.languageFromFilePath("/path/to/File.kt"))
            assertEquals("java", MarkdownRenderer.languageFromFilePath("/path/to/File.java"))
        }
    }

    @Nested
    inner class HtmlEscaping {
        @Test
        fun `escapeHtml used in SessionPanel prevents XSS`() {
            val escaped = MarkdownRenderer.escapeHtml("<script>alert('xss')</script>")
            assertFalse(escaped.contains("<script>"))
            assertTrue(escaped.contains("&lt;script&gt;"))
        }
    }
}
