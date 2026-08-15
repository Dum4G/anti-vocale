package com.antivocale.app.transcription

import android.content.Context
import com.antivocale.app.R
import com.antivocale.app.data.FakePreferencesManager
import com.antivocale.app.service.ExtractionService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File
import kotlin.reflect.KProperty1

/**
 * TDD red-phase test for [BackendRegistry] (TASK-254).
 *
 * Contract pinned by this test:
 *   data class BackendDescriptor(
 *       val backendId: String,
 *       val modelType: ExtractionService.ModelType,
 *       val shareAlias: String,
 *       val isStreaming: Boolean,
 *       val displayNameResId: Int?,
 *       val deriveDisplayName: (context: Context, path: String) -> String,
 *       val modelPathFlow: (PreferencesManager) -> Flow<String?>,
 *       val saveModelPath: suspend (PreferencesManager, String) -> Unit,
 *       val clearModelPath: suspend (PreferencesManager) -> Unit,
 *   )
 *   @Singleton class BackendRegistry @Inject constructor() {
 *       val backends: List<BackendDescriptor>
 *       fun byBackendId(backendId: String?): BackendDescriptor?
 *       fun byModelType(modelType: ExtractionService.ModelType): BackendDescriptor?
 *       fun byShareAlias(alias: String?): BackendDescriptor?
 *   }
 *
 * The registry is metadata plumbing only: no consumer is migrated in TASK-254,
 * so these tests pin the identifier mappings (BACKEND_ID <-> ModelType <->
 * ShareReceiverActivity ALIAS_*), the per-backend PreferencesManager members,
 * and the display-name derivation, exactly as the dispatch sites hardcode them
 * today. Migration follow-ups can then assert behavior-parity against this.
 *
 * Share aliases below are pinned against the manifest activity-alias literals
 * (the registry is the single source since TASK-323: ShareReceiverActivity
 * resolves aliases via byShareAlias, so this pins registry <-> manifest).
 */
class BackendRegistryTest {

    private val registry = BackendRegistry()

    /** The five enabled backends, in canonical order (default backend first). */
    private val expectedIds = listOf(
        SherpaOnnxBackend.BACKEND_ID,
        WhisperBackend.BACKEND_ID,
        Qwen3AsrBackend.BACKEND_ID,
        NemotronStreamingBackend.BACKEND_ID,
        LlmTranscriptionBackend.BACKEND_ID,
    )

    /** backendId -> the FakePreferencesManager backing flow its accessors must use. */
    private val expectedPrefFlows: Map<String, KProperty1<FakePreferencesManager, MutableStateFlow<String?>>> = mapOf(
        SherpaOnnxBackend.BACKEND_ID to FakePreferencesManager::_parakeetModelPath,
        WhisperBackend.BACKEND_ID to FakePreferencesManager::_whisperModelPath,
        Qwen3AsrBackend.BACKEND_ID to FakePreferencesManager::_qwen3AsrModelPath,
        NemotronStreamingBackend.BACKEND_ID to FakePreferencesManager::_nemotronModelPath,
        LlmTranscriptionBackend.BACKEND_ID to FakePreferencesManager::_modelPath,
    )

