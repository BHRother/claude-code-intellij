package com.claudecode.ui

import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
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
    private val scrollPane: JBScrollPane
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

        scrollPane = JBScrollPane(textPane).apply {
            border = JBUI.Borders.empty()
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }

        add(scrollPane, BorderLayout.CENTER)
    }

    /**
     * Enables Cursor-style auto-expansion. The input starts at [minLines] tall,
     * grows row-by-row as the user types, and caps at [maxLines] — past that
     * the vertical scrollbar kicks in. Submitting (which clears the text)
     * brings it back down to [minLines].
     *
     * Computes target height from the textPane's own preferred size so
     * word-wrapped long lines count correctly.
     */
    fun enableAutoGrow(minLines: Int, maxLines: Int) {
        autoGrowMinLines = minLines
        autoGrowMaxLines = maxLines
        textPane.styledDocument.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = scheduleAutoGrow()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = scheduleAutoGrow()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = scheduleAutoGrow()
        })
        // Apply initial sizing once the panel has a width to compute wrapping from.
        SwingUtilities.invokeLater { applyAutoGrow() }
    }

    private var autoGrowMinLines: Int = 0
    private var autoGrowMaxLines: Int = 0

    /**
     * Fires immediately BEFORE the input area changes its preferred height
     * via auto-grow. Lets the host (SessionPanel) snapshot scroll state and
     * re-pin it after the parent layout settles — without this, growing the
     * input shifts the chat output upward and breaks the "stick to bottom"
     * affordance.
     */
    var onBeforeAutoGrow: (() -> Unit)? = null

    private fun scheduleAutoGrow() {
        // DocumentListener fires inside the document mutation — deferring to
        // invokeLater lets the textPane recompute its preferredSize after the
        // text actually lands, and avoids re-entrancy with DocumentFilter.
        SwingUtilities.invokeLater { applyAutoGrow() }
    }

    private fun applyAutoGrow() {
        if (autoGrowMinLines <= 0) return
        val fm = textPane.getFontMetrics(textPane.font)
        val lineH = fm.height
        val verticalInsets = scrollPane.insets.let { it.top + it.bottom } +
            insets.let { it.top + it.bottom }
        val minH = lineH * autoGrowMinLines + verticalInsets
        val maxH = lineH * autoGrowMaxLines + verticalInsets
        // textPane.preferredSize reflects the wrapped text height. Use the
        // pane's own preferredSize so long lines that wrap count as multiple
        // visual rows.
        val natural = textPane.preferredSize.height + verticalInsets
        val target = natural.coerceIn(minH, maxH)
        if (preferredSize.height != target) {
            onBeforeAutoGrow?.invoke()
            preferredSize = Dimension(preferredSize.width, target)
            // Tell the bottom-panel BoxLayout to re-layout above us.
            parent?.let { p ->
                p.revalidate()
                p.repaint()
            }
        }
    }

    // ------------------------------------------------------------------
    // Paste interception
    //
    // Two — and only two — paste entry points:
    //
    //   1. AnAction registered for Cmd/Ctrl+V scoped to textPane. IntelliJ's
    //      IdeKeyEventDispatcher checks component-scoped shortcuts before
    //      routing to the default $Paste action, so our handler runs
    //      *instead of* $Paste — not in addition to it. No racing insert,
    //      no duplicate chip.
    //
    //   2. TransferHandler for drag-and-drop. Independent path; DnD events
    //      never collide with the key-event path.
    //
    // The DocumentFilter (installed separately) catches text inserts from
    // *other* sources (autocomplete, programmatic edits) but never chips
    // for a paste action — that's the AnAction's job. This means exactly
    // one chip-creating path per user action. No dedupe needed.
    // ------------------------------------------------------------------

    private fun installPasteHandlers() {
        // TransferHandler — only for drag-and-drop. Cmd/Ctrl+V is handled
        // by the AnAction override (installed in addNotify). Keeping
        // canImport/getSourceActions correct so DnD targeting works.
        textPane.transferHandler = object : TransferHandler() {
            override fun importData(comp: JComponent?, t: Transferable?): Boolean {
                return importTransferable(t)
            }

            override fun importData(support: TransferSupport?): Boolean {
                return importTransferable(support?.transferable)
            }

            override fun canImport(comp: JComponent?, transferFlavors: Array<out DataFlavor>?): Boolean {
                return transferFlavors?.any {
                    it == DataFlavor.stringFlavor ||
                        it == DataFlavor.javaFileListFlavor ||
                        it == DataFlavor.imageFlavor
                } ?: false
            }

            override fun canImport(support: TransferSupport?): Boolean {
                return support?.isDataFlavorSupported(DataFlavor.stringFlavor) == true ||
                    support?.isDataFlavorSupported(DataFlavor.javaFileListFlavor) == true ||
                    support?.isDataFlavorSupported(DataFlavor.imageFlavor) == true
            }

            override fun getSourceActions(c: JComponent?): Int = COPY_OR_MOVE
        }
    }

    private var pasteActionOverride: com.intellij.openapi.actionSystem.AnAction? = null

    /**
     * Register an IntelliJ AnAction for Cmd/Ctrl+V scoped to the textPane.
     * IntelliJ's IdeKeyEventDispatcher checks component-scoped shortcuts
     * BEFORE routing the key event to the default $Paste action — so when
     * our action handles the event, $Paste never runs, doc.insertString
     * never fires from the racing path, and there's exactly one chip per
     * paste.
     *
     * Install/uninstall is tied to addNotify/removeNotify so the action
     * stays alive across tool-window hide/show cycles.
     */
    private fun installPasteActionOverride() {
        if (pasteActionOverride != null) return
        val action = object : com.intellij.openapi.actionSystem.AnAction() {
            override fun getActionUpdateThread() =
                com.intellij.openapi.actionSystem.ActionUpdateThread.BGT
            override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                LOG.info("paste-entry: AnAction \$Paste override fired")
                handleClipboardPaste()
            }
        }
        // menuShortcutKeyMaskEx → Cmd on macOS, Ctrl on Linux/Windows.
        val pasteKeyStroke = KeyStroke.getKeyStroke(
            KeyEvent.VK_V,
            Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
        )
        val shortcutSet = com.intellij.openapi.actionSystem.CustomShortcutSet(
            com.intellij.openapi.actionSystem.KeyboardShortcut(pasteKeyStroke, null)
        )
        action.registerCustomShortcutSet(shortcutSet, textPane)
        pasteActionOverride = action
    }

    private fun uninstallPasteActionOverride() {
        pasteActionOverride?.unregisterCustomShortcutSet(textPane)
        pasteActionOverride = null
    }

    override fun addNotify() {
        super.addNotify()
        installPasteActionOverride()
    }

    override fun removeNotify() {
        uninstallPasteActionOverride()
        super.removeNotify()
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

        // Image data — screenshots (Cmd+Shift+Ctrl+4 on macOS, Win+Shift+S
        // on Windows), Preview's Edit→Copy, browser image right-click→Copy.
        // Saved to a temp PNG and inserted as an @<path> chip so the CLI
        // uploads it as a multimodal block on submit.
        // On macOS, try osascript first — JBR's clipboard bridge often
        // misses image flavors that NSPasteboard exposes natively (same
        // pattern as the file-paste path above).
        // Routine paste-flow trace at INFO. Enable per-category in
        // Help → Diagnostic Tools → Debug Log Settings if you need this in
        // idea.log without restarting. The "insertImageFromTransferable
        // returned false" branch stays WARN because it's a real anomaly
        // (clipboard advertised an image but we couldn't extract it).
        LOG.info("paste: handler entered")
        val macImage = readClipboardImageViaOsascript()
        if (macImage != null) {
            LOG.info("paste: osascript image -> ${macImage.absolutePath}")
            insertImageReference(macImage, dimensionsOfFile(macImage))
            return
        }
        for (data in listOfNotNull(ideContents, systemContents)) {
            val flavors = data.transferDataFlavors?.joinToString(", ") { it.mimeType.substringBefore(';') }
                ?: "(none)"
            LOG.info("paste: flavors=[$flavors]")
            if (data.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                LOG.info("paste: imageFlavor advertised; trying insertImageFromTransferable")
                if (insertImageFromTransferable(data)) {
                    LOG.info("paste: image inserted via transferable")
                    return
                }
                LOG.warn("paste: insertImageFromTransferable returned false")
            }
        }

        // No files or images — fall back to text from whichever source has it.
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
        LOG.info("paste-entry: TransferHandler.importTransferable (data=${data != null})")
        if (data == null) return false

        // 1. Image data first — screenshots and image copies should land as
        // image chips, not as text/file fallbacks. Some sources advertise
        // BOTH image and file flavors (e.g. dragging an image file from
        // Finder), and for those we still prefer the file path.
        val files = extractFiles(data)
        if (files.isEmpty() && data.isDataFlavorSupported(DataFlavor.imageFlavor)) {
            if (insertImageFromTransferable(data)) return true
        }

        // 2. Extract any concrete file references from any available flavor.
        if (files.isNotEmpty()) {
            for (file in files) {
                insertFileReference(file)
            }
            return true
        }

        // 3. Fall back to plain text from the most informative text-bearing flavor.
        val text = readBestText(data)
        if (text != null) {
            insertSmart(text)
            return true
        }

        return false
    }

    /**
     * Reads an image off the clipboard/transferable, writes it to a temp PNG,
     * and inserts it as an image chip whose underlying content is `@<path>` so
     * the Claude CLI uploads it as a multimodal block on submit.
     */
    private fun insertImageFromTransferable(data: Transferable): Boolean {
        val image = runCatching { data.getTransferData(DataFlavor.imageFlavor) as? java.awt.Image }
            .getOrNull() ?: return false
        val file = saveImageToTempFile(image) ?: return false
        val w = image.getWidth(null).coerceAtLeast(0)
        val h = image.getHeight(null).coerceAtLeast(0)
        insertImageReference(file, if (w > 0 && h > 0) w to h else null)
        return true
    }

    /**
     * macOS-only: read a PNG/TIFF image off NSPasteboard via AppleScript and
     * write it directly to a temp file. The JBR clipboard bridge often fails
     * to surface image flavors that NSPasteboard does have — same problem we
     * saw with file flavors and solved with osascript above.
     */
    private fun readClipboardImageViaOsascript(): File? {
        val osName = System.getProperty("os.name")?.lowercase().orEmpty()
        if (!osName.contains("mac")) return null

        val tempFile = try {
            java.nio.file.Files.createTempFile("claude-paste-", ".png").toFile()
        } catch (e: Exception) {
            LOG.warn("Could not create temp file for pasted image", e)
            return null
        }

        // The AppleScript coerces the clipboard to «class PNGf» and writes
        // the binary data to disk. If the clipboard has no image (or only a
        // non-coercible image type), the inner try fails and we get "no".
        val script = """
            try
              set png_data to the clipboard as «class PNGf»
              set out_file to (open for access POSIX file "${tempFile.absolutePath}" with write permission)
              set eof of out_file to 0
              write png_data to out_file
              close access out_file
              return "ok"
            on error errMsg
              try
                close access POSIX file "${tempFile.absolutePath}"
              end try
              return "no:" & errMsg
            end try
        """.trimIndent()

        return try {
            val proc = ProcessBuilder("/usr/bin/osascript", "-e", script)
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader().readText().trim()
            val finished = proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                tempFile.delete()
                LOG.warn("osascript clipboard image read timed out")
                return null
            }
            if (output == "ok" && tempFile.length() > 0) {
                LOG.info("osascript clipboard image read: ${tempFile.length()} bytes -> ${tempFile.absolutePath}")
                tempFile
            } else {
                tempFile.delete()
                LOG.info("osascript clipboard image read: $output (no image on clipboard)")
                null
            }
        } catch (e: Exception) {
            tempFile.delete()
            LOG.warn("osascript clipboard image read failed", e)
            null
        }
    }

    private fun dimensionsOfFile(file: File): Pair<Int, Int>? {
        return try {
            val img = javax.imageio.ImageIO.read(file) ?: return null
            img.width to img.height
        } catch (_: Exception) {
            null
        }
    }

    private fun saveImageToTempFile(image: java.awt.Image): File? {
        return try {
            val width = image.getWidth(null)
            val height = image.getHeight(null)
            if (width <= 0 || height <= 0) return null
            val buffered = if (image is java.awt.image.BufferedImage) {
                image
            } else {
                val bi = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
                val g = bi.createGraphics()
                try {
                    g.drawImage(image, 0, 0, null)
                } finally {
                    g.dispose()
                }
                bi
            }
            val tempFile = java.nio.file.Files.createTempFile("claude-paste-", ".png").toFile()
            javax.imageio.ImageIO.write(buffered, "PNG", tempFile)
            tempFile
        } catch (e: Exception) {
            LOG.warn("Failed to save pasted image", e)
            null
        }
    }

    private fun insertImageReference(file: File, dimensions: Pair<Int, Int>?) {
        val sizeLabel = dimensions?.let { " · ${it.first}×${it.second}" } ?: ""
        // pasteCounter+1: insertChip itself increments before reading.
        val label = "🖼 Pasted image #${pasteCounter + 1}$sizeLabel"
        val content = "@${file.absolutePath}"
        insertChip(label = label, content = content, tooltip = file.absolutePath, expandable = false)
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

        // 3. Big paste → text chip. The AnAction override is the only path
        // that produces text chips for Cmd/Ctrl+V; this branch only fires
        // for non-paste inserts (programmatic edits, autocomplete, etc.)
        // that happen to be large.
        val isLargePaste = text.length >= CHIP_CHAR_THRESHOLD || lines.size > CHIP_LINE_THRESHOLD
        if (isLargePaste) {
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

        // Web URLs (http://, https://, ftp://, …) must never become file chips,
        // even when the last path segment matches a project filename.
        if (looksLikeWebUrl(text)) return null

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
            // A path-like string (absolute path, ~path, or relative path with
            // separators) refers only to that exact location. If it doesn't
            // exist on disk, do NOT fall back to basename matching — otherwise
            // an unrelated absolute path such as /elsewhere/SessionPanel.kt
            // would be silently re-mapped to a same-named project file.
            return null
        }

        // Bare filename (no path separators) — safe to match against the
        // project tree. Only meaningful if it has no spaces and looks like a file.
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

    /**
     * True for strings that carry a non-`file` URI scheme (http, https, ftp,
     * ssh, …). These are web/remote references and must never be resolved to a
     * local file, regardless of how their trailing path segment looks.
     */
    private fun looksLikeWebUrl(text: String): Boolean {
        val idx = text.indexOf("://")
        if (idx <= 0) return false
        val scheme = text.substring(0, idx)
        if (!scheme.all { it.isLetterOrDigit() || it == '+' || it == '.' || it == '-' }) return false
        return !scheme.equals("file", ignoreCase = true)
    }

    private fun findInProjectIndex(fileName: String): String? {
        if (fileName.length > 80) return null

        // All FilenameIndex / VFS reads must run inside a ReadAction. Calling
        // them straight from the EDT throws IllegalStateException — the
        // previous implementation was silently swallowing that, which is why
        // stem search never worked for files like NewSessionAction.kt where
        // IntelliJ's tree hides the extension. We use Application.runReadAction
        // (not ReadAction.nonBlocking().executeSynchronously()) because paste
        // handlers can fire on the EDT, and executeSynchronously deadlocks there.
        return com.intellij.openapi.application.ApplicationManager.getApplication().runReadAction(
            com.intellij.openapi.util.Computable<String?> {
                val scope = com.intellij.psi.search.GlobalSearchScope.projectScope(project)

                // 1. Exact filename match (covers the case where the clipboard
                // already carries the extension, e.g. "FileContextActions.kt").
                try {
                    val exact = com.intellij.psi.search.FilenameIndex.getVirtualFilesByName(fileName, scope)
                    uniquePath(exact)?.let { return@Computable it }
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
                            uniquePath(files)?.let { return@Computable it }
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
                        if (resolvedPaths.size == 1) return@Computable resolvedPaths.first()
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
                    if (matches.size == 1) return@Computable matches.first()
                } catch (e: Throwable) {
                    LOG.warn("ProjectFileIndex walk failed for '$fileName'", e)
                }

                null
            }
        )
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

        // Every chip — pasted content and file references alike — expands back
        // into its underlying text on double-click. For file chips that means
        // revealing the real @<absolute-path>; for pasted chips, the raw text.
        val expandHint = if (expandable) {
            "Double-click to expand into the input"
        } else {
            "Double-click to reveal the path"
        }
        if (chip.toolTipText == null) chip.toolTipText = expandHint
        if (labelComp.toolTipText == null) labelComp.toolTipText = expandHint
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
