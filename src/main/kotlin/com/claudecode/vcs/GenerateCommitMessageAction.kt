package com.claudecode.vcs

import com.claudecode.cli.ClaudeOneShot
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.CommitMessageI
import com.intellij.openapi.vcs.VcsDataKeys
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class GenerateCommitMessageAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val commitMessage = e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL)
        e.presentation.isEnabledAndVisible = e.project != null && commitMessage != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val commitMessage = e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) ?: return
        val workingDir = project.basePath ?: return

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project, "Claude: generating commit message", true
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true

                val diff = collectDiff(workingDir)
                if (diff.isBlank()) {
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showInfoMessage(
                            project,
                            "No staged or unstaged changes found. Stage some changes first.",
                            "Generate Commit Message",
                        )
                    }
                    return
                }

                val recentCommits = collectRecentCommits(workingDir)
                val truncated = truncateForPrompt(diff)
                val prompt = buildPrompt(truncated, recentCommits)
                val result = ClaudeOneShot.run(workingDir, prompt, timeoutSeconds = 90)
                val message = cleanMessage(result.text)

                ApplicationManager.getApplication().invokeLater {
                    if (message.isBlank()) {
                        Messages.showWarningDialog(
                            project,
                            "Claude returned an empty response (exit ${result.exitCode}).",
                            "Generate Commit Message",
                        )
                    } else {
                        commitMessage.setCommitMessage(message)
                    }
                }
            }
        })
    }

    private fun collectDiff(workingDir: String): String {
        val staged = runGit(workingDir, listOf("diff", "--cached"))
        if (staged.isNotBlank()) return staged
        return runGit(workingDir, listOf("diff"))
    }

    /**
     * Pulls the last few commit messages (excluding merges) to use as style
     * examples so Claude matches the project's existing voice and conventions
     * instead of imposing Conventional Commits.
     */
    private fun collectRecentCommits(workingDir: String, count: Int = 10): String {
        val sep = "<<<COMMIT-SEP>>>"
        val raw = runGit(workingDir, listOf(
            "log", "-n", count.toString(), "--no-merges",
            "--author=\"$(git config user.name)\"",
            "--pretty=format:%B$sep",
        ))
        if (raw.isBlank()) return ""
        return raw.split(sep)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n\n---\n\n")
    }

    private fun runGit(workingDir: String, args: List<String>): String {
        return try {
            val pb = ProcessBuilder(listOf("git") + args)
                .directory(File(workingDir))
                .redirectErrorStream(true)
            val process = pb.start()
            val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            val finished = process.waitFor(20, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return ""
            }
            if (process.exitValue() == 0) output else ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun truncateForPrompt(diff: String, maxChars: Int = 24_000): String {
        if (diff.length <= maxChars) return diff
        return diff.take(maxChars) + "\n\n[…diff truncated, ${diff.length - maxChars} chars omitted]"
    }

    private fun buildPrompt(diff: String, recentCommits: String): String {
        val styleSection = if (recentCommits.isNotBlank()) {
            """
            These are the most recent commits from this project. Match their style — prefix conventions (or lack thereof), capitalization, voice, subject length, whether bodies are typical, whether scopes are used, anything that defines this project's commit voice. If the project doesn't use Conventional Commits, don't introduce them.

            <recent-commits>
            $recentCommits
            </recent-commits>

            """.trimIndent() + "\n"
        } else {
            // Empty repo or git unavailable — fall back to a sensible default style.
            """
            No recent commits available for style reference. Use a short imperative subject (under 72 chars), no prefix unless the change is purely chore/docs/test. Add a body only if non-trivial.

            """.trimIndent() + "\n"
        }

        return """
            Write a commit message for the staged changes below.

            $styleSection
            Rules that always apply:
            - Imperative mood ("add" not "added", "fix" not "fixed").
            - Capture intent and the "why": what behavior changes, what problem is solved, what constraint motivated the change. Avoid file-by-file enumeration of what was edited — the diff already shows that.
            - Subject line on its own, then a blank line, then an optional body wrapped at ~72 chars. Skip the body if a one-line subject is enough.
            - No trailing period on the subject.
            - Return ONLY the commit message text. No preamble like "Here is…", no markdown fences, no commentary, no quotes around it.

            Diff:
            $diff
        """.trimIndent()
    }

    private fun cleanMessage(text: String): String {
        var msg = text.trim()

        // Strip outer code fences if Claude ignored the instruction
        val lines = msg.lines()
        if (lines.size >= 2 &&
            lines.first().trimStart().startsWith("```") &&
            lines.last().trim() == "```"
        ) {
            msg = lines.subList(1, lines.size - 1).joinToString("\n").trim()
        }

        // Strip a single pair of wrapping quotes if Claude added them
        if (msg.length >= 2 &&
            (msg.first() == '"' || msg.first() == '“') &&
            (msg.last() == '"' || msg.last() == '”')
        ) {
            msg = msg.substring(1, msg.length - 1).trim()
        }

        // Drop a "Here is..." / "Here's the commit message:" preamble if present
        val firstNl = msg.indexOf('\n')
        if (firstNl > 0) {
            val firstLine = msg.substring(0, firstNl).trim().lowercase()
            if (firstLine.startsWith("here is") || firstLine.startsWith("here's") ||
                firstLine.endsWith("commit message:")
            ) {
                msg = msg.substring(firstNl + 1).trim()
            }
        }

        // Collapse 3+ consecutive blank lines down to a single blank line
        msg = msg.replace(Regex("\n{3,}"), "\n\n")

        return msg.trim()
    }
}
