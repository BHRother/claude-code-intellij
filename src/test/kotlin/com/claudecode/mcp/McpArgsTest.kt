package com.claudecode.mcp

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class McpArgsTest {

    @Test
    fun `one arg per line is preserved`() {
        assertEquals(listOf("-y", "@upstash/context7-mcp@latest"), splitMcpArgs("-y\n@upstash/context7-mcp@latest"))
    }

    @Test
    fun `space-separated tokens on one line are split`() {
        assertEquals(listOf("-y", "@upstash/context7-mcp@latest"), splitMcpArgs("-y @upstash/context7-mcp@latest"))
    }

    @Test
    fun `mixed lines and spaces, plus tabs and multiple spaces, all split`() {
        val input = "run   --flag\nvalue\t-x  y"
        assertEquals(listOf("run", "--flag", "value", "-x", "y"), splitMcpArgs(input))
    }

    @Test
    fun `blank lines and surrounding whitespace are dropped`() {
        assertEquals(listOf("a", "b"), splitMcpArgs("\n  a  \n\n   \nb\n"))
    }

    @Test
    fun `empty input yields empty list`() {
        assertTrue(splitMcpArgs("").isEmpty())
        assertTrue(splitMcpArgs("   \n  \n").isEmpty())
    }
}
