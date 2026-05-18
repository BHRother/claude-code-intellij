package com.claudecode.history

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import java.io.File

/**
 * Persisted "recent chats" registry, keyed by absolute project path.
 *
 * Pure logic — no Swing, no project services. Consumers (chip row, side
 * panel, status-bar widget, …) call [recentForProject] to read and
 * [touch] / [remove] to mutate. The store handles its own retention:
 * max [MAX_PER_PROJECT] per project, dropping entries older than
 * [RETENTION_DAYS] on [pruneOld].
 *
 * Storage: a single JSON file at `<config>/claude-code/sessions.json`,
 * keyed by project root path. Writes are async on a pooled thread so
 * the EDT never blocks; reads are synchronous after a one-time load.
 */
object RecentSessionsStore {

    // Tunables — exposed as constants so consumers can document them
    // without depending on the store.
    const val MAX_PER_PROJECT = 5
    const val RETENTION_DAYS = 30L
    const val MAX_MESSAGES = 5
    const val MAX_MESSAGE_CHARS = 500

    private val LOG = Logger.getInstance(RecentSessionsStore::class.java)
    private val GSON = GsonBuilder().setPrettyPrinting().create()

    @Volatile private var loaded = false
    // Project path → ordered list of sessions (most recent first after sort).
    private val data: MutableMap<String, MutableList<RecentSession>> = HashMap()
    private val lock = Any()

    private val storageFile: File by lazy {
        File(PathManager.getConfigPath(), "claude-code/sessions.json")
    }

    /** Returns up to [MAX_PER_PROJECT] sessions for the project, newest first. Empty if none. */
    fun recentForProject(projectPath: String): List<RecentSession> {
        ensureLoaded()
        synchronized(lock) {
            return (data[projectPath].orEmpty())
                .sortedByDescending { it.lastUsedAt }
                .take(MAX_PER_PROJECT)
        }
    }

    /**
     * Upsert a session. Existing entry with the same [RecentSession.id] is
     * replaced; the list is then capped to [MAX_PER_PROJECT] by recency.
     * Message text in [session] should already be truncated by the caller
     * via [truncateMessages] — the store applies the cap defensively too.
     */
    fun touch(projectPath: String, session: RecentSession) {
        if (projectPath.isBlank() || session.id.isBlank()) return
        ensureLoaded()
        val normalized = session.copy(lastMessages = truncateMessages(session.lastMessages))
        synchronized(lock) {
            val list = data.getOrPut(projectPath) { mutableListOf() }
            list.removeAll { it.id == normalized.id }
            list.add(normalized)
            // Cap by recency — drop oldest beyond MAX_PER_PROJECT.
            val capped = list.sortedByDescending { it.lastUsedAt }.take(MAX_PER_PROJECT)
            data[projectPath] = capped.toMutableList()
        }
        scheduleSave()
    }

    /**
     * Wipes the cached [RecentSession.lastMessages] tail for a single entry,
     * leaving the entry itself in place (id, name, lastUsedAt, etc.).
     * Called after a successful "Load full history" pull from Claude's
     * JSONL — that file is authoritative, so the local tail is redundant
     * until the next live turn repopulates it.
     */
    fun clearMessagesFor(projectPath: String, sessionId: String) {
        ensureLoaded()
        var changed = false
        synchronized(lock) {
            val list = data[projectPath] ?: return
            val idx = list.indexOfFirst { it.id == sessionId }
            if (idx < 0) return
            val existing = list[idx]
            if (existing.lastMessages.isEmpty()) return
            list[idx] = existing.copy(lastMessages = emptyList())
            changed = true
        }
        if (changed) scheduleSave()
    }

    fun remove(projectPath: String, sessionId: String) {
        ensureLoaded()
        synchronized(lock) {
            val list = data[projectPath] ?: return
            val removed = list.removeAll { it.id == sessionId }
            if (list.isEmpty()) data.remove(projectPath)
            if (!removed) return
        }
        scheduleSave()
    }

