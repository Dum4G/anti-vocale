package com.antivocale.app.transcription

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-340 Fix 1b: tail padding must not allocate a fresh `samples + silence`
 * copy per chunk. The buffer handed to the recognizer's second acceptWaveform
 * call is one shared, lazily-allocated, all-zero array.
 */
class TailSilenceBufferTest {

    @Test
    fun `same instance returned for repeated requests of the same size`() {
        val buffer = TailSilenceBuffer()
        val first = buffer.get(16000)
        val second = buffer.get(16000)
        assertSame(first, second)
    }

    @Test
    fun `buffer is all zeros and correctly sized`() {
        val silence = TailSilenceBuffer().get(16000)
        assertEquals(16000, silence.size)
        assertTrue(silence.all { it == 0.0f })
    }

    @Test
    fun `no allocation on first request of a different size then reuse`() {
        val buffer = TailSilenceBuffer()
        val oneSecond = buffer.get(16000)
        val twoSeconds = buffer.get(32000)
        assertEquals(32000, twoSeconds.size)
        assertSame(twoSeconds, buffer.get(32000))
        assertSame(oneSecond, buffer.get(16000))
    }

    @Test
    fun `zero-length pad returns empty array without caching`() {
        val buffer = TailSilenceBuffer()
        assertTrue(FloatArray(0).contentEquals(buffer.get(0)))
    }
}
