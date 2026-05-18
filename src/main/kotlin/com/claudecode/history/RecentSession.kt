package com.claudecode.history

/**
 * A persisted Claude session that can be resumed via `claude -p --resume <id>`.
 * Holds enough metadata to display in a "Recent" surface and reopen a
 * working chat, plus a small tail of recent messages purely for visual
 * context — Claude itself still owns the full conversation history.
 *
 * All times are epoch milliseconds (UTC). Message text is pre-truncated
 * to [RecentSessionsStore.MAX_MESSAGE_CHARS] so the storage file stays
 * small and predictable.
 */
data class RecentSession(
    val id: String,
    val name: String,
    val workingDirectory: String,
    val createdAt: Long,
    val lastUsedAt: Long,
    /** Total turns in Claude's view (informational, may exceed [lastMessages].size). */
    val messageCount: Int,
    val lastMessages: List<RecentMessage>,
)

data class RecentMessage(
    /** "user" or "assistant" — same vocabulary as Claude's stream. */
    val role: String,
    val text: String,
)
