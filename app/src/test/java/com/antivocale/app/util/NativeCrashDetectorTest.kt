package com.antivocale.app.util

import android.content.Context
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import com.antivocale.app.util.NativeCrashDetector.CrashCheckResult

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class NativeCrashDetectorTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `check returns None when no exit history`() {
        // Fresh app process has no historical exit reasons.
        val result = NativeCrashDetector.checkForRecentCrash(context)
        assertTrue("expected None", result is CrashCheckResult.None)
    }

    @Test
    fun `LowMemory and NativeCrash are distinct result types`() {
        val lowMem = CrashCheckResult.LowMemory(timestamp = 1234L)
        val nativeCrash = CrashCheckResult.NativeCrash(timestamp = 5678L)
        assertTrue("LowMemory is its own type", lowMem is CrashCheckResult.LowMemory)
        assertTrue("NativeCrash is its own type", nativeCrash is CrashCheckResult.NativeCrash)
        assertTrue("the two variants are not the same type", lowMem::class != nativeCrash::class)
    }
}
