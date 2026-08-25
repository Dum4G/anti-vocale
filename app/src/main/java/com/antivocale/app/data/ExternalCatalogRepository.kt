package com.antivocale.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * TASK-401 catalog-URL architecture: loads the community-catalog index from the
 * configured source (default: the index published in our repo), caching the last
 * good copy on disk. Resolution order on [load]:
 *  1. fetch the configured [catalogUrl] (the default, or a user override);
 *  2. on failure, the on-disk cache, but only if it belongs to the SAME url
 *     (an override must never silently fall back to a different list);
 *  3. for the default url only, the index bundled as an app asset (offline
 *     first run). An unreachable override with no cache is a hard [Result]
 *     failure surfaced in the dialog.
 *
 * Pure-injectable: [fetchText] and [filesDir] default to the real thing but are
 * seams for the JVM tests (no Robolectric needed for the fallback matrix).
 */
class ExternalCatalogRepository(
    private val context: Context,
    private val catalogUrl: suspend () -> String,
    private val fetchText: suspend (String) -> String = { url ->
        withContext(Dispatchers.IO) { HuggingFaceRepoListing().fetchText(url) }
    },
    private val filesDir: () -> File = { context.filesDir },
) {
    sealed interface Source {
        /** Fetched from the configured url (default or override). */
        data class Remote(val url: String, val isOverride: Boolean) : Source
        /** Disk cache of [url], used because the fetch failed. */
        data class Cached(val url: String, val isOverride: Boolean) : Source
        /** Asset fallback, only for the default url. */
        data object BundledAsset : Source
    }

    data class CatalogState(
        val entries: List<ExternalCatalog.CatalogEntry>,
        val source: Source,
        /** The url this state was resolved for. */
        val url: String,
    )

    private fun cacheFile(url: String): File =
        File(File(filesDir(), CATALOG_DIR), url.hashCode().toString() + ".json")

    suspend fun load(): Result<CatalogState> = withContext(Dispatchers.IO) {
        val url = catalogUrl()
        val isOverride = url != PreferencesManager.DEFAULT_EXTERNAL_CATALOG_URL
        runCatching {
            val text = fetchText(url)
            val entries = parseNonEmpty(text) { "catalog index at $url carries no entries" }
            runCatching { cacheFile(url).let { f -> f.parentFile?.mkdirs(); f.writeText(text) } }  // cache is best-effort
            CatalogState(entries, Source.Remote(url, isOverride), url)
        }.recoverCatching {
            val cached = cacheFile(url)
            if (cached.isFile) {
                val entries = parseNonEmpty(cached.readText()) { "cached index for $url is empty" }
                return@recoverCatching CatalogState(entries, Source.Cached(url, isOverride), url)
            }
            if (!isOverride) {
                val asset = context.assets.open(BUNDLED_INDEX).bufferedReader().use { it.readText() }
                val entries = parseNonEmpty(asset) { "bundled index is empty" }
                CatalogState(entries, Source.BundledAsset, url)
            } else {
                throw IllegalArgumentException(
                    "catalog $url unreachable and no cached copy (offline?)", it)
            }
        }
    }

    private inline fun parseNonEmpty(text: String, lazyMessage: () -> String) =
        ExternalCatalog.parseIndex(text).also { require(it.isNotEmpty(), lazyMessage) }

    /** Validates an override candidate: it must parse as a non-empty index. */
    suspend fun validateOverride(url: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val entries = ExternalCatalog.parseIndex(fetchText(url))
            entries.isNotEmpty()
        }.getOrDefault(false)
    }

    private companion object {
        const val CATALOG_DIR = "catalog"
        const val BUNDLED_INDEX = "external-catalog/index.json"
    }
}
