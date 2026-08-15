package com.antivocale.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * Minimal [PreferencesManager] fake whose flow accessors return MutableStateFlows
 * that tests can mutate to simulate preference changes.
 *
 * Shared fixture: used by [ActiveModelRepositoryTest] and by the ViewModel tests
 * that drive the real ActiveModelRepository end-to-end (TASK-258 acceptance #4).
 * Extracted from ActiveModelRepositoryTest when a second consumer appeared.
 *
 * Every interface member is stubbed. Suspend mutators are no-ops (or update the
 * backing flow for convenience). Benchmark flows return empty maps.
 * The legacy language getter returns "en".
 */
internal class FakePreferencesManager : PreferencesManager {

    val _modelPath = MutableStateFlow<String?>(null)
    val _keepAliveTimeout = MutableStateFlow(5)
    val _themePreference = MutableStateFlow("DEFAULT")
    val _themeMode = MutableStateFlow("SYSTEM")
    val _transcriptionBackend = MutableStateFlow(PreferencesManager.DEFAULT_TRANSCRIPTION_BACKEND)
    val _parakeetModelPath = MutableStateFlow<String?>(null)
    val _whisperModelPath = MutableStateFlow<String?>(null)
    val _qwen3AsrModelPath = MutableStateFlow<String?>(null)
    val _nemotronModelPath = MutableStateFlow<String?>(null)
    val _ggufModelPath = MutableStateFlow<String?>(null)
    val _autoCopyEnabled = MutableStateFlow(false)
    val _outputFolderUri = MutableStateFlow<String?>(null)
    val _vadEnabled = MutableStateFlow(false)
    val _vadAdvisoryDismissed = MutableStateFlow(false)
    val _progressiveTranscription = MutableStateFlow(true)
    val _defaultPrompt = MutableStateFlow("")
    val _threadCount = MutableStateFlow(PreferencesManager.DEFAULT_THREAD_COUNT)
    val _inferenceProvider = MutableStateFlow("auto")
    val _transcriptionLanguage = MutableStateFlow("auto")
    val _swipeActionMode = MutableStateFlow("REVEAL")
    val _groupLogsByConversation = MutableStateFlow(true)
    val _advancedSharingEnabled = MutableStateFlow(false)
    val _showRetranscribeButton = MutableStateFlow(true)
    val _forceModelLoad = MutableStateFlow(false)
    val _partialTranscriptionText = MutableStateFlow<String?>(null)
    val _partialTranscriptionTimestamp = MutableStateFlow<Long?>(null)
    val _benchmarkResults = MutableStateFlow<Map<String, String>>(emptyMap())

    override val modelPath: Flow<String?> get() = _modelPath
    override val keepAliveTimeout: Flow<Int> get() = _keepAliveTimeout
    override val themePreference: Flow<String> get() = _themePreference
    override val themeMode: Flow<String> get() = _themeMode
    override val transcriptionBackend: Flow<String> get() = _transcriptionBackend
    override val parakeetModelPath: Flow<String?> get() = _parakeetModelPath
    override val whisperModelPath: Flow<String?> get() = _whisperModelPath
    override val qwen3AsrModelPath: Flow<String?> get() = _qwen3AsrModelPath
    override val nemotronModelPath: Flow<String?> get() = _nemotronModelPath
    override val ggufModelPath: Flow<String?> get() = _ggufModelPath
    override val autoCopyEnabled: Flow<Boolean> get() = _autoCopyEnabled
    override val outputFolderUri: Flow<String?> get() = _outputFolderUri
    override val vadEnabled: Flow<Boolean> get() = _vadEnabled
    override val vadAdvisoryDismissed: Flow<Boolean> get() = _vadAdvisoryDismissed
    override val progressiveTranscription: Flow<Boolean> get() = _progressiveTranscription
    override val defaultPrompt: Flow<String> get() = _defaultPrompt
    override val threadCount: Flow<Int> get() = _threadCount
    override val inferenceProvider: Flow<String> get() = _inferenceProvider
    override val transcriptionLanguage: Flow<String> get() = _transcriptionLanguage
    override val swipeActionMode: Flow<String> get() = _swipeActionMode
    override val groupLogsByConversation: Flow<Boolean> get() = _groupLogsByConversation
    override val advancedSharingEnabled: Flow<Boolean> get() = _advancedSharingEnabled
    override val showRetranscribeButton: Flow<Boolean> get() = _showRetranscribeButton
    override val forceModelLoad: Flow<Boolean> get() = _forceModelLoad
    override val partialTranscriptionText: Flow<String?> get() = _partialTranscriptionText
    override val partialTranscriptionTimestamp: Flow<Long?> get() = _partialTranscriptionTimestamp

    // Suspend mutators: update backing flows for convenience
    override suspend fun saveModelPath(path: String) { _modelPath.value = path }
    override suspend fun clearModelPath() { _modelPath.value = null }
    override suspend fun saveKeepAliveTimeout(minutes: Int) { _keepAliveTimeout.value = minutes }
    override suspend fun saveThemePreference(theme: String) { _themePreference.value = theme }
    override suspend fun saveThemeMode(mode: String) { _themeMode.value = mode }
    override suspend fun saveTranscriptionBackend(backendId: String) { _transcriptionBackend.value = backendId }
    override suspend fun saveParakeetModelPath(path: String) { _parakeetModelPath.value = path }
    override suspend fun clearParakeetModelPath() { _parakeetModelPath.value = null }
    override suspend fun saveWhisperModelPath(path: String) { _whisperModelPath.value = path }
    override suspend fun clearWhisperModelPath() { _whisperModelPath.value = null }
    override suspend fun saveQwen3AsrModelPath(path: String) { _qwen3AsrModelPath.value = path }
    override suspend fun clearQwen3AsrModelPath() { _qwen3AsrModelPath.value = null }
    override suspend fun saveNemotronModelPath(path: String) { _nemotronModelPath.value = path }
    override suspend fun clearNemotronModelPath() { _nemotronModelPath.value = null }
    override suspend fun saveGgufModelPath(path: String) { _ggufModelPath.value = path }
    override suspend fun clearGgufModelPath() { _ggufModelPath.value = null }
    override suspend fun saveAutoCopyEnabled(enabled: Boolean) { _autoCopyEnabled.value = enabled }
    override suspend fun saveOutputFolderUri(uri: String?) { _outputFolderUri.value = uri }
    override suspend fun saveVadEnabled(enabled: Boolean) { _vadEnabled.value = enabled }
    override suspend fun saveVadAdvisoryDismissed(dismissed: Boolean) { _vadAdvisoryDismissed.value = dismissed }
    override suspend fun saveProgressiveTranscription(enabled: Boolean) { _progressiveTranscription.value = enabled }
    override suspend fun saveDefaultPrompt(prompt: String) { _defaultPrompt.value = prompt }
    override suspend fun saveThreadCount(threads: Int) { _threadCount.value = threads }
    override suspend fun saveInferenceProvider(provider: String) { _inferenceProvider.value = provider }
    override suspend fun saveTranscriptionLanguage(language: String) { _transcriptionLanguage.value = language }
    override suspend fun saveSwipeActionMode(mode: String) { _swipeActionMode.value = mode }
    override suspend fun saveGroupLogsByConversation(enabled: Boolean) { _groupLogsByConversation.value = enabled }
    override suspend fun saveAdvancedSharingEnabled(enabled: Boolean) { _advancedSharingEnabled.value = enabled }
    override suspend fun saveShowRetranscribeButton(enabled: Boolean) { _showRetranscribeButton.value = enabled }
    override suspend fun saveForceModelLoad(enabled: Boolean) { _forceModelLoad.value = enabled }
    override suspend fun savePartialTranscriptionState(text: String) { _partialTranscriptionText.value = text }
    override suspend fun clearPartialTranscriptionState() { _partialTranscriptionText.value = null }
    override suspend fun getLegacyLanguagePreference(): String = "en"

    override suspend fun saveBenchmarkResult(modelId: String, jsonResult: String) {
        _benchmarkResults.value = _benchmarkResults.value + (modelId to jsonResult)
    }

    override fun getBenchmarkResult(modelId: String): Flow<String?> =
        _benchmarkResults.map { it[modelId] }

    override fun getAllBenchmarkResults(): Flow<Map<String, String>> = _benchmarkResults

    override suspend fun clearBenchmarkResult(modelId: String) {
        _benchmarkResults.value = _benchmarkResults.value - modelId
    }

    override suspend fun clearAllBenchmarkResults() {
        _benchmarkResults.value = emptyMap()
    }
}
