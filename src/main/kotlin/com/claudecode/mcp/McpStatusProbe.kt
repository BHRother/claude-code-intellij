package com.claudecode.mcp

/**
 * Per-server connection check with a short-TTL cache, used to confirm an OAuth
 * sign-in completed (and the token persisted) before the driver tears down.
 *
 * It checks one server at a time via `claude mcp get <name>` rather than
 * `claude mcp list`: `list` health-checks *every* configured server on each
 * call, so with many servers it runs long and can exceed its timeout and report
 * nothing — which stalled "Authenticate All" success detection. `get` touches
 * only the server we care about, so it stays fast regardless of list size.
 *
 * The TTL coalesces repeated polls for the same server (the sign-in loop polls
 * every few seconds). Must be called off the EDT — [McpCli.status] blocks on a
 * subprocess.
 */
class McpStatusProbe(workDir: String?, private val ttlMs: Long = 2_000L) {

    private val cli = McpCli(workDir)
    private val cache = HashMap<String, Pair<McpServerStatus, Long>>()  // name → (status, fetchedAt)

    /** `claude mcp get [name]` status (cached up to [ttlMs]). */
    @Synchronized
    fun status(name: String): McpServerStatus {
        val now = System.currentTimeMillis()
        cache[name]?.let { (status, at) -> if (now - at <= ttlMs) return status }
        val status = cli.status(name)
        cache[name] = status to now
        return status
    }
}
