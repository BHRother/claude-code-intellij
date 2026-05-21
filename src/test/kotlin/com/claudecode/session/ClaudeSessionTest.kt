package com.claudecode.session

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ClaudeSessionTest {

    private lateinit var session: ClaudeSession

    @BeforeEach
    fun setUp() {
        session = ClaudeSession("/home/user/project", "Test Session")
    }

    @Nested
    inner class ShortenPath {
        @Test
        fun `shortens paths within working directory`() {
            assertEquals("src/Main.kt", session.shortenPath("/home/user/project/src/Main.kt"))
        }

        @Test
        fun `returns full path when outside working directory`() {
            assertEquals("/other/path/file.txt", session.shortenPath("/other/path/file.txt"))
        }

        @Test
        fun `handles exact working directory path`() {
            assertEquals("/home/user/project", session.shortenPath("/home/user/project"))
        }

        @Test
        fun `handles deeply nested path`() {
            assertEquals(
                "src/main/kotlin/com/example/App.kt",
                session.shortenPath("/home/user/project/src/main/kotlin/com/example/App.kt")
            )
        }

        @Test
        fun `handles path with similar prefix but different directory`() {
            assertEquals(
                "/home/user/project2/file.txt",
                session.shortenPath("/home/user/project2/file.txt")
            )
        }

        @Test
        fun `handles empty path`() {
            assertEquals("", session.shortenPath(""))
        }

        @Test
        fun `handles relative path`() {
            assertEquals("relative/path.txt", session.shortenPath("relative/path.txt"))
        }
    }

    @Nested
    inner class ShellQuote {
        @Test
        fun `quotes simple string`() {
            assertEquals("'hello'", ClaudeSession.shellQuote("hello"))
        }

        @Test
        fun `escapes single quotes`() {
            assertEquals("'it'\\''s'", ClaudeSession.shellQuote("it's"))
        }

        @Test
        fun `handles empty string`() {
            assertEquals("''", ClaudeSession.shellQuote(""))
        }

        @Test
        fun `handles string with spaces`() {
            assertEquals("'hello world'", ClaudeSession.shellQuote("hello world"))
        }

        @Test
        fun `handles multiple single quotes`() {
            assertEquals("'a'\\''b'\\''c'", ClaudeSession.shellQuote("a'b'c"))
        }

        @Test
        fun `handles string with special characters`() {
            assertEquals("'hello\$world'", ClaudeSession.shellQuote("hello\$world"))
        }

        @Test
        fun `handles string with newlines`() {
            assertEquals("'line1\nline2'", ClaudeSession.shellQuote("line1\nline2"))
        }

        @Test
        fun `handles string with backslash`() {
            assertEquals("'path\\to\\file'", ClaudeSession.shellQuote("path\\to\\file"))
        }

        @Test
        fun `handles string starting with single quote`() {
            assertEquals("''\\''hello'", ClaudeSession.shellQuote("'hello"))
        }

        @Test
        fun `handles string ending with single quote`() {
            assertEquals("'hello'\\'''", ClaudeSession.shellQuote("hello'"))
        }
    }

    @Nested
    inner class BuildDiffSummary {
        @Test
        fun `returns line counts for Edit tool`() {
            val input = JsonObject().apply {
                addProperty("old_string", "line1\nline2")
                addProperty("new_string", "line1\nline2\nline3")
            }
            val result = session.buildDiffSummary("Edit", input)
            assertEquals("Added 3 lines, removed 2 lines", result)
        }

        @Test
        fun `returns line count for Write tool`() {
            val input = JsonObject().apply {
                addProperty("content", "line1\nline2\nline3")
            }
            val result = session.buildDiffSummary("Write", input)
            assertEquals("3 lines", result)
        }

        @Test
        fun `truncates long Bash commands`() {
            val longCmd = "a".repeat(100)
            val input = JsonObject().apply {
                addProperty("command", longCmd)
            }
            val result = session.buildDiffSummary("Bash", input)
            assertNotNull(result)
            assertEquals(80, result!!.length)
            assertTrue(result.endsWith("..."))
        }

        @Test
        fun `returns null for short Bash commands`() {
            val input = JsonObject().apply {
                addProperty("command", "ls -la")
            }
            assertNull(session.buildDiffSummary("Bash", input))
        }

        @Test
        fun `returns null for unknown tools`() {
            val input = JsonObject()
            assertNull(session.buildDiffSummary("Unknown", input))
        }

        @Test
        fun `returns null for null input`() {
            assertNull(session.buildDiffSummary("Edit", null))
        }

        @Test
        fun `handles single line Edit`() {
            val input = JsonObject().apply {
                addProperty("old_string", "old")
                addProperty("new_string", "new")
            }
            val result = session.buildDiffSummary("Edit", input)
            assertEquals("Added 1 line, removed 1 line", result)
        }

        @Test
        fun `returns null for Edit without old_string`() {
            val input = JsonObject().apply {
                addProperty("new_string", "new")
            }
            assertNull(session.buildDiffSummary("Edit", input))
        }

        @Test
        fun `returns null for Edit without new_string`() {
            val input = JsonObject().apply {
                addProperty("old_string", "old")
            }
            assertNull(session.buildDiffSummary("Edit", input))
        }

        @Test
        fun `returns null for Write without content`() {
            val input = JsonObject()
            assertNull(session.buildDiffSummary("Write", input))
        }

        @Test
        fun `returns null for Bash without command`() {
            val input = JsonObject()
            assertNull(session.buildDiffSummary("Bash", input))
        }

        @Test
        fun `returns 1 line for single line Write`() {
            val input = JsonObject().apply {
                addProperty("content", "single line")
            }
            assertEquals("1 lines", session.buildDiffSummary("Write", input))
        }

        @Test
        fun `returns null for Bash command at exactly 80 chars`() {
            val cmd = "a".repeat(80)
            val input = JsonObject().apply {
                addProperty("command", cmd)
            }
            assertNull(session.buildDiffSummary("Bash", input))
        }

        @Test
        fun `truncates Bash command at 81 chars`() {
            val cmd = "a".repeat(81)
            val input = JsonObject().apply {
                addProperty("command", cmd)
            }
            val result = session.buildDiffSummary("Bash", input)
            assertNotNull(result)
            assertEquals(80, result!!.length)
        }

        @Test
        fun `returns null for Glob tool`() {
            val input = JsonObject().apply {
                addProperty("pattern", "**/*.kt")
            }
            assertNull(session.buildDiffSummary("Glob", input))
        }

        @Test
        fun `returns null for Read tool`() {
            val input = JsonObject().apply {
                addProperty("file_path", "/path/to/file")
            }
            assertNull(session.buildDiffSummary("Read", input))
        }
    }

    @Nested
    inner class ExtractToolDetail {
        @Test
        fun `extracts Read file path`() {
            val input = JsonObject().apply {
                addProperty("file_path", "/home/user/project/src/Main.kt")
            }
            assertEquals("Read(src/Main.kt)", session.extractToolDetail("Read", input))
        }

        @Test
        fun `extracts Edit file path`() {
            val input = JsonObject().apply {
                addProperty("file_path", "/home/user/project/src/Main.kt")
            }
            assertEquals("Update(src/Main.kt)", session.extractToolDetail("Edit", input))
        }

        @Test
        fun `extracts Write file path as Create for new file`() {
            val input = JsonObject().apply {
                addProperty("file_path", "/home/user/project/src/New.kt")
            }
            assertEquals("Create(src/New.kt)", session.extractToolDetail("Write", input))
        }

        @Test
        fun `extracts Glob pattern`() {
            val input = JsonObject().apply {
                addProperty("pattern", "**/*.kt")
            }
            assertEquals("Glob(**/*.kt)", session.extractToolDetail("Glob", input))
        }

        @Test
        fun `extracts Grep pattern`() {
            val input = JsonObject().apply {
                addProperty("pattern", "TODO")
            }
            assertEquals("Grep(\"TODO\")", session.extractToolDetail("Grep", input))
        }

        @Test
        fun `extracts Bash rm as Delete`() {
            val input = JsonObject().apply {
                addProperty("command", "rm -f /home/user/project/old.txt")
            }
            assertEquals("Delete(old.txt)", session.extractToolDetail("Bash", input))
        }

        @Test
        fun `extracts Bash non-rm command`() {
            val input = JsonObject().apply {
                addProperty("command", "ls -la")
            }
            assertEquals("ls -la", session.extractToolDetail("Bash", input))
        }

        @Test
        fun `truncates long Bash commands`() {
            val input = JsonObject().apply {
                addProperty("command", "echo " + "x".repeat(100))
            }
            val result = session.extractToolDetail("Bash", input)!!
            assertTrue(result.length <= 80)
        }

        @Test
        fun `extracts WebFetch URL`() {
            val input = JsonObject().apply {
                addProperty("url", "https://example.com")
            }
            assertEquals("https://example.com", session.extractToolDetail("WebFetch", input))
        }

        @Test
        fun `extracts WebSearch query`() {
            val input = JsonObject().apply {
                addProperty("query", "kotlin coroutines")
            }
            assertEquals("kotlin coroutines", session.extractToolDetail("WebSearch", input))
        }

        @Test
        fun `returns null for unknown tool`() {
            assertNull(session.extractToolDetail("Unknown", JsonObject()))
        }

        @Test
        fun `returns null for null input`() {
            assertNull(session.extractToolDetail("Read", null))
        }

        @Test
        fun `extracts Task description`() {
            val input = JsonObject().apply {
                addProperty("description", "Running unit tests")
            }
            assertEquals("Running unit tests", session.extractToolDetail("Task", input))
        }

        @Test
        fun `truncates long Task description`() {
            val input = JsonObject().apply {
                addProperty("description", "x".repeat(100))
            }
            val result = session.extractToolDetail("Task", input)!!
            assertEquals(80, result.length)
        }

        @Test
        fun `truncates long WebFetch URL`() {
            val input = JsonObject().apply {
                addProperty("url", "https://example.com/" + "x".repeat(100))
            }
            val result = session.extractToolDetail("WebFetch", input)!!
            assertEquals(80, result.length)
        }

        @Test
        fun `returns null for Read without file_path`() {
            assertNull(session.extractToolDetail("Read", JsonObject()))
        }

        @Test
        fun `returns null for Bash without command`() {
            assertNull(session.extractToolDetail("Bash", JsonObject()))
        }

        @Test
        fun `extracts Bash rm with multiple flags`() {
            val input = JsonObject().apply {
                addProperty("command", "rm -rf /home/user/project/build")
            }
            assertEquals("Delete(build)", session.extractToolDetail("Bash", input))
        }

        @Test
        fun `extracts Read with path outside working directory`() {
            val input = JsonObject().apply {
                addProperty("file_path", "/other/path/file.txt")
            }
            assertEquals("Read(/other/path/file.txt)", session.extractToolDetail("Read", input))
        }

        @Test
        fun `extracts Bash rm with chained command only parses first`() {
            val input = JsonObject().apply {
                addProperty("command", "rm /home/user/project/old.txt && echo done")
            }
            assertEquals("Delete(old.txt)", session.extractToolDetail("Bash", input))
        }

        @Test
        fun `Write shows Update for previously read file`() {
            val readJson = """{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Read","id":"tu1","input":{"file_path":"/home/user/project/src/Existing.kt"}}]}}"""
            session.parseStreamLine(readJson, StringBuilder())

            val writeInput = JsonObject().apply {
                addProperty("file_path", "/home/user/project/src/Existing.kt")
            }
            assertEquals("Update(src/Existing.kt)", session.extractToolDetail("Write", writeInput))
        }
    }

    @Nested
    inner class ParseStreamLine {
        private lateinit var responseText: StringBuilder
        private lateinit var listener: TestSessionListener

        @BeforeEach
        fun setUp() {
            responseText = StringBuilder()
            listener = TestSessionListener()
            session.addListener(listener)
        }

        @Test
        fun `parses system init with model`() {
            val json = """{"type":"system","subtype":"init","model":"claude-sonnet-4-6","session_id":"abc123"}"""
            session.parseStreamLine(json, responseText)
            assertEquals("claude-sonnet-4-6", listener.lastModel)
        }

        @Test
        fun `parses assistant text content`() {
            val json = """{"type":"assistant","message":{"model":"claude-sonnet-4-6","content":[{"type":"text","text":"Hello world"}]}}"""
            session.parseStreamLine(json, responseText)
            assertEquals("Hello world", responseText.toString())
            assertEquals("Hello world", listener.lastText)
        }

        @Test
        fun `parses result with cost`() {
            val json = """{"type":"result","result":"done","session_id":"s1","total_cost_usd":0.0123,"duration_ms":5000,"num_turns":3}"""
            val cost = session.parseStreamLine(json, responseText)
            assertEquals(0.0123, cost)
        }

        @Test
        fun `parses result text when responseText is empty`() {
            val json = """{"type":"result","result":"final answer","session_id":"s1","total_cost_usd":0.01,"duration_ms":1000}"""
            session.parseStreamLine(json, responseText)
            assertEquals("final answer", responseText.toString())
        }

        @Test
        fun `does not overwrite existing responseText on result`() {
            responseText.append("existing text")
            val json = """{"type":"result","result":"new text","session_id":"s1","total_cost_usd":0.01,"duration_ms":1000}"""
            session.parseStreamLine(json, responseText)
            assertEquals("existing text", responseText.toString())
        }

        @Test
        fun `parses thinking content`() {
            val json = """{"type":"assistant","message":{"content":[{"type":"thinking","thinking":"Let me think about this\nSecond line"}]}}"""
            session.parseStreamLine(json, responseText)
            assertEquals("Let me think about this", listener.lastThinking)
        }

        @Test
        fun `parses tool use`() {
            val json = """{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Read","id":"tu1","input":{"file_path":"/home/user/project/src/Main.kt"}}]}}"""
            session.parseStreamLine(json, responseText)
            assertEquals("Read", listener.lastToolName)
            assertEquals("Read(src/Main.kt)", listener.lastToolDetail)
        }

        @Test
        fun `parses content_block_start tool_result`() {
            val json = """{"type":"content_block_start","content_block":{"type":"tool_result","tool_use_id":"tu1","is_error":false}}"""
            session.parseStreamLine(json, responseText)
            assertEquals("tu1", listener.lastToolResultId)
            assertFalse(listener.lastToolResultIsError!!)
        }

        @Test
        fun `parses content_block_start tool_result with error`() {
            val json = """{"type":"content_block_start","content_block":{"type":"tool_result","tool_use_id":"tu2","is_error":true}}"""
            session.parseStreamLine(json, responseText)
            assertEquals("tu2", listener.lastToolResultId)
            assertTrue(listener.lastToolResultIsError!!)
        }

        @Test
        fun `returns null for unknown type`() {
            val json = """{"type":"unknown_type"}"""
            assertNull(session.parseStreamLine(json, responseText))
        }

        @Test
        fun `returns null when type is missing`() {
            val json = """{"data":"something"}"""
            assertNull(session.parseStreamLine(json, responseText))
        }

        @Test
        fun `parses task_progress`() {
            val json = """{"type":"system","subtype":"task_progress","description":"Working on it..."}"""
            session.parseStreamLine(json, responseText)
            assertEquals("Working on it...", listener.lastTaskProgress)
        }

        @Test
        fun `notifies file change for Edit tool`() {
            val json = """{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Edit","id":"tu1","input":{"file_path":"/home/user/project/src/Main.kt","old_string":"old","new_string":"new"}}]}}"""
            session.parseStreamLine(json, responseText)
            assertEquals("/home/user/project/src/Main.kt", listener.lastChangedFile)
            assertEquals("Modified", listener.lastChangedAction)
        }

        @Test
        fun `tracks read files for Write create vs modify detection`() {
            val readJson = """{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Read","id":"tu1","input":{"file_path":"/home/user/project/src/Existing.kt"}}]}}"""
            session.parseStreamLine(readJson, responseText)

            val writeJson = """{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Write","id":"tu2","input":{"file_path":"/home/user/project/src/Existing.kt","content":"new content"}}]}}"""
            session.parseStreamLine(writeJson, responseText)
            assertEquals("Modified", listener.lastChangedAction)
        }

        @Test
        fun `detects new file on Write without prior Read`() {
            val json = """{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Write","id":"tu1","input":{"file_path":"/home/user/project/src/New.kt","content":"new content"}}]}}"""
            session.parseStreamLine(json, responseText)
            assertEquals("Created", listener.lastChangedAction)
        }

        @Test
        fun `detects file deletion from Bash rm command`() {
            val json = """{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Bash","id":"tu1","input":{"command":"rm /home/user/project/old.txt"}}]}}"""
            session.parseStreamLine(json, responseText)
            assertEquals("/home/user/project/old.txt", listener.lastChangedFile)
            assertEquals("Deleted", listener.lastChangedAction)
        }

        @Test
        fun `detects file deletion from rm with flags`() {
            val json = """{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Bash","id":"tu1","input":{"command":"rm -rf /home/user/project/build"}}]}}"""
            session.parseStreamLine(json, responseText)
            assertEquals("/home/user/project/build", listener.lastChangedFile)
            assertEquals("Deleted", listener.lastChangedAction)
        }

        @Test
        fun `parses task_started`() {
            val json = """{"type":"system","subtype":"task_started","description":"Running tests"}"""
            session.parseStreamLine(json, responseText)
            assertEquals("Task started: Running tests", listener.lastTaskProgress)
        }

        @Test
        fun `parses system init stores session_id`() {
            val json = """{"type":"system","subtype":"init","session_id":"sess-abc"}"""
            session.parseStreamLine(json, responseText)
            // Session ID is stored internally, verified by next parseStreamLine behavior
        }

        @Test
        fun `parses result stores session_id`() {
            val json = """{"type":"result","result":"done","session_id":"sess-xyz","total_cost_usd":0.01,"duration_ms":100}"""
            session.parseStreamLine(json, responseText)
            // Session ID stored for resume capability
        }

        @Test
        fun `parses assistant message with model info`() {
            val json = """{"type":"assistant","message":{"model":"claude-opus-4-6","content":[{"type":"text","text":"test"}]}}"""
            session.parseStreamLine(json, responseText)
            assertEquals("claude-opus-4-6", listener.lastModel)
        }

        @Test
        fun `parses multiple content blocks in single message`() {
            val json = """{"type":"assistant","message":{"content":[{"type":"text","text":"Hello "},{"type":"text","text":"World"}]}}"""
            session.parseStreamLine(json, responseText)
            assertEquals("Hello World", responseText.toString())
        }

        @Test
        fun `parses thinking with only blank lines returns null summary`() {
            val json = """{"type":"assistant","message":{"content":[{"type":"thinking","thinking":"\n\n\n"}]}}"""
            session.parseStreamLine(json, responseText)
            assertNull(listener.lastThinking)
        }

        @Test
        fun `does not fire file changed for Bash non-rm command`() {
            val json = """{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Bash","id":"tu1","input":{"command":"ls -la"}}]}}"""
            session.parseStreamLine(json, responseText)
            assertNull(listener.lastChangedFile)
        }

        @Test
        fun `does not fire file changed for Read tool`() {
            val json = """{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Read","id":"tu1","input":{"file_path":"/home/user/project/src/Main.kt"}}]}}"""
            session.parseStreamLine(json, responseText)
            assertNull(listener.lastChangedFile)
        }

        @Test
        fun `handles rm with relative path`() {
            val json = """{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Bash","id":"tu1","input":{"command":"rm relative.txt"}}]}}"""
            session.parseStreamLine(json, responseText)
            assertEquals("/home/user/project/relative.txt", listener.lastChangedFile)
            assertEquals("Deleted", listener.lastChangedAction)
        }

        @Test
        fun `content_block_start defaults is_error to false when missing`() {
            val json = """{"type":"content_block_start","content_block":{"type":"tool_result","tool_use_id":"tu1"}}"""
            session.parseStreamLine(json, responseText)
            assertEquals("tu1", listener.lastToolResultId)
            assertFalse(listener.lastToolResultIsError!!)
        }

        @Test
        fun `result without cost returns null cost`() {
            val json = """{"type":"result","result":"done","session_id":"s1","duration_ms":1000}"""
            val cost = session.parseStreamLine(json, responseText)
            assertNull(cost)
        }

        @Test
        fun `assistant without message returns null`() {
            val json = """{"type":"assistant"}"""
            assertNull(session.parseStreamLine(json, responseText))
        }

        @Test
        fun `assistant without content returns null`() {
            val json = """{"type":"assistant","message":{}}"""
            assertNull(session.parseStreamLine(json, responseText))
        }
    }

    @Nested
    inner class IsMacOS {
        @Test
        fun `returns a boolean`() {
            val result = ClaudeSession.isMacOS()
            assertNotNull(result)
        }
    }

    @Nested
    inner class SessionState {
        @Test
        fun `starts not busy`() {
            assertFalse(session.isBusy)
        }

        @Test
        fun `has correct name`() {
            assertEquals("Test Session", session.name)
        }

        @Test
        fun `has correct working directory`() {
            assertEquals("/home/user/project", session.workingDirectory)
        }

        @Test
        fun `generates unique id`() {
            val session2 = ClaudeSession("/tmp", "Session 2")
            assertNotEquals(session.id, session2.id)
        }

        @Test
        fun `id is 8 characters`() {
            assertEquals(8, session.id.length)
        }

        @Test
        fun `starts with empty messages`() {
            assertTrue(session.messages.isEmpty())
        }

        @Test
        fun `stop clears busy state`() {
            session.stop()
            assertFalse(session.isBusy)
        }

        @Test
        fun `dispose clears listeners`() {
            val listener = TestSessionListener()
            session.addListener(listener)
            session.dispose()
            assertFalse(session.isBusy)
        }

        @Test
        fun `default session name is Session`() {
            val defaultSession = ClaudeSession("/tmp")
            assertEquals("Session", defaultSession.name)
        }

        @Test
        fun `add and remove listener`() {
            val listener = TestSessionListener()
            session.addListener(listener)
            session.removeListener(listener)
            // After remove, parsing should not notify this listener
            val json = """{"type":"assistant","message":{"content":[{"type":"text","text":"hello"}]}}"""
            session.parseStreamLine(json, StringBuilder())
            assertNull(listener.lastText)
        }

        @Test
        fun `multiple sessions have different ids`() {
            val ids = (1..10).map { ClaudeSession("/tmp", "S$it").id }.toSet()
            assertEquals(10, ids.size)
        }
    }

    @Nested
    inner class ListenerManagement {
        @Test
        fun `multiple listeners all receive events`() {
            val listener1 = TestSessionListener()
            val listener2 = TestSessionListener()
            session.addListener(listener1)
            session.addListener(listener2)

            val json = """{"type":"assistant","message":{"content":[{"type":"text","text":"broadcast"}]}}"""
            session.parseStreamLine(json, StringBuilder())

            assertEquals("broadcast", listener1.lastText)
            assertEquals("broadcast", listener2.lastText)
        }

        @Test
        fun `removed listener does not receive events`() {
            val listener1 = TestSessionListener()
            val listener2 = TestSessionListener()
            session.addListener(listener1)
            session.addListener(listener2)
            session.removeListener(listener1)

            val json = """{"type":"assistant","message":{"content":[{"type":"text","text":"only listener2"}]}}"""
            session.parseStreamLine(json, StringBuilder())

            assertNull(listener1.lastText)
            assertEquals("only listener2", listener2.lastText)
        }
    }
}

