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
    """Read this file and explain it:
1. **Purpose** — what this file is responsible for in the project
2. **Key components** — the main classes, functions, configurations, or resources it defines
3. **How it fits** — how it connects to or is used by other parts of the codebase
4. **Notable details** — any non-obvious patterns, edge cases, or important design decisions

Adapt the explanation to the file type (source code, config, infrastructure, build file, etc.)."""
)

class GenerateTestsFileWithClaudeAction : BaseClaudeFileAction(
    """Read this file and generate unit tests for its public API:
1. Detect and use the same test framework and conventions already used in this project
2. Cover: happy path, edge cases, error conditions, and boundary values
3. Use descriptive test names that explain the scenario being tested
4. Each test should be independent and test one behavior
5. Group tests logically by the method or behavior they cover"""
)

class RefactorFileWithClaudeAction : BaseClaudeFileAction(
    """Read this file and suggest refactoring improvements. For each change:
1. Show the refactored code
2. Explain what changed and why it's better

Focus on: readability, reducing complexity, eliminating duplication, and following idiomatic conventions for the language. Don't change external behavior. Prioritize impactful improvements over cosmetic ones."""
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

class ExplainFolderWithClaudeAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = file != null && file.isDirectory
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val folder = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val tree = buildFolderTree(folder, maxDepth = 2)
        val message = buildPrompt(folder.name, tree)
        sendToClaudeToolWindow(project, message)
    }

    internal fun buildFolderTree(
        dir: com.intellij.openapi.vfs.VirtualFile,
        maxDepth: Int,
        currentDepth: Int = 0
    ): FolderNode {
        val files = mutableListOf<String>()
        val subdirs = mutableListOf<FolderNode>()

        for (child in dir.children.sortedBy { it.name }) {
            if (child.name.startsWith(".")) continue
            if (child.isDirectory) {
                if (currentDepth < maxDepth) {
                    subdirs.add(buildFolderTree(child, maxDepth, currentDepth + 1))
                } else {
                    subdirs.add(FolderNode(child.name, child.path, emptyList(), emptyList()))
                }
            } else {
                files.add(child.name)
            }
        }
        return FolderNode(dir.name, dir.path, files, subdirs)
    }

    internal fun buildPrompt(folderName: String, tree: FolderNode): String {
        val sb = StringBuilder()
        sb.appendLine("Explain the functionality inside this folder/package. Read the files to understand what each part does.")
        sb.appendLine()
        sb.appendLine("## Structure")
        sb.appendLine()
        sb.appendLine("Folder: ${tree.path}")
        sb.appendLine()
        appendTree(sb, tree, indent = "")
        sb.appendLine()
        sb.appendLine("## Instructions")
        sb.appendLine()
        sb.appendLine("1. Start with a **high-level summary** (2-3 sentences) of what `$folderName` does as a whole.")
        sb.appendLine("2. Then for each immediate sub-folder, provide:")
        sb.appendLine("   - A summary of its responsibility")
        sb.appendLine("   - Key files and what they do (source code, configuration, infrastructure, documentation, etc.)")
        sb.appendLine("   - If it has sub-folders of its own, briefly describe each")
        sb.appendLine("3. Adapt the explanation to the file types present: for source code explain classes and methods, for Kubernetes/Docker/CI files explain the deployment and scaling strategy, for config files explain what they configure, etc.")
        sb.appendLine("4. If any folder or file is related to **tests**, explain what is being tested and what scenarios are covered.")
        sb.appendLine("5. Keep explanations concise but informative.")
        return sb.toString().trim()
    }

    private fun appendTree(sb: StringBuilder, node: FolderNode, indent: String) {
        for (file in node.files) {
            sb.appendLine("$indent- $file")
        }
        for (subdir in node.subdirs) {
            sb.appendLine("$indent- ${subdir.name}/")
            appendTree(sb, subdir, "$indent  ")
        }
    }

    data class FolderNode(
        val name: String,
        val path: String,
        val files: List<String>,
        val subdirs: List<FolderNode>
    )
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
        val message = """Read these files and generate unit tests for their public APIs:
1. Detect and use the same test framework and conventions already used in this project
2. For each file, create a corresponding test file
3. Cover: happy path, edge cases, error conditions, and boundary values
4. Use descriptive test names that explain the scenario being tested
5. Each test should be independent and test one behavior

Files:

$fileList"""
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
