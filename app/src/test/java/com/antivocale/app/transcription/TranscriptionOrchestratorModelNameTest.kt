package com.antivocale.app.transcription

import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * GH #45: the log entry must record which model produced the transcription,
 * written as soon as the backend is loaded (before the result lands).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptionOrchestratorModelNameTest : TranscriptionOrchestratorTestBase() {

    @Test
    fun `processRequest records the backend display name on the log row`() = runTest {
        val backend = mockk<TranscriptionBackend>(relaxed = true) {
            every { id } returns "llm"
            every { isReady() } returns true
            every { supportsText } returns true
            every { displayName } returns "Gemma 4 E2B"
        }
        coEvery { backend.generateText(any()) } returns Result.success("OK")
        every { backendManager.hasActiveBackend() } returns true
        every { backendManager.getActiveBackend() } returns backend
        every { preferencesManager.transcriptionBackend } returns flowOf("llm")
        // modelPathForBackend reads this before the display-name derivation
        every { preferencesManager.modelPath } returns flowOf("/models/gemma")
        // The registry resolves the LLM backend name through a string resource
        val context = mockk<android.content.Context>(relaxed = true)
        every { context.getString(any()) } returns "Gemma 4 E2B"

        orchestrator.processRequest(
            taskId = "task-model",
            requestType = "text",
            prompt = "test",
            filePath = null,
            source = null,
            sourcePackage = null,
            queuePosition = 1,
            queueTotal = 1,
            context = context,
            cacheDir = java.io.File("/cache"),
            listener = listener,
            coroutineScope = this,
        )

        coVerify { logDao.setModelName("task-model", "Gemma 4 E2B") }
    }
}
