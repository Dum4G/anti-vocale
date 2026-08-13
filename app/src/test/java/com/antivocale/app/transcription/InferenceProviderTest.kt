package com.antivocale.app.transcription

import org.junit.Assert.*
import org.junit.Test

class InferenceProviderTest {

    // ---- resolve() ----

    @Test
    fun `resolve CPU returns CPU`() {
        assertEquals("cpu", InferenceProvider.resolve("cpu"))
    }

    @Test
    fun `resolve NNAPI returns NNAPI`() {
        assertEquals("nnapi", InferenceProvider.resolve("nnapi"))
    }

    @Test
    fun `resolve auto falls back to CPU on JVM tests`() {
        // Build.VERSION.SDK_INT is 0 in plain JVM tests, so isNnapiAvailable() = false
        assertEquals("cpu", InferenceProvider.resolve("auto"))
    }

    @Test
    fun `resolve unknown value falls back to CPU on JVM tests`() {
        assertEquals("cpu", InferenceProvider.resolve("something_else"))
    }

    @Test
    fun `resolve empty string falls back to CPU on JVM tests`() {
        assertEquals("cpu", InferenceProvider.resolve(""))
    }

    // ---- isNnapiAvailable() ----

    @Test
    fun `isNnapiAvailable is false in JVM test environment`() {
        // Build.VERSION.SDK_INT = 0 in JVM tests, which is < O_MR1 (27)
        assertFalse(InferenceProvider.isNnapiAvailable())
    }

    // ---- options ----

    @Test
    fun `options contains auto nnapi cpu`() {
        assertEquals(listOf("auto", "nnapi", "cpu"), InferenceProvider.options)
    }

    // ---- constants ----

    @Test
    fun `constants are stable`() {
        assertEquals("auto", InferenceProvider.AUTO)
        assertEquals("nnapi", InferenceProvider.NNAPI)
        assertEquals("cpu", InferenceProvider.CPU)
    }

    // ---- isMediaTek detection (pure function) ----

    @Test
    fun `MediaTek detected via SOC_MANUFACTURER`() {
        assertTrue(InferenceProvider.isMediaTek("qcom", "pineapple", "MediaTek"))
    }

    @Test
    fun `MediaTek detected via hardware prefix mt`() {
        assertTrue(InferenceProvider.isMediaTek("mt6855", "mt6855", ""))
    }

    @Test
    fun `MediaTek detected via board prefix mt`() {
        assertTrue(InferenceProvider.isMediaTek("mti", "mt6893", ""))
    }

    @Test
    fun `MediaTek detected via hardware contains mediatek`() {
        assertTrue(InferenceProvider.isMediaTek("mediatek,io", "unknown", ""))
    }

    @Test
    fun `Qualcomm Snapdragon not detected as MediaTek`() {
        assertFalse(InferenceProvider.isMediaTek("qcom", "pineapple", "QTI"))
    }

    @Test
    fun `Google Tensor not detected as MediaTek`() {
        assertFalse(InferenceProvider.isMediaTek("zuma", "zuma", "Google"))
    }

    @Test
    fun `Samsung Exynos not detected as MediaTek`() {
        assertFalse(InferenceProvider.isMediaTek("s5e9945", "universal9945", "samsung"))
    }

    @Test
    fun `empty strings not detected as MediaTek`() {
        assertFalse(InferenceProvider.isMediaTek("", "", ""))
    }

    @Test
    fun `null-like strings (JVM test env) not detected as MediaTek`() {
        // In JVM tests Build.HARDWARE is null, which becomes "" via orEmpty()
        assertFalse(InferenceProvider.isMediaTek("", "", ""))
    }
}
