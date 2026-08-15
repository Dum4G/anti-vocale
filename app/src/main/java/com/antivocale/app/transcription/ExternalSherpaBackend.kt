package com.antivocale.app.transcription

import android.content.Context
import android.util.Log
import com.antivocale.app.data.ExternalModelRecord
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineStream
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single configurable engine for imported external models (spec: external models
 * platform v2a). One [ExternalModelRecord] is configured per [initialize] via
 * [BackendConfig.ExternalConfig]; file names come from
 * [SherpaOnnxBackend.REQUIRED_MODEL_FILES] (the canonical role names the importer
 * downloads/copies to).
 *
 * Identity contract: [id] returns the placeholder "external" before the first
 * successful initialize and after [unload]; the backend manager routes the
 * "external:" prefix to this singleton and never registers it under the placeholder,
 * so no consumer can address a half-configured engine.
 */
@Singleton
class ExternalSherpaBackend @Inject constructor() : TranscriptionBackend {

    companion object {
        private const val TAG = "ExternalSherpaBackend"
        private const val PLACEHOLDER_ID = "external"

        // Canonical role order pinned by SherpaOnnxBackend.REQUIRED_MODEL_FILES:
        // encoder, decoder, joiner, tokens.
        private val ENCODER_FILE get() = SherpaOnnxBackend.REQUIRED_MODEL_FILES[0]
        private val DECODER_FILE get() = SherpaOnnxBackend.REQUIRED_MODEL_FILES[1]
        private val JOINER_FILE get() = SherpaOnnxBackend.REQUIRED_MODEL_FILES[2]
        private val TOKENS_FILE get() = SherpaOnnxBackend.REQUIRED_MODEL_FILES[3]
    }

    @Volatile private var configuredId: String = PLACEHOLDER_ID
    override val id: String get() = configuredId

    override val displayName: String get() = "External model"
    override val supportsAudio: Boolean = true
    override val supportsText: Boolean = false

    // Single-pass like Parakeet/GigaAM: realistic v2a imports are offline transducers.
    override val maxChunkDurationSeconds: Int? = null

    private var recognizer: OfflineRecognizer? = null
    private var modelDir: String? = null
    private var isInitialized = false

