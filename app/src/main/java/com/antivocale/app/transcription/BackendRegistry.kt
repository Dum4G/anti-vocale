package com.antivocale.app.transcription

import android.content.Context
import com.antivocale.app.R
import com.antivocale.app.data.ExternalModelRecord
import com.antivocale.app.data.ExternalModelRecordsProvider
import com.antivocale.app.data.ExternalModelStore
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.service.ExtractionService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
 *    (single source since TASK-323: ShareReceiverActivity and ShareTargetManager
 *    resolve it through the registry; the manifest activity-alias android:name
 *    attributes themselves stay literal strings).
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
     * Share-target activity-alias for this backend: the manifest
     * activity-alias class name that routes shared audio to it. Single
     * source since TASK-323 (ShareReceiverActivity's backendIdForAlias and
     * ShareTargetManager resolve it here); the manifest android:name
     * attributes cannot reference runtime values and stay literal strings,
     * pinned by BackendRegistryTest. Blank is a valid sentinel: backends with
     * no share target carry "", and ShareTargetManager skips them during
     * component sync.
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
 * The list is the static six plus dynamic descriptors derived from the
 * external model store (spec: external models platform v2a): every valid
 * [ExternalModelRecord] yields one descriptor appended after the static
 * backends. The registry is therefore NO LONGER STATELESS, and the
 * construction assumptions built on statelessness are retired: consumers
 * must use the injected singleton (or resolve it via an entry point), never
 * a privately constructed or companion-held instance. Only DI assembles the
 * store+provider pair; extra instances would add duplicate records
 * collectors and split store mutations across racing read-modify-write
 * domains that can lose updates.
 *
 * TASK-254 introduced the abstraction; the migration is complete as of
 * TASK-324. Status of the dispatch sites from the CLAUDE.md "Architecture
 * Gotchas" section, plus the repository noted last:
 *
 * Migrated onto this registry:
 *  - [com.antivocale.app.data.ActiveModelRepository] (TASK-321; backend ids
 *    without a descriptor keep their legacy fallbacks there: GGUF's dedicated
 *    ggufModelPath, generic modelPath for other unknowns)
 *  - [com.antivocale.app.transcription.TranscriptionOrchestrator] (TASK-322;
 *    the backend-load dispatch keys on the descriptor's modelType and the
 *    saved-model-path lookup reads the descriptor's model-path flow, with the
 *    GGUF literal and unknown-id fallbacks kept locally; its calibration
 *    display-name derivation is still a string-keyed when (BACKEND_ID
 *    constants) that deliberately keeps its own dir-name semantics, see
 *    TranscriptionOrchestratorTest)
 *  - the share-target sites (TASK-323):
 *    [com.antivocale.app.receiver.ShareReceiverActivity].backendIdForAlias
 *    resolves the intent's alias component via byShareAlias (unknown aliases
 *    still yield null; the private ALIAS_* constants are gone), and
 *    [com.antivocale.app.data.ShareTargetManager] iterates the registry's
 *    descriptors for component enable/disable and reads the descriptor's
 *    model-path flow for the has-model check (neither site had a GGUF
 *    target, so nothing literal needed preserving)
 *  - [com.antivocale.app.ui.viewmodel.LogsViewModel] (TASK-325): the
 *    re-transcribe picker's model-presence filter reads the descriptor's
 *    model-path flow (the old hand-built id->path map was lookup-only, so
 *    the picker order, driven by the backend manager, is unchanged)
 *  - [com.antivocale.app.ui.viewmodel.SettingsViewModel].loadCurrentModel
 *    (indirectly via TASK-258: it keeps no parallel mapping of its own but
 *    collects [com.antivocale.app.data.ActiveModelRepository]'s
 *    activeModelFlow, which dispatches through this registry since TASK-321)
 *  - [com.antivocale.app.ui.viewmodel.ModelViewModel] (TASK-324: the
 *    file-validity check in loadSavedModelPath keys on the descriptor's
 *    modelType, mirroring the orchestrator, with the GGUF literal matched
 *    first; its benchmark-config when in startBenchmark still keys on
 *    backend-id strings, which selects inference configuration rather than
 *    backend metadata)
 *
 * Remaining sites, deliberately (not migration targets):
 *  - [com.antivocale.app.service.ExtractionService]: assessed in TASK-322,
 *    the ModelType enum stays as the persistence/bookkeeping scheme and the
 *    dispatch carries no registry data, so nothing to migrate there
 *  - [com.antivocale.app.di.TranscriptionModule] (Hilt @IntoSet DI
 *    registration): assembling the backend set is a different concern from
 *    metadata dispatch (this registry describes backends, the multibinding
 *    instantiates them)
 *  - [com.antivocale.app.data.PreferencesManager] / PreferencesManagerImpl
 *    (per-backend xxxModelPath flow + save/clear): this interface IS the
 *    data source the descriptors delegate to ([BackendDescriptor.modelPathFlow],
 *    [BackendDescriptor.saveModelPath], [BackendDescriptor.clearModelPath]
 *    all take a PreferencesManager), so migrating it onto the registry would
 *    be circular
 *  - AndroidManifest.xml (share-target activity-alias) + strings
 *    (share_target_*): the android:name attributes stay literal strings by
 *    necessity (the manifest cannot reference registry values); their values
 *    are pinned by BackendRegistryTest
 *
 * Not registered: the disabled GGUF backend (`gemma4_gguf`,
 * [ExtractionService.ModelType.GEMMA4_GGUF]). It has no BACKEND_ID constant
 * and its manager is disabled (see the commented-out provider in
 * [com.antivocale.app.di.TranscriptionModule]); follow-up: give it a
 * BACKEND_ID and a descriptor if it is ever re-enabled.
 */
