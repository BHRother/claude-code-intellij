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
        var enableCompletion: Boolean = false,
        var completionDebounceMs: Long = 500,
        var maxSessions: Int = 10,
        var fontSize: Int = 13,
        var sendSelectionContext: Boolean = true,
        var autoAcceptPermissions: Boolean = true
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    companion object {
        fun getInstance(): ClaudeSettings =
            ApplicationManager.getApplication().getService(ClaudeSettings::class.java)
    }
}
