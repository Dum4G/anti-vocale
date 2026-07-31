package com.antivocale.app.service

import com.antivocale.app.transcription.TranscriptionException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the no-model-configured error detection logic used in
 * [com.antivocale.app.transcription.TranscriptionOrchestrator.isNoModelConfiguredError].
 *
 * Production logic (TranscriptionOrchestrator.kt):
 * ```kotlin
 * internal fun isNoModelConfiguredError(error: Throwable): Boolean {
 *     return error is TranscriptionException.NotInitialized
 * }
 * ```
 *
 * If the production logic changes, this test must be updated to match.
 */
class NoModelErrorDetectionTest {

    private fun isNoModelConfiguredError(error: Throwable): Boolean {
        return error is TranscriptionException.NotInitialized
    }

    // ---------------------------------------------------------------------------
    // Positive cases: errors that SHOULD be detected as "no model configured"
    // ---------------------------------------------------------------------------

    @Test
    fun `detects NotInitialized from orchestrator no-model paths`() {
        val error = TranscriptionException.NotInitialized()
        assertTrue(isNoModelConfiguredError(error))
    }

    @Test
    fun `detects NotInitialized from backend null-recognizer guard`() {
        val error = TranscriptionException.NotInitialized()
        assertTrue(isNoModelConfiguredError(error))
    }

    // ---------------------------------------------------------------------------
    // Negative cases: real errors that should NOT trigger the no-model notification
    // ---------------------------------------------------------------------------

    @Test
    fun `does not detect model load error as no-model`() {
        val error = TranscriptionException.ModelLoadError("directory not found")
        assertFalse(isNoModelConfiguredError(error))
    }

    @Test
    fun `does not detect native error as no-model`() {
        val error = TranscriptionException.NativeError("JNI crash")
        assertFalse(isNoModelConfiguredError(error))
    }

    @Test
    fun `does not detect generic backend load failure`() {
        val error = RuntimeException("Failed to load backend: some JNI error")
        assertFalse(isNoModelConfiguredError(error))
    }

    @Test
    fun `does not detect null audio data error`() {
        val error = NullPointerException("Null audio data received")
        assertFalse(isNoModelConfiguredError(error))
    }

    @Test
    fun `does not detect model directory not found for Parakeet`() {
        val error = TranscriptionException.ModelLoadError(
            "Parakeet model directory not found: /path/to/model"
        )
        assertFalse(isNoModelConfiguredError(error))
    }

    // ---------------------------------------------------------------------------
    // Edge cases
    // ---------------------------------------------------------------------------

    @Test
    fun `returns false for throwable with null message`() {
        val error = RuntimeException()
        assertFalse(isNoModelConfiguredError(error))
    }

    @Test
    fun `returns false for empty message`() {
        val error = RuntimeException("")
        assertFalse(isNoModelConfiguredError(error))
    }

    @Test
    fun `returns false for message containing No and model configured but not typed`() {
        val error = IllegalStateException("No Whisper model configured. Open the app to download a model.")
        assertFalse(isNoModelConfiguredError(error))
    }
}