    @Test
    fun `registry registers exactly the five backend ids, all unique`() {
        val ids = registry.backends.map { it.backendId }
        assertEquals(expectedIds.size, ids.size)
        assertEquals(expectedIds, ids)
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `lookup by backendId round-trips to the registered descriptor`() {
        for (descriptor in registry.backends) {
            val found = registry.byBackendId(descriptor.backendId)
            assertEquals("byBackendId(${descriptor.backendId}) must return the registered descriptor", descriptor, found)
        }
    }

    @Test
    fun `lookup by ModelType round-trips to the registered descriptor`() {
        for (descriptor in registry.backends) {
            val found = registry.byModelType(descriptor.modelType)
            assertEquals("byModelType(${descriptor.modelType}) must return the registered descriptor", descriptor, found)
        }
    }

    @Test
    fun `every active ModelType except GEMMA4_GGUF maps to a descriptor`() {
        // GEMMA4_GGUF is the disabled GGUF backend: no BACKEND_ID constant and its
        // manager is disabled (TranscriptionModule), so the registry skips it.
        val mapped = ExtractionService.ModelType.entries - ExtractionService.ModelType.GEMMA4_GGUF
        assertEquals(5, mapped.size)
        for (modelType in mapped) {
            assertNotNull("ModelType.$modelType must resolve to a descriptor", registry.byModelType(modelType))
        }
        assertNull(registry.byModelType(ExtractionService.ModelType.GEMMA4_GGUF))
    }

    @Test
    fun `lookup by shareAlias round-trips to the registered descriptor`() {
        for (descriptor in registry.backends) {
            val found = registry.byShareAlias(descriptor.shareAlias)
            assertEquals("byShareAlias(${descriptor.shareAlias}) must return the registered descriptor", descriptor, found)
        }
    }

    @Test
    fun `share aliases are the ShareReceiverActivity ALIAS values and are unique`() {
        val expectedAliases = setOf(
            "com.antivocale.app.ShareParakeet",
            "com.antivocale.app.ShareWhisper",
            "com.antivocale.app.ShareQwen3",
            "com.antivocale.app.ShareNemotron",
            "com.antivocale.app.ShareGemma",
        )
        val aliases = registry.backends.map { it.shareAlias }
        assertEquals(expectedAliases, aliases.toSet())
        assertEquals(aliases.size, aliases.toSet().size)
    }

    @Test
    fun `unknown or null identifiers return null`() {
        assertNull(registry.byBackendId("no-such-backend"))
        assertNull(registry.byBackendId(null))
        assertNull(registry.byShareAlias("com.antivocale.app.ShareNoSuch"))
        assertNull(registry.byShareAlias(null))
        assertNull(registry.byShareAlias(""))
    }

    @Test
    fun `descriptor identifiers are mutually consistent`() {
        // The three identifier schemes must agree: looking up by any key and
        // re-reading the other two keys yields the same triple everywhere.
        for (descriptor in registry.backends) {
            assertEquals(descriptor.backendId, registry.byModelType(descriptor.modelType)?.backendId)
            assertEquals(descriptor.backendId, registry.byShareAlias(descriptor.shareAlias)?.backendId)
        }
    }

    @Test
    fun `preference accessors delegate to the per-backend PreferencesManager members`() = runTest {
        for ((backendId, expectedFlow) in expectedPrefFlows) {
            val fake = FakePreferencesManager()
            val descriptor = registry.byBackendId(backendId)!!

            val sentinel = "/models/$backendId"
            descriptor.saveModelPath(fake, sentinel)
            assertEquals("$backendId save must hit its own preference", sentinel, expectedFlow.get(fake).value)

            // No cross-talk: every other backend's model-path flow is still null.
            for (other in expectedPrefFlows.values) {
                if (other != expectedFlow) {
                    assertNull("$backendId save must not touch other preferences", other.get(fake).value)
                }
            }

            assertEquals(sentinel, descriptor.modelPathFlow(fake).first())

            descriptor.clearModelPath(fake)
            assertNull("$backendId clear must reset its own preference", expectedFlow.get(fake).value)
        }
    }

    @Test
    fun `parakeet and nemotron expose dedicated display-name resources, others derive from path`() {
        assertEquals(R.string.parakeet_name, registry.byBackendId(SherpaOnnxBackend.BACKEND_ID)?.displayNameResId)
        assertEquals(R.string.nemotron_name, registry.byBackendId(NemotronStreamingBackend.BACKEND_ID)?.displayNameResId)
        assertNull(registry.byBackendId(WhisperBackend.BACKEND_ID)?.displayNameResId)
        assertNull(registry.byBackendId(Qwen3AsrBackend.BACKEND_ID)?.displayNameResId)
        assertNull(registry.byBackendId(LlmTranscriptionBackend.BACKEND_ID)?.displayNameResId)
    }

    @Test
    fun `default display-name derivation falls back to the model file name`() {
        val context = mockk<Context>()
        val descriptor = registry.byBackendId(LlmTranscriptionBackend.BACKEND_ID)!!
        assertEquals("gemma-2b-it.task", descriptor.deriveDisplayName(context, "/data/models/gemma-2b-it.task"))
    }

    @Test
    fun `qwen3 display-name derivation resolves the variant title via Qwen3AsrModelManager`() {
        val context = mockk<Context>()
        every { context.getString(R.string.qwen3_asr_0_6b_title) } returns "Qwen3-ASR 0.6B"
        val descriptor = registry.byBackendId(Qwen3AsrBackend.BACKEND_ID)!!

        // Directory name carries the 0.6b marker the model manager detects.
        assertEquals(
            "Qwen3-ASR 0.6B",
            descriptor.deriveDisplayName(context, "/data/models/qwen3-asr-0.6b"),
        )
        // Unrecognized directory names fall back to the file name.
        assertEquals(
            "qwen3-asr-unknown",
            descriptor.deriveDisplayName(context, "/data/models/qwen3-asr-unknown"),
        )
    }

    @Test
    fun `whisper display-name derivation falls back to the file name for an unrecognized directory`() {
        val context = mockk<Context>(relaxed = true)
        val descriptor = registry.byBackendId(WhisperBackend.BACKEND_ID)!!
        // Not a real model directory on disk, so validateModelDirectory returns null.
        assertEquals(
            File("/models/whisper-foreign-dir").name,
            descriptor.deriveDisplayName(context, "/models/whisper-foreign-dir"),
        )
    }

    @Test
    fun `only nemotron is a streaming backend`() {
        val streaming = registry.backends.filter { it.isStreaming }.map { it.backendId }
        assertEquals(listOf(NemotronStreamingBackend.BACKEND_ID), streaming)
    }
}
