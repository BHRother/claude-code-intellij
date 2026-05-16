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
        PERMISSION_MODE_PLAN,
        PERMISSION_MODE_ACCEPT_EDITS,
        PERMISSION_MODE_BYPASS,
    )

    fun describePermissionMode(mode: String): String = when (mode) {
        PERMISSION_MODE_PLAN -> "Plan — read-only (Read/Grep/Glob only), useful for exploratory chats"
        PERMISSION_MODE_ACCEPT_EDITS -> "Content Only — file writes/edits go through, shell commands still blocked"
        PERMISSION_MODE_BYPASS -> "Unrestricted — Claude can run any tool including shell commands"
        else -> mode
    }

    /** Short labels for chip-row dropdowns. */
    fun shortPermissionModeLabel(mode: String): String = when (mode) {
        PERMISSION_MODE_PLAN -> "Plan"
        PERMISSION_MODE_ACCEPT_EDITS -> "Content Only"
        PERMISSION_MODE_BYPASS -> "Unrestricted"
        else -> mode
    }

    /**
     * Internal placeholders that Claude emits in stream-json output but which
     * aren't real selectable models. `<synthetic>` appears for responses that
     * didn't hit the API (cached / interrupted / tool-only turns). These
     * should never be shown to the user, added to the model dropdown, or
     * trigger a "model diverged" warning.
     */
    fun isPlaceholderModel(model: String): Boolean {
        if (model.isBlank()) return false
        return model.startsWith("<") && model.endsWith(">")
    }

    /**
     * Friendly model name for chip-row dropdown. Empty string → "Default"
     * (CLI's own choice). Known IDs collapse to "Opus 4.7" etc.; unknown
     * IDs (custom models) display as-is.
     */
    fun shortModelLabel(model: String): String = when (model) {
        "" -> "Default"
        MODEL_OPUS_47 -> "Opus 4.7"
        MODEL_OPUS -> "Opus 4.6"
        MODEL_SONNET -> "Sonnet 4.6"
        MODEL_HAIKU -> "Haiku 4.5"
        MODEL_SONNET_PREV -> "Sonnet 4.5"
        else -> model
    }
}
