package com.antivocale.app.audio

import org.junit.Assert.*
import org.junit.Test

/**
 * GH #50: the VAD segment-merge limit used to be hardcoded 28s (Whisper's
 * window). With model-dependent chunk durations it must derive from the model's
 * per-segment limit minus a small margin, so a 380s-chunk model merges speech
 * into large segments instead of Whisper-sized ones.
 */
class VadMergeLimitTest {

    @Test
    fun `whisper keeps the historical 28s merge window`() {
        assertEquals(28, AudioPreprocessor.vadMergeLimitSeconds(30))
    }

    @Test
    fun `parakeet-scale limits merge proportionally under the cap`() {
        assertEquals(378, AudioPreprocessor.vadMergeLimitSeconds(380))
    }

    @Test
    fun `null limit falls back to the whisper default`() {
        assertEquals(28, AudioPreprocessor.vadMergeLimitSeconds(null))
    }

    @Test
    fun `small limits stay positive`() {
        assertEquals(1, AudioPreprocessor.vadMergeLimitSeconds(2))
        assertEquals(1, AudioPreprocessor.vadMergeLimitSeconds(1))
    }
}
