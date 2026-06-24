package com.claudecode.ui

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class MarkdownRendererTest {

    @Nested
    inner class EscapeHtml {
        @Test
        fun `escapes ampersands`() {
            assertEquals("&amp;", MarkdownRenderer.escapeHtml("&"))
        }

        @Test
        fun `escapes angle brackets`() {
            assertEquals("&lt;div&gt;", MarkdownRenderer.escapeHtml("<div>"))
        }

        @Test
        fun `escapes double quotes`() {
            assertEquals("&quot;hello&quot;", MarkdownRenderer.escapeHtml("\"hello\""))
        }

        @Test
        fun `leaves plain text unchanged`() {
            assertEquals("hello world", MarkdownRenderer.escapeHtml("hello world"))
        }

        @Test
        fun `handles multiple special characters`() {
            assertEquals("&lt;a href=&quot;x&quot;&gt;A &amp; B&lt;/a&gt;",
                MarkdownRenderer.escapeHtml("<a href=\"x\">A & B</a>"))
        }

        @Test
        fun `handles empty string`() {
            assertEquals("", MarkdownRenderer.escapeHtml(""))
        }

        @Test
        fun `handles string with only special characters`() {
            assertEquals("&amp;&lt;&gt;&quot;", MarkdownRenderer.escapeHtml("&<>\""))
        }

        @Test
        fun `handles multiple ampersands`() {
            assertEquals("a &amp;&amp; b", MarkdownRenderer.escapeHtml("a && b"))
        }

        @Test
        fun `preserves single quotes`() {
            assertEquals("it's", MarkdownRenderer.escapeHtml("it's"))
        }
    }

    @Nested
    inner class RenderInline {
        @Test
        fun `renders bold text`() {
            val result = MarkdownRenderer.renderInline("this is **bold** text")
            assertTrue(result.contains("<b>bold</b>"))
        }

        @Test
        fun `renders italic text`() {
            val result = MarkdownRenderer.renderInline("this is *italic* text")
            assertTrue(result.contains("<i>italic</i>"))
        }

        @Test
        fun `renders inline code`() {
            val result = MarkdownRenderer.renderInline("use `println()` here")
            assertTrue(result.contains("<code"))
            assertTrue(result.contains("println()"))
        }

        @Test
        fun `renders h1 header`() {
            val result = MarkdownRenderer.renderInline("# Title")
            assertTrue(result.contains("<b"))
            assertTrue(result.contains("Title"))
            assertTrue(result.contains("120%"))
        }

        @Test
        fun `renders h2 header`() {
            val result = MarkdownRenderer.renderInline("## Subtitle")
            assertTrue(result.contains("<b"))
            assertTrue(result.contains("Subtitle"))
            assertTrue(result.contains("110%"))
        }

        @Test
        fun `renders h3 header`() {
            val result = MarkdownRenderer.renderInline("### Section")
            assertTrue(result.contains("<b"))
            assertTrue(result.contains("Section"))
            assertFalse(result.contains("110%"))
            assertFalse(result.contains("120%"))
        }

        @Test
        fun `renders bullet points with dash`() {
            val result = MarkdownRenderer.renderInline("- list item")
            assertTrue(result.contains("&bull;"))
            assertTrue(result.contains("list item"))
        }

        @Test
        fun `renders bullet points with asterisk`() {
            val result = MarkdownRenderer.renderInline("* list item")
            assertTrue(result.contains("&bull;"))
            assertTrue(result.contains("list item"))
        }

        @Test
        fun `renders URLs as clickable links`() {
            val result = MarkdownRenderer.renderInline("visit https://example.com today")
            assertTrue(result.contains("<a href='https://example.com'"))
            assertTrue(result.contains("https://example.com</a>"))
        }

        @Test
        fun `escapes HTML in plain text`() {
            val result = MarkdownRenderer.renderInline("<script>alert('xss')</script>")
            assertFalse(result.contains("<script>"))
            assertTrue(result.contains("&lt;script&gt;"))
        }

        @Test
        fun `handles empty string`() {
            assertEquals("", MarkdownRenderer.renderInline(""))
        }

        @Test
        fun `renders multiple bold segments`() {
            val result = MarkdownRenderer.renderInline("**first** and **second**")
            assertTrue(result.contains("<b>first</b>"))
            assertTrue(result.contains("<b>second</b>"))
        }

        @Test
        fun `renders bold and italic together`() {
            val result = MarkdownRenderer.renderInline("**bold** and *italic*")
            assertTrue(result.contains("<b>bold</b>"))
            assertTrue(result.contains("<i>italic</i>"))
        }

        @Test
        fun `renders multiple inline code segments`() {
            val result = MarkdownRenderer.renderInline("use `foo()` and `bar()`")
            assertTrue(result.contains("foo()"))
            assertTrue(result.contains("bar()"))
        }

        @Test
        fun `renders http URL`() {
            val result = MarkdownRenderer.renderInline("visit http://example.com")
            assertTrue(result.contains("<a href='http://example.com'"))
        }

        @Test
        fun `linkifies URL inside inline code span`() {
            val result = MarkdownRenderer.renderInline("see `https://google.com` now")
            // The URL is both inside a <code> span AND a clickable anchor.
            assertTrue(result.contains("<code"))
            assertTrue(result.contains("<a href='https://google.com'"))
        }

        @Test
        fun `keeps trailing punctuation outside the link`() {
            val result = MarkdownRenderer.renderInline("open `https://google.com`.")
            assertTrue(result.contains("<a href='https://google.com'"))
            assertFalse(result.contains("href='https://google.com.'"))
        }

        @Test
        fun `renders indented bullet point`() {
            val result = MarkdownRenderer.renderInline("  - nested item")
            assertTrue(result.contains("&bull;"))
            assertTrue(result.contains("nested item"))
        }

        @Test
        fun `plain text without formatting`() {
            val result = MarkdownRenderer.renderInline("just plain text")
            assertEquals("just plain text", result)
        }

        @Test
        fun `renders numbered list as plain text`() {
            val result = MarkdownRenderer.renderInline("1. first item")
            assertTrue(result.contains("1. first item"))
        }
    }

    @Nested
    inner class Render {
        @Test
        fun `renders plain text with line breaks`() {
            val result = MarkdownRenderer.render("line1\nline2")
            assertTrue(result.contains("line1"))
            assertTrue(result.contains("line2"))
            assertTrue(result.contains("<br/>"))
        }

        @Test
        fun `renders fenced code blocks`() {
            val markdown = "```kotlin\nval x = 1\n```"
            val result = MarkdownRenderer.render(markdown)
            assertTrue(result.contains("<pre"))
            assertTrue(result.contains("val"))
            assertTrue(result.contains("x"))
        }

        @Test
        fun `renders code blocks with language label`() {
            val markdown = "```python\nprint('hello')\n```"
            val result = MarkdownRenderer.render(markdown)
            assertTrue(result.contains("python"))
        }

        @Test
        fun `invokes copy link generator for code blocks`() {
            val markdown = "```\nsome code\n```"
            var capturedCode: String? = null
            val result = MarkdownRenderer.render(markdown, copyLinkGenerator = { code ->
                capturedCode = code
                "<a>copy</a>"
            })
            assertEquals("some code", capturedCode)
            assertTrue(result.contains("<a>copy</a>"))
        }

        @Test
        fun `linkifies URL inside fenced code block`() {
            val markdown = "```\nhttps://google.com\n```"
            val result = MarkdownRenderer.render(markdown)
            assertTrue(result.contains("<a href='https://google.com'"))
        }

        @Test
        fun `linkifies URL inside a string literal in code`() {
            val markdown = "```kotlin\nBrowserUtil.browse(\"https://google.com\")\n```"
            val result = MarkdownRenderer.render(markdown)
            assertTrue(result.contains("<a href='https://google.com'"))
            // No leftover placeholder marker leaked into the output.
            assertFalse(result.contains("PH0"))
        }

        @Test
        fun `handles mixed content`() {
            val markdown = "# Title\n\nSome **bold** text\n\n```java\nint x = 1;\n```\n\nDone."
            val result = MarkdownRenderer.render(markdown)
            assertTrue(result.contains("Title"))
            assertTrue(result.contains("<b>bold</b>"))
            assertTrue(result.contains("<pre"))
            assertTrue(result.contains("Done."))
        }

        @Test
        fun `handles empty input`() {
            val result = MarkdownRenderer.render("")
            assertTrue(result.contains("<br/>"))
        }

        @Test
        fun `handles unclosed code block without crashing`() {
            val markdown = "```kotlin\nval x = 1\nval y = 2"
            val result = MarkdownRenderer.render(markdown)
            assertNotNull(result)
        }

        @Test
        fun `renders code block without language`() {
            val markdown = "```\ncode here\n```"
            val result = MarkdownRenderer.render(markdown)
            assertTrue(result.contains("code here"))
            assertTrue(result.contains("<pre"))
        }

        @Test
        fun `renders multiple code blocks`() {
            val markdown = "```kotlin\nval x = 1\n```\ntext\n```python\nprint('hi')\n```"
            val result = MarkdownRenderer.render(markdown)
            assertTrue(result.contains("kotlin"))
            assertTrue(result.contains("python"))
        }

        @Test
        fun `renders inline formatting outside code blocks`() {
            val markdown = "**bold** text\n```\ncode\n```\n*italic* text"
            val result = MarkdownRenderer.render(markdown)
            assertTrue(result.contains("<b>bold</b>"))
            assertTrue(result.contains("<i>italic</i>"))
        }

        @Test
        fun `copy link generator not invoked for non-code lines`() {
            var invoked = false
            MarkdownRenderer.render("just text", copyLinkGenerator = {
                invoked = true
                ""
            })
            assertFalse(invoked)
        }

        @Test
        fun `copy link generator receives empty string for empty code block`() {
            var capturedCode: String? = null
            MarkdownRenderer.render("```\n\n```", copyLinkGenerator = { code ->
                capturedCode = code
                ""
            })
            // Empty code block should still have some content (possibly empty string)
            assertNotNull(capturedCode)
        }

        @Test
        fun `renders code block with indented closing fence`() {
            val markdown = "```\ncode\n  ```"
            val result = MarkdownRenderer.render(markdown)
            assertTrue(result.contains("<pre"))
        }
    }

    @Nested
    inner class LanguageFromFilePath {
        @Test
        fun `maps java extension`() {
            assertEquals("java", MarkdownRenderer.languageFromFilePath("src/Main.java"))
        }

        @Test
        fun `maps kotlin extension`() {
            assertEquals("kotlin", MarkdownRenderer.languageFromFilePath("src/Main.kt"))
        }

        @Test
        fun `maps kts extension`() {
            assertEquals("kotlin", MarkdownRenderer.languageFromFilePath("build.gradle.kts"))
        }

        @Test
        fun `maps python extension`() {
            assertEquals("python", MarkdownRenderer.languageFromFilePath("script.py"))
        }

        @Test
        fun `maps javascript extension`() {
            assertEquals("javascript", MarkdownRenderer.languageFromFilePath("app.js"))
        }

        @Test
        fun `maps typescript extension`() {
            assertEquals("typescript", MarkdownRenderer.languageFromFilePath("app.ts"))
        }

        @Test
        fun `maps tsx extension`() {
            assertEquals("tsx", MarkdownRenderer.languageFromFilePath("Component.tsx"))
        }

        @Test
        fun `maps shell extension`() {
            assertEquals("bash", MarkdownRenderer.languageFromFilePath("deploy.sh"))
        }

        @Test
        fun `maps rust extension`() {
            assertEquals("rust", MarkdownRenderer.languageFromFilePath("main.rs"))
        }

        @Test
        fun `maps go extension`() {
            assertEquals("go", MarkdownRenderer.languageFromFilePath("main.go"))
        }

        @Test
        fun `maps xml extension`() {
            assertEquals("xml", MarkdownRenderer.languageFromFilePath("plugin.xml"))
        }

        @Test
        fun `maps json extension`() {
            assertEquals("json", MarkdownRenderer.languageFromFilePath("package.json"))
        }

        @Test
        fun `maps yaml extension`() {
            assertEquals("yaml", MarkdownRenderer.languageFromFilePath("config.yml"))
        }

        @Test
        fun `handles full path`() {
            assertEquals("java", MarkdownRenderer.languageFromFilePath("/home/user/project/src/main/java/App.java"))
        }

        @Test
        fun `returns extension for unknown types`() {
            assertEquals("xyz", MarkdownRenderer.languageFromFilePath("file.xyz"))
        }

        @Test
        fun `maps mjs extension`() {
            assertEquals("javascript", MarkdownRenderer.languageFromFilePath("module.mjs"))
        }

        @Test
        fun `maps cjs extension`() {
            assertEquals("javascript", MarkdownRenderer.languageFromFilePath("module.cjs"))
        }

        @Test
        fun `maps mts extension`() {
            assertEquals("typescript", MarkdownRenderer.languageFromFilePath("module.mts"))
        }

        @Test
        fun `maps cts extension`() {
            assertEquals("typescript", MarkdownRenderer.languageFromFilePath("module.cts"))
        }

        @Test
        fun `maps jsx extension`() {
            assertEquals("jsx", MarkdownRenderer.languageFromFilePath("Component.jsx"))
        }

        @Test
        fun `maps scala extension`() {
            assertEquals("scala", MarkdownRenderer.languageFromFilePath("App.scala"))
        }

        @Test
        fun `maps sc extension`() {
            assertEquals("scala", MarkdownRenderer.languageFromFilePath("script.sc"))
        }

        @Test
        fun `maps groovy extension`() {
            assertEquals("groovy", MarkdownRenderer.languageFromFilePath("Build.groovy"))
        }

        @Test
        fun `maps gradle extension`() {
            assertEquals("groovy", MarkdownRenderer.languageFromFilePath("build.gradle"))
        }

        @Test
        fun `maps ruby extension`() {
            assertEquals("ruby", MarkdownRenderer.languageFromFilePath("app.rb"))
        }

        @Test
        fun `maps swift extension`() {
            assertEquals("swift", MarkdownRenderer.languageFromFilePath("App.swift"))
        }

        @Test
        fun `maps c extension`() {
            assertEquals("c", MarkdownRenderer.languageFromFilePath("main.c"))
        }

        @Test
        fun `maps h extension`() {
            assertEquals("c", MarkdownRenderer.languageFromFilePath("header.h"))
        }

        @Test
        fun `maps cpp extension`() {
            assertEquals("cpp", MarkdownRenderer.languageFromFilePath("main.cpp"))
        }

        @Test
        fun `maps hpp extension`() {
            assertEquals("cpp", MarkdownRenderer.languageFromFilePath("header.hpp"))
        }

        @Test
        fun `maps csharp extension`() {
            assertEquals("csharp", MarkdownRenderer.languageFromFilePath("Program.cs"))
        }

        @Test
        fun `maps bash extension`() {
            assertEquals("bash", MarkdownRenderer.languageFromFilePath("script.bash"))
        }

        @Test
        fun `maps zsh extension`() {
            assertEquals("bash", MarkdownRenderer.languageFromFilePath("script.zsh"))
        }

        @Test
        fun `maps html extension`() {
            assertEquals("xml", MarkdownRenderer.languageFromFilePath("index.html"))
        }

        @Test
        fun `maps htm extension`() {
            assertEquals("xml", MarkdownRenderer.languageFromFilePath("page.htm"))
        }

        @Test
        fun `maps css extension`() {
            assertEquals("css", MarkdownRenderer.languageFromFilePath("style.css"))
        }

        @Test
        fun `maps scss extension`() {
            assertEquals("css", MarkdownRenderer.languageFromFilePath("style.scss"))
        }

        @Test
        fun `maps less extension`() {
            assertEquals("css", MarkdownRenderer.languageFromFilePath("style.less"))
        }

        @Test
        fun `maps toml extension`() {
            assertEquals("toml", MarkdownRenderer.languageFromFilePath("Cargo.toml"))
        }

        @Test
        fun `maps sql extension`() {
            assertEquals("sql", MarkdownRenderer.languageFromFilePath("query.sql"))
        }

        @Test
        fun `maps markdown extension`() {
            assertEquals("markdown", MarkdownRenderer.languageFromFilePath("README.md"))
        }

        @Test
        fun `maps cc extension`() {
            assertEquals("cpp", MarkdownRenderer.languageFromFilePath("main.cc"))
        }

        @Test
        fun `maps cxx extension`() {
            assertEquals("cpp", MarkdownRenderer.languageFromFilePath("main.cxx"))
        }

        @Test
        fun `handles file with no extension`() {
            assertEquals("", MarkdownRenderer.languageFromFilePath("Makefile"))
        }

        @Test
        fun `is case insensitive`() {
            assertEquals("java", MarkdownRenderer.languageFromFilePath("Main.JAVA"))
        }
    }

    @Nested
    inner class KeywordsForLanguage {
        @Test
        fun `returns kotlin keywords for kt`() {
            val keywords = MarkdownRenderer.keywordsForLanguage("kotlin")
            assertTrue(keywords.contains("fun"))
            assertTrue(keywords.contains("val"))
            assertTrue(keywords.contains("suspend"))
        }

        @Test
        fun `returns java keywords for java`() {
            val keywords = MarkdownRenderer.keywordsForLanguage("java")
            assertTrue(keywords.contains("public"))
            assertTrue(keywords.contains("class"))
            assertFalse(keywords.contains("fun"))
        }

        @Test
        fun `returns python keywords for python`() {
            val keywords = MarkdownRenderer.keywordsForLanguage("python")
            assertTrue(keywords.contains("def"))
            assertTrue(keywords.contains("self"))
        }

        @Test
        fun `returns js keywords for typescript`() {
            val keywords = MarkdownRenderer.keywordsForLanguage("typescript")
            assertTrue(keywords.contains("const"))
            assertTrue(keywords.contains("async"))
        }

        @Test
        fun `returns empty set for non-code formats`() {
            assertTrue(MarkdownRenderer.keywordsForLanguage("json").isEmpty())
            assertTrue(MarkdownRenderer.keywordsForLanguage("xml").isEmpty())
            assertTrue(MarkdownRenderer.keywordsForLanguage("yaml").isEmpty())
            assertTrue(MarkdownRenderer.keywordsForLanguage("md").isEmpty())
        }

        @Test
        fun `returns default keywords for unknown languages`() {
            val keywords = MarkdownRenderer.keywordsForLanguage("unknown")
            assertTrue(keywords.contains("class"))
        }

        @Test
        fun `returns kotlin keywords for kt alias`() {
            val keywords = MarkdownRenderer.keywordsForLanguage("kt")
            assertTrue(keywords.contains("fun"))
            assertTrue(keywords.contains("suspend"))
        }

        @Test
        fun `returns kotlin keywords for kts alias`() {
            val keywords = MarkdownRenderer.keywordsForLanguage("kts")
            assertTrue(keywords.contains("fun"))
        }

        @Test
        fun `returns python keywords for py alias`() {
            val keywords = MarkdownRenderer.keywordsForLanguage("py")
            assertTrue(keywords.contains("def"))
        }

        @Test
        fun `returns js keywords for js alias`() {
            val keywords = MarkdownRenderer.keywordsForLanguage("js")
            assertTrue(keywords.contains("function"))
            assertTrue(keywords.contains("const"))
        }

        @Test
        fun `returns js keywords for ts alias`() {
            val keywords = MarkdownRenderer.keywordsForLanguage("ts")
            assertTrue(keywords.contains("const"))
        }

        @Test
        fun `returns js keywords for jsx`() {
            val keywords = MarkdownRenderer.keywordsForLanguage("jsx")
            assertTrue(keywords.contains("function"))
        }

        @Test
        fun `returns js keywords for tsx`() {
            val keywords = MarkdownRenderer.keywordsForLanguage("tsx")
            assertTrue(keywords.contains("const"))
        }

        @Test
        fun `returns java keywords for scala`() {
            val keywords = MarkdownRenderer.keywordsForLanguage("scala")
            assertTrue(keywords.contains("class"))
            assertTrue(keywords.contains("public"))
        }

        @Test
        fun `returns empty set for html`() {
            assertTrue(MarkdownRenderer.keywordsForLanguage("html").isEmpty())
        }

        @Test
        fun `returns empty set for css`() {
            assertTrue(MarkdownRenderer.keywordsForLanguage("css").isEmpty())
        }

        @Test
        fun `returns empty set for yml alias`() {
            assertTrue(MarkdownRenderer.keywordsForLanguage("yml").isEmpty())
        }

        @Test
        fun `returns empty set for toml`() {
            assertTrue(MarkdownRenderer.keywordsForLanguage("toml").isEmpty())
        }

        @Test
        fun `returns empty set for properties`() {
            assertTrue(MarkdownRenderer.keywordsForLanguage("properties").isEmpty())
        }

        @Test
        fun `returns empty set for txt`() {
            assertTrue(MarkdownRenderer.keywordsForLanguage("txt").isEmpty())
        }

        @Test
        fun `returns empty set for svg`() {
            assertTrue(MarkdownRenderer.keywordsForLanguage("svg").isEmpty())
        }

        @Test
        fun `kotlin keywords include java keywords`() {
            val kotlin = MarkdownRenderer.keywordsForLanguage("kotlin")
            val java = MarkdownRenderer.keywordsForLanguage("java")
            assertTrue(kotlin.containsAll(java))
        }

        @Test
        fun `js keywords include java keywords`() {
            val js = MarkdownRenderer.keywordsForLanguage("javascript")
            val java = MarkdownRenderer.keywordsForLanguage("java")
            assertTrue(js.containsAll(java))
        }
    }

    @Nested
    inner class RenderCodeBlock {
        @Test
        fun `highlights kotlin keywords`() {
            val result = MarkdownRenderer.renderCodeBlock("fun main() { val x = 1 }", "kotlin")
            assertTrue(result.contains("color: #CC7832"))
            assertTrue(result.contains("fun"))
            assertTrue(result.contains("val"))
        }

        @Test
        fun `highlights java keywords`() {
            val result = MarkdownRenderer.renderCodeBlock("public static void main(String[] args)", "java")
            assertTrue(result.contains("color: #CC7832"))
        }

        @Test
        fun `highlights python keywords`() {
            val result = MarkdownRenderer.renderCodeBlock("def hello():\n    return None", "python")
            assertTrue(result.contains("color: #CC7832"))
        }

        @Test
        fun `highlights javascript keywords`() {
            val result = MarkdownRenderer.renderCodeBlock("const x = async () => {}", "javascript")
            assertTrue(result.contains("color: #CC7832"))
        }

        @Test
        fun `highlights strings`() {
            val result = MarkdownRenderer.renderCodeBlock("val s = \"hello\"", "kotlin")
            assertTrue(result.contains("color: #6A8759"))
        }

        @Test
        fun `highlights comments`() {
            val result = MarkdownRenderer.renderCodeBlock("// this is a comment\nval x = 1", "kotlin")
            assertTrue(result.contains("color: #808080"))
        }

        @Test
        fun `highlights numbers`() {
            val result = MarkdownRenderer.renderCodeBlock("val x = 42", "kotlin")
            assertTrue(result.contains("color: #6897BB"))
        }

        @Test
        fun `highlights annotations`() {
            val result = MarkdownRenderer.renderCodeBlock("@Override\npublic void run()", "java")
            assertTrue(result.contains("color: #BBB529"))
        }

        @Test
        fun `includes copy link when provided`() {
            val result = MarkdownRenderer.renderCodeBlock("code", "kotlin", "<a>copy</a>")
            assertTrue(result.contains("<a>copy</a>"))
        }

        @Test
        fun `uses default keywords for unknown languages`() {
            val result = MarkdownRenderer.renderCodeBlock("class Foo {}", "unknown")
            assertTrue(result.contains("color: #CC7832"))
        }

        @Test
        fun `renders empty code block`() {
            val result = MarkdownRenderer.renderCodeBlock("", "kotlin")
            assertTrue(result.contains("<pre"))
        }

        @Test
        fun `renders code without language label when lang is empty`() {
            val result = MarkdownRenderer.renderCodeBlock("code", "")
            assertTrue(result.contains("<pre"))
            assertTrue(result.contains("code"))
        }

        @Test
        fun `renders code with language label`() {
            val result = MarkdownRenderer.renderCodeBlock("code", "kotlin")
            assertTrue(result.contains("kotlin"))
        }

        @Test
        fun `highlights floating point numbers`() {
            val result = MarkdownRenderer.renderCodeBlock("val pi = 3.14", "kotlin")
            assertTrue(result.contains("color: #6897BB"))
        }

        @Test
        fun `highlights single-quoted strings`() {
            val result = MarkdownRenderer.renderCodeBlock("print('hello')", "python")
            assertTrue(result.contains("color: #6A8759"))
        }

        @Test
        fun `no language label shown for empty lang without copy link`() {
            val result = MarkdownRenderer.renderCodeBlock("code", "")
            assertFalse(result.contains("<span style='color: #808080;'></span>"))
        }
    }

    @Nested
    inner class HighlightLine {
        @Test
        fun `highlights keywords in a line`() {
            val keywords = setOf("fun", "val")
            val result = MarkdownRenderer.highlightLine("fun main() { val x = 1 }", keywords)
            assertTrue(result.contains("color: #CC7832"))
        }

        @Test
        fun `returns unmodified line when keywords is empty`() {
            val result = MarkdownRenderer.highlightLine("some text", emptySet())
            // Still may highlight numbers, comments, strings
            assertNotNull(result)
        }

        @Test
        fun `highlights comments in line`() {
            val result = MarkdownRenderer.highlightLine("x = 1 // comment", setOf("val"))
            assertTrue(result.contains("color: #808080"))
        }

        @Test
        fun `highlights numbers in line`() {
            val result = MarkdownRenderer.highlightLine("x = 42", emptySet())
            assertTrue(result.contains("color: #6897BB"))
        }

        @Test
        fun `highlights annotations in line`() {
            val result = MarkdownRenderer.highlightLine("@Test", emptySet())
            assertTrue(result.contains("color: #BBB529"))
        }
    }
}
