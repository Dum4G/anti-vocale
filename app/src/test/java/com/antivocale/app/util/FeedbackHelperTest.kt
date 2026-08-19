package com.antivocale.app.util

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FeedbackHelperTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val labels = FeedbackHelper.BodyLabels(
        version = "App version",
        android = "Android",
        device = "Device",
        locale = "Language",
        model = "Active model",
        yourMessage = "Your message:",
        note = "You can remove the technical details above if you prefer."
    )

    private val diagnostics = FeedbackHelper.Diagnostics(
        versionName = "1.10.0",
        versionCode = 27L,
        androidVersion = "16 (API 36)",
        device = "realme RMX3853",
        locale = "it-IT",
        activeBackendId = "parakeet_tdt",
        activeModelName = "Parakeet TDT 0.6B v2"
    )

    // --- Subjects ---

    @Test
    fun `feedback subject uses the agreed prefix`() {
        assertEquals("[Anti-Vocale feedback]", FeedbackHelper.feedbackSubject())
    }

    @Test
    fun `translation subject embeds the current locale`() {
        assertEquals("[Anti-Vocale translation] it-IT", FeedbackHelper.translationSubject("it-IT"))
    }

    // --- Body templates ---

    @Test
    fun `feedback body contains version device locale and active model`() {
        val body = FeedbackHelper.buildFeedbackBody(diagnostics, labels)
        assertTrue(body.contains("1.10.0"))
        assertTrue(body.contains("27"))
        assertTrue(body.contains("16 (API 36)"))
        assertTrue(body.contains("realme RMX3853"))
        assertTrue(body.contains("it-IT"))
        assertTrue(body.contains("parakeet_tdt"))
        assertTrue(body.contains("Parakeet TDT 0.6B v2"))
    }

    @Test
    fun `feedback body has the message section and deletable-diagnostics note`() {
        val body = FeedbackHelper.buildFeedbackBody(diagnostics, labels)
        assertTrue(body.contains("Your message:"))
        assertTrue(body.contains("You can remove the technical details above if you prefer."))
        // The message placeholder must come after the diagnostics block.
        assertTrue(body.indexOf("Your message:") > body.indexOf("1.10.0"))
    }

    @Test
    fun `translation body embeds locale and skips the diagnostics dump`() {
        val body = FeedbackHelper.buildTranslationBody(diagnostics, labels)
        assertTrue(body.contains("it-IT"))
        assertTrue(body.contains("Your message:"))
        assertFalse(body.contains("realme RMX3853"))
        assertFalse(body.contains("1.10.0"))
    }

    // --- Intent factory ---

    @Test
    fun `email intent uses ACTION_SENDTO with mailto uri and extras`() {
        val intent = FeedbackHelper.createEmailIntent("subj", "body")
        assertEquals(Intent.ACTION_SENDTO, intent.action)
        assertEquals("mailto:paolo.antinori@risorseartificiali.com", intent.data.toString())
        assertEquals("subj", intent.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals("body", intent.getStringExtra(Intent.EXTRA_TEXT))
    }

    // --- Fallback path ---

    @Test
    fun `mailto intent is not callable when no mail app is installed`() {
        val intent = FeedbackHelper.createEmailIntent("s", "b")
        // Robolectric resolves no mail activity by default.
        assertNull(intent.resolveActivity(context.packageManager))
        assertFalse(FeedbackHelper.isCallable(context, intent))
    }

    @Test
    fun `sendOrCopy falls back to clipboard when no mail app handles the intent`() {
        val handled = FeedbackHelper.sendOrCopy(context, "s", "b")
        assertFalse(handled)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(
            FeedbackHelper.FEEDBACK_ADDRESS,
            clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        )
    }
}
