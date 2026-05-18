package com.claudecode.models

import com.claudecode.ClaudeConstants
import com.claudecode.settings.ClaudeSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single source of truth for the model dropdown contents and deprecation
 * state. Resolution order on first access:
 *
 *   1. Cached JSON in [ClaudeSettings] if younger than [ClaudeConstants.MODELS_CATALOG_TTL_MS].
 *   2. Bundled JSON shipped with the plugin (always available, even offline).
 *   3. Triggers an async fetch of [ClaudeConstants.MODELS_CATALOG_URL] when
 *      cached is missing or stale; the new catalog is written to settings
 *      and used on the next access (or via [reload]).
 *
 * The remote fetch never blocks any user-facing path — worst case the user
 * sees the bundled (or last-cached) list until the background fetch lands.
 */
object ModelsRegistry {

    private val LOG = Logger.getInstance(ModelsRegistry::class.java)
    private val fetchInFlight = AtomicBoolean(false)

    @Volatile private var current: ModelsCatalog = loadBundled()
    @Volatile private var initializedFromCache = false

    /** Returns the currently effective catalog (cached / bundled / freshly fetched). */
    fun catalog(): ModelsCatalog {
        ensureInitialized()
        return current
    }

    /** Resolves a model ID to its friendly label, falling back to the ID itself. */
    fun friendlyName(id: String): String {
        if (id.isBlank()) return "Default"
        return catalog().findById(id)?.name ?: id
    }

    fun isDeprecated(id: String): Boolean =
        id.isNotBlank() && (catalog().findById(id)?.deprecated == true)

    fun replacementFor(id: String): String? = catalog().findById(id)?.replacement

    fun noteFor(id: String): String? = catalog().findById(id)?.note

    /** Active (non-deprecated) IDs, in catalog order. */
    fun activeIds(): List<String> = catalog().activeIds()

    /** Every known ID including deprecated. Used so existing user selections still render. */
    fun allKnownIds(): List<String> = catalog().allIds()

    /** Force a refresh on next access (clears the TTL gate). Used by the Settings "refresh" button. */
    fun invalidateCache() {
        ClaudeSettings.getInstance().state.cachedModelsAt = 0L
    }

    /**
     * Trigger an async refresh from the remote URL, regardless of TTL.
     * Result lands in settings + [current] when (and if) the HTTP request
     * succeeds. Safe to call from EDT.
     *
     * Failure modes (all are silently survivable — the plugin keeps using
     * its cached or bundled catalog, no user-visible breakage):
     *   - No network / DNS failure → IOException, logged at WARN
     *   - Non-2xx HTTP response → logged at WARN with status code
     *   - Body doesn't parse as ModelsCatalog → logged at WARN
     *   - Placeholder URL still in ClaudeConstants → skipped silently
     *   - Any other Throwable → caught and logged at WARN
     */
    fun refreshAsync(onComplete: ((Boolean) -> Unit)? = null) {
        if (!fetchInFlight.compareAndSet(false, true)) {
            onComplete?.invoke(false)
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val ok = try {
                fetchAndStore()
            } catch (t: Throwable) {
                // Catch Throwable, not just Exception — even OOM or VM
                // errors should not propagate up to the EDT pool and
                // potentially kill IntelliJ's background-task plumbing.
                LOG.warn("ModelsRegistry refresh failed — keeping previous catalog", t)
                false
            } finally {
                fetchInFlight.set(false)
            }
            onComplete?.invoke(ok)
        }
    }

    // ──────────────────── internals ────────────────────

    private fun ensureInitialized() {
        if (!initializedFromCache) {
            synchronized(this) {
                if (!initializedFromCache) {
                    loadCacheIntoCurrent()
                    initializedFromCache = true
                }
            }
        }
        maybeRefresh()
    }

