package com.claudecode.session

import com.claudecode.settings.ClaudeSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.util.concurrent.CopyOnWriteArrayList

interface SessionManagerListener {
    fun onSessionAdded(session: ClaudeSession)
    fun onSessionRemoved(session: ClaudeSession)
}

@Service(Service.Level.PROJECT)
class SessionManager(private val project: Project) : Disposable {

    private val sessions = mutableListOf<ClaudeSession>()
    private val listeners = CopyOnWriteArrayList<SessionManagerListener>()

    fun addListener(listener: SessionManagerListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: SessionManagerListener) {
        listeners.remove(listener)
    }

    fun getSessions(): List<ClaudeSession> = sessions.toList()

    fun createSession(name: String? = null): ClaudeSession? {
        val maxSessions = ClaudeSettings.getInstance().state.maxSessions
        if (sessions.size >= maxSessions) return null

        val sessionName = name ?: "Session ${sessions.size + 1}"
        val workDir = project.basePath ?: System.getProperty("user.home")
        val session = ClaudeSession(workDir, sessionName)
        sessions.add(session)
        listeners.forEach { it.onSessionAdded(session) }
        return session
    }

    fun removeSession(session: ClaudeSession) {
        session.dispose()
        sessions.remove(session)
        listeners.forEach { it.onSessionRemoved(session) }
    }

    override fun dispose() {
        sessions.forEach { it.dispose() }
        sessions.clear()
        listeners.clear()
    }

    companion object {
        fun getInstance(project: Project): SessionManager =
            project.getService(SessionManager::class.java)
    }
}
