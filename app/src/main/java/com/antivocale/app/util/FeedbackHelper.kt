package com.antivocale.app.util

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import com.antivocale.app.R
import java.util.Locale

/**
 * Builds the feedback email (issue #34 / TASK-341): subject/body templates from
 * device diagnostics, an ACTION_SENDTO intent factory, and a copy-to-clipboard
 * fallback when no mail app can handle the intent. Pure template functions so
 * the email content is unit-testable without a device.
 */
object FeedbackHelper {

    const val FEEDBACK_ADDRESS = "paolo.antinori@risorseartificiali.com"
    const val SOURCE_CODE_URL = "https://github.com/RisorseArtificiali/anti-vocale"

    private const val SUBJECT_FEEDBACK = "[Anti-Vocale feedback]"
    private const val SUBJECT_TRANSLATION_PREFIX = "[Anti-Vocale translation]"

    /** Device/app facts embedded in the feedback body. */
    data class Diagnostics(
        val versionName: String,
        val versionCode: Long,
        val androidVersion: String,
        val device: String,
        val locale: String,
        val activeBackendId: String?,
        val activeModelName: String?
    )

    /** Localized labels for the body template (wired from string resources). */
    data class BodyLabels(
        val version: String,
        val android: String,
        val device: String,
        val locale: String,
        val model: String,
        val yourMessage: String,
        val note: String
    )

    fun feedbackSubject(): String = SUBJECT_FEEDBACK

    fun translationSubject(locale: String): String = "$SUBJECT_TRANSLATION_PREFIX $locale"

    fun buildFeedbackBody(d: Diagnostics, l: BodyLabels): String = buildString {
        appendLine("${l.version}: ${d.versionName} (${d.versionCode})")
        appendLine("${l.android}: ${d.androidVersion}")
        appendLine("${l.device}: ${d.device}")
        appendLine("${l.locale}: ${d.locale}")
        appendLine("${l.model}: ${d.activeBackendId ?: "-"} / ${d.activeModelName ?: "-"}")
        appendLine()
        appendLine(l.yourMessage)
        appendLine()
        appendLine(l.note)
    }

    /** Shorter variant for wrong-translation reports: locale only, no device dump. */
    fun buildTranslationBody(d: Diagnostics, l: BodyLabels): String = buildString {
        appendLine("${l.locale}: ${d.locale}")
        appendLine()
        appendLine(l.yourMessage)
        appendLine()
        appendLine(l.note)
    }

    fun createEmailIntent(subject: String, body: String): Intent =
        Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$FEEDBACK_ADDRESS")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }

    fun isCallable(context: Context, intent: Intent): Boolean =
        intent.resolveActivity(context.packageManager) != null

    /**
     * Launches the mail app, or falls back to copying the address to the
     * clipboard with a toast. Returns true when the mail intent was started.
     */
    fun sendOrCopy(context: Context, subject: String, body: String): Boolean {
        val intent = createEmailIntent(subject, body)
        if (!isCallable(context, intent)) {
            copyAddressToClipboard(context)
            return false
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            copyAddressToClipboard(context)
            false
        }
    }

    private fun copyAddressToClipboard(context: Context) {
        val clipboard =
            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("email", FEEDBACK_ADDRESS))
        Toast.makeText(
            context,
            context.getString(R.string.settings_feedback_address_copied, FEEDBACK_ADDRESS),
            Toast.LENGTH_LONG
        ).show()
    }

    /** Gathers the diagnostics from the running app; the model fields come from the ViewModel. */
    fun currentDiagnostics(
        context: Context,
        activeBackendId: String?,
        activeModelName: String?
    ): Diagnostics {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        return Diagnostics(
            versionName = packageInfo.versionName ?: "unknown",
            versionCode = packageInfo.longVersionCode,
            androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            device = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            locale = Locale.getDefault().toLanguageTag(),
            activeBackendId = activeBackendId,
            activeModelName = activeModelName
        )
    }
}
