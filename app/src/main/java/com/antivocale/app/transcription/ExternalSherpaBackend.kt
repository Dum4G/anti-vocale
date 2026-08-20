package com.antivocale.app.transcription

import android.content.Context
import android.util.Log
import com.antivocale.app.data.ExternalModelRecord
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineStream
import com.antivocale.app.data.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single configurable engine for imported external models (spec: external models
 * platform v2a). One [ExternalModelRecord] is configured per [initialize] via
 * [BackendConfig.ExternalConfig]; file names and the sherpa model config come
 * from [ModelFamilySupport.forFamily] (the per-family table the importer also
 * uses, so the two cannot drift).
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
    }

    @Volatile private var configuredId: String = PLACEHOLDER_ID
    override val id: String get() = configuredId

    override val displayName: String get() = "External model"
    override val supportsAudio: Boolean = true
    override val supportsText: Boolean = false

    // Single-pass like Parakeet/GigaAM: realistic v2a imports are offline transducers.
    override val maxChunkDurationSeconds: Int? = null

    // @Volatile: a concurrent transcribeAudio on another thread must not read a stale
    // null recognizer after initialize completes (the unload-during-transcription window
    // is inherited from the sibling backends and unchanged).
    @Volatile private var recognizer: OfflineRecognizer? = null
    private var modelDir: String? = null
    @Volatile private var isInitialized = false

    /** Idle-unload timer, same rationale as SherpaBackend (TASK-344 / issue #42). */
    private val keepAliveScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val keepAlive = NativeKeepAlive(
        scope = keepAliveScope,
        tag = TAG,
        defaultTimeoutMinutes = PreferencesManager.DEFAULT_KEEP_ALIVE_TIMEOUT,
        onIdleUnload = { runCatching { unload() } },
    )
    private val onAutoUnloadCallback = java.util.concurrent.atomic.AtomicReference<(() -> Unit)?>(null)

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
        // when the family's model file is missing critical metadata, killing the app silently.
        val support = ModelFamilySupport.forFamily(record.family)
        return withContext(Dispatchers.IO) {
            val missing = support.requiredRoles().filterNot { File(dir, it).exists() }
            if (missing.isNotEmpty()) {
                return@withContext Result.failure(TranscriptionException.ModelLoadError(
                    "missing files in ${record.dir}: $missing"))
            }

            // Family validation shared with the importer (single definition):
            // [ModelFamilySupport.metadataKeys] plus value-aware discriminators.
            val metadataFile = File(dir, support.metadataFileRole())
            val (missingMeta, metadataValue) = SherpaBackend.missingOnnxMetadataAndValue(
                metadataFile, support.metadataKeys(record.modelType), support.valueMetadataKey())
            if (missingMeta.isNotEmpty()) {
                Log.e(TAG, "${support.metadataFileRole()} missing required ONNX metadata: $missingMeta")
                return@withContext Result.failure(TranscriptionException.ModelLoadError(
                    "model file is missing required metadata ($missingMeta). " +
                        "The model may be corrupt, an incompatible export, or the wrong family. " +
                        "Try re-importing it or correcting its family."))
            }
            try {
                support.validateImportedModel(metadataValue)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Family validation failed for ${record.backendId}: ${e.message}")
                return@withContext Result.failure(TranscriptionException.ModelLoadError(
                    "family validation failed for ${record.backendId}: ${e.message ?: "no detail provided"}", e))
            }

            try {
                val modelConfig = support.buildModelConfig(record, externalConfig.numThreads, externalConfig.provider)

                val recognizerConfig = OfflineRecognizerConfig(
                    modelConfig = modelConfig,
                    featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                    decodingMethod = "greedy_search"
                )

                recognizer = OfflineRecognizer(config = recognizerConfig)
                modelDir = record.dir
                configuredId = record.backendId
                isInitialized = true
                keepAlive.start()

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

        keepAlive.beginWork()
        try {
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
        } finally {
            keepAlive.endWork()
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
        keepAlive.stop()
        recognizer?.release()
        recognizer = null
        modelDir = null
        isInitialized = false
        configuredId = PLACEHOLDER_ID
        onAutoUnloadCallback.get()?.invoke()
    }

    override fun setKeepAliveTimeout(minutes: Int) {
        keepAlive.setTimeout(minutes)
    }

    override fun setOnAutoUnloadCallback(callback: (() -> Unit)?) {
        onAutoUnloadCallback.set(callback)
    }

    override fun getModelPath(): String? = modelDir
}
