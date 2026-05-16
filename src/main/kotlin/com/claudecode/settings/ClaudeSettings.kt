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
        var customModels: String = ""
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    fun getCustomModelsList(): List<String> =
        myState.customModels.split(",")
            .filter { it.isNotBlank() }
            .filterNot { com.claudecode.ClaudeConstants.isPlaceholderModel(it) }

    fun addCustomModel(model: String) {
        if (model.isBlank()) return
        if (com.claudecode.ClaudeConstants.isPlaceholderModel(model)) return
        if (model in com.claudecode.ClaudeConstants.AVAILABLE_MODELS) return
        val existing = getCustomModelsList().toMutableList()
        if (model !in existing) {
            existing.add(model)
            myState.customModels = existing.joinToString(",")
        }
    }

    fun getAllModels(): List<String> =
        (com.claudecode.ClaudeConstants.AVAILABLE_MODELS + getCustomModelsList())
            .distinct()
            .filterNot { com.claudecode.ClaudeConstants.isPlaceholderModel(it) }

    companion object {
        fun getInstance(): ClaudeSettings =
            ApplicationManager.getApplication().getService(ClaudeSettings::class.java)
    }
}
