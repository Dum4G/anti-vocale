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
 *   @Singleton class BackendRegistry @Inject constructor(
 *       externalModelStore: ExternalModelStore,
 *       recordsProvider: ExternalModelRecordsProvider,
 *   ) {
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

    // Static-backend fixture: an empty external-model provider derives no dynamic
    // descriptors, so the static tests below pin exactly the static six.
    private val store = com.antivocale.app.data.ExternalModelStore(
        FakePreferencesManager(),
        dirExists = { true },
    )
    private val registry = BackendRegistry(store, emptyRecordsProvider())

    /** The six enabled backends, in canonical order (default backend first). */
    private val expectedIds = listOf(
        SherpaOnnxBackend.BACKEND_ID,
        WhisperBackend.BACKEND_ID,
        Qwen3AsrBackend.BACKEND_ID,
        NemotronStreamingBackend.BACKEND_ID,
        GigaAmBackend.BACKEND_ID,
        LlmTranscriptionBackend.BACKEND_ID,
    )

    /** backendId -> the FakePreferencesManager backing flow its accessors must use. */
    private val expectedPrefFlows: Map<String, KProperty1<FakePreferencesManager, MutableStateFlow<String?>>> = mapOf(
        SherpaOnnxBackend.BACKEND_ID to FakePreferencesManager::_parakeetModelPath,
        WhisperBackend.BACKEND_ID to FakePreferencesManager::_whisperModelPath,
        Qwen3AsrBackend.BACKEND_ID to FakePreferencesManager::_qwen3AsrModelPath,
        NemotronStreamingBackend.BACKEND_ID to FakePreferencesManager::_nemotronModelPath,
        GigaAmBackend.BACKEND_ID to FakePreferencesManager::_gigaamModelPath,
        LlmTranscriptionBackend.BACKEND_ID to FakePreferencesManager::_modelPath,
    )

    @Test
    fun `static six backend ids, dynamic externals counted separately`() {
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
        // EXTERNAL maps only through dynamic descriptors: an empty provider derives
        // none by design, so it is excluded here and pinned separately below.
        val mapped = ExtractionService.ModelType.entries -
            ExtractionService.ModelType.GEMMA4_GGUF -
            ExtractionService.ModelType.EXTERNAL
        assertEquals(6, mapped.size)
        for (modelType in mapped) {
            assertNotNull("ModelType.$modelType must resolve to a descriptor", registry.byModelType(modelType))
        }
        assertNull(registry.byModelType(ExtractionService.ModelType.GEMMA4_GGUF))
        assertNull("empty provider derives no EXTERNAL descriptor", registry.byModelType(ExtractionService.ModelType.EXTERNAL))
        assertNotNull(
            "provider holding a record derives the EXTERNAL descriptor",
            BackendRegistry(store, providerWith(externalRecord())).byModelType(ExtractionService.ModelType.EXTERNAL),
        )
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
            "com.antivocale.app.ShareGigaam",
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
        assertNull("no static backend carries the blank alias anymore", registry.byShareAlias(""))
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
    fun `parakeet, nemotron and gigaam expose dedicated display-name resources, others derive from path`() {
        assertEquals(R.string.parakeet_name, registry.byBackendId(SherpaOnnxBackend.BACKEND_ID)?.displayNameResId)
        assertEquals(R.string.nemotron_name, registry.byBackendId(NemotronStreamingBackend.BACKEND_ID)?.displayNameResId)
        assertEquals(R.string.gigaam_name, registry.byBackendId(GigaAmBackend.BACKEND_ID)?.displayNameResId)
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

    // ---- dynamic external descriptors (spec v2a) ----

    private fun externalRecord(id: String = "a1b2c3d4e5f6") = com.antivocale.app.data.ExternalModelRecord(
        id = id, displayName = "GigaAM v3", dir = "/x/gigaam-v3-$id",
        family = com.antivocale.app.data.ModelFamily.TRANSDUCER, modelType = "nemo_transducer",
        languages = listOf("ru"), source = com.antivocale.app.data.ExternalModelSource.LOCAL, sourceUrl = null,
        files = mapOf("encoder.int8.onnx" to com.antivocale.app.data.FilePin("00", verified = true)),
        sizeBytes = 1L, importedAt = 0L,
    )

    private fun providerWith(vararg records: com.antivocale.app.data.ExternalModelRecord): com.antivocale.app.data.ExternalModelRecordsProvider =
        object : com.antivocale.app.data.ExternalModelRecordsProvider {
            override val records = kotlinx.coroutines.flow.MutableStateFlow(records.toList())
        }

    @Test
    fun `external records derive descriptors with no share alias`() = runTest {
        val fake = FakePreferencesManager()
        val store = com.antivocale.app.data.ExternalModelStore(fake, dirExists = { true })
        store.add(externalRecord())
        val registry = BackendRegistry(store, providerWith(externalRecord()))

        val descriptor = registry.byBackendId("external:a1b2c3d4e5f6")
        assertNotNull(descriptor)
        assertEquals(ExtractionService.ModelType.EXTERNAL, descriptor!!.modelType)
        assertEquals("", descriptor.shareAlias)
        assertEquals("GigaAM v3", descriptor.deriveDisplayName(mockk(), "/anywhere"))
    }

    @Test
    fun `provider with no records derives nothing`() = runTest {
        val fake = FakePreferencesManager()
        val store = com.antivocale.app.data.ExternalModelStore(fake, dirExists = { false })
        store.add(externalRecord())
        // Empty provider: the real provider filters invalid records out, so nothing derives.
        val registry = BackendRegistry(store, emptyRecordsProvider())
        assertNull(registry.byBackendId("external:a1b2c3d4e5f6"))
    }

    @Test
    fun `static six plus N external backends coexist and stay unique`() = runTest {
        val fake = FakePreferencesManager()
        val store = com.antivocale.app.data.ExternalModelStore(fake, dirExists = { true })
        store.add(externalRecord("111111111111")); store.add(externalRecord("222222222222"))
        val registry = BackendRegistry(store, providerWith(externalRecord("111111111111"), externalRecord("222222222222")))
        val ids = registry.backends.map { it.backendId }
        assertEquals(expectedIds.size + 2, ids.size)
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(expectedIds, ids.take(expectedIds.size))  // static first, canonical order preserved
    }

    @Test
    fun `model-path accessors delegate to the store record`() = runTest {
        val fake = FakePreferencesManager()
        val store = com.antivocale.app.data.ExternalModelStore(fake, dirExists = { true })
        store.add(externalRecord())
        val registry = BackendRegistry(store, providerWith(externalRecord()))
        val descriptor = registry.byBackendId("external:a1b2c3d4e5f6")!!
        descriptor.saveModelPath(fake, "/new/dir")
        // Store records are keyed by identity, not a path preference: saving redirects the record's dir.
        assertEquals("/new/dir", store.records().first().dir)
    }
}
