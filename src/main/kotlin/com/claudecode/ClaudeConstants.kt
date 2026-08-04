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

    // ───────── Appearance / theme ─────────
    // How the chat UI derives its color palette. FOLLOW_IDE tracks the active
    // IDE theme (light vs dark) and blends in the real editor/panel colors;
    // DARK/LIGHT pin one of two hand-tuned presets regardless of the IDE.
    // See com.claudecode.ui.theme.ChatTheme.
    const val THEME_FOLLOW_IDE = "FOLLOW_IDE"
    const val THEME_DARK = "DARK"
    const val THEME_LIGHT = "LIGHT"
    val THEME_MODES = listOf(THEME_FOLLOW_IDE, THEME_DARK, THEME_LIGHT)

    fun shortThemeModeLabel(mode: String): String = when (mode) {
        THEME_FOLLOW_IDE -> "Follow IDE theme"
        THEME_DARK -> "Dark"
        THEME_LIGHT -> "Light"
        else -> mode
    }

    fun describeThemeMode(mode: String): String = when (mode) {
        THEME_FOLLOW_IDE -> "Match the IDE's active theme (light or dark) and blend in its editor colors"
        THEME_DARK -> "Always use the dark palette (the plugin's original look)"
        THEME_LIGHT -> "Always use the light palette"
        else -> mode
    }

    val AVAILABLE_MODELS = listOf("", MODEL_OPUS_47, MODEL_OPUS, MODEL_SONNET, MODEL_HAIKU, MODEL_SONNET_PREV)

    // ───────── Remote model catalog ─────────
    // The plugin fetches a JSON list of currently-supported model IDs from a
    // public GitHub Gist so we can ship new models / deprecate retired ones
    // without a plugin release. Schema: see models/ModelsCatalog.kt and the
    // bundled fallback at resources/claude-code-intellij-models.json.
    //
    const val MODELS_CATALOG_URL =
        "https://gist.githubusercontent.com/BHRother/0216a8b6f8eef1245db171638f6cf2ce/raw/claude-code-intellij-models.json"
    /** Cache TTL for the remote catalog. After this, the next access triggers a background refresh. */
    const val MODELS_CATALOG_TTL_MS: Long = 24L * 60L * 60L * 1000L

    // ───────── Extended thinking budget ─────────
    // Maps user-facing labels to the integer token budget passed to claude
    // via the MAX_THINKING_TOKENS env var. Only Opus / Sonnet 4.x families
    // actually act on this; on other models the env var is ignored.
    // "off" is the absence of the env var (no extended thinking).
    const val THINKING_OFF = "off"
    const val THINKING_LOW = "low"
    const val THINKING_MEDIUM = "medium"
    const val THINKING_HIGH = "high"

    val THINKING_BUDGETS = listOf(THINKING_OFF, THINKING_LOW, THINKING_MEDIUM, THINKING_HIGH)

    fun thinkingBudgetTokens(level: String): Int? = when (level) {
        THINKING_LOW -> 1024
        THINKING_MEDIUM -> 8192
        THINKING_HIGH -> 32768
        else -> null  // off / unknown → don't set the env var
    }

    fun shortThinkingBudgetLabel(level: String): String = when (level) {
        THINKING_OFF -> "Off"
        THINKING_LOW -> "Low (1k)"
        THINKING_MEDIUM -> "Medium (8k)"
        THINKING_HIGH -> "High (32k)"
        else -> level
    }

    fun describeThinkingBudget(level: String): String = when (level) {
        THINKING_OFF -> "No extended thinking — fastest, lowest cost"
        THINKING_LOW -> "Up to 1,024 thinking tokens — small boost on tricky tasks"
        THINKING_MEDIUM -> "Up to 8,192 thinking tokens — strong boost on harder reasoning"
        THINKING_HIGH -> "Up to 32,768 thinking tokens — maximum reasoning depth, slower and more expensive"
        else -> level
    }

    // ───────── Effort level (--effort) ─────────
    // Per-session effort the CLI spends on a task. Blank = the CLI's own default
    // (currently "high"). Higher = more thorough, slower, more expensive.
    const val EFFORT_DEFAULT = ""
    val EFFORT_LEVELS = listOf(EFFORT_DEFAULT, "low", "medium", "high", "xhigh", "max")

    fun shortEffortLabel(level: String): String = when (level) {
        EFFORT_DEFAULT -> "Default"
        "low" -> "Low"
        "medium" -> "Medium"
        "high" -> "High"
        "xhigh" -> "X-High"
        "max" -> "Max"
        else -> level
    }

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
     * Reduces a provider-qualified model ID to its canonical Anthropic ID so
     * IDs from different sources can be compared.
     *
     * Bedrock and Vertex take an inference-profile ID (what the user selects,
     * e.g. `us.anthropic.claude-opus-5[1m]`) but report back the plain model
     * name (`claude-opus-5`). Comparing those raw strings makes every
     * Bedrock/Vertex turn look like a fallback. Strips, in order:
     *   - a `[1m]` (or similar) context-window suffix
     *   - everything up to and including the `anthropic.` vendor segment,
     *     which also removes any `us.` / `eu.` / `apac.` / `global.`
     *     cross-region routing prefix without having to enumerate them
     *   - a Bedrock `-v1:0` style version suffix
     */
    fun canonicalModelId(model: String): String {
        if (model.isBlank()) return model
        var id = model.trim()
        id = id.substringBefore('[')
        if (id.contains("anthropic.")) id = id.substringAfterLast("anthropic.")
        id = id.replace(Regex("-v\\d+:\\d+$"), "")
        return id
    }

    /**
     * True when [selected] and [reported] name the same underlying model and
     * only differ by provider decoration — i.e. not a real model fallback.
     */
    fun isSameModel(selected: String, reported: String): Boolean =
        canonicalModelId(selected) == canonicalModelId(reported)

    /**
     * Friendly model name for chip-row dropdown. Empty string → "Default"
     * (CLI's own choice). Known IDs collapse to their friendly label from
     * the live catalog (which the plugin refreshes from the published
     * Gist); unknown IDs (custom models) display as-is.
     */
    fun shortModelLabel(model: String): String {
        if (model.isBlank()) return "Default"
        // Live catalog first — picks up renames / new entries shipped via
        // the remote JSON without a plugin release.
        com.claudecode.models.ModelsRegistry.catalog().findById(model)?.let {
            return it.name
        }
        // Fallback to hardcoded short names for the original ship list,
        // in case the bundled JSON failed to load entirely.
        return when (model) {
            MODEL_OPUS_47 -> "Opus 4.7"
            MODEL_OPUS -> "Opus 4.6"
            MODEL_SONNET -> "Sonnet 4.6"
            MODEL_HAIKU -> "Haiku 4.5"
            MODEL_SONNET_PREV -> "Sonnet 4.5"
            else -> model
        }
    }
}
