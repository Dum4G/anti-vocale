package com.antivocale.app.transcription

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Transcription backend using sherpa-onnx with ONNX Runtime.
 *
 * This backend supports Parakeet TDT and other ONNX-based ASR models.
 * It's typically faster and smaller than multimodal LLM approaches.
 *
 * Key features:
 * - Uses ONNX Runtime (separate from LiteRT-LM's TFLite)
 * - Supports 25 European languages with automatic detection (Parakeet TDT)
 * - ~862MB model size vs ~3.3GB for Gemma 3n
 * - 2.4-2.8x faster transcription
 *
 * Decoding: Uses greedy_search instead of modified_beam_search because
 * benchmarking (2026-04-26, 8 Italian FLEURS samples) showed identical
 * WER across all decoding methods, but greedy_search is ~3x faster.
 * See memory/research_parakeet_decoding.md for full results.
 */
@Singleton
class SherpaOnnxBackend @Inject constructor() : TranscriptionBackend {

    companion object {
        const val BACKEND_ID = "sherpa-onnx"
        private const val TAG = "SherpaOnnxBackend"

        // Required model files for Parakeet TDT (transducer model)
        val REQUIRED_MODEL_FILES = listOf(
            "encoder.int8.onnx",
            "decoder.int8.onnx",
            "joiner.int8.onnx",
            "tokens.txt"
        )

        // Canonical role names, resolved BY PREFIX, not by list position: reordering
        // REQUIRED_MODEL_FILES must never silently repoint a role. The external-model
        // importer copies/renames sources onto these names and the external engine
        // loads them, so both resolve the roles here rather than in parallel copies.
        val CANONICAL_ENCODER = REQUIRED_MODEL_FILES.first { it.startsWith("encoder") }
        val CANONICAL_DECODER = REQUIRED_MODEL_FILES.first { it.startsWith("decoder") }
        val CANONICAL_JOINER = REQUIRED_MODEL_FILES.first { it.startsWith("joiner") }
        val CANONICAL_TOKENS = REQUIRED_MODEL_FILES.first { it.startsWith("tokens") }

        /**
         * Metadata keys a transducer encoder must carry for [modelType], shared by the
         * external-model importer (import-time validation) and the external engine
         * (load-time validation) so the two cannot drift: vocab_size always; the nemo
         * loader's subsampling_factor + model_type only for the nemo family (a zipformer
         * import with modelType "" does not carry them and must not be rejected for
         * their absence).
         */
        fun requiredTransducerMetadataKeys(modelType: String): List<String> =
            if (modelType == "nemo_transducer") {
                listOf("vocab_size", "subsampling_factor", "model_type")
            } else {
                listOf("vocab_size")
            }

        private const val ONNX_METADATA_SCAN_LIMIT: Long = 2L * 1024 * 1024

        /**
         * Returns the metadata keys from [requiredKeys] that are NOT present in [file].
         *
         * ONNX stores metadata_props (protobuf key-value pairs); for large models they land near
         * the END of the file, so we scan the last [maxScanBytes] once and test every key against
         * the same buffer. Empty/missing file returns every key as missing.
         *
         * This prevents native crashes: sherpa-onnx calls exit(255) when required metadata
         * (e.g. vocab_size) is missing, killing the process with no catchable exception.
         */
        fun missingOnnxMetadata(
            file: File,
            requiredKeys: List<String>,
            maxScanBytes: Long = ONNX_METADATA_SCAN_LIMIT
        ): List<String> {
            if (!file.exists() || file.length() == 0L) return requiredKeys
            val fileSize = file.length()
            val scanStart = maxOf(0L, fileSize - maxScanBytes)
            val data = ByteArray((fileSize - scanStart).toInt())
            try {
                java.io.RandomAccessFile(file, "r").use { raf ->
                    raf.seek(scanStart)
                    raf.readFully(data)
                }
            } catch (e: java.io.IOException) {
                // Treat unreadable as "all metadata missing" so the caller shows a clear
                // ModelLoadError instead of letting an IOException propagate uncaught.
                return requiredKeys
            }
            return missingOnnxMetadataKeys(data, requiredKeys)
        }

        /**
         * Returns the metadata keys from [requiredKeys] whose UTF-8 bytes do not appear as a
         * contiguous subsequence in [data]. Pure (no I/O) so it can be unit-tested directly.
         */
        @JvmStatic
        @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
        internal fun missingOnnxMetadataKeys(data: ByteArray, requiredKeys: List<String>): List<String> {
            return requiredKeys.filter { key ->
                val needle = key.toByteArray(Charsets.UTF_8)
                needle.isNotEmpty() && !containsSubsequence(data, needle)
            }
        }

        /** Returns true if [haystack] contains [needle] as a contiguous byte subsequence. */
        @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
        internal fun containsSubsequence(haystack: ByteArray, needle: ByteArray): Boolean {
            val lastStart = haystack.size - needle.size
            if (lastStart < 0) return false
            for (i in 0..lastStart) {
                var j = 0
                while (j < needle.size && haystack[i + j] == needle[j]) j++
                if (j == needle.size) return true
            }
            return false
        }
    }

    override val id: String = BACKEND_ID
    override val displayName: String = "Parakeet TDT (sherpa-onnx)"
    override val supportsAudio: Boolean = true
    override val supportsText: Boolean = false  // ASR-only, no text generation

    // Parakeet can handle up to 24 minutes in single pass - no chunking needed
    override val maxChunkDurationSeconds: Int? = null

    private var recognizer: OfflineRecognizer? = null
    private var modelDir: String? = null
    private var isInitialized = false

    override suspend fun initialize(context: Context, config: BackendConfig): Result<Unit> {
        val sherpaConfig = config as? BackendConfig.SherpaOnnxConfig
            ?: return Result.failure(IllegalArgumentException("Invalid config type for SherpaOnnxBackend"))

        if (isInitialized) {
            Log.w(TAG, "Already initialized, returning success")
            return Result.success(Unit)
        }

        val modelDirectory = sherpaConfig.modelDir
        Log.i(TAG, "Initializing sherpa-onnx with model dir: $modelDirectory")

        // Validate model directory exists
        val dir = File(modelDirectory)
        if (!dir.exists() || !dir.isDirectory) {
            return Result.failure(TranscriptionException.ModelLoadError("directory not found: $modelDirectory"))
        }

        // Validate required model files exist
        val missingFiles = REQUIRED_MODEL_FILES.filter { !File(dir, it).exists() }
        if (missingFiles.isNotEmpty()) {
            return Result.failure(TranscriptionException.ModelLoadError(
                "missing files in $modelDirectory: ${missingFiles.joinToString()}"
            ))
        }

        return withContext(Dispatchers.IO) {
            // Pre-native validation (inside IO dispatcher): sherpa-onnx calls exit(255) when the
            // encoder is missing critical metadata, killing the app silently. Scan the file tail.
            val encoderFile = File(dir, "encoder.int8.onnx")
            val missingMeta = missingOnnxMetadata(
                encoderFile,
                listOf("vocab_size", "subsampling_factor", "model_type")
            )
            if (missingMeta.isNotEmpty()) {
                Log.e(TAG, "Encoder missing required ONNX metadata: $missingMeta")
                return@withContext Result.failure(TranscriptionException.ModelLoadError(
                    "model file is missing required metadata ($missingMeta). " +
                        "The model may be corrupt or an incompatible export. Try re-downloading it."
                ))
            }

            try {
                Log.i(TAG, "Creating OfflineRecognizer config...")

                // Configure the transducer model (Parakeet TDT uses transducer architecture)
                val modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = "${modelDirectory}/encoder.int8.onnx",
                        decoder = "${modelDirectory}/decoder.int8.onnx",
                        joiner = "${modelDirectory}/joiner.int8.onnx"
                    ),
                    tokens = "${modelDirectory}/tokens.txt",
                    modelType = sherpaConfig.modelType,
                    numThreads = sherpaConfig.numThreads,
                    debug = false,
                    provider = sherpaConfig.provider
                )

                val recognizerConfig = OfflineRecognizerConfig(
                    modelConfig = modelConfig,
                    featConfig = FeatureConfig(
                        sampleRate = 16000,
                        featureDim = 80
                    ),
                    decodingMethod = "greedy_search"
                )

                Log.i(TAG, "Creating OfflineRecognizer...")
                recognizer = OfflineRecognizer(config = recognizerConfig)

                modelDir = modelDirectory
                isInitialized = true

                Log.i(TAG, "sherpa-onnx initialized successfully")
                Result.success(Unit)

            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize sherpa-onnx", e)
                Result.failure(TranscriptionException.ModelLoadError(e.message ?: "unknown", e))
            } catch (e: Error) {
                // Catch native errors (UnsatisfiedLinkError, etc.)
                Log.e(TAG, "Native error initializing sherpa-onnx", e)
                Result.failure(TranscriptionException.NativeError(e.message ?: "unknown", e))
            }
        }
    }

    override suspend fun transcribeAudio(samples: FloatArray, sampleRate: Int, prompt: String): Result<TranscriptionResult> {
        val rec = recognizer
            ?: return Result.failure(TranscriptionException.NotInitialized())

        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Transcribing audio: ${samples.size} samples at ${sampleRate}Hz")

                // Append 1s of silence to improve final token accuracy.
                // Benchmarking on real WhatsApp audio showed 2% WER improvement
                // (12.1% → 10.1%) from giving the model a brief silence tail
                // to finalize trailing tokens. More padding doesn't help further.
                val silencePad = FloatArray(sampleRate)
                val padded = samples + silencePad

                // Create stream and process audio
                val stream = rec.createStream()
                stream.acceptWaveform(padded, sampleRate)
                rec.decode(stream)

                // Get result
                val result = rec.getResult(stream)
                val transcription = result.text
                val detectedLang = result.lang.ifBlank { null }

                // Release stream
                stream.release()

                Log.d(TAG, "Transcription complete: '${transcription.take(100)}...' (${transcription.length} chars)")

                if (transcription.isBlank()) {
                    Result.failure(TranscriptionException.NoTranscriptionProduced())
                } else {
                    val confidence = TranscriptionResult.computeConfidence(transcription, padded.size, sampleRate)
                    Result.success(TranscriptionResult(
                        text = transcription,
                        confidence = confidence,
                        detectedLanguage = detectedLang
                    ))
                }

            } catch (e: Exception) {
                Log.e(TAG, "Transcription failed", e)
                Result.failure(TranscriptionException.NativeError(e.message ?: "unknown", e))
            }
        }
    }

    override suspend fun generateText(prompt: String): Result<String> {
        // sherpa-onnx is ASR-only, no text generation support
        return Result.failure(UnsupportedOperationException(
            "Text generation not supported by sherpa-onnx backend. Use for audio transcription only."
        ))
    }

    override fun isReady(): Boolean = isInitialized && recognizer != null

    override fun isAudioSupported(): Boolean = true

    override fun unload() {
        Log.i(TAG, "Unloading sherpa-onnx backend")
        recognizer?.release()
        recognizer = null
        modelDir = null
        isInitialized = false
    }

    override fun setKeepAliveTimeout(minutes: Int) {
        // No-op: SherpaOnnx backend doesn't manage its own lifecycle
    }

    override fun getModelPath(): String? = modelDir

}
