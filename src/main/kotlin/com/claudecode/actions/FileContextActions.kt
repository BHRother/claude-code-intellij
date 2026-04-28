package com.claudecode.actions

import com.claudecode.toolwindow.sendContextToClaudeToolWindow
import com.claudecode.toolwindow.sendToClaudeToolWindow
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys

abstract class BaseClaudeFileAction(
    private val prompt: String
) : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = file != null && !file.isDirectory
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val message = "$prompt\n\nFile: ${file.path}"
        sendToClaudeToolWindow(project, message)
    }
}

class ExplainFileWithClaudeAction : BaseClaudeFileAction(
    "Explain this file in detail. What does it do, what are the key classes/methods, and why is it structured this way?"
)

class GenerateTestsFileWithClaudeAction : BaseClaudeFileAction(
    "Generate comprehensive unit tests for all the public methods in this file."
)

class RefactorFileWithClaudeAction : BaseClaudeFileAction(
    "Review this file and suggest refactoring improvements for readability, performance, and best practices."
)

class SendFileToClaudeAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = file != null && !file.isDirectory
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val context = "File: ${file.path}\n\n"
        sendContextToClaudeToolWindow(project, context)
    }
}

class GenerateTestsFolderWithClaudeAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = file != null && file.isDirectory
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val folder = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val files = collectSourceFiles(folder)
        if (files.isEmpty()) return

        val fileList = files.joinToString("\n") { "- ${it.path}" }
        val message = "Generate comprehensive unit tests for all the public methods in each of the following files:\n\n$fileList"
        sendToClaudeToolWindow(project, message)
    }

    private fun collectSourceFiles(dir: com.intellij.openapi.vfs.VirtualFile): List<com.intellij.openapi.vfs.VirtualFile> {
        val result = mutableListOf<com.intellij.openapi.vfs.VirtualFile>()
        for (child in dir.children) {
            if (child.isDirectory) {
                result.addAll(collectSourceFiles(child))
            } else if (!child.name.startsWith(".") && isSourceFile(child.name)) {
                result.add(child)
            }
        }
        return result
    }

    private fun isSourceFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in setOf(
            "java", "kt", "kts", "py", "js", "mjs", "ts", "mts", "tsx", "jsx",
            "scala", "go", "rs", "swift", "c", "cpp", "cc", "h", "hpp",
            "cs", "rb", "php", "groovy", "dart", "lua"
        )
    }
}
