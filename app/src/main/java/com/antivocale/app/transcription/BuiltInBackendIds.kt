package com.antivocale.app.transcription

/**
 * Canonical bundled-catalog entry ids for the built-in sherpa-onnx backends.
 *
 * Single source for the ids used across DI (the [SherpaBackend] instances),
 * [BackendRegistry], the orchestrator and the manifest share aliases. The
 * catalog asset itself is pinned to this set by BundledModelCatalogTest.
 */
object BuiltInBackendIds {
    const val PARAKEET = "sherpa-onnx"
    const val WHISPER = "whisper"
    const val QWEN3_ASR = "qwen3-asr"
    const val NEMOTRON = "nemotron-streaming"
    const val GIGAAM = "gigaam"

    /** All five built-in catalog entry ids, in canonical UI order (default backend first). */
    val ALL: List<String> = listOf(PARAKEET, WHISPER, QWEN3_ASR, NEMOTRON, GIGAAM)
}