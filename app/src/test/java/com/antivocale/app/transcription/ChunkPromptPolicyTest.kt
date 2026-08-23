package com.antivocale.app.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `llm chunks get the plain prompt and the custom prompt runs as final pass`() {
        val plan = ChunkPromptPolicy.plan(llm, custom)
        assertEquals(ChunkPromptPolicy.PLAIN_TRANSCRIPTION_PROMPT, plan.perChunk)
        assertEquals(custom, plan.finalPass)
    }

    @Test
    fun `llm chunks keep the default transcription prompt when nothing custom is set`() {
        val plan = ChunkPromptPolicy.plan(llm, ChunkPromptPolicy.DEFAULT_AUDIO_PROMPT)
        assertEquals(ChunkPromptPolicy.DEFAULT_AUDIO_PROMPT, plan.perChunk)
        assertNull(plan.finalPass)
    }

    @Test
    fun `non-llm backends receive the prompt unchanged (whisper language prompting etc)`() {
        val plan = ChunkPromptPolicy.plan("whisper", custom)
        assertEquals(custom, plan.perChunk)
        assertNull(plan.finalPass)
    }

    @Test
    fun `blank prompt gets no final pass`() {
        assertNull(ChunkPromptPolicy.plan(llm, "").finalPass)
    }

    @Test
    fun `final prompt embeds the transcript after the user instruction`() {
        assertEquals(
            "$custom\n\nTranscript:\nuno due tre",
            ChunkPromptPolicy.finalPrompt(custom, "uno due tre"))
    }
}