    private fun loadCacheIntoCurrent() {
        val state = ClaudeSettings.getInstance().state
        if (state.cachedModelsJson.isNotBlank()) {
            val parsed = ModelsCatalog.parse(state.cachedModelsJson)
            if (parsed != null) {
                current = parsed.copy(source = "cached")
                LOG.info("ModelsRegistry: loaded ${parsed.models.size} model(s) from cache " +
                    "(updatedAt=${parsed.updatedAt ?: "n/a"})")
                return
            } else {
                // Corrupt cache: reset the timestamp so maybeRefresh() does
                // re-fetch, and fall through to the bundled list. Without
                // this, a one-time bad parse would freeze the user on
                // bundled forever (or until manual Refresh).
                LOG.warn("ModelsRegistry: cached catalog failed to parse — clearing cache timestamp")
                state.cachedModelsJson = ""
                state.cachedModelsAt = 0L
            }
        }
        // Bundled is the default already set in `current`.
        LOG.info("ModelsRegistry: using bundled catalog (${current.models.size} models)")
    }

    private fun maybeRefresh() {
        val state = ClaudeSettings.getInstance().state
        val age = System.currentTimeMillis() - state.cachedModelsAt
        if (age < ClaudeConstants.MODELS_CATALOG_TTL_MS && state.cachedModelsJson.isNotBlank()) {
            return
        }
        refreshAsync()
    }

    private fun fetchAndStore(): Boolean {
        val url = ClaudeConstants.MODELS_CATALOG_URL
        if (!url.startsWith("http")) {
            LOG.warn("ModelsRegistry: catalog URL is not http(s), skipping fetch")
            return false
        }
        // Skip the placeholder URL so we don't spam logs before a real Gist
        // is wired up.
        if (url.contains("REPLACE_WITH_GIST_ID")) {
            LOG.debug("ModelsRegistry: catalog URL is placeholder, skipping fetch")
            return false
        }

        val client = try {
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
        } catch (t: Throwable) {
            LOG.warn("ModelsRegistry: HttpClient construction failed", t)
            return false
        }

        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(10))
            .header("Accept", "application/json")
            .header("User-Agent", "claude-code-intellij")
            .GET()
            .build()

        // Wrap the network round-trip in its own try so we can log the
        // specific failure mode (DNS, timeout, TLS, …) before bailing.
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (t: Throwable) {
            LOG.warn("ModelsRegistry: HTTP request to $url failed (${t.javaClass.simpleName}: ${t.message})")
            return false
        }

        if (response.statusCode() !in 200..299) {
            LOG.warn("ModelsRegistry: fetch from $url returned HTTP ${response.statusCode()}")
            return false
        }

        val body = response.body() ?: ""
        val parsed = ModelsCatalog.parse(body)
        if (parsed == null) {
            LOG.warn("ModelsRegistry: response body (${body.length} bytes) did not parse as ModelsCatalog")
            return false
        }

        val state = ClaudeSettings.getInstance().state
        state.cachedModelsJson = body
        state.cachedModelsAt = System.currentTimeMillis()
        current = parsed.copy(source = "remote")
        val activeCount = parsed.activeIds().size
        val deprecatedCount = parsed.models.size - activeCount
        LOG.info(
            "ModelsRegistry: fetched catalog from $url — " +
                "${parsed.models.size} model(s) ($activeCount active, $deprecatedCount deprecated), " +
                "updatedAt=${parsed.updatedAt ?: "n/a"}"
        )
        return true
    }

    private fun loadBundled(): ModelsCatalog {
        // Belt-and-suspenders: failure to load the bundled catalog should
        // still leave a usable (empty) registry rather than crashing the
        // plugin on startup. The chip dropdown will then only show "Default"
        // until either the user types a model or the remote fetch succeeds.
        return try {
            val stream = ModelsRegistry::class.java.getResourceAsStream("/claude-code-intellij-models.json")
                ?: run {
                    LOG.warn("ModelsRegistry: bundled catalog resource not found on classpath")
                    return ModelsCatalog(schemaVersion = 1, source = "empty-fallback")
                }
            val text = stream.bufferedReader().use { it.readText() }
            val parsed = ModelsCatalog.parse(text)
            if (parsed != null) {
                parsed.copy(source = "bundled")
            } else {
                LOG.warn("ModelsRegistry: bundled catalog failed to parse")
                ModelsCatalog(source = "empty-fallback")
            }
        } catch (t: Throwable) {
            LOG.warn("ModelsRegistry: failed to load bundled models catalog", t)
            ModelsCatalog(source = "empty-fallback")
        }
    }
}
