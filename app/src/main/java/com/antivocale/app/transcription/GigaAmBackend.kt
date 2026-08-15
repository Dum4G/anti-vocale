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
 * Transcription backend using sherpa-onnx with the GigaAM v3 E2E RNNT (nemo_transducer).
 *
 * GigaAM is an end-to-end Russian ASR model from Sber (MIT) with native punctuation and
 * capitalization. It is the strongest on-device choice for Russian — the ONNX files
 * ship as GitHub release assets (see [GigaAmDownloader]).
 *
 * Architecture: nemo_transducer, exactly the same shape as Parakeet TDT, so it reuses the
 * sherpa `OfflineTransducerModelConfig` with GigaAM's own encoder/decoder/joiner/tokens.
 */
@Singleton
class GigaAmBackend @Inject constructor() : TranscriptionBackend {

    companion object {
        const val BACKEND_ID = "gigaam"
        private const val TAG = "GigaAmBackend"
    }

    override val id: String = BACKEND_ID
    override val displayName: String = "GigaAM v3 (Russian)"
    override val supportsAudio: Boolean = true
    override val supportsText: Boolean = false  // ASR-only, no text generation

    // GigaAM handles long audio in a single pass - no chunking needed.
    override val maxChunkDurationSeconds: Int? = null

    private var recognizer: OfflineRecognizer? = null
    private var modelDir: String? = null
    private var isInitialized = false

    override suspend fun initialize(context: Context, config: BackendConfig): Result<Unit> {
        val sherpaConfig = config as? BackendConfig.SherpaOnnxConfig
            ?: return Result.failure(IllegalArgumentException("Invalid config type for GigaAmBackend"))

        if (isInitialized) {
            Log.w(TAG, "Already initialized, returning success")
            return Result.success(Unit)
        }

        val modelDirectory = sherpaConfig.modelDir
        Log.i(TAG, "Initializing GigaAM with model dir: $modelDirectory")

        val dir = File(modelDirectory)
        if (!dir.exists() || !dir.isDirectory) {
            return Result.failure(TranscriptionException.ModelLoadError("directory not found: $modelDirectory"))
        }

        // Pre-native validation (inside IO dispatcher): sherpa-onnx calls exit(255)
        // when the encoder is missing critical metadata, killing the app silently.
        return withContext(Dispatchers.IO) {
            if (GigaAmModelManager.validateModelDirectory(dir) == null) {
                return@withContext Result.failure(TranscriptionException.ModelLoadError(
                    "missing files in $modelDirectory: ${GigaAmModelManager.REQUIRED_FILES.joinToString()}"
                ))
            }

            val encoderFile = File(dir, GigaAmModelManager.ENCODER_FILE)
            val missingMeta = SherpaOnnxBackend.missingOnnxMetadata(encoderFile, listOf("vocab_size", "subsampling_factor", "model_type"))
            if (missingMeta.isNotEmpty()) {
                Log.e(TAG, "Encoder missing required ONNX metadata: $missingMeta")
                return@withContext Result.failure(TranscriptionException.ModelLoadError(
                    "model file is missing required metadata ($missingMeta). " +
                        "The model may be corrupt or an incompatible export. Try re-downloading it."
                ))
            }

            try {
                Log.i(TAG, "Creating OfflineRecognizer config for GigaAM...")

                // Configure the transducer model (GigaAM v3 E2E RNNT uses nemo_transducer)
                val modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = "${modelDirectory}/${GigaAmModelManager.ENCODER_FILE}",
                        decoder = "${modelDirectory}/${GigaAmModelManager.DECODER_FILE}",
                        joiner = "${modelDirectory}/${GigaAmModelManager.JOINER_FILE}"
                    ),
                    tokens = "${modelDirectory}/${GigaAmModelManager.TOKENS_FILE}",
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

                Log.i(TAG, "GigaAM backend initialized successfully")
                Result.success(Unit)

            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize GigaAM", e)
                Result.failure(TranscriptionException.ModelLoadError(e.message ?: "unknown", e))
            } catch (e: Error) {
                // Catch native errors (UnsatisfiedLinkError, etc.)
                Log.e(TAG, "Native error initializing GigaAM", e)
                Result.failure(TranscriptionException.NativeError(e.message ?: "unknown", e))
            }
        }
    }

    override suspend fun transcribeAudio(samples: FloatArray, sampleRate: Int, prompt: String): Result<TranscriptionResult> {
        val rec = recognizer
            ?: return Result.failure(TranscriptionException.NotInitialized())

        return withContext(Dispatchers.IO) {
            // Release the native OfflineStream on EVERY path (happy, exception, blank-result)
            // so the JNI handle is freed deterministically, not left to GC finalization
            // (same shape as NemotronStreamingBackend).
            var stream: OfflineStream? = null
            try {
                Log.d(TAG, "Transcribing audio: ${samples.size} samples at ${sampleRate}Hz")

                // Append 1s of silence to improve final token accuracy (same as Parakeet).
                val silencePad = FloatArray(sampleRate)
                val padded = samples + silencePad

                stream = rec.createStream()
                stream.acceptWaveform(padded, sampleRate)
                rec.decode(stream)

                val result = rec.getResult(stream)
                val transcription = result.text
                val detectedLang = result.lang.ifBlank { null }

                Log.d(TAG, "Transcription complete: '${transcription.take(100)}...' (${transcription.length} chars)")

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
                Log.e(TAG, "Transcription failed", e)
                Result.failure(TranscriptionException.NativeError(e.message ?: "unknown", e))
            } finally {
                stream?.release()
            }
        }
    }

    override suspend fun generateText(prompt: String): Result<String> {
        // sherpa-onnx is ASR-only, no text generation support
        return Result.failure(UnsupportedOperationException(
            "Text generation not supported by GigaAM backend. Use for audio transcription only."
        ))
    }

    override fun isReady(): Boolean = isInitialized && recognizer != null

    override fun isAudioSupported(): Boolean = true

    override fun unload() {
        Log.i(TAG, "Unloading GigaAM backend")
        recognizer?.release()
        recognizer = null
        modelDir = null
        isInitialized = false
    }

    override fun setKeepAliveTimeout(minutes: Int) {
        // No-op: GigaAM backend doesn't manage its own lifecycle
    }

    override fun getModelPath(): String? = modelDir

}
