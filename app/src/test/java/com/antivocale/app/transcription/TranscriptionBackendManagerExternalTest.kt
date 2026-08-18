package com.antivocale.app.transcription

import com.antivocale.app.data.ExternalModelRecord
import com.antivocale.app.data.ExternalModelSource
import com.antivocale.app.data.FilePin
import com.antivocale.app.data.ModelFamily
import com.antivocale.app.data.ExternalModelRecordsProvider
import com.antivocale.app.manager.LlmManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * External:-prefix routing in TranscriptionBackendManager (plan v2a, Task 5):
 * snapshot resolution, no silent config defaults, per-record handles, getBackend
 * semantics. The engine is mocked; "known" means the provider snapshot holds the
 * record, not engine state.
 */
class TranscriptionBackendManagerExternalTest {

    private lateinit var llmManager: LlmManager
    private lateinit var engine: ExternalSherpaBackend
    private lateinit var providerRecords: MutableStateFlow<List<ExternalModelRecord>>
    private lateinit var manager: TranscriptionBackendManager
    private val context = mockk<android.content.Context>()

    private var recordDir: File? = null

    @Before
    fun setUp() {
        llmManager = mockk(relaxed = true)
        engine = mockk()
        coEvery { engine.initialize(any(), any()) } returns Result.success(Unit)
        providerRecords = MutableStateFlow(emptyList())
        val provider = object : ExternalModelRecordsProvider {
            override val records = providerRecords
        }
        manager = TranscriptionBackendManager(llmManager, emptySet(), provider, engine)
    }

    private fun record(dir: String = "/x/external") = ExternalModelRecord(
        id = "abc123def456",
        displayName = "GigaAM v3",
        dir = dir,
        family = ModelFamily.TRANSDUCER,
        modelType = "nemo_transducer",
        languages = listOf("ru"),
        source = ExternalModelSource.LOCAL,
        sourceUrl = null,
        files = SherpaBackend.REQUIRED_MODEL_FILES.associateWith { FilePin("00", verified = true) },
        sizeBytes = 1L,
        importedAt = 0L,
    )

    private fun seed(vararg records: ExternalModelRecord) {
        providerRecords.value = records.toList()
    }

    private fun sherpaConfig() = BackendConfig.SherpaOnnxConfig(modelDir = "/x", numThreads = 4, provider = "cpu")

    @Test
    fun `setActiveBackend routes external ids to the engine with the exact config`() = runTest {
        val record = record()
        seed(record)
        val config = BackendConfig.ExternalConfig(record, numThreads = 4, provider = "cpu")

        val result = manager.setActiveBackend(record.backendId, context, config)

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { engine.initialize(context, config) }
    }

    @Test
    fun `external id with a non-external config fails, no silent defaults`() = runTest {
        val record = record()
        seed(record)

        val result = manager.setActiveBackend(record.backendId, context, sherpaConfig())

        assertTrue(result.isFailure)
        coVerify(exactly = 0) { engine.initialize(any(), any()) }
    }

    @Test
    fun `unknown external id fails with Unknown backend`() = runTest {
        val result = manager.setActiveBackend("external:nosuch", context, sherpaConfig())
        assertTrue(result.isFailure)
    }

    @Test
    fun `invalid external id (dir gone) fails the same way`() = runTest {
        // Provider seeded empty: what the real provider emits once validity filters the record out.
        val result = manager.setActiveBackend(record().backendId, context, sherpaConfig())
        assertTrue(result.isFailure)
    }

    @Test
    fun `getAvailableBackends appends one handle per valid record`() {
        // isReady() is a real File(dir).exists() check: the record's dir must be an actual temp dir.
        val dir = java.nio.file.Files.createTempDirectory("external-handle").toFile()
        val present = record(dir.absolutePath)
        seed(present)

        val handles = manager.getAvailableBackends().filter { it.id == present.backendId }

        assertEquals(1, handles.size)
        assertEquals(present.displayName, handles[0].displayName)
        assertTrue(handles[0].isReady())
    }

    @Test
    fun `getBackend resolves provider-known external ids to the engine`() = runTest {
        val record = record()
        seed(record)
        assertSame(engine, manager.getBackend(record.backendId))
        assertNull(manager.getBackend("external:nosuch"))
    }
}
