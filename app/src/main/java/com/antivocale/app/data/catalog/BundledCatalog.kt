package com.antivocale.app.data.catalog

import android.content.Context

/**
 * Shared, lazily-loaded access to the bundled catalog asset.
 *
 * The catalog is parsed exactly once per process and cached, so the DI-provided
 * [BundledModelCatalog], the static sherpa `object` downloaders/managers, and
 * the future catalog-driven engines all see the same immutable entries. The
 * asset parse is strict: a malformed catalog throws [IllegalArgumentException]
 * from [ModelCatalogJson] on first touch.
 *
 * Two access paths share the same cache:
 *  - [attach]/[byId] for the static sherpa objects, which have no injected
 *    Context (called from [android.app.Application.onCreate]);
 *  - [load]/[byId] with an explicit Context (the DI facade, tests).
 */
object BundledCatalog {

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cache: List<CatalogEntry>? = null

    /**
     * Captures the application context for the static accessors. Must be called
     * once from [android.app.Application.onCreate]; parsing stays lazy.
     */
    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    /** Entries, resolving the context from [attach]. Uses the cache when already parsed. */
    fun entries(): List<CatalogEntry> {
        cache?.let { return it }
        return load(
            appContext ?: throw IllegalStateException(
                "BundledCatalog.attach(context) must be called from Application.onCreate")
        )
    }

    fun byId(id: String): CatalogEntry? = entries().firstOrNull { it.id == id }

    /** Parses (once) and returns every built-in entry, using [context] for the asset. */
    @Synchronized
    fun load(context: Context): List<CatalogEntry> {
        cache?.let { return it }
        val text = context.assets.open(ASSET_PATH)
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        return ModelCatalogJson.parseCatalog(text).also { cache = it }
    }

    fun byId(context: Context, id: String): CatalogEntry? =
        load(context).firstOrNull { it.id == id }

    /**
     * Seeds the cache with externally-provided entries.
     *
     * Test-only: unit tests without Android assets can seed the parsed catalog so
     * the static downloaders/managers resolve without touching [android.content.res.AssetManager].
     * In the app the catalog is always parsed from the asset via [load].
     */
    @Synchronized
    fun seed(entries: List<CatalogEntry>) {
        cache = entries
    }

    private const val ASSET_PATH = "models_catalog.json"
}