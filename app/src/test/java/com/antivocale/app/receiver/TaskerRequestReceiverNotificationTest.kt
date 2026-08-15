package com.antivocale.app.receiver

import android.app.NotificationManager
import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import io.mockk.every
import io.mockk.spyk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Tests that TaskerRequestReceiver creates the correct notification channel
 * in the fallback notification path. These tests target the CURRENT production
 * code and establish a baseline for the notification channel refactor.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class TaskerRequestReceiverNotificationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun `fallback notification path creates tasker_fallback_channel with IMPORTANCE_HIGH and badge`() {
        // Use spyk to intercept startForegroundService and force the fallback path
        val contextSpy = spyk(context)
        every { contextSpy.startForegroundService(any()) } throws
            ForegroundServiceStartNotAllowedException("test: blocked from background")

        val receiver = TaskerRequestReceiver()
        val intent = Intent(TaskerRequestReceiver.ACTION_PROCESS_REQUEST).apply {
            putExtra(TaskerRequestReceiver.EXTRA_REQUEST_TYPE, "text")
            putExtra(TaskerRequestReceiver.EXTRA_TASK_ID, "test_task")
            putExtra(TaskerRequestReceiver.EXTRA_PROMPT, "test prompt")
        }

        receiver.onReceive(contextSpy, intent)

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val channel = notificationManager.getNotificationChannel("tasker_fallback_channel")

        assertNotNull("tasker_fallback_channel should be created", channel)
        assertEquals(
            "tasker_fallback_channel should have IMPORTANCE_HIGH",
            NotificationManager.IMPORTANCE_HIGH,
            channel.importance
        )
        assertEquals(
            "tasker_fallback_channel should show badge",
            true,
            channel.canShowBadge()
        )
    }

    @Test
    fun `channel creation is idempotent - posting fallback twice does not crash`() {
        val contextSpy = spyk(context)
        every { contextSpy.startForegroundService(any()) } throws
            ForegroundServiceStartNotAllowedException("test: blocked from background")

        val receiver = TaskerRequestReceiver()
        val intent = Intent(TaskerRequestReceiver.ACTION_PROCESS_REQUEST).apply {
            putExtra(TaskerRequestReceiver.EXTRA_REQUEST_TYPE, "text")
            putExtra(TaskerRequestReceiver.EXTRA_TASK_ID, "test_task")
            putExtra(TaskerRequestReceiver.EXTRA_PROMPT, "test prompt")
        }

        // Call twice — should not crash
        receiver.onReceive(contextSpy, intent)
        receiver.onReceive(contextSpy, intent)

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val channel = notificationManager.getNotificationChannel("tasker_fallback_channel")
        assertNotNull("tasker_fallback_channel should still exist after double creation", channel)
    }
}
