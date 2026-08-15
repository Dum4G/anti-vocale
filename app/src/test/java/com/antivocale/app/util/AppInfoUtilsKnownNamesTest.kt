package com.antivocale.app.util

import android.content.Context
import android.content.pm.PackageManager
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * TASK-320: the known-package name map must cover the major share-source
 * apps so the Logs grouping never depends on PackageManager visibility
 * (Android 11+ package visibility intermittently fails for packages the
 * app has not queried, falling back to the raw "com.*" name).
 */
class AppInfoUtilsKnownNamesTest {

    @Test
    fun `known chat and file apps map to their logical names`() {
        assertEquals("WhatsApp", AppInfoUtils.knownAppName("com.whatsapp"))
        assertEquals("WhatsApp Business", AppInfoUtils.knownAppName("com.whatsapp.w4b"))
        assertEquals("Telegram", AppInfoUtils.knownAppName("org.telegram.messenger"))
        assertEquals("Signal", AppInfoUtils.knownAppName("org.thoughtcrime.securesms"))
        assertEquals("Files by Google", AppInfoUtils.knownAppName("com.google.android.apps.nbu.files"))
    }

    @Test
    fun `unknown packages return null so the PackageManager fallback applies`() {
        assertNull(AppInfoUtils.knownAppName("com.example.unknown"))
    }

    @Test
    fun `null and blank package names return null`() {
        assertNull(AppInfoUtils.knownAppName(null))
        assertNull(AppInfoUtils.knownAppName(""))
    }

    @Test
    fun `getAppName prefers known name over PackageManager`() {
        val context = mockk<Context>(relaxed = true)
        val pm = mockk<PackageManager>(relaxed = true)
        every { context.packageManager } returns pm
        assertEquals("Files by Google", AppInfoUtils.getAppName(context, "com.google.android.apps.nbu.files"))
        verify { pm wasNot Called }
    }

    @Test
    fun `getAppName falls back to package when label is blank or resolution fails`() {
        val context = mockk<Context>(relaxed = true)
        val pm = mockk<PackageManager>(relaxed = true)
        every { context.packageManager } returns pm

        // Blank label: must degrade to the raw package, never to empty text.
        every { pm.getApplicationLabel(any()) } returns ""
        assertEquals("com.example.unknown", AppInfoUtils.getAppName(context, "com.example.unknown"))

        // Resolution failure (NameNotFoundException on old devices / hidden packages).
        every { pm.getApplicationInfo(any<String>(), any<Int>()) } throws PackageManager.NameNotFoundException()
        assertEquals("com.example.unknown", AppInfoUtils.getAppName(context, "com.example.unknown"))
    }
}
