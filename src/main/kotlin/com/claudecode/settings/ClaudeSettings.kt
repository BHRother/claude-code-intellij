package com.claudecode.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(
    name = "ClaudeCodeSettings",
    storages = [Storage("ClaudeCodeSettings.xml")]
)
class ClaudeSettings : PersistentStateComponent<ClaudeSettings.State> {

    data class State(
        var claudePath: String = com.claudecode.ClaudeConstants.DEFAULT_CLI_PATH,
        var model: String = "",
        var maxSessions: Int = 10,
        var fontSize: Int = 13,
        var sendSelectionContext: Boolean = true,
        var permissionMode: String = com.claudecode.ClaudeConstants.PERMISSION_MODE_ACCEPT_EDITS,
        var customModels: String = "",
        // Remote model-catalog cache (see ModelsRegistry). Stored as raw JSON
        // so the schema can evolve without an XML migration. cachedModelsAt
        // is epoch millis; 0 = never fetched.
        var cachedModelsJson: String = "",
        var cachedModelsAt: Long = 0L,
        // ── Model & inference (FEATURE_MAP) ──
        // Extended thinking budget — one of THINKING_OFF/LOW/MEDIUM/HIGH.
        // Mapped to MAX_THINKING_TOKENS env var on the spawned claude.
        var thinkingBudget: String = com.claudecode.ClaudeConstants.THINKING_OFF,
        // Persistent text appended to claude's system prompt on every spawn
        // via --append-system-prompt. Blank = no append. Power-user lever
        // for "always be terse", project conventions, persona instructions.
        var appendSystemPrompt: String = "",
        // Hard cap on agentic loop turns via --max-turns. 0 = unlimited
        // (default; CLI behavior).
        var maxAgenticTurns: Int = 0
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    /**
     * Truly-custom IDs the user typed or that Claude returned and we
     * persisted via [addCustomModel]. Entries that the live catalog has
     * since adopted are pruned at read time — they'd appear anyway via the
     * catalog, with their friendly name + any deprecation badge, so
     * keeping them duplicated in `customModels` adds nothing.
     */
    fun getCustomModelsList(): List<String> {
        val raw = myState.customModels.split(",")
            .filter { it.isNotBlank() }
            .filterNot { com.claudecode.ClaudeConstants.isPlaceholderModel(it) }
        val catalogIds = com.claudecode.models.ModelsRegistry.allKnownIds().toSet()
        val pruned = raw.filterNot { it in catalogIds }
        // Persist the pruned list back so it doesn't grow forever as the
        // catalog catches up. Only writes when the prune actually changed
        // something so we don't churn ClaudeCodeSettings.xml on every read.
        if (pruned.size != raw.size) {
            myState.customModels = pruned.joinToString(",")
        }
        return pruned
    }

    fun addCustomModel(model: String) {
        if (model.isBlank()) return
        if (com.claudecode.ClaudeConstants.isPlaceholderModel(model)) return
        // Already known in the live catalog → no need to persist as "custom".
        if (model in com.claudecode.models.ModelsRegistry.allKnownIds()) return
        val existing = getCustomModelsList().toMutableList()
        // Move-to-front semantics: if the user is re-using an old custom ID,
        // bump it to the most-recently-used position so it survives the cap.
        existing.remove(model)
        existing.add(model)
        // Cap the list — keeps ClaudeCodeSettings.xml small and means an
        // unused experimental ID eventually rotates out instead of living
        // forever. Oldest entries (front of the list) drop first.
        while (existing.size > MAX_CUSTOM_MODELS) {
            existing.removeAt(0)
        }
        myState.customModels = existing.joinToString(",")
    }

    /** Wipes every persisted custom-model entry. Called from the Settings "Clear" button. */
    fun clearCustomModels() {
        myState.customModels = ""
    }

    /**
     * Returns the IDs the dropdown should offer:
     *   - "" (CLI default) first
     *   - active (non-deprecated) catalog models
     *   - any deprecated catalog models the user currently has selected,
     *     so existing selections still render with their friendly label
     *   - any custom IDs the user has typed or that Claude reported back
     *
     * Deprecation handling for the UI lives in [com.claudecode.models.ModelsRegistry].
     */
    fun getAllModels(): List<String> {
        val registry = com.claudecode.models.ModelsRegistry
        val active = registry.activeIds()
        val current = myState.model
        val deprecatedKeep = if (current.isNotBlank() && registry.isDeprecated(current))
            listOf(current) else emptyList()
        return (listOf("") + active + deprecatedKeep + getCustomModelsList())
            .distinct()
            .filterNot { com.claudecode.ClaudeConstants.isPlaceholderModel(it) }
    }

    companion object {
        /**
         * Maximum number of persisted custom (non-catalog) model IDs. Oldest
         * entries fall off the front when the cap is exceeded. Tuned for
         * "I've tried a few experimental models" not "I've used dozens" —
         * if a user genuinely needs more, the cap is one constant change.
         */
        const val MAX_CUSTOM_MODELS = 10

        fun getInstance(): ClaudeSettings =
            ApplicationManager.getApplication().getService(ClaudeSettings::class.java)
    }
}
