package com.antivocale.app.transcription

import android.app.ActivityManager
import android.content.Context
import com.antivocale.app.data.PreferencesManager
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptionOrchestratorBackendOverrideTest : TranscriptionOrchestratorTestBase() {

    private val tempDirs = mutableListOf<File>()

    override fun baseSetUp() {
        super.baseSetUp()
        every { preferencesManager.inferenceProvider } returns flowOf("auto")
        // loadCatalogBackend reads transcriptionLanguage to wire the offline language;
        // without a stub the relaxed mock Flow never emits (first() cannot complete).
        every { preferencesManager.transcriptionLanguage } returns flowOf("auto")
    }

    @After
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
    }

    private fun createTempModelDir(prefix: String = "model"): File {
        val parent = File(System.getProperty("java.io.tmpdir"), "$prefix-${System.nanoTime()}")
        parent.mkdirs()
        tempDirs.add(parent)
        // The model dir must carry a catalog dir name so SherpaModelManager.isValidModelPath
        // accepts it. The .onnx files must be non-empty (isFileComplete rejects zero-length
        // files); tokens.txt just needs to exist.
        val dir = File(parent, "parakeet-tdt-0.6b-v3-smoothquant")
        dir.mkdirs()
        SherpaModelManager.of(BuiltInBackendIds.PARAKEET).REQUIRED_FILES.forEach { name ->
            File(dir, name).writeBytes(if (name.endsWith(".onnx")) byteArrayOf(0) else ByteArray(0))
        }
        return dir
    }

    /**
     * Creates a mock Context with getSystemService(ActivityManager) stubbed to null so the
     * OOM pre-flight memory check in configureSherpaBackend fails open (availBytes=0) instead
     * of throwing a ClassCastException from the relaxed mock. filesDir points at a real empty
     * temp dir so SherpaModelManager.resolveActiveModelPath (used when no saved path exists)
     * scans real paths instead of NPE-ing on a mock File with a null path.
     */
    private fun createMockContext(): Context {
        val contextFilesDir = File(System.getProperty("java.io.tmpdir"), "context-files-${System.nanoTime()}").apply { mkdirs() }
        tempDirs.add(contextFilesDir)
        return mockk<Context>(relaxed = true) {
            every { getSystemService(ActivityManager::class.java) } returns null
            every { filesDir } returns contextFilesDir
        }
    }

    @Test
    fun `backend override loads specified backend instead of preference`() = runTest {
        val parakeetDir = createTempModelDir("parakeet")

        // Preference says whisper, but override says sherpa-onnx
        every { preferencesManager.transcriptionBackend } returns flowOf(BuiltInBackendIds.WHISPER)
        every { preferencesManager.sherpaModelPath(BuiltInBackendIds.PARAKEET) } returns flowOf(parakeetDir.absolutePath)
        every { preferencesManager.threadCount } returns flowOf(4)
        every { preferencesManager.keepAliveTimeout } returns flowOf(5)
        every { backendManager.hasActiveBackend() } returns false
        coEvery { backendManager.setActiveBackend(any(), any(), any()) } returns Result.success(Unit)

        val context = createMockContext()
        orchestrator.processRequest(
            taskId = "test-override-parakeet",
            requestType = "text",
            prompt = "hi",
            filePath = null,
            source = "share",
            sourcePackage = null,
            backendOverride = BuiltInBackendIds.PARAKEET,
            queuePosition = 1,
            queueTotal = 1,
            context = context,
            cacheDir = File("/cache"),
            listener = listener,
            coroutineScope = this
        )

        // Should load sherpa-onnx (Parakeet), not Whisper from preferences
        coVerify {
            backendManager.setActiveBackend(
                backendId = BuiltInBackendIds.PARAKEET,
                context = context,
                config = match {
                    it is BackendConfig.SherpaOnnxConfig &&
                        it.modelDir == parakeetDir.absolutePath
                }
            )
        }
    }

    @Test
    fun `backend override with whisper loads whisper backend`() = runTest {
        val whisperDir = createTempModelDir("whisper")

        // Preference says llm, but override says whisper
        every { preferencesManager.transcriptionBackend } returns flowOf("llm")
        every { preferencesManager.sherpaModelPath(BuiltInBackendIds.WHISPER) } returns flowOf(whisperDir.absolutePath)
        every { preferencesManager.threadCount } returns flowOf(4)
        every { preferencesManager.transcriptionLanguage } returns flowOf("auto")
        every { preferencesManager.keepAliveTimeout } returns flowOf(5)
        every { backendManager.hasActiveBackend() } returns false
        coEvery { backendManager.setActiveBackend(any(), any(), any()) } returns Result.success(Unit)

        val context = createMockContext()
        orchestrator.processRequest(
            taskId = "test-override-whisper",
            requestType = "text",
            prompt = "hi",
            filePath = null,
            source = "share",
            sourcePackage = null,
            backendOverride = BuiltInBackendIds.WHISPER,
            queuePosition = 1,
            queueTotal = 1,
            context = context,
            cacheDir = File("/cache"),
            listener = listener,
            coroutineScope = this
        )

        coVerify {
            backendManager.setActiveBackend(
                backendId = BuiltInBackendIds.WHISPER,
                context = context,
                config = any()
            )
        }
    }

    @Test
    fun `no override falls back to preference`() = runTest {
        val modelDir = createTempModelDir("parakeet")

        every { preferencesManager.transcriptionBackend } returns flowOf(BuiltInBackendIds.PARAKEET)
        every { preferencesManager.sherpaModelPath(BuiltInBackendIds.PARAKEET) } returns flowOf(modelDir.absolutePath)
        every { preferencesManager.threadCount } returns flowOf(4)
        every { preferencesManager.keepAliveTimeout } returns flowOf(5)
        every { backendManager.hasActiveBackend() } returns false
        coEvery { backendManager.setActiveBackend(any(), any(), any()) } returns Result.success(Unit)

        val context = createMockContext()
        orchestrator.processRequest(
            taskId = "test-no-override",
            requestType = "text",
            prompt = "hi",
            filePath = null,
            source = "share",
            sourcePackage = null,
            backendOverride = null,
            queuePosition = 1,
            queueTotal = 1,
            context = context,
            cacheDir = File("/cache"),
            listener = listener,
            coroutineScope = this
        )

        coVerify {
            backendManager.setActiveBackend(
                backendId = BuiltInBackendIds.PARAKEET,
                context = context,
                config = any()
            )
        }
    }

    @Test
    fun `override forces reload when active backend differs`() = runTest {
        val parakeetDir = createTempModelDir("parakeet")

        // Active backend is whisper, preference is whisper, but override demands parakeet
        val whisperBackend = mockk<TranscriptionBackend>(relaxed = true) {
            every { id } returns BuiltInBackendIds.WHISPER
            every { isReady() } returns true
        }
        every { backendManager.hasActiveBackend() } returns true
        every { backendManager.getActiveBackend() } returns whisperBackend
        every { preferencesManager.transcriptionBackend } returns flowOf(BuiltInBackendIds.WHISPER)
        every { preferencesManager.sherpaModelPath(BuiltInBackendIds.PARAKEET) } returns flowOf(parakeetDir.absolutePath)
        every { preferencesManager.threadCount } returns flowOf(4)
        every { preferencesManager.keepAliveTimeout } returns flowOf(5)
        coEvery { backendManager.setActiveBackend(any(), any(), any()) } returns Result.success(Unit)

        val context = createMockContext()
        orchestrator.processRequest(
            taskId = "test-override-reload",
            requestType = "text",
            prompt = "hi",
            filePath = null,
            source = "share",
            sourcePackage = null,
            backendOverride = BuiltInBackendIds.PARAKEET,
            queuePosition = 1,
            queueTotal = 1,
            context = context,
            cacheDir = File("/cache"),
            listener = listener,
            coroutineScope = this
        )

        verify { backendManager.unloadActiveBackend() }
        coVerify {
            backendManager.setActiveBackend(
                backendId = BuiltInBackendIds.PARAKEET,
                context = context,
                config = any()
            )
        }
    }

    @Test
    fun `override with missing model path returns failure`() = runTest {
        // Override asks for parakeet but no model is configured
        every { preferencesManager.sherpaModelPath(BuiltInBackendIds.PARAKEET) } returns flowOf(null)
        every { backendManager.hasActiveBackend() } returns false

        val context = createMockContext()
        val result = orchestrator.processRequest(
            taskId = "test-override-no-model",
            requestType = "text",
            prompt = "hi",
            filePath = null,
            source = "share",
            sourcePackage = null,
            backendOverride = BuiltInBackendIds.PARAKEET,
            queuePosition = 1,
            queueTotal = 1,
            context = context,
            cacheDir = File("/cache"),
            listener = listener,
            coroutineScope = this
        )

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(
            "Expected NotInitialized but got: ${error?.javaClass?.simpleName}: ${error?.message}",
            error is TranscriptionException.NotInitialized
        )
    }

    @Test
    fun `backend override routes external id to the engine with the record config`() = runTest {
        val dir = createTempModelDir("external")
        val record = externalRecord(id = "abc123def456", dir = dir.absolutePath)
        fakeStore.add(record)
        every { preferencesManager.threadCount } returns flowOf(4)
        every { preferencesManager.inferenceProvider } returns flowOf("cpu")
        every { backendManager.hasActiveBackend() } returns false
        coEvery { backendManager.setActiveBackend(any(), any(), any()) } returns Result.success(Unit)

        val context = createMockContext()
        orchestrator.processRequest(
            taskId = "test-override-external",
            requestType = "text",
            prompt = "hi",
            filePath = null,
            source = "share",
            sourcePackage = null,
            backendOverride = record.backendId,
            queuePosition = 1,
            queueTotal = 1,
            context = context,
            cacheDir = File("/cache"),
            listener = listener,
            coroutineScope = this
        )

        coVerify {
            backendManager.setActiveBackend(
                backendId = record.backendId,
                context = any(),
                config = match { c ->
                    (c as BackendConfig.ExternalConfig).record == record && c.numThreads == 4
                },
            )
        }
    }

    @Test
    fun `backend override with unknown external id fails with ExternalModelUnavailable even when preference is valid`() = runTest {
        val dir = createTempModelDir("external-valid")
        val record = externalRecord(id = "valid999", dir = dir.absolutePath)
        fakeStore.add(record)

        // Preference points at a valid external record, but override asks for a nonexistent one.
        every { preferencesManager.transcriptionBackend } returns flowOf(record.backendId)
        every { preferencesManager.threadCount } returns flowOf(4)
        every { preferencesManager.inferenceProvider } returns flowOf("cpu")
        every { backendManager.hasActiveBackend() } returns false

        val context = createMockContext()
        val result = orchestrator.processRequest(
            taskId = "test-override-external-unknown",
            requestType = "text",
            prompt = "hi",
            filePath = null,
            source = "share",
            sourcePackage = null,
            backendOverride = "external:unknown",
            queuePosition = 1,
            queueTotal = 1,
            context = context,
            cacheDir = File("/cache"),
            listener = listener,
            coroutineScope = this
        )

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(
            "Expected ExternalModelUnavailable but got: ${error?.javaClass?.simpleName}: ${error?.message}",
            error is TranscriptionException.ExternalModelUnavailable
        )
        coVerify(exactly = 0) { backendManager.setActiveBackend(any(), any(), any()) }
    }

    @Test
    fun `override unloads backend after transcription`() = runTest {
        val parakeetDir = createTempModelDir("parakeet")

        // Override says sherpa-onnx
        every { preferencesManager.sherpaModelPath(BuiltInBackendIds.PARAKEET) } returns flowOf(parakeetDir.absolutePath)
        every { preferencesManager.threadCount } returns flowOf(4)
        every { preferencesManager.keepAliveTimeout } returns flowOf(5)
        every { backendManager.hasActiveBackend() } returns false
        coEvery { backendManager.setActiveBackend(any(), any(), any()) } returns Result.success(Unit)
        every { backendManager.unloadActiveBackend() } returns Unit

        val context = createMockContext()
        orchestrator.processRequest(
            taskId = "test-restore",
            requestType = "text",
            prompt = "hi",
            filePath = null,
            source = "share",
            sourcePackage = null,
            backendOverride = BuiltInBackendIds.PARAKEET,
            queuePosition = 1,
            queueTotal = 1,
            context = context,
            cacheDir = File("/cache"),
            listener = listener,
            coroutineScope = this
        )

        // Should load sherpa-onnx for the override, then unload it
        coVerify(ordering = io.mockk.Ordering.ORDERED) {
            backendManager.setActiveBackend(
                backendId = BuiltInBackendIds.PARAKEET,
                context = context,
                config = any()
            )
        }
        verify { backendManager.unloadActiveBackend() }
    }
}