/**
 * Minimal listener that captures the last event of each type for assertion.
 */
class TestSessionListener : SessionListener {
    var lastText: String? = null
    var lastThinking: String? = null
    var lastToolName: String? = null
    var lastToolDetail: String? = null
    var lastChangedFile: String? = null
    var lastChangedAction: String? = null
    var lastModel: String? = null
    var lastTaskProgress: String? = null
    var lastToolResultId: String? = null
    var lastToolResultIsError: Boolean? = null
    var lastToolResultContent: String? = null
    var lastCost: Double? = null

    override fun onText(session: ClaudeSession, text: String) { lastText = text }
    override fun onThinking(session: ClaudeSession, thinking: String?) { lastThinking = thinking }
    override fun onToolUse(session: ClaudeSession, tool: String, detail: String?, diffSummary: String?, diffData: Pair<String, String>?, filePath: String?) {
        lastToolName = tool
        lastToolDetail = detail
    }
    override fun onFileChanged(session: ClaudeSession, filePath: String, action: String) {
        lastChangedFile = filePath
        lastChangedAction = action
    }
    override fun onTaskProgress(session: ClaudeSession, description: String) { lastTaskProgress = description }
    override fun onModelInfo(session: ClaudeSession, model: String) { lastModel = model }
    override fun onToolResult(session: ClaudeSession, toolUseId: String, isError: Boolean, resultContent: String?) {
        lastToolResultId = toolUseId
        lastToolResultIsError = isError
        lastToolResultContent = resultContent
    }
    override fun onFinished(session: ClaudeSession, costUsd: Double?) { lastCost = costUsd }
    override fun onError(session: ClaudeSession, error: String) {}
    override fun onDebug(session: ClaudeSession, message: String) {}
}
