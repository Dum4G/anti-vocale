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
 * Backend for user-imported (sideloaded) sherpa-onnx transducer models.
 *
 * This is the generalized "Strada B" import path: instead of hardcoding a named backend per model
 * (e.g. a dedicated GigaAM backend), a user imports any sherpa-onnx transducer model directory
 * (encoder.int8.onnx + decoder.int8.onnx + joiner.int8.onnx + tokens.txt) and transcribes with it.
 *
 * Architecturally identical to [SherpaOnnxBackend] (both build an [OfflineRecognizer] over a
 * transducer model), but differs in two load-bearing ways:
 * - [modelType] is read from the config rather than fixed, because a wrong modelType triggers an
 *   uncatchable native exit(255). The user picks it in the import UI (default nemo_transducer,
 *   covering GigaAM-ru and Parakeet; zipformer/conformer importers change it).
 * - Metadata validation checks only `vocab_size` (the universally-required key), not the
 *   Parakeet-specific trio (subsampling_factor, model_type), so valid non-Parakeet imports pass.
 *
 * Uses composition over inheritance: reuses [SherpaOnnxBackend.REQUIRED_MODEL_FILES] and
 * [SherpaOnnxBackend.missingOnnxMetadata] rather than subclassing the stateful Parakeet singleton.
 *
 * No chunking: like Parakeet, processes the whole audio in a single pass. A high-memory custom
 * model may OOM on very long audio; the import UI surfaces this risk to the user.
 */
@Singleton
class CustomTransducerBackend @Inject constructor() : TranscriptionBackend {

    companion object {
        const val BACKEND_ID = "custom-transducer"
        private const val TAG = "CustomTransducerBackend"

        // vocab_size is required by every sherpa-onnx transducer decoder init path; without it
        // sherpa calls exit(255). Other Parakeet-specific keys (subsampling_factor, model_type)
        // are intentionally NOT required here so non-Parakeet transducers validate.
        private val REQUIRED_METADATA_KEYS = listOf("vocab_size")
    }

    // Custom transducers run single-pass like Parakeet; no chunking limit.
    override val maxChunkDurationSeconds: Int? = null

    override val id: String = BACKEND_ID
    override val displayName: String = "Custom model"
    override val supportsAudio: Boolean = true
    override val supportsText: Boolean = false

    private var recognizer: OfflineRecognizer? = null
    private var modelDir: String? = null
    private var isInitialized = false

    override suspend fun initialize(context: Context, config: BackendConfig): Result<Unit> {
        val sherpaConfig = config as? BackendConfig.SherpaOnnxConfig
            ?: return Result.failure(IllegalArgumentException("Invalid config type for CustomTransducerBackend"))

        if (isInitialized) {
            Log.w(TAG, "Already initialized, returning success")
            return Result.success(Unit)
        }

        val modelDirectory = sherpaConfig.modelDir
        Log.i(TAG, "Initializing custom-transducer with model dir: $modelDirectory (modelType='${sherpaConfig.modelType}')")

        val dir = File(modelDirectory)
        if (!dir.exists() || !dir.isDirectory) {
            return Result.failure(TranscriptionException.ModelLoadError("directory not found: $modelDirectory"))
        }

        val missingFiles = SherpaOnnxBackend.REQUIRED_MODEL_FILES.filter { !File(dir, it).exists() }
        if (missingFiles.isNotEmpty()) {
            return Result.failure(TranscriptionException.ModelLoadError(
                "missing files in $modelDirectory: ${missingFiles.joinToString()}. " +
                    "A custom transducer model needs encoder.int8.onnx, decoder.int8.onnx, " +
                    "joiner.int8.onnx, and tokens.txt in one folder."
            ))
        }

        return withContext(Dispatchers.IO) {
            // Pre-native validation: sherpa-onnx calls exit(255) when the encoder is missing
            // vocab_size metadata, killing the app silently. Scan the file tail first.
            val encoderFile = File(dir, "encoder.int8.onnx")
            val missingMeta = SherpaOnnxBackend.missingOnnxMetadata(encoderFile, REQUIRED_METADATA_KEYS)
            if (missingMeta.isNotEmpty()) {
                Log.e(TAG, "Encoder missing required ONNX metadata: $missingMeta")
                return@withContext Result.failure(TranscriptionException.ModelLoadError(
                    "model file is missing required metadata ($missingMeta). " +
                        "The model may be corrupt or not a sherpa-onnx transducer export."
                ))
            }

            try {
                Log.i(TAG, "Creating OfflineRecognizer config...")
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

                Log.i(TAG, "custom-transducer initialized successfully")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize custom-transducer", e)
                Result.failure(TranscriptionException.ModelLoadError(e.message ?: "unknown", e))
            } catch (e: Error) {
                Log.e(TAG, "Native error initializing custom-transducer", e)
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

                // Append 1s of silence to improve final token accuracy (same rationale as Parakeet).
                val silencePad = FloatArray(sampleRate)
                val padded = samples + silencePad

                val stream = rec.createStream()
                stream.acceptWaveform(padded, sampleRate)
                rec.decode(stream)

                val result = rec.getResult(stream)
                val transcription = result.text
                val detectedLang = result.lang.ifBlank { null }

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

    override suspend fun generateText(prompt: String): Result<String> =
        Result.failure(UnsupportedOperationException(
            "Text generation not supported by custom-transducer backend. Use for audio transcription only."
        ))

    override fun isReady(): Boolean = isInitialized && recognizer != null

    override fun isAudioSupported(): Boolean = true

    override fun unload() {
        Log.i(TAG, "Unloading custom-transducer backend")
        recognizer?.release()
        recognizer = null
        modelDir = null
        isInitialized = false
    }

    override fun setKeepAliveTimeout(minutes: Int) {
        // No-op: mirrors SherpaOnnxBackend; lifecycle managed by the orchestrator.
    }

    override fun getModelPath(): String? = modelDir
}
