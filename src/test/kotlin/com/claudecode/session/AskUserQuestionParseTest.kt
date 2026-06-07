package com.claudecode.session

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AskUserQuestionParseTest {

    private val session = ClaudeSession("/tmp/project", "T")

    private fun parse(json: String) =
        session.parseAskUserQuestion(JsonParser.parseString(json).asJsonObject)

    @Test
    fun `parses a single single-select question`() {
        val qs = parse(
            """
            {"questions":[{"question":"How should I format?","header":"Format","multiSelect":false,
              "options":[{"label":"Summary","description":"Brief"},{"label":"Detailed","description":"Full"}]}]}
            """.trimIndent()
        )
        assertEquals(1, qs.size)
        val q = qs[0]
        assertEquals("How should I format?", q.question)
        assertEquals("Format", q.header)
        assertFalse(q.multiSelect)
        assertEquals(listOf("Summary", "Detailed"), q.options.map { it.label })
        assertEquals("Brief", q.options[0].description)
    }

    @Test
    fun `parses multiSelect flag and multiple questions`() {
        val qs = parse(
            """
            {"questions":[
              {"question":"Q1","header":"A","multiSelect":true,"options":[{"label":"x","description":""},{"label":"y","description":""}]},
              {"question":"Q2","header":"B","options":[{"label":"z","description":""}]}
            ]}
            """.trimIndent()
        )
        assertEquals(2, qs.size)
        assertTrue(qs[0].multiSelect)
        assertFalse(qs[1].multiSelect) // missing → default false
    }

    @Test
    fun `tolerates missing description and drops questions with no options`() {
        val qs = parse(
            """
            {"questions":[
              {"question":"Has options","header":"H","options":[{"label":"only-label"}]},
              {"question":"No options","header":"H2","options":[]},
              {"question":"Null options","header":"H3"}
            ]}
            """.trimIndent()
        )
        assertEquals(1, qs.size)
        assertEquals("Has options", qs[0].question)
        assertEquals("", qs[0].options[0].description)
    }

    @Test
    fun `empty or malformed input yields empty list`() {
        assertTrue(parse("""{}""").isEmpty())
        assertTrue(parse("""{"questions":[]}""").isEmpty())
        assertTrue(parse("""{"questions":"nope"}""").isEmpty())
        assertTrue(session.parseAskUserQuestion(null).isEmpty())
    }
}
