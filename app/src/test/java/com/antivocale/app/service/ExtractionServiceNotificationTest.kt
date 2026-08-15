package com.antivocale.app.service

import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests that ExtractionService creates the correct notification channel in onCreate().
 * These tests target the CURRENT production code and establish a baseline for the
 * notification channel refactor.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class ExtractionServiceNotificationTest {

    @Test
    fun `onCreate registers extraction_channel with IMPORTANCE_LOW and no badge`() {
        val controller = Robolectric.buildService(ExtractionService::class.java)
        val service = controller.create().get()

        val notificationManager = service.getSystemService(NotificationManager::class.java)
        val channel = notificationManager.getNotificationChannel("extraction_channel")

        assertNotNull("extraction_channel should be created", channel)
        assertEquals(
            "extraction_channel should have IMPORTANCE_LOW",
            NotificationManager.IMPORTANCE_LOW,
            channel.importance
        )
        assertEquals(
            "extraction_channel should not show badge",
            false,
            channel.canShowBadge()
        )
    }

    @Test
    fun `onCreate registers exactly 1 notification channel`() {
        val controller = Robolectric.buildService(ExtractionService::class.java)
        val service = controller.create().get()

        val notificationManager = service.getSystemService(NotificationManager::class.java)
        val channels = notificationManager.notificationChannels

        assertEquals(
            "ExtractionService should create exactly 1 notification channel",
            1,
            channels.size
        )
    }
}
