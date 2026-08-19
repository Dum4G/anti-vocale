package com.antivocale.app.ui.viewmodel

import android.content.Context
import android.net.Uri
import com.antivocale.app.data.ActiveModelRepository
import com.antivocale.app.data.ExternalModelImportOperations
import com.antivocale.app.data.ExternalModelRecord
import com.antivocale.app.data.ExternalModelSource
import com.antivocale.app.data.PreferencesManager
import com.antivocale.app.data.FakePreferencesManager
import com.antivocale.app.data.ModelFamily
import com.antivocale.app.transcription.staticRegistry
import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests proving the ModelViewModel import wrappers forward family, options and
 * languages to the importer, and that the positional modelType argument is gone:
 * non-CTC families pass modelType = null so the importer's family-aware
 * resolveModelType governs (TASK-331 Task 12 amendment).
 *
 * Construction follows ModelViewModelActiveModelTest. There is no fake-importer
 * seam on ExternalModelImporter, so the test introduces the minimal
 * [ExternalModelImportOperations] interface the ViewModel now depends on and drives
 * it with a recording fake (the interface choice is documented in the commit).
 * Robolectric is needed only for Uri.parse (framework class).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class ModelViewModelExternalImportTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakePrefs: FakePreferencesManager
    private lateinit var viewModel: ModelViewModel
    private lateinit var fakeImporter: RecordingImporter

    /** One captured importer invocation. */
    data class ImportCall(
        val url: String?,
        val modelType: String?,
        val family: ModelFamily,
        val options: Map<String, String>,
        val languages: List<String>,
    )

    /** Records every call and returns a valid record; the latch lets the test wait
     *  for the real Dispatchers.IO coroutine runExternalImport launches. */
    private class RecordingImporter(val latch: CountDownLatch) : ExternalModelImportOperations {
        val calls = mutableListOf<ImportCall>()
        var treeUri: Uri? = null

        private fun record(url: String?, modelType: String?, family: ModelFamily,
                           options: Map<String, String>, languages: List<String>) {
            calls.add(ImportCall(url, modelType, family, options, languages))
            latch.countDown()
        }

        override suspend fun importFromTreeUri(
            context: Context, treeUri: Uri, modelType: String?, family: ModelFamily,
            options: Map<String, String>, languages: List<String>,
        ): ExternalModelRecord {
            this.treeUri = treeUri
            record(null, modelType, family, options, languages)
            return sampleRecord()
        }

        override suspend fun importFromUrl(
            url: String, modelType: String?, family: ModelFamily,
            options: Map<String, String>, languages: List<String>,
        ): ExternalModelRecord {
            record(url, modelType, family, options, languages)
            return sampleRecord()
        }
    }

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakePrefs = FakePreferencesManager()
        fakeImporter = RecordingImporter(CountDownLatch(1))
        val filesRoot = Files.createTempDirectory("mvm-ext").toFile()
        val mockContext: Context = mockk<Context>(relaxed = true) {
            every { filesDir } returns filesRoot
            every { getString(any()) } answers { "str:${args[0]}" }
            every { getString(any(), *anyVararg()) } answers {
                val formatArgs = (args.getOrNull(1) as? Array<*>)?.joinToString(",") ?: ""
                "str:${args[0]}:$formatArgs"
            }
        }
        viewModel = ModelViewModel(
            preferencesManager = fakePrefs,
            activeModelRepository = ActiveModelRepository(fakePrefs, mockContext, staticRegistry()),
            tokenManager = mockk(relaxed = true),
            benchmarkManager = mockk(relaxed = true),
            backendManager = mockk(relaxed = true),
            llmManager = mockk(relaxed = true),
            shareTargetManager = mockk(relaxed = true),
            ctx = mockContext,
            backendRegistry = staticRegistry(),
            externalModelStore = com.antivocale.app.data.ExternalModelStore(fakePrefs),
            externalModelImporter = fakeImporter,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `folder import forwards family options languages with null modelType for whisper`() = runTest {
        val uri = Uri.parse("content://tree/xyz")
        viewModel.importExternalFromFolder(
            context = mockk(relaxed = true), treeUri = uri,
            family = ModelFamily.WHISPER,
            options = mapOf("whisper.language" to "ar"),
            languages = listOf("ar"),
        )
        awaitImporter()
        assertEquals(uri, fakeImporter.treeUri)
        val call = fakeImporter.calls.single()
        assertNull(call.url)
        assertNull(call.modelType)
        assertEquals(ModelFamily.WHISPER, call.family)
        assertEquals(mapOf("whisper.language" to "ar"), call.options)
        assertEquals(listOf("ar"), call.languages)
    }

    @Test
    fun `url import forwards family options languages`() = runTest {
        viewModel.importExternalFromUrl(
            url = "https://example.com/model.json",
            family = ModelFamily.SENSE_VOICE,
            options = mapOf("sensevoice.itn" to "true"),
            languages = listOf("en"),
        )
        awaitImporter()
        val call = fakeImporter.calls.single()
        assertEquals("https://example.com/model.json", call.url)
        assertNull(call.modelType)
        assertEquals(ModelFamily.SENSE_VOICE, call.family)
        assertEquals(mapOf("sensevoice.itn" to "true"), call.options)
        assertEquals(listOf("en"), call.languages)
    }

    @Test
    fun `ctc import passes the explicit subtype as modelType`() = runTest {
        viewModel.importExternalFromUrl(
            url = "https://huggingface.co/istupakov/gigaam-v3-onnx",
            family = ModelFamily.CTC,
            ctcModelType = "zipformer_ctc",
        )
        awaitImporter()
        val call = fakeImporter.calls.single()
        assertEquals("zipformer_ctc", call.modelType)
        assertEquals(ModelFamily.CTC, call.family)
        assertEquals(emptyMap<String, String>(), call.options)
        assertEquals(emptyList<String>(), call.languages)
    }

    @Test
    fun `deleting the active external model resets the backend preference to default`() = runTest {
        // Same store instance the ViewModel was built with in setup? It was built with
        // its own ExternalModelStore(fakePrefs) in setup; replicate that here for seeding.
        val store = com.antivocale.app.data.ExternalModelStore(fakePrefs)
        val dir = Files.createTempDirectory("ext-delete").toFile()
        val record = sampleRecord().copy(id = "active-ext", dir = dir.absolutePath)
        store.add(record)

        viewModel.useExternalModel(record)
        runCurrent()
        assertEquals(record.backendId, fakePrefs._transcriptionBackend.value)

        viewModel.deleteExternalModel(record)
        // deleteExternalModel launches on Dispatchers.IO (real dispatcher): poll the
        // preference until the reset lands or the deadline expires.
        val deadline = System.currentTimeMillis() + 5_000
        while (fakePrefs._transcriptionBackend.value == record.backendId &&
            System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertEquals(PreferencesManager.DEFAULT_TRANSCRIPTION_BACKEND, fakePrefs._transcriptionBackend.value)
        dir.deleteRecursively()
    }

    /** runExternalImport launches on Dispatchers.IO (a real dispatcher in JVM tests):
     *  block until the fake was reached, then drain the Main dispatcher. */
    private fun kotlinx.coroutines.test.TestScope.awaitImporter() {
        fakeImporter.latch.await(5, TimeUnit.SECONDS)
        runCurrent()
    }
}

/** Minimal valid record the fake importer returns. */
internal fun sampleRecord(): ExternalModelRecord = ExternalModelRecord(
    id = "testid", displayName = "Test Model", dir = "/tmp/test-model",
    family = ModelFamily.TRANSDUCER, modelType = "nemo_transducer",
    languages = emptyList(), source = ExternalModelSource.LOCAL, sourceUrl = null,
    files = emptyMap(), sizeBytes = 0L, importedAt = 0L,
)
