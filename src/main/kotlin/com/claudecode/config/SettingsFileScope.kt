package com.claudecode.config

import java.io.File

/** The three Claude Code settings.json scopes, in the order shown in the editor. */
enum class SettingsFileScope(val display: String) {
    PROJECT("Project — .claude/settings.json (shared / checked in)"),
    PROJECT_LOCAL("Project-local — .claude/settings.local.json (this machine, gitignored)"),
    GLOBAL("Global — ~/.claude/settings.json (all your projects)");

    fun file(projectBasePath: String?): File {
        val base = projectBasePath ?: System.getProperty("user.home")
        return when (this) {
            PROJECT -> ClaudeSettingsFileStore.projectFile(base)
            PROJECT_LOCAL -> ClaudeSettingsFileStore.projectLocalFile(base)
            GLOBAL -> ClaudeSettingsFileStore.globalFile()
        }
    }
}