    /** Drop entries older than [RETENTION_DAYS] across all projects. Cheap; safe to call on startup. */
    fun pruneOld() {
        ensureLoaded()
        val cutoff = System.currentTimeMillis() - RETENTION_DAYS * 24L * 3600L * 1000L
        var changed = false
        synchronized(lock) {
            val iterator = data.entries.iterator()
            while (iterator.hasNext()) {
                val (_, list) = iterator.next()
                val before = list.size
                list.removeAll { it.lastUsedAt < cutoff }
                if (list.size != before) changed = true
                if (list.isEmpty()) iterator.remove()
            }
        }
        if (changed) scheduleSave()
    }

    /** Truncate per-message text and cap message count. Pure; used by callers building a [RecentSession]. */
    fun truncateMessages(messages: List<RecentMessage>): List<RecentMessage> {
        if (messages.isEmpty()) return emptyList()
        return messages.takeLast(MAX_MESSAGES).map {
            if (it.text.length <= MAX_MESSAGE_CHARS) it
            else it.copy(text = it.text.take(MAX_MESSAGE_CHARS - 1) + "…")
        }
    }

    // ─────────────── internals ───────────────

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            try {
                if (storageFile.exists()) {
                    val text = storageFile.readText()
                    if (text.isNotBlank()) parseInto(text, data)
                }
            } catch (t: Throwable) {
                LOG.warn("RecentSessionsStore: failed to load — starting empty", t)
                data.clear()
            }
            loaded = true
        }
    }

    private fun parseInto(json: String, target: MutableMap<String, MutableList<RecentSession>>) {
        val root = JsonParser.parseString(json).asJsonObject
        val schema = root.get("schemaVersion")?.asInt ?: 1
        if (schema != 1) {
            LOG.warn("RecentSessionsStore: unknown schemaVersion=$schema, ignoring file")
            return
        }
        val byProject = root.getAsJsonObject("byProject") ?: return
        for ((projectPath, sessionsJson) in byProject.entrySet()) {
            val arr = sessionsJson.asJsonArray
            val list = mutableListOf<RecentSession>()
            for (el in arr) {
                val obj = el.asJsonObject
                try {
                    val msgs = obj.getAsJsonArray("lastMessages")?.map { mEl ->
                        val mObj = mEl.asJsonObject
                        RecentMessage(
                            role = mObj.get("role")?.asString ?: "user",
                            text = mObj.get("text")?.asString ?: "",
                        )
                    } ?: emptyList()
                    list.add(
                        RecentSession(
                            id = obj.get("id").asString,
                            name = obj.get("name").asString,
                            workingDirectory = obj.get("workingDirectory").asString,
                            createdAt = obj.get("createdAt").asLong,
                            lastUsedAt = obj.get("lastUsedAt").asLong,
                            messageCount = obj.get("messageCount")?.asInt ?: msgs.size,
                            lastMessages = msgs,
                        )
                    )
                } catch (_: Exception) {
                    // Skip malformed entry but keep the rest.
                }
            }
            if (list.isNotEmpty()) target[projectPath] = list
        }
    }

    private fun scheduleSave() {
        // Pool thread keeps disk IO off the EDT. The file is tiny (<100KB
        // realistically) so a coalescing delay isn't worth the complexity.
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                saveNow()
            } catch (t: Throwable) {
                LOG.warn("RecentSessionsStore: save failed", t)
            }
        }
    }

    private fun saveNow() {
        val snapshot: Map<String, List<RecentSession>>
        synchronized(lock) {
            snapshot = data.mapValues { (_, v) -> v.toList() }
        }
        val root = mutableMapOf<String, Any>(
            "schemaVersion" to 1,
            "byProject" to snapshot,
        )
        storageFile.parentFile?.mkdirs()
        storageFile.writeText(GSON.toJson(root))
    }
}
