package com.claudecode.history

/**
 * A persisted Claude session that can be resumed via `claude -p --resume <id>`.
 * Metadata only — message content lives in Claude Code's own JSONL transcript
 * under `~/.claude/projects/`, loaded on demand by [ClaudeSessionFile]. We
 * intentionally don't cache the transcript locally: the JSONL is the source
 * of truth, a cache would only drift, and the storage savings here add up.
 *
 * All times are epoch milliseconds (UTC).
 */
data class RecentSession(
    val id: String,
    val name: String,
    val workingDirectory: String,
    val createdAt: Long,
    val lastUsedAt: Long,
    /** Total turns in Claude's view at last touch. Drives the "X turns" label in the dropdown. */
    val messageCount: Int,
)
