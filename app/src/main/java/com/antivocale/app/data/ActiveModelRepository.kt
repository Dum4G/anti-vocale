package com.antivocale.app.data

import android.content.Context
import com.antivocale.app.R
import com.antivocale.app.transcription.Qwen3AsrModelManager
import com.antivocale.app.transcription.WhisperModelManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for the "currently active model" state.
 *
 * Reactively combines the selected transcription backend with the
 * corresponding per-backend model-path preference so that consumers
 * always see a consistent [ActiveModel].
 *
 * NOTE: This class contains a `when` block keyed on backend-id strings.
 * When BackendRegistry (TASK-254) lands, this is the single place to
 * replace with descriptor-based accessor lookups.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ActiveModelRepository @Inject constructor(
    private val preferencesManager: PreferencesManager,
    @ApplicationContext private val context: Context,
) {
    /**
     * The active backend plus its saved model path and display name, reactively
     * derived from the backend preference and the matching per-backend model-path
     * flow. Exposed as a cold [Flow]; consumers that want a current-value snapshot
     * can `.first()` it, and view models can `collect` it to stay in sync. Both
     * [ModelViewModel] and [SettingsViewModel] collect this instead of dispatching
     * per-backend themselves, so a model change in one tab is reflected in the
     * other without a manual reload.
     */
    val activeModelFlow: Flow<ActiveModel> =
        preferencesManager.transcriptionBackend.flatMapLatest { backend ->
            when (backend) {
                "sherpa-onnx" -> preferencesManager.parakeetModelPath.map { path ->
                    path.toActiveModel(backend) { context.getString(R.string.parakeet_name) }
                }
                "whisper" -> preferencesManager.whisperModelPath.map { path ->
                    path.toActiveModel(backend) { p ->
                        WhisperModelManager.validateModelDirectory(File(p))
                            ?.variant?.let { v -> context.getString(v.titleResId) }
                            ?: File(p).name
                    }
                }
                "qwen3-asr" -> preferencesManager.qwen3AsrModelPath.map { path ->
                    path.toActiveModel(backend) { p ->
                        Qwen3AsrModelManager.detectVariant(File(p).name)?.let { v ->
                            context.getString(v.titleResId)
                        } ?: File(p).name
                    }
                }
                "nemotron-streaming" -> preferencesManager.nemotronModelPath.map { path ->
                    path.toActiveModel(backend) { context.getString(R.string.nemotron_name) }
                }
                "gemma4_gguf" -> preferencesManager.ggufModelPath.map { path ->
                    path.toActiveModel(backend) { File(it).name }
                }
                else -> preferencesManager.modelPath.map { path ->
                    path.toActiveModel(backend) { File(it).name }
                }
            }
        }

    private fun String?.toActiveModel(backendId: String, nameForPath: (String) -> String): ActiveModel {
        val effectivePath = this?.takeUnless { it.isBlank() }
        return ActiveModel(
            backendId = backendId,
            modelPath = effectivePath,
            modelName = effectivePath?.let(nameForPath)
        )
    }
}

data class ActiveModel(
    val backendId: String,
    val modelPath: String?,
    val modelName: String?
)
