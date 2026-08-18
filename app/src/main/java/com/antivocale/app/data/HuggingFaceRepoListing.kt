package com.antivocale.app.data

import com.antivocale.app.data.catalog.CatalogDisplay
import com.antivocale.app.data.catalog.ModelCatalogJson
import okhttp3.OkHttpClient

/**
 * Minimal HTTP text fetcher for the external-model JSON-only import (spec:
 * external models platform v2a, JSON-only revision). The importer's URL entry
 * fetches the catalog-entry JSON through [fetchText]; the repo-tree listing
 * machinery was retired when external import became serialized-JSON-only.
 */
class HuggingFaceRepoListing(
    private val client: OkHttpClient = OkHttpClient(),
) {
    /** Small GET for entry JSON and other textual metadata. */
    fun fetchText(url: String): String {
        val request = okhttp3.Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalArgumentException("fetch failed: HTTP ${response.code} for $url")
            }
            return response.body?.string() ?: throw IllegalArgumentException("empty body for $url")
        }
    }
}

/**
 * Single-model catalog-entry JSON — the JSON-only external import format (spec
 * decision). It is the SAME unified schema the bundled catalog lists many of
 * (see [ModelCatalogJson]); a bare entry is how third parties share one model
 * with integrity. Parsing delegates to the unified parser so the built-in and
 * external shapes cannot drift; external entries are the external branch of the
 * parser (literal `name`/`description`, every file carrying url + sha256 + size).
 */
object ExternalModelEntryJson {

    data class EntryFile(val name: String, val url: String, val sha256: String, val size: Long)

    data class Entry(
        val name: String,
        val description: String?,
        val modelType: String,
        val languages: List<String>,
        val files: List<EntryFile>,
    )

    fun parse(text: String): Entry {
        val e = ModelCatalogJson.parseEntry(text)
        require(e.id.isBlank()) { "external entry must not carry a catalog id" }
        val display = e.display
        require(display is CatalogDisplay.Literal) {
            "external entry name must be a literal string (external strings are never localized)"
        }
        val variant = e.variants.single()
        return Entry(
            name = display.text,
            description = (e.description as? CatalogDisplay.Literal)?.text,
            modelType = e.modelType,
            languages = e.languages,
            files = variant.files.map { f ->
                // The external parser branch already enforces url/sha256/size;
                // the requireNotNull is a type-safe guarantee, not a re-check.
                EntryFile(
                    name = f.name,
                    url = requireNotNull(f.url) { "external file ${f.name} is missing its url" },
                    sha256 = requireNotNull(f.sha256) { "external file ${f.name} is missing its sha256" },
                    size = requireNotNull(f.size) { "external file ${f.name} is missing its size" },
                )
            },
        )
    }
}