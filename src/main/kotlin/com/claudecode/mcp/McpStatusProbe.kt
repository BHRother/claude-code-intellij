package com.claudecode.mcp

/**
 * A short-TTL cache over `claude mcp list` so several concurrent OAuth drivers
 * (the pipelined "Authenticate All") share **one** health-check subprocess
 * instead of each spawning its own every few seconds.
 *
 * `claude mcp list` connects to *every* configured server, so it's relatively
 * expensive; with up to 3 sign-ins in flight, polling per-driver would mean 3×
 * that cost on overlapping timers. Here the first caller in a window pays for
 * the fetch and everyone else within [ttlMs] reads the cached map. The fetch
 * runs under the monitor, so callers that arrive mid-fetch block until it
 * lands and then read the fresh result (coalesced, never duplicated).
 *
 * Must be called off the EDT — [McpCli.list] blocks on a subprocess.
 */
class McpStatusProbe(workDir: String?, private val ttlMs: Long = 2_000L) {

    private val cli = McpCli(workDir)
    private var cache: Map<String, McpServerStatus> = emptyMap()
    private var fetchedAt = 0L

    /** True if `claude mcp list` reports [name] as Connected (cached up to [ttlMs]). */
    @Synchronized
    fun isConnected(name: String): Boolean {
        val now = System.currentTimeMillis()
        if (now - fetchedAt > ttlMs) {
            cache = runCatching { cli.list() }.getOrDefault(emptyMap())
            fetchedAt = now
        }
        return cache[name] == McpServerStatus.CONNECTED
    }
}
