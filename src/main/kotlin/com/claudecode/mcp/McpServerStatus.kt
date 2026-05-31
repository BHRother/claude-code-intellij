package com.claudecode.mcp

/**
 * Live connection / auth status for a server, derived from `claude mcp list`
 * output (which health-checks each server). Purely advisory — used to render a
 * badge in the UI.
 */
enum class McpServerStatus(val label: String) {
    CONNECTED("✓ connected"),
    FAILED("✗ failed"),
    PENDING_APPROVAL("⏸ pending approval"),
    NEEDS_AUTH("🔒 needs authentication"),
    CHECKING("checking…"),
    UNKNOWN("—");

    companion object {
        /**
         * Classify the trailing status text of a `claude mcp list` line such as
         * `name: npx … - ✓ Connected`. Matching is intentionally fuzzy so it
         * survives minor wording changes across CLI versions.
         */
        fun fromListStatusText(text: String): McpServerStatus {
            val t = text.lowercase()
            return when {
                t.contains("auth") || t.contains("unauthor") || t.contains("login") ||
                    t.contains("sign in") -> NEEDS_AUTH
                t.contains("pending") || t.contains("approv") -> PENDING_APPROVAL
                // Check failure BEFORE success: "✗ Failed to connect" contains
                // the substring "connect", so it must be classified as FAILED first.
                t.contains("fail") || t.contains("error") || t.contains("✗") ||
                    t.contains("disconnect") -> FAILED
                t.contains("✓") || t.contains("connect") || t.contains("ready") -> CONNECTED
                else -> UNKNOWN
            }
        }
    }
}
