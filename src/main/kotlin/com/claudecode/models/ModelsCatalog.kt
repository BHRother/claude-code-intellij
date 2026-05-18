package com.claudecode.models

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException

/**
 * Schema for `claude-code-intellij-models.json`. Mirrors the JSON exactly.
 *
 * - [id]: the model ID passed to `claude --model`.
 * - [name]: friendly display label shown in chip + Settings dropdowns.
 * - [deprecated]: when true, the dropdown greys the entry and a banner
 *   prompts the user to switch to [replacement] (if set).
 * - [replacement]: ID of the recommended successor; can be null if there's
 *   no clean swap (rare).
 * - [note]: optional one-line context shown in tooltips / banner copy.
 */
data class CatalogModel(
    val id: String,
    val name: String,
    val deprecated: Boolean = false,
    val replacement: String? = null,
    val note: String? = null,
)

/**
 * Top-level wrapper. [source] is informational only (bundled / remote /
 * cached) and gets set programmatically — the JSON file itself doesn't
 * need to provide it.
 */
data class ModelsCatalog(
    val schemaVersion: Int = 1,
    val updatedAt: String? = null,
    val models: List<CatalogModel> = emptyList(),
    val source: String? = null,
) {
    fun activeIds(): List<String> = models.filterNot { it.deprecated }.map { it.id }
    fun allIds(): List<String> = models.map { it.id }
    fun findById(id: String): CatalogModel? = models.firstOrNull { it.id == id }

    companion object {
        private val GSON = Gson()

        fun parse(json: String): ModelsCatalog? = try {
            GSON.fromJson(json, ModelsCatalog::class.java)?.takeIf { it.schemaVersion >= 1 }
        } catch (_: JsonSyntaxException) {
            null
        } catch (_: Exception) {
            null
        }
    }
}
