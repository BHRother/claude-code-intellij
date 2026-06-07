package com.claudecode.mcp

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class McpConfigReaderTest {

    private fun write(dir: File, name: String, content: String): File =
        File(dir, name).apply { writeText(content) }

    @Test
    fun `aggregates project local and user scopes`(@TempDir tmp: Path) {
        val projectDir = File(tmp.toFile(), "proj").apply { mkdirs() }
        val canonicalProject = projectDir.canonicalPath

        val dotMcp = write(projectDir, ".mcp.json", """
            { "mcpServers": { "proj-stdio": { "command": "node", "args": ["server.js"] } } }
        """.trimIndent())

        val global = write(tmp.toFile(), ".claude.json", """
            {
              "mcpServers": { "user-http": { "type": "http", "url": "https://example.com/mcp" } },
              "projects": {
                "$canonicalProject": {
                  "mcpServers": { "local-stdio": { "type": "stdio", "command": "npx", "args": ["-y", "pkg"], "env": { "K": "V" } } }
                }
              }
            }
        """.trimIndent())

        val servers = McpConfigReader.readFrom(projectDir.path, dotMcp, global)

        // Order is project → local → user
        assertEquals(listOf("proj-stdio", "local-stdio", "user-http"), servers.map { it.name })
        assertEquals(McpScope.PROJECT, servers[0].scope)
        assertEquals(McpScope.LOCAL, servers[1].scope)
        assertEquals(McpScope.USER, servers[2].scope)

        val local = servers[1]
        assertEquals(McpTransport.STDIO, local.transport)
        assertEquals("npx", local.command)
        assertEquals(listOf("-y", "pkg"), local.args)
        assertEquals(mapOf("K" to "V"), local.env)

        val user = servers[2]
        assertEquals(McpTransport.HTTP, user.transport)
        assertEquals("https://example.com/mcp", user.url)
    }

    @Test
    fun `local scope matches when stored key is canonical and basePath is not`(@TempDir tmp: Path) {
        // On macOS, /var/... is a symlink to /private/var/...; Claude stores the
        // canonical path. Simulate that: store under canonicalPath, read with the
        // raw (possibly symlinked) path. Use a child whose canonical differs by
        // round-tripping through canonicalPath.
        val raw = File(tmp.toFile(), "work")
        raw.mkdirs()
        val canonical = raw.canonicalPath

        val global = write(tmp.toFile(), ".claude.json", """
            { "projects": { "$canonical": { "mcpServers": { "ctx": { "command": "npx" } } } } }
        """.trimIndent())

        // Even if raw.path == canonical on this FS, the match must still succeed.
        val servers = McpConfigReader.readFrom(raw.path, null, global)
        assertEquals(listOf("ctx"), servers.map { it.name })
        assertEquals(McpScope.LOCAL, servers[0].scope)
    }

    @Test
    fun `malformed global json yields no servers and does not throw`(@TempDir tmp: Path) {
        val global = write(tmp.toFile(), ".claude.json", "{ this is : not json ]")
        val servers = McpConfigReader.readFrom(tmp.toFile().path, null, global)
        assertTrue(servers.isEmpty())
    }

    @Test
    fun `missing files yield empty list`(@TempDir tmp: Path) {
        val servers = McpConfigReader.readFrom(
            tmp.toFile().path,
            File(tmp.toFile(), "nope.json"),
            File(tmp.toFile(), "absent.json"),
        )
        assertTrue(servers.isEmpty())
    }

    @Test
    fun `bare server map in dot mcp json is tolerated`(@TempDir tmp: Path) {
        val dotMcp = write(tmp.toFile(), ".mcp.json", """
            { "bare": { "command": "node" } }
        """.trimIndent())
        val servers = McpConfigReader.readFrom(tmp.toFile().path, dotMcp, null)
        assertEquals(listOf("bare"), servers.map { it.name })
        assertEquals(McpTransport.STDIO, servers[0].transport)
    }

    @Test
    fun `reads OAuth client id and callback port from nested oauth object`(@TempDir tmp: Path) {
        // Mirrors exactly what `claude mcp add --client-id X --callback-port N`
        // writes: clientId/callbackPort nested under an "oauth" object (the port
        // as a JSON number), NOT at the server's top level.
        val dotMcp = write(tmp.toFile(), ".mcp.json", """
            {
              "mcpServers": {
                "gh": {
                  "type": "http",
                  "url": "https://api.example.com/mcp",
                  "oauth": { "clientId": "MY_CLIENT_123", "callbackPort": 8910 }
                }
              }
            }
        """.trimIndent())
        val servers = McpConfigReader.readFrom(tmp.toFile().path, dotMcp, null)
        assertEquals(1, servers.size)
        assertEquals("MY_CLIENT_123", servers[0].clientId)
        assertEquals(8910, servers[0].callbackPort)
    }

    @Test
    fun `transport inferred from url when type absent`(@TempDir tmp: Path) {
        val dotMcp = write(tmp.toFile(), ".mcp.json", """
            { "mcpServers": { "remote": { "url": "https://h/mcp", "headers": { "Authorization": "Bearer x" } } } }
        """.trimIndent())
        val servers = McpConfigReader.readFrom(tmp.toFile().path, dotMcp, null)
        assertEquals(McpTransport.HTTP, servers[0].transport)
        assertEquals(mapOf("Authorization" to "Bearer x"), servers[0].headers)
    }
}
