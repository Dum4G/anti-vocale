package com.antivocale.app.data

import org.json.JSONObject

/**
 * The bundled external-model catalog index (TASK-331 Task 13): a minimal list of
 * curated models (name, languages, family, entry-JSON URL) shipped as an asset at
 * assets/external-catalog/index.json. The URL import dialog uses it as an
 * autocomplete assist (search by language or name), NOT as a catalog browser;
 * tapping a suggestion fills the URL field and the family.
 *
 * The matcher and parsing are pure so both are unit-testable without Robolectric.
 */
object ExternalCatalog {

    private val WHITESPACE = Regex("\\s+")

    data class CatalogEntry(
        val name: String,
        val languages: List<String>,
        val entryUrl: String,
        val family: ModelFamily,
    )

    /**
     * True when every whitespace-separated query token matches the entry: against
     * any language code (equal or prefix, case-insensitive, so "pt" finds "pt-BR")
     * or as a substring of the display name ("arabic" and "ar" both find an entry
     * named "... Arabic ..." with language "ar"). A blank query matches everything.
     */
    fun matchesQuery(name: String, languages: List<String>, query: String): Boolean {
        val tokens = query.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return true
        return tokens.all { token ->
            languages.any { it.equals(token, ignoreCase = true) || it.startsWith(token, ignoreCase = true) } ||
                name.contains(token, ignoreCase = true)
        }
    }

    /** Entries matching [query], in index order. */
    fun filter(entries: List<CatalogEntry>, query: String): List<CatalogEntry> =
        entries.filter { matchesQuery(it.name, it.languages, query) }

    /**
     * Parses the index JSON ({"entries": [...]}). Malformed entries and unknown
     * family strings are skipped: a catalog read must never crash the import
     * dialog. An entry without "family" defaults to TRANSDUCER, matching the
     * entry-JSON backward-compat rule.
     */
    fun parseIndex(text: String): List<CatalogEntry> {
        val arr = runCatching { JSONObject(text).optJSONArray("entries") }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                val name = e.optString("name")
                val url = e.optString("entryUrl")
                if (name.isBlank() || url.isBlank()) continue
                val family = runCatching {
                    ModelFamily.valueOf(e.optString("family", ModelFamily.TRANSDUCER.name))
                }.getOrNull() ?: continue
                val langs = e.optJSONArray("languages")?.optStringList() ?: emptyList()
                add(CatalogEntry(
                    name = name,
                    languages = langs,
                    entryUrl = url,
                    family = family,
                ))
            }
        }
    }
}
