package com.antivocale.app.transcription

/**
 * Common interface for transcription model variants.
 *
 * [CatalogVariantUi] (catalog-driven) and [ModelDownloader.ModelVariant] (Gemma)
 * implement this, enabling generic UI components like [ModelVariantCard] to render
 * any variant.
 */
interface ModelVariant {
    val titleResId: Int
    val descriptionResId: Int
    val dirName: String
    val estimatedSizeMB: Long
    val supportedLanguageCodes: Set<String> get() = emptySet()
}
