package com.antivocale.app.ui.viewmodel

import android.app.Application
import android.content.Context
import com.antivocale.app.data.ActiveModelRepository
import com.antivocale.app.data.FakePreferencesManager
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Unit tests proving that a preference change propagates reactively into
 * [SettingsViewModel.uiState] through the REAL [ActiveModelRepository]
 * (TASK-258 acceptance #4).
 *
 * Construction follows the house LogsViewModel pattern: a
 * [StandardTestDispatcher] is installed as Main in [setup] and reset in
 * [tearDown]. [FakePreferencesManager] and [ActiveModelRepository] are real;
 * every other constructor dependency is a relaxed mockk. The Application
 * parameter is a relaxed mockk (SettingsViewModel is an AndroidViewModel).
 *
 * runCurrent() drains the StandardTestDispatcher's queue one step at a time
 * so the multi-hop flatMapLatest chain (backend flow -> per-backend path
 * flow -> uiState update) settles before assertions read emissions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelActiveModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakePrefs: FakePreferencesManager
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakePrefs = FakePreferencesManager()
        viewModel = SettingsViewModel(
            application = mockk<Application>(relaxed = true),
            preferencesManager = fakePrefs,
            huggingFaceTokenManager = mockk(relaxed = true),
            huggingFaceAuthManager = mockk(relaxed = true),
            huggingFaceApiClient = mockk(relaxed = true),
            perAppPreferencesManager = mockk(relaxed = true),
            transcriptionCalibrator = mockk(relaxed = true),
            backendManager = mockk(relaxed = true),
            llmManager = mockk(relaxed = true),
            shareTargetManager = mockk(relaxed = true),
            activeModelRepository = ActiveModelRepository(fakePrefs, mockk<Context>(relaxed = true)),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Mechanical wiring proof: drive the fake preferences to a known state
     * (whisper backend with a saved whisper model path), start the collector
     * via loadCurrentModel(), drain the dispatcher, and assert the UiState
     * mirrors the ActiveModel emission.
     *
     * The path does not point at a real whisper model directory, so the
     * repository's name derivation falls back to the last path segment
     * (File(path).name) and no Context resource is involved.
     */
    @Test
    fun `loadCurrentModel mirrors whisper backend and saved model path in uiState`() = runTest {
        fakePrefs._transcriptionBackend.value = "whisper"
        fakePrefs._whisperModelPath.value = "/models/whisper-test"

        viewModel.loadCurrentModel()
        runCurrent()

        val state = viewModel.uiState.value
        assertEquals("whisper", state.transcriptionBackend)
        assertEquals("/models/whisper-test", state.currentModelPath)
        assertEquals("whisper-test", state.currentModelName)
    }

    @Test
    fun `backend switch mid-collection updates state reactively`() = runTest {
        // Collector running on the initial backend with a saved model path.
        fakePrefs._transcriptionBackend.value = "whisper"
        fakePrefs._whisperModelPath.value = "/models/whisper-initial"
        viewModel.loadCurrentModel()
        runCurrent()

        // Switch to a second backend that has a DIFFERENT saved model path.
        fakePrefs._ggufModelPath.value = "/models/gemma-4-e2b-it.gguf"
        fakePrefs._transcriptionBackend.value = "gemma4_gguf"
        runCurrent()

        // Assertions (profile: model fields exactly; chosen over full-state
        // equality to stay robust against unrelated UiState churn).
        val state = viewModel.uiState.value
        assertEquals("gemma4_gguf", state.transcriptionBackend)
        assertEquals("/models/gemma-4-e2b-it.gguf", state.currentModelPath)
        assertEquals("gemma-4-e2b-it.gguf", state.currentModelName)
    }
}
