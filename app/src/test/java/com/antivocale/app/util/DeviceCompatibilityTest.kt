package com.antivocale.app.util

import android.app.ActivityManager
import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-395: boundary tests for the two-tier RAM gate. The hasRamForModel
 * budget is max(size * 2.5, MIN_MODEL_BUDGET_MB=1200); the totalMem value is
 * injected via a mocked ActivityManager.
 */
class DeviceCompatibilityTest {

    private fun contextWithRam(mb: Long): Context {
        val am = mockk<ActivityManager>()
        val memInfo = ActivityManager.MemoryInfo().apply { totalMem = mb * 1024 * 1024 }
        every { am.getMemoryInfo(any()) } answers { arg<ActivityManager.MemoryInfo>(0).let { it.totalMem = memInfo.totalMem; Unit } }
        val ctx = mockk<Context>()
        every { ctx.getSystemService(Context.ACTIVITY_SERVICE) } returns am
        return ctx
    }

    @Test
    fun `smallest model passes on a 2GB-nominal device`() {
        // Parakeet int8 464MB * 2.5 = 1160 -> floor 1200; device 1.8GB
        assertTrue(DeviceCompatibility.hasRamForModel(contextWithRam(1_800), 464))
    }

    @Test
    fun `floor blocks the smallest model on a 1GB device`() {
        // 1.2GB budget > 0.9GB totalMem
        assertFalse(DeviceCompatibility.hasRamForModel(contextWithRam(900), 464))
    }

    @Test
    fun `gemma e2b passes on an 8GB phone`() {
        // 2600 * 2.5 = 6500 < 7400 (8GB-nominal totalMem)
        assertTrue(DeviceCompatibility.hasRamForModel(contextWithRam(7_400), 2_600))
    }

    @Test
    fun `gemma e2b warns on a 6GB phone`() {
        // 6500 > 5500
        assertFalse(DeviceCompatibility.hasRamForModel(contextWithRam(5_500), 2_600))
    }

    @Test
    fun `whisper turbo passes on a 3GB phone`() {
        // 988 * 2.5 = 2470 < 2800
        assertTrue(DeviceCompatibility.hasRamForModel(contextWithRam(2_800), 988))
    }
}
