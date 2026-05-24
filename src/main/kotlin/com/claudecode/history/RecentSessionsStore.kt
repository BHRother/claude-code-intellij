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
    const val MAX_PER_PROJECT = 10
    const val RETENTION_DAYS = 30L

    private val LOG = Logger.getInstance(RecentSessionsStore::class.java)
    private val GSON = GsonBuilder().setPrettyPrinting().create()

    @Volatile private var loaded = false
    // Project path → an ordered list of sessions (most recent first after sort).
    private val data: MutableMap<String, MutableList<RecentSession>> = HashMap()
    private val lock = Any()

    private val storageFile: File by lazy {
        File(PathManager.getConfigPath(), "claude-code/sessions.json")
    }

    /**
     * Returns sessions for the project, **pinned first** (each group sorted
     * by lastUsedAt descending), then unpinned up to [MAX_PER_PROJECT]
     * total. Pinned entries don't count toward the recency cap, so a user
     * who pins everything will see all their pinned chats plus some
     * recent unpinned ones.
     */
    fun recentForProject(projectPath: String): List<RecentSession> {
        ensureLoaded()
        synchronized(lock) {
            val all = data[projectPath].orEmpty()
            val pinned = all.filter { it.pinned }.sortedByDescending { it.lastUsedAt }
            val unpinned = all.filter { !it.pinned }.sortedByDescending { it.lastUsedAt }
                .take(MAX_PER_PROJECT)
            return pinned + unpinned
        }
    }

    /**
     * Upsert a session. Existing entry with the same [RecentSession.id] is
     * replaced; the list is then capped — pinned entries are kept
     * regardless of recency, unpinned entries beyond [MAX_PER_PROJECT]
     * fall off the end. If the incoming session matches an existing pinned
     * entry, the pinned flag is preserved (callers don't have to remember
     * to set it).
     */
    fun touch(projectPath: String, session: RecentSession) {
        if (projectPath.isBlank() || session.id.isBlank()) return
        ensureLoaded()
        synchronized(lock) {
            val list = data.getOrPut(projectPath) { mutableListOf() }
            val existing = list.firstOrNull { it.id == session.id }
            val toStore = if (existing != null && existing.pinned && !session.pinned)
                session.copy(pinned = true) else session
            list.removeAll { it.id == session.id }
            list.add(toStore)
            // Cap unpinned only; pinned entries always survive.
            val pinned = list.filter { it.pinned }
            val unpinned = list.filter { !it.pinned }
                .sortedByDescending { it.lastUsedAt }
                .take(MAX_PER_PROJECT)
            data[projectPath] = (pinned + unpinned).toMutableList()
        }
        scheduleSave()
    }

    /** Toggle / set the pinned flag for a single entry. No-op if not found. */
    fun setPinned(projectPath: String, sessionId: String, pinned: Boolean) {
        ensureLoaded()
        var changed = false
        synchronized(lock) {
            val list = data[projectPath] ?: return
            val idx = list.indexOfFirst { it.id == sessionId }
            if (idx < 0) return
            val existing = list[idx]
            if (existing.pinned == pinned) return
            list[idx] = existing.copy(pinned = pinned)
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
                    list.add(
                        RecentSession(
                            id = obj.get("id").asString,
                            name = obj.get("name").asString,
                            workingDirectory = obj.get("workingDirectory").asString,
                            createdAt = obj.get("createdAt").asLong,
                            lastUsedAt = obj.get("lastUsedAt").asLong,
                            // Older files persisted by previous plugin builds
                            // included a lastMessages array; we read only the
                            // count if present, ignore the rest.
                            messageCount = obj.get("messageCount")?.asInt ?: 0,
                            // pinned was added in a later build — defaults to
                            // false for entries written before this field existed.
                            pinned = obj.get("pinned")?.asBoolean ?: false,
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
