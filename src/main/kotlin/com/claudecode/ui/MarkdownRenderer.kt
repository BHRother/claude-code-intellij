package com.claudecode.ui

object MarkdownRenderer {

    /** Palette for the current appearance setting; resolved per call so a theme change is picked up. */
    private val palette get() = com.claudecode.ui.theme.ChatTheme.current()

    private val JAVA_KEYWORDS = setOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
        "class", "const", "continue", "default", "do", "double", "else", "enum",
        "extends", "final", "finally", "float", "for", "goto", "if", "implements",
        "import", "instanceof", "int", "interface", "long", "native", "new",
        "package", "private", "protected", "public", "return", "short", "static",
        "strictfp", "super", "switch", "synchronized", "this", "throw", "throws",
        "transient", "try", "void", "volatile", "while", "var", "record", "sealed",
        "permits", "yield", "null", "true", "false"
    )

    private val KOTLIN_KEYWORDS = JAVA_KEYWORDS + setOf(
        "fun", "val", "var", "when", "is", "in", "object", "companion", "data",
        "suspend", "override", "open", "internal", "lateinit", "by", "init",
        "typealias", "inline", "reified", "crossinline", "noinline"
    )

    private val PYTHON_KEYWORDS = setOf(
        "def", "class", "if", "elif", "else", "for", "while", "return", "import",
        "from", "as", "try", "except", "finally", "raise", "with", "yield",
        "lambda", "pass", "break", "continue", "and", "or", "not", "in", "is",
        "None", "True", "False", "self", "async", "await"
    )

    private val JS_KEYWORDS = JAVA_KEYWORDS + setOf(
        "function", "const", "let", "var", "async", "await", "of", "in",
        "undefined", "typeof", "export", "require", "module", "yield", "from"
    )

    fun render(
        markdown: String,
        copyLinkGenerator: ((String) -> String)? = null,
        applyLinkGenerator: ((String, String) -> String)? = null,
    ): String {
        val lines = markdown.lines()
        val html = StringBuilder()
        var i = 0

        while (i < lines.size) {
            val line = lines[i]

            // Fenced code block
            if (line.trimStart().startsWith("```")) {
                val lang = line.trimStart().removePrefix("```").trim().lowercase()
                val codeLines = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    if (codeLines.isNotEmpty()) codeLines.append("\n")
                    codeLines.append(lines[i])
                    i++
                }
                i++ // skip closing ```
                val rawCode = codeLines.toString()
                val copyLink = copyLinkGenerator?.invoke(rawCode) ?: ""
                val applyLink = applyLinkGenerator?.invoke(rawCode, lang) ?: ""
                html.append(renderCodeBlock(rawCode, lang, copyLink, applyLink))
                continue
            }

            // Regular line
            html.append(renderInline(line))
            html.append("<br/>")
            i++
        }

        return html.toString()
    }

    internal fun renderCodeBlock(
        code: String,
        lang: String,
        copyLink: String = "",
        applyLink: String = "",
    ): String {
        val keywords = keywordsForLanguage(lang)

        val highlighted = highlightCode(escapeHtml(code), keywords)
        val langLabel = if (lang.isNotEmpty()) lang else ""
        val hasHeader = langLabel.isNotEmpty() || copyLink.isNotEmpty() || applyLink.isNotEmpty()
        val header = if (hasHeader) {
            val sep = if (copyLink.isNotEmpty() && applyLink.isNotEmpty()) " " else ""
            "<span style='color: ${palette.fgMutedHex};'>$langLabel</span> $copyLink$sep$applyLink<br/>"
        } else ""

        return "<div style='background-color: ${palette.surfaceHex}; padding: 2px;'>" +
                "$header<pre style='margin: 0; padding: 6px; color: ${palette.fgHex};'>$highlighted</pre></div>"
    }

    fun keywordsForLanguage(lang: String): Set<String> = when {
        lang in listOf("java", "scala") -> JAVA_KEYWORDS
        lang in listOf("kotlin", "kt", "kts") -> KOTLIN_KEYWORDS
        lang in listOf("python", "py") -> PYTHON_KEYWORDS
        lang in listOf("javascript", "js", "typescript", "ts", "jsx", "tsx") -> JS_KEYWORDS
        lang in listOf("xml", "html", "css", "json", "yaml", "yml", "toml", "properties", "md", "txt", "svg") -> emptySet()
        else -> JAVA_KEYWORDS
    }

    fun languageFromFilePath(filePath: String): String {
        val ext = filePath.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "java" -> "java"
            "kt", "kts" -> "kotlin"
            "py" -> "python"
            "js", "mjs", "cjs" -> "javascript"
            "ts", "mts", "cts" -> "typescript"
            "jsx" -> "jsx"
            "tsx" -> "tsx"
            "scala", "sc" -> "scala"
            "groovy", "gradle" -> "groovy"
            "rb" -> "ruby"
            "rs" -> "rust"
            "go" -> "go"
            "swift" -> "swift"
            "c", "h" -> "c"
            "cpp", "cc", "cxx", "hpp" -> "cpp"
            "cs" -> "csharp"
            "sh", "bash", "zsh" -> "bash"
            "xml", "html", "htm" -> "xml"
            "css", "scss", "less" -> "css"
            "json" -> "json"
            "yaml", "yml" -> "yaml"
            "toml" -> "toml"
            "sql" -> "sql"
            "md" -> "markdown"
            else -> ext
        }
    }

    fun highlightLine(escapedLine: String, keywords: Set<String>): String {
        return highlightCode(escapedLine, keywords)
    }

    private fun highlightCode(escapedCode: String, keywords: Set<String>): String {
        // Use placeholders to prevent regex passes from corrupting each other's HTML
        val placeholders = mutableListOf<String>()
        fun placeholder(html: String): String {
            val idx = placeholders.size
            placeholders.add(html)
            return "\u0000PH$idx\u0000"
        }

        var result = escapedCode

        // Linkify URLs first so the later passes can't fragment them — in
        // particular the comment pass below would otherwise treat the `//` in
        // `https://…` as the start of a line comment. Placeholdered, so a URL
        // sitting inside a string literal (e.g. browse("https://…")) stays
        // clickable rather than being styled as a plain string.
        result = result.replace(URL_IN_ESCAPED) { m ->
            val (anchor, trailing) = urlAnchor(m.value)
            placeholder(anchor) + trailing
        }

        // Highlight single-line comments first (highest priority)
        result = result.replace(Regex("(//.*?)(\n|$)", RegexOption.MULTILINE)) { m ->
            placeholder("<span style=\"color: ${palette.synCommentHex};\">${m.groupValues[1]}</span>") + m.groupValues[2]
        }

        // Highlight strings (double and single quoted)
        result = result.replace(Regex("(&quot;)(.*?)(&quot;)")) { m ->
            placeholder("<span style=\"color: ${palette.synStringHex};\">${m.value}</span>")
        }
        result = result.replace(Regex("('.*?')")) { m ->
            placeholder("<span style=\"color: ${palette.synStringHex};\">${m.value}</span>")
        }

        // Highlight annotations (@Something)
        result = result.replace(Regex("@\\w+")) { m ->
            placeholder("<span style=\"color: ${palette.synAnnotationHex};\">${m.value}</span>")
        }

        // Highlight keywords (word boundary match)
        for (kw in keywords) {
            result = result.replace(Regex("\\b($kw)\\b")) { m ->
                placeholder("<span style=\"color: ${palette.synKeywordHex};\">${m.value}</span>")
            }
        }

        // Highlight numbers
        result = result.replace(Regex("\\b(\\d+\\.?\\d*)\\b")) { m ->
            placeholder("<span style=\"color: ${palette.synNumberHex};\">${m.value}</span>")
        }

        // Restore all placeholders in reverse: a later (higher-index) placeholder
        // can contain an earlier one (e.g. a string span wrapping a linkified
        // URL), so the outer must be restored before the inner reappears.
        for (i in placeholders.indices.reversed()) {
            result = result.replace("\u0000PH$i\u0000", placeholders[i])
        }

        return result
    }

    internal fun renderInline(line: String): String {
        var result = escapeHtml(line)

        // Bold: **text**
        result = result.replace(Regex("\\*\\*(.*?)\\*\\*")) { m ->
            "<b>${m.groupValues[1]}</b>"
        }

        // Italic: *text* (but not **)
        result = result.replace(Regex("(?<!\\*)\\*(?!\\*)(.*?)(?<!\\*)\\*(?!\\*)")) { m ->
            "<i>${m.groupValues[1]}</i>"
        }

        // Inline code: `code`. Linkify any URL inside so a backticked link
        // (Claude often wraps URLs in code spans) is clickable, not just text.
        result = result.replace(Regex("`([^`]+)`")) { m ->
            "<code style='background-color: ${palette.surfaceHex}; padding: 1px 4px; color: ${palette.fgCodeHex};'>${linkifyEscaped(m.groupValues[1])}</code>"
        }

        // Headers: # ## ###
        result = result.replace(Regex("^#{3}\\s+(.*)")) { m ->
            "<b style='color: ${palette.mdHeaderHex};'>${m.groupValues[1]}</b>"
        }
        result = result.replace(Regex("^#{2}\\s+(.*)")) { m ->
            "<b style='color: ${palette.mdHeaderHex}; font-size: 110%;'>${m.groupValues[1]}</b>"
        }
        result = result.replace(Regex("^#\\s+(.*)")) { m ->
            "<b style='color: ${palette.mdHeaderHex}; font-size: 120%;'>${m.groupValues[1]}</b>"
        }

        // Bullet points: - item or * item
        result = result.replace(Regex("^(\\s*)[\\-\\*]\\s+(.*)")) { m ->
            "${m.groupValues[1]}&bull; ${m.groupValues[2]}"
        }

        // URLs: make http/https links clickable (but not already inside an <a> tag)
        result = result.replace(Regex("(?<![\"'>])(https?://[^\\s<\"']+)")) { m ->
            val url = m.groupValues[1]
            "<a href='$url' style='color: ${palette.linkHex};'>$url</a>"
        }

        return result
    }

    // Matches an http(s) URL inside already-HTML-escaped text: stops before an
    // escaped quote/angle-bracket (a closing string/tag delimiter) or whitespace,
    // but keeps `&amp;` so query strings survive intact.
    private val URL_IN_ESCAPED = Regex("https?://(?:(?!&quot;|&lt;|&gt;|&#39;|\\s).)+")
    // Trailing characters that are almost always punctuation around a URL rather
    // than part of it (e.g. `(https://x)` or `see https://x.`), trimmed off the
    // link target so the anchor doesn't swallow them.
    private const val URL_TRAILING_PUNCT = ").,;:!?"

    /**
     * Build a clickable anchor for one matched URL, returning the anchor HTML and
     * any trailing punctuation that should stay *outside* the link.
     */
    private fun urlAnchor(matched: String): Pair<String, String> {
        var url = matched
        val trailing = StringBuilder()
        while (url.isNotEmpty() && url.last() in URL_TRAILING_PUNCT) {
            trailing.insert(0, url.last())
            url = url.dropLast(1)
        }
        return "<a href='$url' style='color: ${palette.linkHex};'>$url</a>" to trailing.toString()
    }

    /** Wrap every bare http(s) URL in already-escaped [text] with a clickable anchor. */
    internal fun linkifyEscaped(text: String): String =
        text.replace(URL_IN_ESCAPED) { m ->
            val (anchor, trailing) = urlAnchor(m.value)
            anchor + trailing
        }

    internal fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }
}
