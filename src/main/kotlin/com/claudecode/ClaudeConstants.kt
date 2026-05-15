package com.claudecode

object ClaudeConstants {
    const val TOOL_WINDOW_ID = "Claude Code"

    const val MODEL_OPUS_47 = "claude-opus-4-7"
    const val MODEL_OPUS = "claude-opus-4-6"
    const val MODEL_SONNET = "claude-sonnet-4-6"
    const val MODEL_HAIKU = "claude-haiku-4-5-20251001"
    const val MODEL_SONNET_PREV = "claude-sonnet-4-5-20250514"

    const val DEFAULT_CLI_PATH = "claude"
    const val DEFAULT_SHELL = "/bin/zsh"
    const val FONT_FAMILY = "JetBrains Mono"
    const val ENV_TERM_VALUE = "dumb"

    val AVAILABLE_MODELS = listOf("", MODEL_OPUS_47, MODEL_OPUS, MODEL_SONNET, MODEL_HAIKU, MODEL_SONNET_PREV)

    // Maps to the CLI's --permission-mode flag. We expose only the three modes
    // that make sense in -p (non-interactive) mode. The CLI's "default" /
    // "dontAsk" / "auto" assume an interactive terminal that doesn't exist in
    // -p, so they'd silently block tool calls.
    const val PERMISSION_MODE_ACCEPT_EDITS = "acceptEdits"
    const val PERMISSION_MODE_BYPASS = "bypassPermissions"
    const val PERMISSION_MODE_PLAN = "plan"

    val PERMISSION_MODES = listOf(
        PERMISSION_MODE_ACCEPT_EDITS,
        PERMISSION_MODE_BYPASS,
        PERMISSION_MODE_PLAN,
    )

    fun describePermissionMode(mode: String): String = when (mode) {
        PERMISSION_MODE_ACCEPT_EDITS -> "Accept file edits — file writes/edits go through, shell commands still blocked"
        PERMISSION_MODE_BYPASS -> "Bypass all permissions — Claude can run any tool including shell commands"
        PERMISSION_MODE_PLAN -> "Plan (read-only) — Read/Grep/Glob only, useful for exploratory chats"
        else -> mode
    }
}
