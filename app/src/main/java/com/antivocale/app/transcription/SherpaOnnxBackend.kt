package com.antivocale.app.transcription

import android.content.Context
import android.util.Log
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

        private const val ONNX_METADATA_SCAN_LIMIT: Long = 2L * 1024 * 1024
        private const val ONNX_SCAN_CHUNK_SIZE: Int = 64 * 1024

        /**
         * Lightweight check for an ONNX metadata key without loading the model in onnxruntime.
         * ONNX appends metadata_props (protobuf key-value pairs) near the END of the file for
         * large models. We scan the last [maxScanBytes] of the file for the key as a UTF-8 string.
         *
         * This prevents native crashes: sherpa-onnx calls exit(255) when required metadata
         * (e.g. vocab_size) is missing, killing the process with no catchable exception.
         */
        fun hasOnnxMetadata(file: File, key: String, maxScanBytes: Long = ONNX_METADATA_SCAN_LIMIT): Boolean {
            if (!file.exists() || file.length() == 0L) return false
            val keyBytes = key.toByteArray(Charsets.UTF_8)
            if (keyBytes.isEmpty()) return false
            val fileSize = file.length()
            val scanStart = maxOf(0L, fileSize - maxScanBytes)
            val scanLen = (fileSize - scanStart).toInt()
            val data = ByteArray(scanLen)
            java.io.RandomAccessFile(file, "r").use { raf ->
                raf.seek(scanStart)
                raf.readFully(data)
            }
            return containsSubsequence(data, keyBytes)
        }

        /** Returns true if [haystack] contains [needle] as a contiguous subsequence. */
        private fun containsSubsequence(haystack: ByteArray, needle: ByteArray): Boolean {
            if (needle.isEmpty()) return true
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

        // Pre-native validation: check the encoder ONNX has required sherpa-onnx metadata.
        // sherpa-onnx calls exit(255) (native process death) when metadata like vocab_size
        // or subsampling_factor is missing, killing the app silently with no catchable
        // exception. We check for multiple critical keys (any missing = reject).
        val encoderFile = File(dir, "encoder.int8.onnx")
        val requiredMetadataKeys = listOf("vocab_size", "subsampling", "model_type")
        val missingMeta = requiredMetadataKeys.filter { !hasOnnxMetadata(encoderFile, it) }
        if (missingMeta.isNotEmpty()) {
            Log.e(TAG, "Encoder missing required ONNX metadata: $missingMeta")
            return Result.failure(TranscriptionException.ModelLoadError(
                "model file is missing required metadata ($missingMeta). " +
                    "The model may be corrupt or an incompatible export. Try re-downloading it."
            ))
        }

        return withContext(Dispatchers.IO) {
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
