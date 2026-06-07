package com.claudecode.mcp

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.dsl.builder.*
import javax.swing.JComponent
import javax.swing.JTextField

/**
 * Add/edit form for a single MCP server. Returns the constructed [McpServer] in
 * [result]; the caller is responsible for applying it via the `claude mcp` CLI.
 *
 * Args/env/headers use one-entry-per-line text areas (args: raw lines;
 * env: `KEY=VALUE`; headers: `Key: Value`) — compact, predictable, and natural
 * for the power users who manage MCP servers.
 */
class McpServerEditDialog(
    private val project: Project,
    private val existing: McpServer?,
    /** Names already used per scope, to enforce uniqueness (excludes [existing]). */
    private val takenNamesByScope: Map<McpScope, Set<String>>,
    /** When true (the server is failing/needs-auth) and No Auth is selected, show a hint. */
    private val needsAuthHint: Boolean = false,
) : DialogWrapper(project, true) {

    var result: McpServer? = null
        private set

    private lateinit var scopeCombo: javax.swing.JComboBox<McpScope>
    private lateinit var nameField: JTextField
    private lateinit var transportCombo: javax.swing.JComboBox<McpTransport>
    private lateinit var commandField: JTextField
    private lateinit var argsArea: javax.swing.JTextArea
    private lateinit var envArea: javax.swing.JTextArea
    private lateinit var urlField: JTextField
    private lateinit var authTypeCombo: javax.swing.JComboBox<McpAuthType>
    private lateinit var apiTokenField: JTextField
    private lateinit var headersArea: javax.swing.JTextArea
    private lateinit var clientIdField: JTextField
    private lateinit var clientSecretField: com.intellij.ui.components.JBPasswordField
    private lateinit var callbackPortField: JTextField

    private val stdioRows = mutableListOf<Row>()
    private val remoteRows = mutableListOf<Row>()    // common remote rows (URL, headers, auth type)
    private val apiKeyRows = mutableListOf<Row>()    // shown only when auth type = API Key
    private val oauthRows = mutableListOf<Row>()     // shown only when auth type = OAuth
    private var authHintRow: Row? = null             // "looks like it needs auth" notice (No Auth + failing)

    init {
        title = if (existing == null) "Add MCP Server" else "Edit MCP Server — ${existing.name}"
        setOKButtonText(if (existing == null) "Add" else "Save")
        init()
        updateVisibility()
    }

    override fun createCenterPanel(): JComponent {
        val scopeRenderer = com.claudecode.ui.comboTextRenderer<McpScope> { it.display }
        val transportRenderer = com.claudecode.ui.comboTextRenderer<McpTransport> { it.cliValue }
        val authTypeRenderer = com.claudecode.ui.comboTextRenderer<McpAuthType> { it.display }
        val panel = panel {
            row("Scope:") {
                comboBox(McpScope.entries.toList(), scopeRenderer)
                    .applyToComponent { scopeCombo = this }
                    .comment("Where the server is stored. Maps to <code>claude mcp add --scope</code>.")
            }
            row("Name:") {
                textField().columns(28).applyToComponent { nameField = this }
                    .comment("Unique identifier, no spaces (e.g. <code>github</code>, <code>sentry</code>).")
            }
            row("Transport:") {
                comboBox(McpTransport.entries.toList(), transportRenderer)
                    .applyToComponent {
                        transportCombo = this
                        addItemListener { updateVisibility() }
                    }
            }
            stdioRows += row("Command:") {
                textField().columns(36).applyToComponent { commandField = this }
                    .comment("Executable to launch, e.g. <code>npx</code>.")
            }
            stdioRows += row("Args:") {
                textArea().rows(3).align(Align.FILL).applyToComponent { argsArea = this }
                    .comment(
                        "One argument per line, e.g. <code>-y</code> then <code>@upstash/context7-mcp@latest</code>. " +
                            "Space-separated tokens on a line are split into separate args on save."
                    )
            }
            stdioRows += row("Environment:") {
                textArea().rows(3).align(Align.FILL).applyToComponent { envArea = this }
                    .comment("One <code>KEY=VALUE</code> per line.")
            }
            remoteRows += row("URL:") {
                textField().columns(36).applyToComponent { urlField = this }
                    .comment("Remote endpoint, e.g. <code>https://mcp.sentry.dev/mcp</code>.")
            }
            remoteRows += row("Headers:") {
                textArea().rows(3).align(Align.FILL).applyToComponent { headersArea = this }
                    .comment(
                        "Advanced — one <code>Header-Name: value</code> per line, for any auth type. An API " +
                            "token (below, for API Key auth) is merged in as the <code>Authorization</code> header."
                    )
            }
            remoteRows += row("Authorization:") {
                comboBox(McpAuthType.entries.toList(), authTypeRenderer)
                    .applyToComponent {
                        authTypeCombo = this
                        addItemListener { updateVisibility() }
                    }
                    .comment(
                        "How this server authenticates. <b>API Key / Token</b> for a PAT/key (e.g. GitHub), " +
                            "<b>OAuth</b> for browser sign-in (e.g. Sentry, Google Drive), <b>No Auth</b> for open " +
                            "servers. The <b>Headers</b> field applies to every type."
                    )
            }
            authHintRow = row("") {
                comment(
                    "⚠ This server <b>failed to connect</b> — it probably requires authentication. " +
                        "Pick <b>API Key / Token</b> or <b>OAuth</b> above."
                )
            }
            apiKeyRows += row("API token:") {
                textField().columns(36).applyToComponent { apiTokenField = this }
                    .comment(
                        "Sent as <code>Authorization: Bearer &lt;token&gt;</code> — authenticates directly in " +
                            "chat sessions with no OAuth flow or <code>/mcp</code> step. Use a personal access " +
                            "token / API key (e.g. a GitHub PAT)."
                    )
            }
            oauthRows += row("OAuth client ID:") {
                textField().columns(28).applyToComponent { clientIdField = this }
                    .comment("For OAuth providers that need a pre-registered client (e.g. Google Drive). " +
                        "Leave blank for providers that register dynamically (e.g. Sentry).")
            }
            oauthRows += row("OAuth client secret:") {
                cell(com.intellij.ui.components.JBPasswordField())
                    .columns(28)
                    .applyToComponent { clientSecretField = this }
                    .comment(
                        "<b>Required by confidential clients</b> (e.g. Google) — without it the " +
                            "code→token exchange fails and the server stays unauthenticated even after " +
                            "the browser says “success”. Stored securely by Claude (never in config). " +
                            "Write-only: re-enter it whenever you edit the server, since editing re-creates it."
                    )
            }
            oauthRows += row("OAuth callback port:") {
                textField().columns(8).applyToComponent { callbackPortField = this }
                    .comment("Optional. Fixed port for the OAuth redirect, if the server pre-registers one.")
            }
        }
        prefill()
        return panel
    }

    private fun prefill() {
        if (existing != null) {
            scopeCombo.selectedItem = existing.scope
            nameField.text = existing.name
            transportCombo.selectedItem = existing.transport
            commandField.text = existing.command
            argsArea.text = existing.args.joinToString("\n")
            envArea.text = existing.env.entries.joinToString("\n") { "${it.key}=${it.value}" }
            urlField.text = existing.url
            // Pull a Bearer Authorization header into the dedicated token field;
            // show any other headers in the advanced area.
            val authEntry = existing.headers.entries.firstOrNull { it.key.equals("Authorization", true) }
            val bearer = authEntry?.value?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
                ?.substring("Bearer ".length)?.trim()
            apiTokenField.text = bearer.orEmpty()
            val shownHeaders = if (bearer != null)
                existing.headers.filterKeys { !it.equals("Authorization", true) } else existing.headers
            headersArea.text = shownHeaders.entries.joinToString("\n") { "${it.key}: ${it.value}" }
            clientIdField.text = existing.clientId
            callbackPortField.text = existing.callbackPort?.toString().orEmpty()
            // Auth type is inferred from the saved config (see McpAuthType.infer).
            authTypeCombo.selectedItem = McpAuthType.infer(existing)
        } else {
            scopeCombo.selectedItem = McpScope.LOCAL
            transportCombo.selectedItem = McpTransport.STDIO
            authTypeCombo.selectedItem = McpAuthType.API_KEY
        }
    }

    private fun selectedTransport(): McpTransport = transportCombo.selectedItem as? McpTransport ?: McpTransport.STDIO

    private fun selectedAuthType(): McpAuthType = authTypeCombo.selectedItem as? McpAuthType ?: McpAuthType.OAUTH

    private fun updateVisibility() {
        if (stdioRows.isEmpty()) return
        val stdio = selectedTransport() == McpTransport.STDIO
        stdioRows.forEach { it.visible(stdio) }
        remoteRows.forEach { it.visible(!stdio) }
        // Within a remote server, show only the fields for the chosen auth type.
        val authType = if (stdio) null else selectedAuthType()
        apiKeyRows.forEach { it.visible(authType == McpAuthType.API_KEY) }
        oauthRows.forEach { it.visible(authType == McpAuthType.OAUTH) }
        // Only nudge when the server is failing AND still set to No Auth.
        authHintRow?.visible(needsAuthHint && authType == McpAuthType.NONE)
    }

    override fun doValidate(): ValidationInfo? {
        val name = nameField.text.trim()
        if (name.isEmpty()) return ValidationInfo("Name is required.", nameField)
        if (name.any { it.isWhitespace() }) return ValidationInfo("Name must not contain spaces.", nameField)
        val scope = scopeCombo.selectedItem as McpScope
        val taken = takenNamesByScope[scope].orEmpty()
        if (name in taken) return ValidationInfo("A server named “$name” already exists in ${scope.display}.", nameField)

        if (selectedTransport() == McpTransport.STDIO) {
            if (commandField.text.trim().isEmpty()) return ValidationInfo("Command is required for stdio.", commandField)
            parseEnv(envArea.text)?.let { return ValidationInfo(it, envArea) }
        } else {
            val url = urlField.text.trim()
            if (url.isEmpty()) return ValidationInfo("URL is required for ${selectedTransport().cliValue}.", urlField)
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                return ValidationInfo("URL must start with http:// or https://.", urlField)
            }
            parseHeaders(headersArea.text)?.let { return ValidationInfo(it, headersArea) }
            if (selectedAuthType() == McpAuthType.OAUTH) {
                val port = callbackPortField.text.trim()
                if (port.isNotEmpty() && port.toIntOrNull() == null) {
                    return ValidationInfo("Callback port must be a number.", callbackPortField)
                }
            }
        }
        return null
    }

    override fun doOKAction() {
        val scope = scopeCombo.selectedItem as McpScope
        val name = nameField.text.trim()
        val transport = selectedTransport()
        result = if (transport == McpTransport.STDIO) {
            McpServer(
                name = name, scope = scope, transport = transport,
                command = commandField.text.trim(),
                // One argument per line; space-separated tokens on a single line
                // are split into separate args (see splitMcpArgs).
                args = splitMcpArgs(argsArea.text),
                env = parseEnvMap(envArea.text),
            )
        } else {
            val headers = LinkedHashMap(parseHeadersMap(headersArea.text))
            var clientId = ""
            var clientSecret = ""
            var callbackPort: Int? = null
            // Only the selected auth type contributes its fields, so switching
            // type doesn't drag along stale values from another type's inputs.
            when (selectedAuthType()) {
                McpAuthType.API_KEY ->
                    apiTokenField.text.trim().takeIf { it.isNotEmpty() }
                        ?.let { headers["Authorization"] = "Bearer $it" }
                McpAuthType.OAUTH -> {
                    clientId = clientIdField.text.trim()
                    clientSecret = String(clientSecretField.password).trim()
                    callbackPort = callbackPortField.text.trim().toIntOrNull()
                }
                McpAuthType.NONE -> { /* no auth fields */ }
            }
            McpServer(
                name = name, scope = scope, transport = transport,
                url = urlField.text.trim(),
                headers = headers,
                clientId = clientId,
                clientSecret = clientSecret,
                callbackPort = callbackPort,
            )
        }
        super.doOKAction()
    }

    // --- line parsers (return an error string, or null if valid) ---

    private fun parseEnv(text: String): String? {
        for (line in text.lines()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            if (!t.contains('=') || t.substringBefore('=').isBlank()) return "Invalid env line: “$t” (expected KEY=VALUE)."
        }
        return null
    }

    private fun parseEnvMap(text: String): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        for (line in text.lines()) {
            val t = line.trim()
            if (t.isEmpty() || !t.contains('=')) continue
            m[t.substringBefore('=').trim()] = t.substringAfter('=')
        }
        return m
    }

    private fun parseHeaders(text: String): String? {
        for (line in text.lines()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            if (!t.contains(':') || t.substringBefore(':').isBlank()) return "Invalid header line: “$t” (expected Name: value)."
        }
        return null
    }

    private fun parseHeadersMap(text: String): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        for (line in text.lines()) {
            val t = line.trim()
            if (t.isEmpty() || !t.contains(':')) continue
            m[t.substringBefore(':').trim()] = t.substringAfter(':').trim()
        }
        return m
    }
}
