package com.claudecode.ui

import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.io.File
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.text.*

class PasteAwareInputArea(
    private val font: Font,
    private val project: com.intellij.openapi.project.Project
) : JPanel(BorderLayout()) {

    private val textPane: JTextPane
    private val chipContentMap = mutableMapOf<Component, String>()
    private var pasteCounter = 0

    // Suppresses the document-filter's smart handling for inserts we make ourselves
    // (chip components, file refs, programmatic text setters).
    private var isProgrammaticInsert = false

    init {
        isOpaque = false

        textPane = object : JTextPane() {
            override fun getScrollableTracksViewportWidth(): Boolean = true
        }.apply {
            this.font = this@PasteAwareInputArea.font
            border = JBUI.Borders.empty(8)
            background = JBColor(Color(0x2B, 0x2D, 0x30), Color(0x2B, 0x2D, 0x30))
            foreground = JBColor(Color(0xBC, 0xBE, 0xC4), Color(0xBC, 0xBE, 0xC4))
            caretColor = JBColor(Color(0xBC, 0xBE, 0xC4), Color(0xBC, 0xBE, 0xC4))
            contentType = "text/plain"
        }

        installDocumentFilter()
        installPasteHandlers()

        val scrollPane = JBScrollPane(textPane).apply {
            border = JBUI.Borders.empty()
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }

        add(scrollPane, BorderLayout.CENTER)
    }

    // ------------------------------------------------------------------
    // Paste interception
    // ------------------------------------------------------------------

    private fun installPasteHandlers() {
        val pasteAction = object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                handleClipboardPaste()
            }
        }

        // Bind every paste action name we know of to our handler.
        val inputMap = textPane.getInputMap(JComponent.WHEN_FOCUSED)
        val actionMap = textPane.actionMap
        val menuMask = Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_V, menuMask), "smart-paste")
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_INSERT, InputEvent.SHIFT_DOWN_MASK), "smart-paste")
        actionMap.put("smart-paste", pasteAction)
        actionMap.put("paste", pasteAction)
        actionMap.put("paste-from-clipboard", pasteAction)
        actionMap.put(DefaultEditorKit.pasteAction, pasteAction)

        // Low-level safety net: even if some IDE handler steals the action lookup,
        // catch Cmd/Ctrl+V at the key event level and consume it.
        textPane.addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_V && (e.isMetaDown || e.isControlDown) && !e.isAltDown) {
                    e.consume()
                    handleClipboardPaste()
                }
            }
        })

        // TransferHandler covers DnD plus the TransferHandler-based paste fallback.
        textPane.transferHandler = object : TransferHandler() {
            override fun importData(comp: JComponent?, t: Transferable?): Boolean {
                return importTransferable(t)
            }

            override fun importData(support: TransferSupport?): Boolean {
                return importTransferable(support?.transferable)
            }

            override fun canImport(comp: JComponent?, transferFlavors: Array<out DataFlavor>?): Boolean {
                return transferFlavors?.any {
                    it == DataFlavor.stringFlavor || it == DataFlavor.javaFileListFlavor
                } ?: false
            }

            override fun canImport(support: TransferSupport?): Boolean {
                return support?.isDataFlavorSupported(DataFlavor.stringFlavor) == true ||
                    support?.isDataFlavorSupported(DataFlavor.javaFileListFlavor) == true
            }

            override fun getSourceActions(c: JComponent?): Int = COPY_OR_MOVE
        }
    }

    private fun handleClipboardPaste() {
        // On macOS, try osascript first. The JDK clipboard bridge for
        // Finder copies is unreliable — it often exposes only the bare
        // filename through stringFlavor and javaFileListFlavor while the
        // actual path lives in NSPasteboard's `public.file-url`. AppleScript
        // reads that pasteboard type directly, which is exactly how Terminal
        // gets the full path when you Cmd+V a file.
        val macFiles = readClipboardFilesViaOsascript()
        if (macFiles.isNotEmpty()) {
            macFiles.forEach { insertFileReference(it) }
            return
        }

        // Two sources to consider:
        //   1. IntelliJ's CopyPasteManager — knows about IDE-internal flavors.
        //   2. Raw AWT system clipboard — anything else.
        val ideContents = runCatching {
            com.intellij.openapi.ide.CopyPasteManager.getInstance().contents
        }.getOrNull()
        val systemContents = runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.getContents(null)
        }.getOrNull()

        for (data in listOfNotNull(ideContents, systemContents)) {
            val files = extractFiles(data)
            if (files.isNotEmpty()) {
                files.forEach { insertFileReference(it) }
                return
            }
        }

        // No files anywhere — fall back to text from whichever source has it.
        for (data in listOfNotNull(ideContents, systemContents)) {
            val text = readBestText(data)
            if (!text.isNullOrEmpty()) {
                insertSmart(text)
                return
            }
        }
    }

    private fun readClipboardFilesViaOsascript(): List<File> {
        val osName = System.getProperty("os.name")?.lowercase().orEmpty()
        if (!osName.contains("mac")) return emptyList()

        // Try several coercions in order. Different macOS versions and copy
        // sources expose the file under different pasteboard types; «class
        // furl» is the most common, but Finder also writes file URLs that
        // can be coerced via list of «class furl» or alias.
        // NOTE: `items` is a reserved word in AppleScript — use theItems.
        val script = """
            on emit(theItem)
              try
                return POSIX path of theItem
              on error
                try
                  return POSIX path of (theItem as alias)
                on error
                  return ""
                end try
              end try
            end emit

            on collectPaths(theItems)
              set output to ""
              repeat with anItem in theItems
                set p to my emit(anItem)
                if p is not "" then set output to output & p & linefeed
              end repeat
              return output
            end collectPaths

            try
              set x to the clipboard as «class furl»
              if class of x is list then
                return my collectPaths(x)
              else
                return my emit(x)
              end if
            end try

            try
              set x to the clipboard as list of «class furl»
              return my collectPaths(x)
            end try

            try
              set x to the clipboard as alias
              return my emit(x)
            end try

            try
              set txt to the clipboard as «class utf8»
              if (txt starts with "/") and (txt does not contain (linefeed)) then
                return txt
              end if
            end try

            return ""
        """.trimIndent()

        return try {
            val process = ProcessBuilder("/usr/bin/osascript", "-e", script)
                .redirectErrorStream(false)
                .start()
            val finished = process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                LOG.warn("osascript clipboard read timed out")
                return emptyList()
            }
            val exit = process.exitValue()
            val output = process.inputStream.bufferedReader().readText()
            val errOutput = process.errorStream.bufferedReader().readText()
            if (exit != 0) {
                LOG.warn("osascript exited $exit, stderr: ${errOutput.take(200)}")
                return emptyList()
            }
            output.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("ERROR") }
                .map { File(it) }
                .filter { it.exists() }
                .toList()
        } catch (e: Throwable) {
            LOG.warn("osascript clipboard read failed", e)
            emptyList()
        }
    }

    private fun importTransferable(data: Transferable?): Boolean {
        if (data == null) return false

        // 1. Extract any concrete file references from any available flavor.
        val files = extractFiles(data)
        if (files.isNotEmpty()) {
            for (file in files) {
                insertFileReference(file)
            }
            return true
        }

        // 2. Fall back to plain text from the most informative text-bearing flavor.
        val text = readBestText(data)
        if (text != null) {
            insertSmart(text)
            return true
        }

        return false
    }

    /**
     * Walks every flavor on the transferable and extracts any file references it
     * can find. Different sources advertise files under different flavors:
     *   - Finder / IntelliJ project-view DnD → javaFileListFlavor (List<File>)
     *   - Finder clipboard on JBR / macOS    → application/x-java-url, text/uri-list, or File-typed flavors
     *   - GNOME / Nautilus                   → x-special/gnome-copied-files, text/uri-list
     *   - KDE / Dolphin                      → text/uri-list, application/x-kde-cutselection
     *   - Windows Explorer                   → CF_HDROP via javaFileListFlavor
     *   - File manager → JTextPane          → may only expose a URI string flavor
     */
    private fun extractFiles(data: Transferable): List<File> {
        val out = LinkedHashSet<File>()

        // Fast path 0: IntelliJ's own helper. It already knows about
        // javaFileListFlavor, LinuxDragAndDropSupport.uriListFlavor, and the
        // platform-specific quirks (including macOS Finder, where the JDK's
        // text representation is just the filename). This is what the IDE
        // itself uses for file paste, so it's the most reliable source.
        runCatching {
            val paths = com.intellij.ide.dnd.FileCopyPasteUtil.getFiles(data)
            paths?.forEach { out.add(it.toFile()) }
        }.onFailure { LOG.warn("FileCopyPasteUtil.getFiles failed", it) }
        if (out.isNotEmpty()) return out.toList()

        // Fast path 1: javaFileListFlavor (kept as a safety net in case the
        // helper above isn't on the classpath at runtime for some reason).
        // On some platforms the list contains URLs/URIs instead of Files —
        // unwrapToFiles handles every common element type.
        if (data.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            try {
                val raw = data.getTransferData(DataFlavor.javaFileListFlavor)
                unwrapToFiles(raw).forEach(out::add)
            } catch (e: Throwable) {
                LOG.warn("Failed to read javaFileListFlavor", e)
            }
            if (out.isNotEmpty()) return out.toList()
        }

        // Fast path 2: gnome-copied-files (e.g. "copy\nfile:///path/...\n").
        for (flavor in data.transferDataFlavors.orEmpty()) {
            if (flavor.mimeType.orEmpty().startsWith("x-special/gnome-copied-files")) {
                runCatching {
                    val raw = data.getTransferData(flavor)
                    val text = when (raw) {
                        is String -> raw
                        is java.io.InputStream -> raw.bufferedReader().readText()
                        is java.io.Reader -> raw.readText()
                        is ByteArray -> String(raw)
                        else -> null
                    }
                    if (text != null) {
                        text.lineSequence()
                            .drop(1) // first line is "copy" or "cut"
                            .forEach { line ->
                                val trimmed = line.trim()
                                if (trimmed.isNotEmpty()) {
                                    runCatching { File(java.net.URI(trimmed)) }
                                        .getOrNull()
                                        ?.takeIf { it.exists() }
                                        ?.let(out::add)
                                }
                            }
                    }
                }.onFailure { LOG.warn("Failed to read gnome-copied-files", it) }
                if (out.isNotEmpty()) return out.toList()
            }
        }

        // Generic walk: try every flavor and unwrap whatever it gives us.
        for (flavor in data.transferDataFlavors.orEmpty()) {
            if (flavor == DataFlavor.stringFlavor) continue // handled in text fallback
            val raw = runCatching { data.getTransferData(flavor) }.getOrNull() ?: continue
            unwrapToFiles(raw).forEach(out::add)
        }

        return out.toList()
    }

    private fun unwrapToFiles(raw: Any?): List<File> {
        return when (raw) {
            null -> emptyList()
            is File -> listOf(raw)
            is java.nio.file.Path -> listOf(raw.toFile())
            is java.net.URI -> listOfNotNull(uriToFile(raw))
            is java.net.URL -> listOfNotNull(runCatching { raw.toURI() }.getOrNull()?.let(::uriToFile))
            is List<*> -> raw.flatMap { unwrapToFiles(it) }
            is Array<*> -> raw.flatMap { unwrapToFiles(it) }
            is String -> parseUriOrPathString(raw)
            is java.io.Reader -> parseUriOrPathString(raw.readText())
            is java.io.InputStream -> parseUriOrPathString(raw.bufferedReader().readText())
            is ByteArray -> parseUriOrPathString(String(raw))
            else -> emptyList()
        }
    }

    private fun parseUriOrPathString(s: String): List<File> {
        if (s.isBlank()) return emptyList()
        // Multi-line uri-list style payload.
        if (s.contains('\n')) {
            return s.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .mapNotNull(::parseSingleFileLine)
                .toList()
        }
        return listOfNotNull(parseSingleFileLine(s.trim()))
    }

    private fun parseSingleFileLine(line: String): File? {
        if (line.isEmpty()) return null
        // file:// URI
        if (line.startsWith("file:")) {
            return runCatching { File(java.net.URI(line)) }.getOrNull()?.takeIf { it.exists() }
        }
        // Absolute path
        if (line.startsWith("/") || line.startsWith("~")) {
            val expanded = if (line.startsWith("~")) {
                File(System.getProperty("user.home"), line.removePrefix("~").removePrefix("/"))
            } else File(line)
            return expanded.takeIf { it.exists() }
        }
        // Generic URI with file scheme
        runCatching { java.net.URI(line) }.getOrNull()?.let { uri ->
            uriToFile(uri)?.let { return it }
        }
        return null
    }

    /** Reads the best plain-text representation from the transferable. */
    private fun readBestText(data: Transferable): String? {
        if (data.isDataFlavorSupported(DataFlavor.stringFlavor)) {
            val s = runCatching { data.getTransferData(DataFlavor.stringFlavor) as? String }.getOrNull()
            if (!s.isNullOrEmpty()) return s
        }
        // Some sources expose only Reader-based text flavors.
        for (flavor in data.transferDataFlavors.orEmpty()) {
            if (flavor.representationClass == java.io.Reader::class.java) {
                runCatching {
                    val r = data.getTransferData(flavor) as? java.io.Reader
                    val txt = r?.readText()
                    if (!txt.isNullOrEmpty()) return txt
                }
            }
        }
        return null
    }

    private fun uriToFile(uri: java.net.URI): File? {
        return try {
            if (uri.scheme == "file") File(uri) else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun stringAsFile(s: String): File? {
        val cleaned = when {
            s.startsWith("file://") -> runCatching { File(java.net.URI(s)) }.getOrNull()
            s.startsWith("/") || s.startsWith("~") -> {
                if (s.startsWith("~")) File(System.getProperty("user.home"), s.removePrefix("~").removePrefix("/"))
                else File(s)
            }
            else -> null
        } ?: return null
        return if (cleaned.exists()) cleaned else null
    }

    /** Routes a pasted text payload to the right insertion path. */
    private fun insertSmart(text: String) {
        if (text.isEmpty()) return

        val lines = text.lines()
        val trimmed = text.trim()

        // Single-line: try to interpret as a file (file:// URL, absolute path,
        // or bare filename that exists in the project).
        if (lines.size == 1 || (lines.size == 2 && lines[1].isEmpty())) {
            // file:// URL → @<absolute path>
            if (trimmed.startsWith("file://")) {
                val asFile = stringAsFile(trimmed)
                if (asFile != null) {
                    insertFileReference(asFile)
                    return
                }
            }
            val resolved = resolveAsAbsoluteFile(trimmed)
            if (resolved != null) {
                insertFileReference(File(resolved))
                return
            }
        }

        // Large paste → chip
        if (text.length >= CHIP_CHAR_THRESHOLD || lines.size > CHIP_LINE_THRESHOLD) {
            insertChipAtCaret(text, lines.size)
            return
        }

        // Otherwise, paste as plain text at caret.
        insertPlainTextAtCaret(text)
    }

    private fun insertPlainTextAtCaret(text: String) {
        isProgrammaticInsert = true
        try {
            textPane.replaceSelection(text)
        } finally {
            isProgrammaticInsert = false
        }
    }

    // ------------------------------------------------------------------
    // Document filter — safety net only
    // ------------------------------------------------------------------
    //
    // Most paste/DnD inserts are intercepted before reaching the document via
    // installPasteHandlers(). This filter exists to catch any text that slips
    // through (e.g. middle-click paste on Linux, IDE features that call
    // doc.insertString directly, etc.).

    private fun installDocumentFilter() {
        (textPane.styledDocument as? AbstractDocument)?.documentFilter = object : DocumentFilter() {
            override fun insertString(fb: FilterBypass, offset: Int, string: String?, attr: AttributeSet?) {
                if (isProgrammaticInsert || string == null) {
                    fb.insertString(offset, string, attr)
                    return
                }
                processInsertion(fb, offset, 0, string, attr)
            }

            override fun replace(fb: FilterBypass, offset: Int, length: Int, text: String?, attrs: AttributeSet?) {
                if (isProgrammaticInsert || text == null) {
                    fb.replace(offset, length, text, attrs)
                    return
                }
                processInsertion(fb, offset, length, text, attrs)
            }
        }
    }

    private fun processInsertion(fb: DocumentFilter.FilterBypass, offset: Int, length: Int, text: String, attrs: AttributeSet?) {
        // Pass through normal typing.
        if (text.length <= 1) {
            fb.replace(offset, length, text, attrs)
            return
        }

        val lines = text.lines()
        val trimmed = text.trim()

        // 1. Resolve via project (works for IDE-internal copies + file:// URLs + absolute paths).
        if (lines.size == 1) {
            val filePath = resolveAsAbsoluteFile(trimmed)
            if (filePath != null) {
                if (length > 0) fb.replace(offset, length, "", attrs)
                insertFileChipViaBypass(fb, offset, File(filePath))
                return
            }
        }

        // 2. macOS Finder fallback. JBR's clipboard bridge often surfaces only
        // the bare filename for Finder copies, hiding the actual path that
        // lives in NSPasteboard's `public.file-url`. Cmd+V routes the text
        // straight to the document — bypassing our KeyListener and
        // TransferHandler — so this filter is the only place we can intercept.
        // Run osascript to read the file URL directly, the same mechanism
        // Terminal uses to paste full paths.
        if (lines.size == 1 && couldBeFilename(trimmed)) {
            val macFile = readClipboardFileViaOsascriptForFilename(trimmed)
            if (macFile != null) {
                if (length > 0) fb.replace(offset, length, "", attrs)
                insertFileChipViaBypass(fb, offset, macFile)
                return
            }
        }

        // 3. Big paste → text chip.
        if (text.length >= CHIP_CHAR_THRESHOLD || lines.size > CHIP_LINE_THRESHOLD) {
            if (length > 0) fb.replace(offset, length, "", attrs)
            insertTextChipViaBypass(fb, offset, text, lines.size)
            return
        }

        fb.replace(offset, length, text, attrs)
    }

    /**
     * Tight filter: only run osascript when the inserted text plausibly came
     * from a Finder copy. Excludes anything that looks like code (parens,
     * brackets, operators, quotes) so autocomplete inserts don't trigger the
     * shell call. Filenames typically match `[A-Za-z0-9._-]+`.
     */
    private fun couldBeFilename(s: String): Boolean {
        if (s.isEmpty() || s.length > 120) return false
        return s.all { c ->
            c.isLetterOrDigit() || c == '.' || c == '_' || c == '-'
        }
    }

    /**
     * macOS-only: run AppleScript to read NSPasteboard's file URL and return
     * a File whose name matches `expectedName`. Strict name match prevents a
     * stale clipboard from producing a wrong chip when the user actually
     * pasted unrelated short text that just happens to look filename-like.
     */
    private fun readClipboardFileViaOsascriptForFilename(expectedName: String): File? {
        val files = readClipboardFilesViaOsascript()
        if (files.isEmpty()) return null
        return files.firstOrNull {
            it.name == expectedName || it.nameWithoutExtension == expectedName
        }
    }

    private fun insertTextChipViaBypass(fb: DocumentFilter.FilterBypass, offset: Int, content: String, lineCount: Int) {
        pasteCounter++
        val label = if (lineCount > 1) {
            "Pasted #$pasteCounter · $lineCount lines"
        } else {
            "Pasted #$pasteCounter · ${content.length} chars"
        }
        insertChipViaBypass(fb, offset, label, content, tooltip = null, expandable = true)
    }

    private fun insertFileChipViaBypass(fb: DocumentFilter.FilterBypass, offset: Int, file: File) {
        pasteCounter++
        insertChipViaBypass(
            fb, offset,
            label = "📎 ${file.name}",
            content = "@${file.absolutePath}",
            tooltip = file.absolutePath,
            expandable = false,
        )
    }

    private fun insertChipViaBypass(
        fb: DocumentFilter.FilterBypass,
        offset: Int,
        label: String,
        content: String,
        tooltip: String?,
        expandable: Boolean = false,
    ) {
        val chip = createChip(label, content, tooltip, expandable = expandable)
        val style = textPane.addStyle("chip-$pasteCounter", null)
        StyleConstants.setComponent(style, chip)
        try {
            fb.insertString(offset, "￼", style)
        } catch (e: BadLocationException) {
            LOG.warn("Failed to insert chip via bypass", e)
            return
        }
        SwingUtilities.invokeLater {
            try {
                textPane.caretPosition = (offset + 1).coerceAtMost(textPane.styledDocument.length)
            } catch (_: IllegalArgumentException) {}
        }
    }

    // ------------------------------------------------------------------
    // File-path resolution
    // ------------------------------------------------------------------

    private fun resolveAsAbsoluteFile(text: String): String? {
        if (text.isEmpty()) return null
        if (text.contains("\n") || text.contains("\t")) return null
        if (text.contains("  ")) return null

        val projectBase = project.basePath

        val looksLikePath = text.contains(File.separator) ||
            text.contains("/") ||
            text.startsWith("~") ||
            text.startsWith(".")

        if (looksLikePath) {
            val direct = when {
                text.startsWith("/") -> File(text)
                text.startsWith("~") -> File(System.getProperty("user.home"), text.removePrefix("~").removePrefix("/"))
                projectBase != null -> File(projectBase, text)
                else -> null
            }
            if (direct != null && direct.exists()) return direct.absolutePath
        }

        // Treat as bare filename — only meaningful if it has no spaces and looks like a file.
        if (text.contains(" ")) return null
        val nameCandidate = text.substringAfterLast('/').substringAfterLast(File.separatorChar)
        if (nameCandidate.isEmpty()) return null

        if (projectBase != null) {
            val inRoot = File(projectBase, nameCandidate)
            if (inRoot.exists() && inRoot.isFile) return inRoot.absolutePath
        }

        val indexed = findInProjectIndex(nameCandidate)
        if (indexed != null) return indexed

        return null
    }

    private fun findInProjectIndex(fileName: String): String? {
        if (fileName.length > 80) return null

        // All FilenameIndex / VFS reads must run inside a ReadAction. Calling
        // them straight from the EDT throws IllegalStateException — the
        // previous implementation was silently swallowing that, which is why
        // stem search never worked for files like NewSessionAction.kt where
        // IntelliJ's tree hides the extension.
        return com.intellij.openapi.application.ReadAction.compute<String?, Throwable> {
            val scope = com.intellij.psi.search.GlobalSearchScope.projectScope(project)

            // 1. Exact filename match (covers the case where the clipboard
            // already carries the extension, e.g. "FileContextActions.kt").
            try {
                val exact = com.intellij.psi.search.FilenameIndex.getVirtualFilesByName(fileName, scope)
                uniquePath(exact)?.let { return@compute it }
            } catch (e: Throwable) {
                LOG.warn("FilenameIndex exact lookup failed for '$fileName'", e)
            }

            // 2. Common-extension probing — deterministic and cheap. This is
            // what fixes the "NewSessionAction" case: try
            // "NewSessionAction.kt", "NewSessionAction.java", etc.
            if (!fileName.contains('.')) {
                for (ext in COMMON_FILE_EXTENSIONS) {
                    try {
                        val withExt = "$fileName.$ext"
                        val files = com.intellij.psi.search.FilenameIndex.getVirtualFilesByName(withExt, scope)
                        uniquePath(files)?.let { return@compute it }
                    } catch (e: Throwable) {
                        LOG.warn("FilenameIndex ext lookup failed for '$fileName.$ext'", e)
                    }
                }
            }

            // 3. Stem scan — slower fallback that handles uncommon extensions.
            if (!fileName.contains('.')) {
                try {
                    val all = com.intellij.psi.search.FilenameIndex.getAllFilenames(project)
                    val stemMatches = all.filter { it.substringBeforeLast('.', "") == fileName }
                    val resolvedPaths = stemMatches.flatMap { name ->
                        com.intellij.psi.search.FilenameIndex.getVirtualFilesByName(name, scope).map { it.path }
                    }.distinct()
                    if (resolvedPaths.size == 1) return@compute resolvedPaths.first()
                } catch (e: Throwable) {
                    LOG.warn("FilenameIndex stem scan failed for '$fileName'", e)
                }
            }

            // 4. Last-resort VFS walk over project content. Reliable but
            // O(N) in project size — only runs when the index lookups
            // couldn't decide.
            try {
                val matches = mutableListOf<String>()
                com.intellij.openapi.roots.ProjectFileIndex.getInstance(project).iterateContent { file ->
                    if (!file.isDirectory) {
                        val matchesName = file.name == fileName ||
                            (!fileName.contains('.') && file.nameWithoutExtension == fileName)
                        if (matchesName) {
                            matches.add(file.path)
                            if (matches.size > 1) return@iterateContent false // ambiguous, stop
                        }
                    }
                    true
                }
                if (matches.size == 1) return@compute matches.first()
            } catch (e: Throwable) {
                LOG.warn("ProjectFileIndex walk failed for '$fileName'", e)
            }

            null
        }
    }

    private fun uniquePath(files: Collection<com.intellij.openapi.vfs.VirtualFile>): String? {
        return when {
            files.isEmpty() -> null
            files.size == 1 -> files.first().path
            else -> null
        }
    }

    // ------------------------------------------------------------------
    // Inserts (programmatic — bypass the smart filter)
    // ------------------------------------------------------------------

    private fun clearSelectionIfAny(): Int {
        val caret = textPane.caret
        val p0 = minOf(caret.dot, caret.mark)
        val p1 = maxOf(caret.dot, caret.mark)
        if (p1 > p0) {
            try {
                textPane.styledDocument.remove(p0, p1 - p0)
            } catch (e: BadLocationException) {
                LOG.warn("Failed to remove selection", e)
            }
        }
        return p0.coerceAtMost(textPane.styledDocument.length)
    }

    /**
     * Inserts a compact chip showing just the filename. The chip stores the
     * full @<absolute-path> text — that's what gets sent to Claude when the
     * message is submitted (see [getFullText]).
     */
    private fun insertFileReference(file: File) {
        val displayName = file.name.ifEmpty { file.absolutePath }
        val content = "@${file.absolutePath}"
        insertChip(label = "📎 $displayName", content = content, tooltip = file.absolutePath, expandable = false)
    }

    private fun insertChipAtCaret(content: String, lineCount: Int) {
        val label = if (lineCount > 1) {
            "Pasted #${pasteCounter + 1} · $lineCount lines"
        } else {
            "Pasted #${pasteCounter + 1} · ${content.length} chars"
        }
        insertChip(label, content, tooltip = null, expandable = true)
    }

    /**
     * Single insertion path for any chip. Inserts the U+FFFC placeholder at
     * the caret (replacing any selection) and parks the caret right after the
     * chip. Caret positioning is deferred via [SwingUtilities.invokeLater] so
     * it survives any same-cycle event handlers that might otherwise reset it.
     */
    private fun insertChip(label: String, content: String, tooltip: String?, expandable: Boolean = false) {
        pasteCounter++
        val chip = createChip(label, content, tooltip, expandable = expandable)
        val style = textPane.addStyle("chip-$pasteCounter", null)
        StyleConstants.setComponent(style, chip)

        isProgrammaticInsert = true
        val targetCaret: Int
        try {
            val insertPos = clearSelectionIfAny()
            val doc = textPane.styledDocument
            // U+FFFC OBJECT REPLACEMENT CHARACTER — canonical placeholder for embedded components.
            doc.insertString(insertPos, "￼", style)
            targetCaret = (insertPos + 1).coerceAtMost(doc.length)
        } catch (e: BadLocationException) {
            LOG.warn("Failed to insert chip", e)
            isProgrammaticInsert = false
            return
        } finally {
            isProgrammaticInsert = false
        }

        // Set caret immediately, then again on next EDT cycle to defeat any
        // listener (Swing's own paste action, IDE handlers) that might run
        // after us and snap the caret elsewhere.
        runCatching { textPane.caretPosition = targetCaret }
        SwingUtilities.invokeLater {
            runCatching {
                val len = textPane.styledDocument.length
                textPane.caretPosition = targetCaret.coerceAtMost(len)
            }
        }
    }

    private fun createChip(
        label: String,
        content: String,
        tooltip: String? = null,
        expandable: Boolean = false,
    ): JPanel {
        val chip = object : JPanel(BorderLayout(4, 0)) {
            override fun getAlignmentY(): Float = 0.8f
            override fun getPreferredSize(): Dimension {
                val pref = super.getPreferredSize()
                return Dimension(pref.width, 22)
            }
            override fun getMaximumSize(): Dimension = preferredSize
            override fun getMinimumSize(): Dimension = preferredSize
        }.apply {
            isOpaque = true
            background = JBColor(Color(0x3C, 0x3F, 0x41), Color(0x3C, 0x3F, 0x41))
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor(Color(0x50, 0x53, 0x56), Color(0x50, 0x53, 0x56)), 1, true),
                EmptyBorder(2, 6, 2, 4)
            )
            if (tooltip != null) toolTipText = tooltip
        }

        val labelComp = JLabel(label).apply {
            font = this@PasteAwareInputArea.font.deriveFont(11f)
            foreground = JBColor(Color(0xA9, 0xB7, 0xC6), Color(0xA9, 0xB7, 0xC6))
            if (tooltip != null) toolTipText = tooltip
        }

        val removeBtn = JLabel("✕").apply {
            font = this@PasteAwareInputArea.font.deriveFont(10f)
            foreground = JBColor(Color(0x80, 0x80, 0x80), Color(0x80, 0x80, 0x80))
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                    removeChip(chip)
                }
            })
        }

        if (expandable) {
            val expandTooltip = "Double-click to expand into the input"
            chip.toolTipText = tooltip ?: expandTooltip
            labelComp.toolTipText = tooltip ?: expandTooltip
            labelComp.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            val expandListener = object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                    if (e != null && e.clickCount >= 2 && !e.isConsumed) {
                        expandChip(chip, content)
                        e.consume()
                    }
                }
            }
            chip.addMouseListener(expandListener)
            labelComp.addMouseListener(expandListener)
        }

        chip.add(labelComp, BorderLayout.CENTER)
        chip.add(removeBtn, BorderLayout.EAST)

        chipContentMap[chip] = content
        return chip
    }

    private fun expandChip(chip: JPanel, content: String) {
        val doc = textPane.styledDocument
        val root = doc.defaultRootElement
        for (i in 0 until root.elementCount) {
            val para = root.getElement(i)
            for (j in 0 until para.elementCount) {
                val elem = para.getElement(j)
                if (StyleConstants.getComponent(elem.attributes) === chip) {
                    val start = elem.startOffset
                    val len = elem.endOffset - start
                    isProgrammaticInsert = true
                    try {
                        doc.remove(start, len)
                        doc.insertString(start, content, null)
                    } catch (e: BadLocationException) {
                        LOG.warn("Failed to expand chip", e)
                    } finally {
                        isProgrammaticInsert = false
                    }
                    chipContentMap.remove(chip)
                    SwingUtilities.invokeLater {
                        val caretTarget = (start + content.length).coerceAtMost(doc.length)
                        textPane.caretPosition = caretTarget
                        textPane.requestFocusInWindow()
                    }
                    return
                }
            }
        }
    }

    private fun removeChip(chip: JPanel) {
        val doc = textPane.styledDocument
        val root = doc.defaultRootElement
        for (i in 0 until root.elementCount) {
            val para = root.getElement(i)
            for (j in 0 until para.elementCount) {
                val elem = para.getElement(j)
                if (StyleConstants.getComponent(elem.attributes) === chip) {
                    isProgrammaticInsert = true
                    try {
                        doc.remove(elem.startOffset, elem.endOffset - elem.startOffset)
                    } catch (e: BadLocationException) {
                        LOG.warn("Failed to remove chip", e)
                    } finally {
                        isProgrammaticInsert = false
                    }
                    chipContentMap.remove(chip)
                    return
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    fun getFullText(): String {
        val doc = textPane.styledDocument
        val sb = StringBuilder()
        val root = doc.defaultRootElement

        for (i in 0 until root.elementCount) {
            val para = root.getElement(i)
            for (j in 0 until para.elementCount) {
                val elem = para.getElement(j)
                val comp = StyleConstants.getComponent(elem.attributes)
                if (comp != null) {
                    val content = chipContentMap[comp]
                    if (content != null) sb.append(content)
                } else {
                    val start = elem.startOffset
                    val end = elem.endOffset.coerceAtMost(doc.length)
                    if (end > start) {
                        sb.append(doc.getText(start, end - start))
                    }
                }
            }
        }

        return sb.toString().trim()
    }

    fun clear() {
        isProgrammaticInsert = true
        try {
            textPane.styledDocument.remove(0, textPane.styledDocument.length)
        } catch (_: BadLocationException) {
            textPane.text = ""
        } finally {
            isProgrammaticInsert = false
        }
        chipContentMap.clear()
        pasteCounter = 0
    }

    fun getTextInputMap(condition: Int): InputMap = textPane.getInputMap(condition)
    fun getTextActionMap(): ActionMap = textPane.actionMap

    var text: String
        get() = textPane.text
        set(value) {
            isProgrammaticInsert = true
            try {
                textPane.text = value
            } finally {
                isProgrammaticInsert = false
            }
        }

    var caretPosition: Int
        get() = textPane.caretPosition
        set(value) { textPane.caretPosition = value }

    override fun requestFocusInWindow(): Boolean = textPane.requestFocusInWindow()

    fun insert(str: String, pos: Int) {
        isProgrammaticInsert = true
        try {
            textPane.styledDocument.insertString(pos, str, null)
        } catch (_: BadLocationException) {
            // ignore
        } finally {
            isProgrammaticInsert = false
        }
    }

    fun getTextComponent(): JTextComponent = textPane

    companion object {
        private val LOG = Logger.getInstance(PasteAwareInputArea::class.java)
        private const val CHIP_CHAR_THRESHOLD = 250
        private const val CHIP_LINE_THRESHOLD = 3
        // Probed in order. Languages first, then markup/data, then scripts/config.
        private val COMMON_FILE_EXTENSIONS = listOf(
            "kt", "kts", "java", "py", "ts", "tsx", "js", "jsx",
            "go", "rs", "rb", "swift", "scala", "groovy", "clj",
            "cpp", "cc", "cxx", "c", "h", "hpp", "cs", "m", "mm",
            "php", "lua", "dart", "ex", "exs",
            "md", "txt", "rst", "adoc",
            "json", "yaml", "yml", "toml", "xml", "html", "css", "scss", "sass",
            "sh", "bash", "zsh", "fish", "ps1",
            "sql", "proto", "graphql", "vue", "svelte"
        )
    }
}
