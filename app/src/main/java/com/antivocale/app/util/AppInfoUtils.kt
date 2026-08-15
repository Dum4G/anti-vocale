package com.antivocale.app.util

import android.content.Context
import android.content.pm.PackageManager
import com.antivocale.app.R
import com.antivocale.app.data.PerAppPreferencesManager

/**
 * Utility for getting app information from package names.
 */
object AppInfoUtils {
    /**
     * Known share-source apps mapped to their logical names. Brand names are
     * proper nouns and intentionally not localized. This map guarantees a
     * friendly grouping name regardless of Android package visibility, which
     * on 11+ intermittently hides apps this one has not queried (the raw
     * "com.*" fallback the Logs grouping used to show for Google Files).
     */
    private val commonNames = mapOf(
        PerAppPreferencesManager.WHATSAPP to "WhatsApp",
        "com.whatsapp.w4b" to "WhatsApp Business",
        PerAppPreferencesManager.TELEGRAM to "Telegram",
        PerAppPreferencesManager.SIGNAL to "Signal",
        "com.google.android.apps.nbu.files" to "Files by Google",
        "com.google.android.apps.docs" to "Google Drive",
        "com.google.android.gm" to "Gmail",
        "com.Slack" to "Slack",
        "com.discord" to "Discord",
        "com.android.chrome" to "Chrome",
        "org.mozilla.firefox" to "Firefox",
    )

    /**
     * Logical name for a known package, or null when unknown (callers should
     * fall back to the PackageManager label, then to the raw package name).
     */
    fun knownAppName(packageName: String?): String? =
        packageName?.takeIf { it.isNotBlank() }?.let { commonNames[it] }

    /**
     * Get the display name for an app package.
     *
     * @param context Application context
     * @param packageName Package name (e.g., "com.whatsapp")
     * @return Display name (e.g., "WhatsApp") or package name if not found
     */
    fun getAppName(context: Context, packageName: String?): String {
        if (packageName == null) return ""

        return knownAppName(packageName) ?: try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString().takeIf { it.isNotBlank() } ?: packageName
        } catch (e: Exception) {
            packageName
        }
    }

    /**
     * Get the "Send to [App]" text for Share Back button.
     *
     * @param context Application context
     * @param packageName Package name
     * @return Localized string like "Send to WhatsApp"
     */
    fun getSendToText(context: Context, packageName: String?): String {
        if (packageName == null) return ""

        val appName = getAppName(context, packageName)
        return context.getString(R.string.send_to_app, appName)
    }
}
