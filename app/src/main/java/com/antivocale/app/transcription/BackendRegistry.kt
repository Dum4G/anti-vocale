package com.antivocale.app.transcription

import android.content.Context
import com.antivocale.app.R
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.service.ExtractionService
import kotlinx.coroutines.flow.Flow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Immutable metadata about one transcription backend, tying together the three
 * independent identifier schemes the app currently dispatches on:
 *
 *  - the [backendId] string ([TranscriptionBackend.id] / the `BACKEND_ID`
 *    companion constants, also persisted as the `transcriptionBackend`
 *    preference),
 *  - the [modelType] enum used by download/bookkeeping code
 *    ([ExtractionService.ModelType]),
 *  - the [shareAlias] manifest activity-alias used to route share targets
 *    (mirrors ShareReceiverActivity's private `ALIAS_*` constants).
 *
 * It also carries the per-backend saved-model-path preference accessors and the
 * display-name derivation, so the parallel `when` blocks can collapse into
 * registry lookups.
 *
 * Display-name contract: if [displayNameResId] is non-null the backend has a
 * fixed localized name (`context.getString(displayNameResId)`); otherwise call
 * [deriveDisplayName] with the saved model path. Backends whose name depends on
 * the downloaded variant (Whisper, Qwen3-ASR) derive it via their model
 * manager; the default derivation is the model file name. This is the single
 * implementation of the derivations (ActiveModelRepository consumes it since
 * TASK-321).
 *
 * Differences between backends are expressed as capability flags
 * ([isStreaming]) rather than forcing uniform metadata: the LLM backend
 * (Gemma) has no dedicated display-name resource and stores its model path in
 * the generic `modelPath` preference, which its accessors reflect.
 */
data class BackendDescriptor(
    /** Value of the backend's `BACKEND_ID` companion constant (e.g. "sherpa-onnx"). */
    val backendId: String,

    /** Download/bookkeeping enum for this backend's models. */
    val modelType: ExtractionService.ModelType,

    /**
     * Share-target activity-alias for this backend. Literal mirror of the
     * private `ALIAS_*` constants in `ShareReceiverActivity`; pinned by
     * BackendRegistryTest until the registry becomes the single source.
     */
    val shareAlias: String,

    /** True for the streaming recognizer backend (Nemotron); all others are batch. */
    val isStreaming: Boolean = false,

    /** Dedicated localized display name, or null when the name derives from the model path. */
    val displayNameResId: Int? = null,

    /**
     * Derives the user-visible model name from the saved model path.
     * Used when [displayNameResId] is null; the [Context] supplies localized
     * variant titles where the model manager resolves one.
     */
    val deriveDisplayName: (context: Context, path: String) -> String = { _, path -> File(path).name },

    /** Saved model-path preference flow for this backend. */
    val modelPathFlow: (PreferencesManager) -> Flow<String?>,

    /** Persists this backend's model path preference. */
    val saveModelPath: suspend (PreferencesManager, String) -> Unit,

    /** Clears this backend's model path preference. */
    val clearModelPath: suspend (PreferencesManager) -> Unit,
)

/**
 * Single source of truth for transcription-backend metadata: the ordered list
 * of [BackendDescriptor]s plus lookups by backend-id, [ExtractionService.ModelType],
 * and share alias.
 *
 * TASK-254 introduces the abstraction only; the dispatch sites below still
 * hardcode their own mappings and are meant to migrate onto this registry in
 * follow-up tasks. Checklist (from the CLAUDE.md "Architecture Gotchas"
 * section, plus the repository noted last):
 *
 *  - [com.antivocale.app.ui.viewmodel.ModelViewModel] (loadSavedModelPath,
 *    modelPathForBackend, benchmark config)
 *  - [com.antivocale.app.ui.viewmodel.SettingsViewModel].loadCurrentModel
 *    (Settings active-model display; separate state from ModelViewModel)
 *  - [com.antivocale.app.service.ExtractionService] (ModelType enum +
 *    download/cancel/displayName dispatch. Assessed in TASK-322: the enum
 *    stays as the persistence/bookkeeping scheme and the dispatch carries no
 *    registry data, so nothing to migrate there)
 *  - [com.antivocale.app.di.TranscriptionModule] (Hilt @IntoSet DI registration)
 *  - [com.antivocale.app.data.ShareTargetManager] (TARGETS list + hasModel) and
 *    [com.antivocale.app.receiver.ShareReceiverActivity] (ALIAS_* constants +
 *    backendIdForAlias)
 *  - AndroidManifest.xml (share-target activity-alias) + strings (share_target_*)
 *  - [com.antivocale.app.data.PreferencesManager] / PreferencesManagerImpl
 *    (per-backend xxxModelPath flow + save/clear)
 *
 * Migrated onto this registry: [com.antivocale.app.data.ActiveModelRepository]
 * (TASK-321; backend ids without a descriptor keep their legacy fallbacks
 * there: GGUF's dedicated ggufModelPath, generic modelPath for other
 * unknowns), and [com.antivocale.app.transcription.TranscriptionOrchestrator]
 * (TASK-322; the backend-load dispatch keys on the descriptor's modelType and
 * the saved-model-path lookup reads the descriptor's model-path flow, with the
 * GGUF literal and unknown-id fallbacks kept locally; its calibration
 * display-name derivation deliberately keeps its own dir-name semantics, see
 * TranscriptionOrchestratorTest).
 *
 * Not registered: the disabled GGUF backend (`gemma4_gguf`,
 * [ExtractionService.ModelType.GEMMA4_GGUF]). It has no BACKEND_ID constant
 * and its manager is disabled (see the commented-out provider in
 * [com.antivocale.app.di.TranscriptionModule]); follow-up: give it a
 * BACKEND_ID and a descriptor if it is ever re-enabled.
 */
@Singleton
class BackendRegistry @Inject constructor() {

    /** The five enabled backends in canonical order (default backend first). */
    val backends: List<BackendDescriptor> = listOf(
        BackendDescriptor(
            backendId = SherpaOnnxBackend.BACKEND_ID,
            modelType = ExtractionService.ModelType.PARAKEET,
            shareAlias = "com.antivocale.app.ShareParakeet",
            displayNameResId = R.string.parakeet_name,
            modelPathFlow = { it.parakeetModelPath },
            saveModelPath = { prefs, path -> prefs.saveParakeetModelPath(path) },
            clearModelPath = { it.clearParakeetModelPath() },
        ),
        BackendDescriptor(
            backendId = WhisperBackend.BACKEND_ID,
            modelType = ExtractionService.ModelType.WHISPER,
            shareAlias = "com.antivocale.app.ShareWhisper",
            deriveDisplayName = { context, path ->
                WhisperModelManager.validateModelDirectory(File(path))
                    ?.variant
                    ?.let { context.getString(it.titleResId) }
                    ?: File(path).name
            },
            modelPathFlow = { it.whisperModelPath },
            saveModelPath = { prefs, path -> prefs.saveWhisperModelPath(path) },
            clearModelPath = { it.clearWhisperModelPath() },
        ),
        BackendDescriptor(
            backendId = Qwen3AsrBackend.BACKEND_ID,
            modelType = ExtractionService.ModelType.QWEN3_ASR,
            shareAlias = "com.antivocale.app.ShareQwen3",
            deriveDisplayName = { context, path ->
                Qwen3AsrModelManager.detectVariant(File(path).name)
                    ?.let { context.getString(it.titleResId) }
                    ?: File(path).name
            },
            modelPathFlow = { it.qwen3AsrModelPath },
            saveModelPath = { prefs, path -> prefs.saveQwen3AsrModelPath(path) },
            clearModelPath = { it.clearQwen3AsrModelPath() },
        ),
        BackendDescriptor(
            backendId = NemotronStreamingBackend.BACKEND_ID,
            modelType = ExtractionService.ModelType.NEMOTRON,
            shareAlias = "com.antivocale.app.ShareNemotron",
            isStreaming = true,
            displayNameResId = R.string.nemotron_name,
            modelPathFlow = { it.nemotronModelPath },
            saveModelPath = { prefs, path -> prefs.saveNemotronModelPath(path) },
            clearModelPath = { it.clearNemotronModelPath() },
        ),
        BackendDescriptor(
            backendId = LlmTranscriptionBackend.BACKEND_ID,
            modelType = ExtractionService.ModelType.GEMMA,
            shareAlias = "com.antivocale.app.ShareGemma",
            // The LLM backend stores its model path in the generic preference.
            modelPathFlow = { it.modelPath },
            saveModelPath = { prefs, path -> prefs.saveModelPath(path) },
            clearModelPath = { it.clearModelPath() },
        ),
    )

    private val byId: Map<String, BackendDescriptor> =
        backends.associateBy(BackendDescriptor::backendId)

    private val byType: Map<ExtractionService.ModelType, BackendDescriptor> =
        backends.associateBy(BackendDescriptor::modelType)

    private val byAlias: Map<String, BackendDescriptor> =
        backends.associateBy(BackendDescriptor::shareAlias)

    /** Returns the descriptor for [backendId], or null if unknown (including null/blank). */
    fun byBackendId(backendId: String?): BackendDescriptor? = backendId?.let { byId[it] }

    /** Returns the descriptor for [modelType], or null if none is registered (GEMMA4_GGUF). */
    fun byModelType(modelType: ExtractionService.ModelType): BackendDescriptor? = byType[modelType]

    /** Returns the descriptor for a share-target [alias], or null if unknown (including null/blank). */
    fun byShareAlias(alias: String?): BackendDescriptor? = alias?.let { byAlias[it] }
}
