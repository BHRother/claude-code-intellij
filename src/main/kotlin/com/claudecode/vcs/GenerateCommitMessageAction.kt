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

        // Read what's *actually selected in the commit dialog* — i.e. the
        // changes the user has ticked. This sidesteps the case where git's
        // index hasn't caught up with the dialog state yet (which would
        // otherwise force the user to click "refresh" before Claude saw the
        // full picture).
        val selectedPaths = collectSelectedPathsFromDialog(e)

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project, "Claude: generating commit message", true
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true

                val (stat, diff) = if (selectedPaths.isNotEmpty()) {
                    collectDiffForPaths(workingDir, selectedPaths)
                } else {
                    collectDiffWithStat(workingDir)
                }
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
                val prompt = buildPrompt(truncated, stat, recentCommits)
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

    /**
     * Returns (stat, patch) for whichever of staged / unstaged actually has
     * changes. The stat summary (one line per file with insert/delete counts)
     * is included separately in the prompt so Claude sees the *shape* of the
     * change set before drowning in the patch bytes — important when one file
     * has a large diff that would otherwise dominate attention.
     */
    private fun collectDiffWithStat(workingDir: String): Pair<String, String> {
        val stagedPatch = runGit(workingDir, listOf("diff", "--cached"))
        if (stagedPatch.isNotBlank()) {
            return Pair(runGit(workingDir, listOf("diff", "--cached", "--stat")), stagedPatch)
        }
        val unstagedPatch = runGit(workingDir, listOf("diff"))
        if (unstagedPatch.isNotBlank()) {
            return Pair(runGit(workingDir, listOf("diff", "--stat")), unstagedPatch)
        }
        return Pair("", "")
    }

    /**
     * Pull the absolute paths of the changes the user currently has selected
     * in the IntelliJ commit dialog. Reads VcsDataKeys.SELECTED_CHANGES, which
     * reflects the dialog's tick-box state and stays in sync without the user
     * needing to click "refresh". A Change can come from various sources
     * (working tree, staged, etc.) so we fall back through the available
     * revisions to find a path.
     */
    private fun collectSelectedPathsFromDialog(e: AnActionEvent): List<String> {
        val changes = e.getData(VcsDataKeys.SELECTED_CHANGES) ?: return emptyList()
        return changes.mapNotNull { change ->
            change.virtualFile?.path
                ?: change.afterRevision?.file?.path
                ?: change.beforeRevision?.file?.path
        }
    }

    /**
     * Diff exactly the files the user selected in the commit dialog, regardless
     * of whether they're already in git's index. `git diff HEAD -- <paths>`
     * shows the working-tree-vs-HEAD diff for those paths, which is what those
     * files will look like after the commit lands.
     *
     * Paths are passed relative to the repo root with forward slashes — git
     * accepts both on Windows but using forward slashes is the safe, portable
     * choice.
     */
    private fun collectDiffForPaths(workingDir: String, absolutePaths: List<String>): Pair<String, String> {
        val workingDirFile = File(workingDir)
        val relativePaths = absolutePaths.mapNotNull { path ->
            val file = File(path)
            val rel = if (file.isAbsolute) {
                try {
                    workingDirFile.toPath().relativize(file.toPath()).toString()
                } catch (_: Exception) {
                    return@mapNotNull null
                }
            } else {
                path
            }
            rel.replace('\\', '/').takeIf { it.isNotBlank() && !it.startsWith("..") }
        }
        if (relativePaths.isEmpty()) return Pair("", "")

        val statArgs = listOf("diff", "HEAD", "--stat", "--") + relativePaths
        val patchArgs = listOf("diff", "HEAD", "--") + relativePaths
        val patch = runGit(workingDir, patchArgs)
        if (patch.isBlank()) return Pair("", "")
        return Pair(runGit(workingDir, statArgs), patch)
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

    private fun buildPrompt(diff: String, stat: String, recentCommits: String): String {
        val styleSection = if (recentCommits.isNotBlank()) {
            """
            These are the most recent commits from this project. Match their style — prefix conventions (or lack thereof), capitalization, voice, subject length, whether bodies are typical, whether scopes are used, anything that defines this project's commit voice. If the project doesn't use Conventional Commits, don't introduce them.

            <recent-commits>
            $recentCommits
            </recent-commits>

            """.trimIndent() + "\n"
        } else {
            """
            No recent commits available for style reference. Use a short imperative subject (under 72 chars), no prefix unless the change is purely chore/docs/test. Add a body only if non-trivial.

            """.trimIndent() + "\n"
        }

        val statSection = if (stat.isNotBlank()) {
            """
            <files-changed>
            $stat
            </files-changed>

            """.trimIndent() + "\n"
        } else ""

        return """
            Write a commit message for the staged changes below.

            How to approach this:
            1. The change set may span multiple files. Use the <files-changed> summary to see the full shape, then read the diff to understand each change.
            2. Find the *unifying intent* tying the files together (e.g. "switching license", "adding feature X", "fixing a regression"). The subject line must reflect that unifying intent, not just the most prominent or largest single file.
            3. If the change set has no single theme, write a subject that names the bundle (e.g. "Misc cleanup") and use the body to list each concern.
            4. Every distinct concern the change set addresses should be covered — either by the subject (if it's one thing) or by the body (if it's several).

            $styleSection
            $statSection
            Rules:
            - Imperative mood ("add" not "added", "fix" not "fixed").
            - Subject line under 72 chars, no trailing period.
            - When the change set has multiple distinct concerns, include a body. Separate from the subject by a blank line; wrap body lines at ~72 chars.
            - Don't enumerate every file by name in the message — the <files-changed> summary already shows them — but DO make sure every distinct concern is reflected somewhere in the message.
            - Return ONLY the commit message text. No preamble like "Here is…", no markdown fences, no commentary, no quotes around it.

            Full diff:
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
