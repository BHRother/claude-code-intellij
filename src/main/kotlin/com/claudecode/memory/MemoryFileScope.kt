package com.claudecode.memory

import java.io.File

/** The CLAUDE.md "memory" scopes Claude Code reads, in the order shown in the editor. */
enum class MemoryFileScope(val display: String) {
    PROJECT("Project — CLAUDE.md (shared / checked in)"),
    PROJECT_LOCAL("Project-local — CLAUDE.local.md (this machine, gitignored)"),
    USER("User — ~/.claude/CLAUDE.md (all your projects)");

    fun file(projectBasePath: String?): File {
        val base = projectBasePath ?: System.getProperty("user.home")
        return when (this) {
            PROJECT -> File(base, "CLAUDE.md")
            PROJECT_LOCAL -> File(base, "CLAUDE.local.md")
            USER -> ClaudeMemoryStore.userFile()
        }
    }
}
