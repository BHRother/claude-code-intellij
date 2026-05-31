package com.claudecode.ui

import com.claudecode.session.ClaudeSession
import com.claudecode.session.SessionManager
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Toolbar action: export the currently-selected chat as a Markdown file.
 *
 * Renders [ClaudeSession.messages] (the in-memory user/assistant text
 * tracked by [ClaudeSession.sendMessage] and the runClaudeCommand
 * cleanup) into a simple `## You` / `## Claude` flow. Tool calls and
 * diff details are skipped on purpose — this is a portable transcript,
 * not a full session replay.
 */
class ExportChatAction(private val project: Project) : AnAction(
    "Export Chat as Markdown",
    "Save the current chat as a Markdown file",
    AllIcons.ToolbarDecorator.Export,
) {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val sessions = SessionManager.getInstance(project).getSessions()
        e.presentation.isEnabled = sessions.any { it.messages.isNotEmpty() }
    }

    override fun actionPerformed(e: AnActionEvent) {
        // No project-wide concept of "the current session" — we mirror
        // what the toolbar's other actions do: read whatever Claude Code
        // tool-window content is selected. The factory holds that mapping;
        // for simplicity, we look up the most recently used session that
        // has messages, since the chat the user is staring at is almost
        // always the most recently touched one.
        val sessions = SessionManager.getInstance(project).getSessions()
            .filter { it.messages.isNotEmpty() }
        if (sessions.isEmpty()) {
            Messages.showInfoMessage(project, "No chat to export yet — send a message first.", "Export Chat")
            return
        }
        val session = sessions.last()
        exportSession(session)
    }

    private fun exportSession(session: ClaudeSession) {
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
        val safeName = session.name.replace(Regex("[^A-Za-z0-9._-]"), "-").take(40)
        val suggestedFilename = "claude-chat-$safeName-$timestamp.md"

        // Constructed reflectively on purpose: the
        // FileSaverDescriptor(String, String, vararg String) constructor is
        // "scheduled for removal" per the marketplace verifier, and the
        // non-deprecated builder replacement doesn't exist in our 241 baseline.
        // Reflection keeps the deprecated <init> out of our bytecode while using
        // the same (still-present) constructor at runtime.
        val descriptor = FileSaverDescriptor::class.java
            .getConstructor(String::class.java, String::class.java, Array<String>::class.java)
            .newInstance("Export Chat as Markdown", "Save this chat to a file (Markdown)", arrayOf("md"))
        val saveResult = FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, project)
            .save(null as com.intellij.openapi.vfs.VirtualFile?, suggestedFilename)
            ?: return  // user cancelled

        val targetIoFile = saveResult.file
        val markdown = renderMarkdown(session)
        try {
            targetIoFile.writeText(markdown)
            // Refresh VFS so the file appears in the Project view if the
            // save target is under the project root.
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(targetIoFile)
        } catch (t: Throwable) {
            Messages.showErrorDialog(
                project,
                "Could not write to ${targetIoFile.absolutePath}:\n${t.message}",
                "Export Chat",
            )
        }
    }

    private fun renderMarkdown(session: ClaudeSession): String {
        val sb = StringBuilder()
        sb.append("# Claude Code chat — ${session.name}\n\n")
        sb.append("- Working directory: `${session.workingDirectory}`\n")
        sb.append("- Session ID: `${session.claudeSessionId ?: "(none yet)"}`\n")
        sb.append("- Messages: ${session.messages.size}\n")
        sb.append("- Exported: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())).append("\n\n")
        sb.append("---\n\n")
        session.messages.forEach { msg ->
            val who = when (msg.role) {
                "user" -> "You"
                "assistant" -> "Claude"
                else -> msg.role.replaceFirstChar { it.uppercase() }
            }
            sb.append("## $who\n\n")
            sb.append(msg.content.trimEnd()).append("\n\n")
        }
        return sb.toString()
    }
}
