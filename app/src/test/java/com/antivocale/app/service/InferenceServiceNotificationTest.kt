package com.antivocale.app.service

import android.app.NotificationManager
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import com.antivocale.app.receiver.TaskerRequestReceiver

/**
 * Tests that InferenceService creates the correct notification channels in onCreate().
 * These tests target the CURRENT production code and establish a baseline for the
 * notification channel refactor.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class InferenceServiceNotificationTest {

    @Test
    fun `onCreate registers inference_channel with IMPORTANCE_LOW and no badge`() {
        val controller = Robolectric.buildService(InferenceService::class.java)
        val service = controller.create().get()

        val notificationManager = service.getSystemService(NotificationManager::class.java)
        val channel = notificationManager.getNotificationChannel("inference_channel")

        assertNotNull("inference_channel should be created", channel)
        assertEquals(
            "inference_channel should have IMPORTANCE_LOW",
            NotificationManager.IMPORTANCE_LOW,
            channel.importance
        )
        assertEquals(
            "inference_channel should not show badge",
            false,
            channel.canShowBadge()
        )
    }

    @Test
    fun `onCreate registers transcription_result_channel with IMPORTANCE_HIGH and badge`() {
        val controller = Robolectric.buildService(InferenceService::class.java)
        val service = controller.create().get()

        val notificationManager = service.getSystemService(NotificationManager::class.java)
        val channel = notificationManager.getNotificationChannel("transcription_result_channel")

        assertNotNull("transcription_result_channel should be created", channel)
        assertEquals(
            "transcription_result_channel should have IMPORTANCE_HIGH",
            NotificationManager.IMPORTANCE_HIGH,
            channel.importance
        )
        assertEquals(
            "transcription_result_channel should show badge",
            true,
            channel.canShowBadge()
        )
    }

    @Test
    fun `onCreate registers exactly 2 notification channels`() {
        val controller = Robolectric.buildService(InferenceService::class.java)
        val service = controller.create().get()

        val notificationManager = service.getSystemService(NotificationManager::class.java)
        val channels = notificationManager.notificationChannels

        assertEquals(
            "InferenceService should create exactly 2 notification channels",
            2,
            channels.size
        )
    }

    /**
     * Verifies the subtitle-track-index intent extra round-trips through the constant
     * used by both [InferenceService.onStartCommand] (read) and the share flow (write).
     * This locks the contract for Task 5/6 wiring without requiring a Hilt-injected
     * orchestrator (which a pure Robolectric service start would need).
     */
    @Test
    fun `EXTRA_SUBTITLE_TRACK_INDEX round-trips through an Intent`() {
        val intent = Intent().putExtra(TaskerRequestReceiver.EXTRA_SUBTITLE_TRACK_INDEX, 3)

        assertEquals(
            "subtitle_track_index extra should round-trip",
            3,
            intent.getIntExtra(TaskerRequestReceiver.EXTRA_SUBTITLE_TRACK_INDEX, -1)
        )
    }

    /**
     * Verifies PendingRequest defaults trackIndex to -1 (the "no subtitle track" sentinel)
     * so existing audio/text requests are unaffected by the new field.
     */
    @Test
    fun `PendingRequest defaults trackIndex to -1`() {
        val request = InferenceService.PendingRequest(
            taskId = "t1",
            requestType = "audio",
            prompt = "",
            filePath = "/tmp/x.mp4"
        )

        assertEquals(
            "PendingRequest.trackIndex should default to -1 when not provided",
            -1,
            request.trackIndex
        )
    }
}
