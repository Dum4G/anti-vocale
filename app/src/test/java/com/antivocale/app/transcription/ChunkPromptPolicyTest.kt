package com.antivocale.app.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-370: in chunk mode the LLM backend must never receive the user's
 * generative prompt per chunk. Chunks are transcribed with a plain
 * instruction; the generative prompt runs exactly once, at the end, as a
 * text-only pass over the concatenated transcript (skipped when the prompt
 * is the built-in default, where the transcript already IS the deliverable).
 */
class ChunkPromptPolicyTest {

    private val llm = LlmTranscriptionBackend.BACKEND_ID
    private val custom = "Riassumi e riscrivi in forma formale questo vocale."

    @Test
    fun `llm chunks get the plain transcription prompt when the user prompt is generative`() {
        assertEquals(
            ChunkPromptPolicy.PLAIN_TRANSCRIPTION_PROMPT,
            ChunkPromptPolicy.perChunkPrompt(llm, custom))
    }

    @Test
    fun `llm chunks keep the default transcription prompt when nothing custom is set`() {
        assertEquals(
            ChunkPromptPolicy.DEFAULT_AUDIO_PROMPT,
            ChunkPromptPolicy.perChunkPrompt(llm, ChunkPromptPolicy.DEFAULT_AUDIO_PROMPT))
    }

    @Test
    fun `non-llm backends receive the prompt unchanged (whisper language prompting etc)`() {
        assertEquals(custom, ChunkPromptPolicy.perChunkPrompt("whisper", custom))
    }

    @Test
    fun `final generative pass runs only for llm with a custom prompt`() {
        assertTrue(ChunkPromptPolicy.shouldRunFinalGenerativePass(llm, custom))
        assertFalse(ChunkPromptPolicy.shouldRunFinalGenerativePass(llm, ChunkPromptPolicy.DEFAULT_AUDIO_PROMPT))
        assertFalse(ChunkPromptPolicy.shouldRunFinalGenerativePass(llm, ""))
        assertFalse(ChunkPromptPolicy.shouldRunFinalGenerativePass("whisper", custom))
    }

    @Test
    fun `final prompt embeds the transcript after the user instruction`() {
        assertEquals(
            "$custom\n\nTranscript:\nuno due tre",
            ChunkPromptPolicy.finalPrompt(custom, "uno due tre"))
    }
}