@Singleton
class BackendRegistry @Inject constructor(
    private val externalModelStore: ExternalModelStore,
    private val recordsProvider: ExternalModelRecordsProvider,
) {

    /** The six enabled static backends in canonical order (default backend first). */
    private val staticBackends: List<BackendDescriptor> = listOf(
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
            backendId = GigaAmBackend.BACKEND_ID,
            modelType = ExtractionService.ModelType.GIGAAM,
            shareAlias = "com.antivocale.app.ShareGigaam",
            displayNameResId = R.string.gigaam_name,
            modelPathFlow = { it.gigaamModelPath },
            saveModelPath = { prefs, path -> prefs.saveGigaAmModelPath(path) },
            clearModelPath = { it.clearGigaAmModelPath() },
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

    /** Static backends first (canonical order), then one descriptor per valid external record. */
    val backends: List<BackendDescriptor>
        get() = staticBackends + recordsProvider.records.value.map(::descriptorFor)

    /**
     * Derives one dynamic descriptor per imported record. Identity is the
     * record's uuid (backendId `external:<id>`), not a path preference:
     * saving a model path redirects the record's dir, clearing it deletes
     * the record.
     */
    private fun descriptorFor(record: ExternalModelRecord): BackendDescriptor = BackendDescriptor(
        backendId = record.backendId,
        modelType = ExtractionService.ModelType.EXTERNAL,
        shareAlias = "",  // spec: the ShareExternal family alias is synced separately
        deriveDisplayName = { _, _ -> record.displayName },
        // The store (not the registry) owns the records JSON: the path flow derives
        // from its decoded list instead of a second raw-preference decoder here.
        modelPathFlow = { _ ->
            externalModelStore.recordsFlow.map { records ->
                records.firstOrNull { it.id == record.id }?.dir } },
        saveModelPath = { _, path ->
            // Identity is the uuid, not a path preference: a save redirects the record's
            // dir via a targeted update, so the captured snapshot reverts nothing else.
            externalModelStore.updateDir(record.id, path) },
        clearModelPath = { externalModelStore.delete(record.id) },
    )

    // The lookups recompute over backends per call: the lists are tiny and the
    // dynamic set can change between calls, so caching a map would go stale.

    /** Returns the descriptor for [backendId], or null if unknown (including null/blank). */
    fun byBackendId(backendId: String?): BackendDescriptor? =
        backendId?.let { id -> backends.firstOrNull { it.backendId == id } }

    /**
     * Returns the descriptor for [modelType], or null if none is registered
     * (GEMMA4_GGUF). The mapping is 1:N for [ExtractionService.ModelType.EXTERNAL]:
     * several external records share it, and the first one wins.
     */
    fun byModelType(modelType: ExtractionService.ModelType): BackendDescriptor? =
        backends.firstOrNull { it.modelType == modelType }

    /**
     * Returns the descriptor for a share-target [alias], or null if unknown
     * (including null/blank).
     */
    fun byShareAlias(alias: String?): BackendDescriptor? =
        alias?.let { a -> backends.firstOrNull { it.shareAlias == a } }
}
