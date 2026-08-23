package com.antivocale.app.transcription

/**
 * GH #49: a model's audio-length capability, derived from metadata only so the
 * model selection UI can declare it BEFORE the download (no per-model UI
 * strings). Terminology mirrors FAQ.md: the per-segment limit is the model's
 * own; anything beyond it is software chunking.
 */
sealed interface AudioLimit {
    /** The model accepts at most [seconds] of audio in one pass (e.g. Gemma: 30s). */
    data class HardCap(val seconds: Int) : AudioLimit

    /** No practical length limit: longer inputs are chunked in software and concatenated. */
    data object ChunkedAnyLength : AudioLimit

    /** No known limit (offline transducers without a cap, streaming models). */
    data object NoKnownLimit : AudioLimit
}

/**
 * @param maxAudioDuration hard per-pass cap from ModelInfoProvider (null = none)
 * @param chunkDurationSeconds catalog flag; > 0 means the app chunks inputs at
 *   that size, i.e. any length is accepted
 */
fun audioLimit(maxAudioDuration: Int?, chunkDurationSeconds: Int): AudioLimit =
    when {
        // Chunking wins: Whisper/Qwen3 declare BOTH a 30s maxAudioDuration and
        // 30s chunks; with software chunking any length is accepted, so their
        // per-segment cap is an implementation detail, not a user-facing limit.
        chunkDurationSeconds > 0 -> AudioLimit.ChunkedAnyLength
        maxAudioDuration != null -> AudioLimit.HardCap(maxAudioDuration)
        else -> AudioLimit.NoKnownLimit
    }

/**
 * Single derivation points so every UI surface resolves the limit the same way
 * (GH #49). Catalog entries join their chunk flag with the metadata cap
 * (lookups resolve by storageDir per ModelInfoProvider's contract); the
 * non-catalog Gemma variants carry their cap in ModelInfoProvider directly.
 */
fun audioLimitForCatalogEntry(storageDir: String?, chunkDurationSeconds: Int): AudioLimit =
    audioLimit(
        maxAudioDuration = storageDir?.let { ModelInfoProvider.getInfoByDirName(it)?.maxAudioDuration },
        chunkDurationSeconds = chunkDurationSeconds,
    )

fun audioLimitForVariants(
    variants: List<ModelVariant>,
    chunkDurationSeconds: Int = 0,
): AudioLimit =
    audioLimit(
        maxAudioDuration = variants.firstNotNullOfOrNull { ModelInfoProvider.getInfo(it)?.maxAudioDuration },
        chunkDurationSeconds = chunkDurationSeconds,
    )
