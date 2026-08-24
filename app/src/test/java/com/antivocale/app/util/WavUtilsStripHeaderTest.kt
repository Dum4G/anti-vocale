package com.antivocale.app.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TASK-370 E4: LiteRT Content.AudioBytes takes raw bytes with no sample-rate
 * carrier; the Edge Gallery reference strips the WAV container and feeds raw
 * 16k mono PCM. stripWavHeader must find the data chunk (RIFF chunk walk, not
 * a blind +44) and pass through anything that is not a RIFF/WAVE.
 */
class WavUtilsStripHeaderTest {

    private fun wav(data: ByteArray, extraChunk: Boolean): ByteArray {
        val header = java.io.ByteArrayOutputStream()
        fun intLE(v: Int) { header.write(byteArrayOf((v and 255).toByte(), ((v shr 8) and 255).toByte(), ((v shr 16) and 255).toByte(), ((v shr 24) and 255).toByte())) }
        header.write("RIFF".toByteArray()); intLE(36 + data.size + if (extraChunk) 24 else 0)
        header.write("WAVE".toByteArray())
        if (extraChunk) {
            header.write("LIST".toByteArray()); intLE(20)
            header.write(ByteArray(20))
        }
        header.write("fmt ".toByteArray()); intLE(16); header.write(ByteArray(16))
        header.write("data".toByteArray()); intLE(data.size)
        header.write(data)
        return header.toByteArray()
    }

    @Test
    fun `strips a canonical 44-byte header`() {
        val pcm = ByteArray(100) { it.toByte() }
        assertArrayEquals(pcm, WavUtils.stripWavHeader(wav(pcm, extraChunk = false)))
    }

    @Test
    fun `walks past extra chunks to find the data chunk`() {
        val pcm = ByteArray(50) { (it * 3).toByte() }
        assertArrayEquals(pcm, WavUtils.stripWavHeader(wav(pcm, extraChunk = true)))
    }

    @Test
    fun `passes through non-RIFF bytes unchanged (already raw PCM)`() {
        val pcm = ByteArray(60) { it.toByte() }
        assertArrayEquals(pcm, WavUtils.stripWavHeader(pcm))
    }

    @Test
    fun `round trip with our own floatSamplesToWav`() {
        val samples = FloatArray(32) { 0.5f }
        val wavBytes = WavUtils.floatSamplesToWav(samples, 16000)
        val stripped = WavUtils.stripWavHeader(wavBytes)
        // 32 float samples -> 32 16-bit PCM samples -> 64 bytes
        assertEquals(64, stripped.size)
    }
}
