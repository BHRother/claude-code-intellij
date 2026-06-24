package com.claudecode.mcp

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class McpCliParseTest {

    @Test
    fun `parses connected server line`() {
        val out = """
            Checking MCP server health…

            context7: npx -y @upstash/context7-mcp@latest - ✓ Connected
        """.trimIndent()
        val map = McpCli.parseListOutput(out)
        assertEquals(McpServerStatus.CONNECTED, map["context7"])
        assertEquals(1, map.size)
    }

    @Test
    fun `parses get-status connected`() {
        val out = """
            context7:
              Scope: User config (available in all your projects)
              Status: ✔ Connected
              Type: stdio
        """.trimIndent()
        assertEquals(McpServerStatus.CONNECTED, McpCli.parseGetStatus(out))
    }

    @Test
    fun `parses get-status needs-auth and failed`() {
        assertEquals(
            McpServerStatus.NEEDS_AUTH,
            McpCli.parseGetStatus("foo:\n  Status: 🔒 Needs authentication\n")
        )
        assertEquals(
            McpServerStatus.FAILED,
            McpCli.parseGetStatus("foo:\n  Status: ✗ Failed to connect\n")
        )
    }

    @Test
    fun `get-status without a status line is unknown`() {
        assertEquals(McpServerStatus.UNKNOWN, McpCli.parseGetStatus("foo:\n  Type: stdio\n"))
    }

    @Test
    fun `classifies failed, pending and auth statuses`() {
        val out = """
            alpha: node a.js - ✗ Failed to connect
            beta: https://h/mcp - ⏸ Pending approval
            gamma: https://h/mcp - Needs authentication
        """.trimIndent()
        val map = McpCli.parseListOutput(out)
        assertEquals(McpServerStatus.FAILED, map["alpha"])
        assertEquals(McpServerStatus.PENDING_APPROVAL, map["beta"])
        assertEquals(McpServerStatus.NEEDS_AUTH, map["gamma"])
    }

    @Test
    fun `ignores header and empty-state lines`() {
        assertTrue(McpCli.parseListOutput("No MCP servers configured. Use `claude mcp add` to add a server.").isEmpty())
        assertTrue(McpCli.parseListOutput("").isEmpty())
    }

    @Test
    fun `uses the last separator so commands containing a dash are not confused`() {
        // The command itself contains " - "; status must come from the final one.
        val out = "srv: my-cmd --flag a - b - ✓ Connected"
        val map = McpCli.parseListOutput(out)
        assertEquals(McpServerStatus.CONNECTED, map["srv"])
    }

    @Test
    fun `http add puts url before variadic header to avoid commandOrUrl error`() {
        val server = McpServer(
            name = "github", scope = McpScope.USER, transport = McpTransport.HTTP,
            url = "https://api.githubcopilot.com/mcp/",
            headers = linkedMapOf("Authorization" to "Bearer x"),
        )
        val args = McpCli.buildAddArgs(server)
        // name, then URL as the 2nd positional, BEFORE -H (which is variadic and
        // would otherwise swallow the URL).
        assertEquals(listOf("mcp", "add", "github", "https://api.githubcopilot.com/mcp/"), args.take(4))
        val urlIdx = args.indexOf("https://api.githubcopilot.com/mcp/")
        val headerIdx = args.indexOf("-H")
        assertTrue(urlIdx in 0 until headerIdx, "URL must come before -H (url=$urlIdx, -H=$headerIdx)")
        assertEquals("Authorization: Bearer x", args[headerIdx + 1])
        assertTrue(args.containsAll(listOf("-s", "user", "-t", "http")))
    }

    @Test
    fun `http add includes oauth client id and callback port`() {
        val server = McpServer(
            name = "remote", scope = McpScope.PROJECT, transport = McpTransport.SSE,
            url = "https://h/mcp", clientId = "cid", callbackPort = 7777,
        )
        val args = McpCli.buildAddArgs(server)
        assertEquals("cid", args[args.indexOf("--client-id") + 1])
        assertEquals("7777", args[args.indexOf("--callback-port") + 1])
        assertTrue(args.indexOf("https://h/mcp") < args.indexOf("--client-id"))
    }

    @Test
    fun `add-json serializes http server with type url and headers`() {
        val server = McpServer(
            name = "github", scope = McpScope.USER, transport = McpTransport.HTTP,
            url = "https://api.githubcopilot.com/mcp/",
            headers = linkedMapOf("Authorization" to "Bearer x"),
        )
        val json = com.google.gson.JsonParser.parseString(McpCli.buildServerJson(server)).asJsonObject
        assertEquals("http", json.get("type").asString)
        assertEquals("https://api.githubcopilot.com/mcp/", json.get("url").asString)
        assertEquals("Bearer x", json.getAsJsonObject("headers").get("Authorization").asString)
        assertFalse(json.has("command"))

        val args = McpCli.buildAddJsonArgs(server)
        assertEquals(listOf("mcp", "add-json", "github"), args.take(3))
        assertEquals(listOf("-s", "user"), args.subList(4, 6))
    }

    @Test
    fun `add-json serializes stdio server with command args and env, omitting empties`() {
        val server = McpServer(
            name = "ctx", scope = McpScope.LOCAL, transport = McpTransport.STDIO,
            command = "npx", args = listOf("-y", "pkg"), env = linkedMapOf("K" to "V"),
        )
        val json = com.google.gson.JsonParser.parseString(McpCli.buildServerJson(server)).asJsonObject
        assertEquals("stdio", json.get("type").asString)
        assertEquals("npx", json.get("command").asString)
        assertEquals(listOf("-y", "pkg"), json.getAsJsonArray("args").map { it.asString })
        assertEquals("V", json.getAsJsonObject("env").get("K").asString)
        assertFalse(json.has("url"))

        val bare = McpServer(name = "b", scope = McpScope.LOCAL, transport = McpTransport.STDIO, command = "node")
        val bareJson = com.google.gson.JsonParser.parseString(McpCli.buildServerJson(bare)).asJsonObject
        assertFalse(bareJson.has("args"))
        assertFalse(bareJson.has("env"))
    }

    @Test
    fun `stdio add terminates options with double dash before command`() {
        val server = McpServer(
            name = "ctx", scope = McpScope.LOCAL, transport = McpTransport.STDIO,
            command = "npx", args = listOf("-y", "pkg"), env = linkedMapOf("K" to "V"),
        )
        val args = McpCli.buildAddArgs(server)
        val dash = args.indexOf("--")
        assertTrue(dash > 0)
        // env (variadic) must be before `--`; command + its args after it.
        assertTrue(args.indexOf("-e") < dash)
        assertEquals("K=V", args[args.indexOf("-e") + 1])
        assertEquals(listOf("npx", "-y", "pkg"), args.subList(dash + 1, args.size))
    }

    @Test
    fun `status text classification is fuzzy`() {
        assertEquals(McpServerStatus.NEEDS_AUTH, McpServerStatus.fromListStatusText("requires authentication"))
        assertEquals(McpServerStatus.NEEDS_AUTH, McpServerStatus.fromListStatusText("Please sign in"))
        assertEquals(McpServerStatus.CONNECTED, McpServerStatus.fromListStatusText("✓ Connected"))
        assertEquals(McpServerStatus.FAILED, McpServerStatus.fromListStatusText("✗ Failed to connect"))
        assertEquals(McpServerStatus.PENDING_APPROVAL, McpServerStatus.fromListStatusText("⏸ Pending approval"))
        assertEquals(McpServerStatus.UNKNOWN, McpServerStatus.fromListStatusText("something else"))
    }
}
