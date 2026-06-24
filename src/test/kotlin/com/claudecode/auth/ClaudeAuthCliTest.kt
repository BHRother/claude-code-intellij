package com.claudecode.auth

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ClaudeAuthCliTest {

    @Test
    fun `parses logged-in max subscription status`() {
        val json = """
            {
              "loggedIn": true,
              "authMethod": "claude.ai",
              "email": "user@example.com",
              "orgName": "user's Organization",
              "subscriptionType": "max"
            }
        """.trimIndent()
        val s = ClaudeAuthCli.parseStatus(json)!!
        assertTrue(s.loggedIn)
        assertEquals("user@example.com", s.email)
        assertEquals("max", s.subscriptionType)
        assertEquals("Claude Max · user@example.com", s.describe())
    }

    @Test
    fun `parses logged-out status`() {
        val s = ClaudeAuthCli.parseStatus("""{"loggedIn": false}""")!!
        assertFalse(s.loggedIn)
        assertEquals("Not signed in", s.describe())
    }

    @Test
    fun `tolerates missing fields`() {
        val s = ClaudeAuthCli.parseStatus("""{"loggedIn": true, "authMethod": "console"}""")!!
        assertTrue(s.loggedIn)
        assertNull(s.email)
        // No subscription → falls back to the auth method, no trailing separator.
        assertEquals("console", s.describe())
    }

    @Test
    fun `returns null on malformed json`() {
        assertNull(ClaudeAuthCli.parseStatus("not json"))
    }
}
