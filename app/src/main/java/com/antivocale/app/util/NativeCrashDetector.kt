package com.antivocale.app.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.util.Log

/**
 * Detects whether the app's previous process died from a native crash (exit 255,
 * sherpa-onnx invalid model) or a low-memory kill (lmkd).
 *
 * On next launch, checks [ActivityManager.getHistoricalProcessExitReasons] for
 * [ApplicationExitInfo.REASON_CRASH_NATIVE] and [ApplicationExitInfo.REASON_LOW_MEMORY],
 * and reports which (if any) the UI should warn about, with a distinct message per reason.
 *
 * API 30+ only; earlier versions silently return [CrashCheckResult.None].
 *
 * Related: GitHub #21, TASK-314.
 */
object NativeCrashDetector {
    private const val TAG = "NativeCrashDetector"
    private const val PREFS_NAME = "native_crash_detection"
    private const val KEY_LAST_NATIVE_CRASH_TS = "last_native_crash_ts"
    private const val KEY_LAST_LOW_MEMORY_TS = "last_low_memory_ts"
    private const val RECENT_WINDOW_MS = 5 * 60 * 1000L // 5 minutes

    /**
     * Sealed result so the caller can pick a distinct message per cause. Each non-None
     * variant carries the exit timestamp for testing/diagnostics.
     */
    sealed class CrashCheckResult {
        data object None : CrashCheckResult()
        data class NativeCrash(val timestamp: Long) : CrashCheckResult()
        data class LowMemory(val timestamp: Long) : CrashCheckResult()
    }

    /**
     * Checks the app's most recent process death. Call exactly once per process launch,
     * from the first Activity's `onCreate`. Each detected reason is recorded in
     * SharedPreferences with a reason-scoped key, so a relaunch within [RECENT_WINDOW_MS]
     * does not nag again, AND a native crash then a low-memory kill (or vice versa) within
     * the window each surface once.
     */
    fun checkForRecentCrash(context: Context): CrashCheckResult {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            return CrashCheckResult.None // API 30+ only
        }

        return try {
            val am = context.getSystemService(ActivityManager::class.java)
            val exitInfos = am?.getHistoricalProcessExitReasons(context.packageName, 0, 5)
                ?: return CrashCheckResult.None

            val mostRecent = exitInfos.firstOrNull() ?: return CrashCheckResult.None
            val crashTime = mostRecent.timestamp

            // Map the OS exit reason to our sealed variant. Only these two are actionable;
            // every other reason (ANR, user-initiated, etc.) maps to None.
            val matched: CrashCheckResult = when (mostRecent.reason) {
                ApplicationExitInfo.REASON_CRASH_NATIVE -> CrashCheckResult.NativeCrash(crashTime)
                ApplicationExitInfo.REASON_LOW_MEMORY -> CrashCheckResult.LowMemory(crashTime)
                else -> return CrashCheckResult.None
            }

            val isRecent = (System.currentTimeMillis() - crashTime) < RECENT_WINDOW_MS
            Log.w(TAG, "Process exit detected: $matched (description=${mostRecent.description}, recent=$isRecent)")

            if (!isRecent) return CrashCheckResult.None

            // Reason-scoped dedup: each reason has its own last-shown timestamp key.
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val dedupKey = when (matched) {
                is CrashCheckResult.NativeCrash -> KEY_LAST_NATIVE_CRASH_TS
                is CrashCheckResult.LowMemory -> KEY_LAST_LOW_MEMORY_TS
                CrashCheckResult.None -> return CrashCheckResult.None
            }
            val lastShownTs = prefs.getLong(dedupKey, 0L)
            if (crashTime <= lastShownTs) return CrashCheckResult.None

            prefs.edit().putLong(dedupKey, crashTime).apply()
            matched
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check for recent crash", e)
            CrashCheckResult.None
        }
    }
}
