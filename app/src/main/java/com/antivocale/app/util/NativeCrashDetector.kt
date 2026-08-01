package com.antivocale.app.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.util.Log

/**
 * Detects whether the app's previous process died from a native crash (exit 255),
 * which happens when sherpa-onnx encounters an invalid model and calls exit().
 *
 * On next launch, checks [ActivityManager.getHistoricalProcessExitReasons] for
 * [ApplicationExitInfo.REASON_CRASH_NATIVE] and reports whether the UI should
 * warn the user that the model file may be corrupt.
 *
 * Only available on Android 11+ (API 30). Earlier versions silently no-op.
 *
 * Related: TASK-309 (pre-validation), GitHub #21.
 */
object NativeCrashDetector {
    private const val TAG = "NativeCrashDetector"
    private const val PREFS_NAME = "native_crash_detection"
    private const val KEY_LAST_CRASH_TS = "last_native_crash_ts"
    private const val RECENT_WINDOW_MS = 5 * 60 * 1000L // 5 minutes

    /**
     * Checks whether the app's previous process died from a native crash and,
     * if so, whether the UI should surface a "model may be corrupt" message.
     *
     * Call this exactly once per process launch, from the first Activity's
     * `onCreate`. Each detected crash is recorded in SharedPreferences so a
     * repeated relaunch within [RECENT_WINDOW_MS] does not nag the user again.
     *
     * Note: `getHistoricalProcessExitReasons` returns entries ordered
     * most-recent-first, so [List.firstOrNull] gives us the last death.
     *
     * @return true if the UI should show the native-crash warning
     */
    fun checkForRecentNativeCrash(context: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            return false // API 30+ only
        }

        return try {
            val am = context.getSystemService(ActivityManager::class.java)
            val exitInfos = am?.getHistoricalProcessExitReasons(context.packageName, 0, 5)
                ?: return false

            val mostRecent = exitInfos.firstOrNull() ?: return false

            if (mostRecent.reason != ApplicationExitInfo.REASON_CRASH_NATIVE) {
                return false
            }

            val crashTime = mostRecent.timestamp
            val isRecent = (System.currentTimeMillis() - crashTime) < RECENT_WINDOW_MS

            Log.w(TAG, "Native crash detected at $crashTime " +
                "(description=${mostRecent.description}, recent=$isRecent)")

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val lastShownTs = prefs.getLong(KEY_LAST_CRASH_TS, 0L)
            val shouldShow = isRecent && crashTime > lastShownTs

            if (shouldShow) {
                prefs.edit().putLong(KEY_LAST_CRASH_TS, crashTime).apply()
            }

            shouldShow
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check for native crash", e)
            false
        }
    }
}
