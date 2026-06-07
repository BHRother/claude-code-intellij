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

    @Test
    fun `remote add emits --client-secret as a bare flag only when a secret is set`() {
        val base = McpServer(
            name = "gdrive", scope = McpScope.LOCAL, transport = McpTransport.HTTP,
            url = "https://example.com/mcp", clientId = "CID",
        )
        // No secret → no flag (otherwise the CLI would block on an interactive prompt).
        assertFalse(McpCli.buildAddArgs(base).contains("--client-secret"))

        // With a secret → bare flag present (value travels via MCP_CLIENT_SECRET env),
        // and the secret value itself never appears on the command line.
        val withSecret = McpCli.buildAddArgs(base.copy(clientSecret = "shhh"))
        assertTrue(withSecret.contains("--client-secret"))
        assertFalse(withSecret.any { it.contains("shhh") })
        // The boolean flag must not swallow a following token's value.
        val idx = withSecret.indexOf("--client-secret")
        assertTrue(idx == withSecret.lastIndex || withSecret[idx + 1].startsWith("--"))
    }
}
