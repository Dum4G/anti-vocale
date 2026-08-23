package com.antivocale.app.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * TASK-370 E1+E2: the audio path must NOT reuse the chat-tuned conversation
 * config. Greedy sampling (a transcript is deterministic content) and a
 * transcription system instruction (fresh-per-chunk sessions otherwise start
 * from the bare chat prefill, which produced refusals and language drift on
 * the 2026-08-23 240s device run). Text chat keeps the conversational config.
 */
class LlmAudioConversationConfigTest {

    @Test
    fun `audio config samples greedily (temp 0, topK 1)`() {
        val sampler = LlmManager.AUDIO_CONVERSATION_CONFIG.samplerConfig
        assertNotNull(sampler)
        assertEquals(0.0, sampler!!.temperature, 0.0)
        assertEquals(1, sampler.topK)
    }

    @Test
    fun `audio config carries a transcription system instruction`() {
        val system = LlmManager.AUDIO_CONVERSATION_CONFIG.systemInstruction
        assertNotNull(system)
    }

    @Test
    fun `text chat config stays conversational (unchanged behavior)`() {
        val sampler = LlmManager.DEFAULT_CONVERSATION_CONFIG.samplerConfig
        assertNotNull(sampler)
        assertEquals(0.8, sampler!!.temperature, 1e-9)
        assertEquals(40, sampler.topK)
    }
}
