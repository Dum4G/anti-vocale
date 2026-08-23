package com.antivocale.app.transcription

import com.antivocale.app.data.ModelDownloader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-371 (GH #49, TASK-370 point c): the Gemma model-card label must say what
 * the runtime does. The llm backend chunks long audio at AUDIO_CHUNK_SECONDS and
 * concatenates, so the non-catalog Gemma variants resolve to ChunkedAnyLength
 * when fed the runtime chunk size. Before the fix the label passed
 * chunkDurationSeconds = 0 and showed a misleading hard 30s cap (issue #49).
 */
class ModelAudioLimitLlmCoherenceTest {

    @Test
    fun `gemma variants with the runtime chunk size are chunked-any-length`() {
        val gemmaVariants = ModelDownloader.ModelVariant.entries.toList()
        assertTrue(gemmaVariants.isNotEmpty())
        val limit = audioLimitForVariants(
            gemmaVariants,
            chunkDurationSeconds = LlmTranscriptionBackend.AUDIO_CHUNK_SECONDS,
        )
        assertTrue("Gemma label must be ChunkedAnyLength, was $limit", limit is AudioLimit.ChunkedAnyLength)
    }

    @Test
    fun `the llm backend exposes the chunk size the label derives from`() {
        val backend = LlmTranscriptionBackend(io.mockk.mockk(relaxed = true))
        assertEquals(LlmTranscriptionBackend.AUDIO_CHUNK_SECONDS, backend.maxChunkDurationSeconds)
        assertTrue(backend.maxChunkDurationSeconds > 0)
    }

    @Test
    fun `call without chunk size keeps the old 30s hard-cap answer`() {
        // The default-0 overload behavior is unchanged so no other caller shifts.
        val limit = audioLimitForVariants(ModelDownloader.ModelVariant.entries.toList())
        assertTrue(limit is AudioLimit.HardCap)
        assertEquals(30, (limit as AudioLimit.HardCap).seconds)
    }
}
