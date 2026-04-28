package com.claudecode

object ClaudeConstants {
    const val TOOL_WINDOW_ID = "Claude Code"

    const val MODEL_OPUS = "claude-opus-4-6"
    const val MODEL_SONNET = "claude-sonnet-4-6"
    const val MODEL_HAIKU = "claude-haiku-4-5-20251001"
    const val MODEL_SONNET_PREV = "claude-sonnet-4-5-20250514"

    const val COMPLETION_MODEL = MODEL_HAIKU

    const val DEFAULT_CLI_PATH = "claude"
    const val DEFAULT_SHELL = "/bin/zsh"
    const val FONT_FAMILY = "JetBrains Mono"
    const val ENV_TERM_VALUE = "dumb"

    val AVAILABLE_MODELS = listOf("", MODEL_OPUS, MODEL_SONNET, MODEL_HAIKU, MODEL_SONNET_PREV)
}