    override suspend fun initialize(context: Context, config: BackendConfig): Result<Unit> {
        val externalConfig = config as? BackendConfig.ExternalConfig
            ?: return Result.failure(IllegalArgumentException(
                "Invalid config type for ExternalSherpaBackend (expected ExternalConfig)"))

        if (isInitialized) {
            Log.w(TAG, "Already initialized as $configuredId, returning success")
            return Result.success(Unit)
        }

        val record = externalConfig.record
        val dir = File(record.dir)
        if (!dir.exists() || !dir.isDirectory) {
            return Result.failure(TranscriptionException.ModelLoadError("directory not found: ${record.dir}"))
        }

        // Pre-native validation (inside IO dispatcher): sherpa-onnx calls exit(255)
        // when the encoder is missing critical metadata, killing the app silently.
        return withContext(Dispatchers.IO) {
            val missing = SherpaOnnxBackend.REQUIRED_MODEL_FILES.filterNot { File(dir, it).exists() }
            if (missing.isNotEmpty()) {
                return@withContext Result.failure(TranscriptionException.ModelLoadError(
                    "missing files in ${record.dir}: $missing"))
            }

            // Metadata rule, deliberately different from both templates: vocab_size
            // ALWAYS; subsampling_factor + model_type ONLY for the nemo family (those
            // keys are what the nemo loader reads; a zipformer import with modelType ""
            // does not carry them and must not be rejected for their absence).
            val requiredKeys = mutableListOf("vocab_size")
            if (record.modelType == "nemo_transducer") {
                requiredKeys += "subsampling_factor"
                requiredKeys += "model_type"
            }
            val missingMeta = SherpaOnnxBackend.missingOnnxMetadata(File(dir, ENCODER_FILE), requiredKeys)
            if (missingMeta.isNotEmpty()) {
                Log.e(TAG, "Encoder missing required ONNX metadata: $missingMeta")
                return@withContext Result.failure(TranscriptionException.ModelLoadError(
                    "model file is missing required metadata ($missingMeta). " +
                        "The model may be corrupt, an incompatible export, or the wrong family. " +
                        "Try re-importing it or correcting its family."))
            }

            try {
                val modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = "${record.dir}/$ENCODER_FILE",
                        decoder = "${record.dir}/$DECODER_FILE",
                        joiner = "${record.dir}/$JOINER_FILE"
                    ),
                    tokens = "${record.dir}/$TOKENS_FILE",
                    modelType = record.modelType,
                    numThreads = externalConfig.numThreads,
                    debug = false,
                    provider = externalConfig.provider
                )

                val recognizerConfig = OfflineRecognizerConfig(
                    modelConfig = modelConfig,
                    featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                    decodingMethod = "greedy_search"
                )

                recognizer = OfflineRecognizer(config = recognizerConfig)
                modelDir = record.dir
                configuredId = record.backendId
                isInitialized = true

                Log.i(TAG, "External backend initialized: $configuredId (family=${record.family}, modelType=${record.modelType})")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize external model ${record.backendId}", e)
                Result.failure(TranscriptionException.ModelLoadError(e.message ?: "unknown", e))
            } catch (e: Error) {
                // Catch native errors (UnsatisfiedLinkError, etc.)
                Log.e(TAG, "Native error initializing external model ${record.backendId}", e)
                Result.failure(TranscriptionException.NativeError(e.message ?: "unknown", e))
            }
        }
    }

    override suspend fun transcribeAudio(samples: FloatArray, sampleRate: Int, prompt: String): Result<TranscriptionResult> {
        val rec = recognizer
            ?: return Result.failure(TranscriptionException.NotInitialized())

        return withContext(Dispatchers.IO) {
            // Release the native OfflineStream on EVERY path so the JNI handle is freed
            // deterministically, not left to GC finalization (NemotronStreamingBackend pattern).
            var stream: OfflineStream? = null
            try {
                // Append 1s of silence to improve final token accuracy (Parakeet pattern).
                val silencePad = FloatArray(sampleRate)
                val padded = samples + silencePad

                stream = rec.createStream()
                stream.acceptWaveform(padded, sampleRate)
                rec.decode(stream)

                val result = rec.getResult(stream)
                val transcription = result.text
                val detectedLang = result.lang.ifBlank { null }

                if (transcription.isBlank()) {
                    Result.failure(TranscriptionException.NoTranscriptionProduced())
                } else {
                    // Words-per-second heuristic: keep the original length, not the padded one.
                    val confidence = TranscriptionResult.computeConfidence(transcription, samples.size, sampleRate)
                    Result.success(TranscriptionResult(
                        text = transcription,
                        confidence = confidence,
                        detectedLanguage = detectedLang
                    ))
                }
            } catch (e: Exception) {
                Log.e(TAG, "External transcription failed", e)
                Result.failure(TranscriptionException.NativeError(e.message ?: "unknown", e))
            } finally {
                stream?.release()
            }
        }
    }

    override suspend fun generateText(prompt: String): Result<String> {
        return Result.failure(UnsupportedOperationException(
            "Text generation not supported by the external sherpa engine. Use for audio transcription only."))
    }

    override fun isReady(): Boolean = isInitialized && recognizer != null

    override fun isAudioSupported(): Boolean = true

    override fun unload() {
        Log.i(TAG, "Unloading external backend: $configuredId")
        recognizer?.release()
        recognizer = null
        modelDir = null
        isInitialized = false
        configuredId = PLACEHOLDER_ID
    }

    override fun setKeepAliveTimeout(minutes: Int) {
        // No-op: the engine does not manage its own lifecycle.
    }

    override fun getModelPath(): String? = modelDir
}
