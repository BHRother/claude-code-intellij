package com.claudecode.platform

import com.intellij.openapi.diagnostic.Logger
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps the host from going to sleep (or starting screensaver) while a
 * Claude turn is in flight. Long agentic runs can easily exceed the
 * default OS idle timeout — without this, the screen locks mid-task and
 * (on some OSes) the process throttles or pauses.
 *
 * Reference-counted: each [start] call should be paired with a [stop].
 * The actual inhibitor process is spawned on the first start and torn
 * down only when the count returns to zero, so multiple concurrent
 * sessions cooperate.
 *
 * Platform implementations:
 *   - macOS: `caffeinate -i`            (held while subprocess is alive)
 *   - Linux: `systemd-inhibit --what=idle:sleep sleep infinity`
 *   - Windows: no-op for v1 (would need a JNA shim around
 *     `kernel32!SetThreadExecutionState`; not worth the complexity yet)
 *
 * Cross-platform principle followed: gate platform-specific shell-outs
 * behind isMacOS / isLinux checks, fall through to no-op on Windows so
 * the chat behaves identically there (just doesn't suppress sleep).
 */
object SleepInhibitor {

    private val LOG = Logger.getInstance(SleepInhibitor::class.java)
    private val refCount = AtomicInteger(0)
    @Volatile private var process: Process? = null

    @Synchronized
    fun start() {
        val newCount = refCount.incrementAndGet()
        if (newCount > 1) return  // already inhibited by another caller
        process = spawnInhibitor()
    }

    @Synchronized
    fun stop() {
        val newCount = refCount.decrementAndGet()
        if (newCount > 0) return
        // Coerce back to zero if we somehow over-decrement — keeps the
        // counter sane across bug / reload cycles.
        if (newCount < 0) refCount.set(0)
        val p = process
        process = null
        if (p != null && p.isAlive) {
            try { p.destroyForcibly() } catch (_: Exception) {}
        }
    }

    private fun spawnInhibitor(): Process? {
        val osName = System.getProperty("os.name", "").lowercase()
        val cmd: List<String> = when {
            osName.contains("mac") -> listOf("caffeinate", "-i")
            osName.contains("nux") || osName.contains("nix") -> listOf(
                "systemd-inhibit",
                "--what=idle:sleep",
                "--who=Claude Code",
                "--why=AI request in progress",
                "sleep", "infinity",
            )
            else -> {
                // Windows / unknown — silent no-op. The SessionPanel
                // doesn't depend on a successful inhibit; this just means
                // long tasks can hit the OS idle timeout on Windows.
                return null
            }
        }
        return try {
            ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
                .also { LOG.debug("SleepInhibitor: started ${cmd.first()} (pid=${it.pid()})") }
        } catch (t: Throwable) {
            // Tool missing (e.g. systemd-inhibit on non-systemd distros)
            // is non-fatal — degrade silently.
            LOG.debug("SleepInhibitor: failed to spawn ${cmd.first()} — sleep may engage during long tasks", t)
            null
        }
    }
}
