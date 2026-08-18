package com.antivocale.app.data.catalog

import android.content.Context

/**
 * DI facade over the bundled catalog (`assets/models_catalog.json`), served as a
 * singleton through [com.antivocale.app.di.AppModule]. The asset is the single
 * source of truth for the built-in sherpa-onnx models; later platform stages
 * (downloaders, managers, engines, registry, UI) consume these entries instead
 * of their hand-written parallel metadata.
 *
 * The actual asset I/O and cache live in [BundledCatalog] so that the static
 * sherpa `object` downloaders/managers and this injected facade share ONE parse.
 * Parsing is lazy (first access) and strict: a malformed asset throws
 * [IllegalArgumentException] from [ModelCatalogJson], surfacing catalog bugs at
 * the first touch instead of mid-flight. The fail-fast validation of the asset's
 * contents (ids vs BACKEND_ID constants, resource keys vs [CatalogStringKeys])
 * lives in the unit test suite.
 */
class BundledModelCatalog(
    private val context: Context,
) {
    val models: List<CatalogEntry> get() = BundledCatalog.load(context)

    fun byId(id: String): CatalogEntry? = BundledCatalog.byId(context, id)
}