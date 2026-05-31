package com.claudecode.config

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SettingsEntryValueTest {

    @Test
    fun `quoted string parses as string`() {
        val e = SettingsEntryDialog.toJsonElement("\"acceptEdits\"")
        assertTrue(e.isJsonPrimitive && e.asJsonPrimitive.isString)
        assertEquals("acceptEdits", e.asString)
    }

    @Test
    fun `unquoted plain text is stored as a string`() {
        val e = SettingsEntryDialog.toJsonElement("acceptEdits")
        assertTrue(e.isJsonPrimitive && e.asJsonPrimitive.isString)
        assertEquals("acceptEdits", e.asString)
    }

    @Test
    fun `numbers and booleans parse as JSON scalars`() {
        assertEquals(42, SettingsEntryDialog.toJsonElement("42").asInt)
        assertTrue(SettingsEntryDialog.toJsonElement("true").asBoolean)
        assertFalse(SettingsEntryDialog.toJsonElement("false").asBoolean)
    }

    @Test
    fun `arrays and objects parse as JSON structures`() {
        val arr = SettingsEntryDialog.toJsonElement("""["Bash(git*)", "Read"]""")
        assertTrue(arr.isJsonArray)
        assertEquals(2, arr.asJsonArray.size())

        val obj = SettingsEntryDialog.toJsonElement("""{"allow":["Read"]}""")
        assertTrue(obj.isJsonObject)
        assertEquals("Read", obj.asJsonObject.getAsJsonArray("allow")[0].asString)
    }

    @Test
    fun `empty input becomes an empty string`() {
        val e = SettingsEntryDialog.toJsonElement("   ")
        assertTrue(e.isJsonPrimitive && e.asJsonPrimitive.isString)
        assertEquals("", e.asString)
    }

    @Test
    fun `malformed json-looking text falls back to a string`() {
        // Starts with '{' so it "looks like" JSON, but doesn't parse → string.
        val e = SettingsEntryDialog.toJsonElement("{not valid")
        assertTrue(e.isJsonPrimitive && e.asJsonPrimitive.isString)
        assertEquals("{not valid", e.asString)
    }
}
