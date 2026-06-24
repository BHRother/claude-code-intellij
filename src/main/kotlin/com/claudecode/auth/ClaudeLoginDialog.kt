package com.claudecode.auth

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.*
import com.intellij.util.ui.JBUI
import javax.swing.Action
import javax.swing.JComponent

/**
 * `/login` — sign in to / out of your Anthropic account, the IDE-native
 * equivalent of Claude Code's `/login` REPL command.
 *
 * Shows the current [ClaudeAuthCli.AuthStatus], then drives [ClaudeLoginFlow]:
 * pick subscription vs Console, press Sign in (claude opens the browser and we
 * surface the authorize link), approve in the browser, then paste the code back.
 * Sign out runs `claude auth logout`. All CLI work happens off the EDT.
 */
class ClaudeLoginDialog(private val project: Project) : DialogWrapper(project, true) {

    private val cli = ClaudeAuthCli(project.basePath)

    private lateinit var statusLabel: JBTextField
    private lateinit var consoleCheck: javax.swing.JCheckBox
    private lateinit var emailField: JBTextField
    private lateinit var signInButton: javax.swing.JButton
    private lateinit var signOutButton: javax.swing.JButton

    private lateinit var urlLink: HyperlinkLabel
    private lateinit var codeField: JBTextField
    private lateinit var submitButton: javax.swing.JButton
    private lateinit var progressLabel: javax.swing.JLabel

    private var codeRow: Row? = null
    private var urlRow: Row? = null
    private var flow: ClaudeLoginFlow? = null

    init {
        title = "Anthropic Account — /login"
        init()
        refreshStatus()
    }

    override fun createActions(): Array<Action> = arrayOf(cancelAction.apply { putValue(Action.NAME, "Close") })

    override fun createCenterPanel(): JComponent {
        val panel = panel {
            row("Status:") {
                statusLabel = JBTextField("Checking…").apply { isEditable = false; columns = 36 }
                cell(statusLabel).align(AlignX.FILL)
            }
            row("Account type:") {
                consoleCheck = checkBox("Use Anthropic Console (API billing) instead of subscription")
                    .component
            }
            row("Email (optional):") {
                emailField = JBTextField().apply { columns = 28 }
                cell(emailField)
                comment("Pre-fills the sign-in page; leave blank to choose in the browser.")
            }
            row {
                signInButton = button("Sign in") { startLogin() }.component
                signOutButton = button("Sign out") { startLogout() }.component
            }
            urlRow = row {
                urlLink = HyperlinkLabel("Open the sign-in page")
                cell(urlLink)
                comment("If the browser didn't open automatically, click here.")
            }.visible(false)
            codeRow = row("Paste code:") {
                codeField = JBTextField().apply { columns = 30 }
                cell(codeField)
                submitButton = button("Submit") { submitCode() }.component
            }.visible(false)
            row {
                progressLabel = label("").component
            }
        }
        panel.preferredSize = panel.preferredSize.apply { width = JBUI.scale(520) }
        return panel
    }

    /** Load the current auth status off the EDT and reflect it in the UI. */
    private fun refreshStatus() {
        statusLabel.text = "Checking…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val status = cli.status()
            onEdt {
                val loggedIn = status?.loggedIn == true
                statusLabel.text = status?.describe() ?: "Couldn't read status (is the Claude CLI installed?)"
                signOutButton.isEnabled = loggedIn
                signInButton.text = if (loggedIn) "Switch account" else "Sign in"
                if (status?.email != null && emailField.text.isBlank()) emailField.text = status.email
            }
        }
    }

    private fun startLogin() {
        setControlsEnabled(false)
        progressLabel.text = "Opening browser to sign in…"
        urlRow?.visible(false)
        codeRow?.visible(false)
        flow = ClaudeLoginFlow.start(
            workDir = project.basePath,
            useConsole = consoleCheck.isSelected,
            email = emailField.text.trim().ifBlank { null },
            onUrl = { url ->
                onEdt {
                    urlLink.setHyperlinkTarget(url)
                    urlRow?.visible(true)
                    codeRow?.visible(true)
                    progressLabel.text = "Approve access in the browser, then paste the code shown."
                    codeField.requestFocusInWindow()
                }
            },
            onFinish = { ok, _ -> onEdt { onLoginFinished(ok) } },
        )
        if (flow == null) {
            progressLabel.text = "Couldn't start sign-in. Check the Claude CLI path in Settings."
            setControlsEnabled(true)
        }
    }

    private fun submitCode() {
        val code = codeField.text.trim()
        if (code.isEmpty()) return
        submitButton.isEnabled = false
        codeField.isEnabled = false
        progressLabel.text = "Completing sign-in…"
        flow?.submitCode(code)
    }

    private fun onLoginFinished(ok: Boolean) {
        flow = null
        urlRow?.visible(false)
        codeRow?.visible(false)
        codeField.text = ""
        codeField.isEnabled = true
        submitButton.isEnabled = true
        setControlsEnabled(true)
        progressLabel.text = if (ok) "✓ Signed in." else "Sign-in didn't complete. Try again."
        refreshStatus()
    }

    private fun startLogout() {
        setControlsEnabled(false)
        progressLabel.text = "Signing out…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val res = cli.logout()
            onEdt {
                progressLabel.text = if (res.success) "Signed out." else "Sign-out failed: ${res.message()}"
                setControlsEnabled(true)
                refreshStatus()
            }
        }
    }

    private fun setControlsEnabled(enabled: Boolean) {
        signInButton.isEnabled = enabled
        signOutButton.isEnabled = enabled && statusLabel.text.let { it != "Not signed in" && !it.startsWith("Couldn't") }
        consoleCheck.isEnabled = enabled
        emailField.isEnabled = enabled
    }

    override fun doCancelAction() {
        flow?.cancel()
        super.doCancelAction()
    }

    private fun onEdt(block: () -> Unit) =
        ApplicationManager.getApplication().invokeLater(block, ModalityState.any())
}
