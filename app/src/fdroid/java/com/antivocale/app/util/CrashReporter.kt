package com.antivocale.app.util

import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName

/**
 * No-op CrashReporter for the fdroid (Firebase-free) build.
 *
 * Preserves the same API as the playStore implementation so callers
 * (BridgeApplication, services, workers) remain unchanged. Exceptions
 * are logged to logcat only; nothing is reported off-device.
 */
object CrashReporter {

    private const val TAG = "CrashReporter"

    /** TASK-396: set by BridgeApplication at startup; must be set before report() can mark OOM. */
    @Volatile var filesDir: java.io.File? = null
    private const val OOM_MARKER_PATH = "/data/data/com.antivocale.app/files/last_crash_oom"

    val handler = CoroutineExceptionHandler { context, throwable ->
        val name = context[CoroutineName]?.name ?: "unnamed"
        report(throwable, "Uncaught exception in coroutine [$name]")
    }

    fun report(throwable: Throwable, context: String) {
        Log.e(TAG, context, throwable)
        markOomIfOOM(throwable)
    }

    /** TASK-396 pt.2: persist an OOM marker readable at the next cold start. */
    private fun markOomIfOOM(throwable: Throwable) {
        if (throwable is OutOfMemoryError) {
            // No SharedPreferences on the fdroid flavor's minimal surface; the
            // marker is a plain file the Application can check cheaply.
            java.io.File(OOM_MARKER_PATH).writeText("1")
        }
    }

    /** True when the previous process died on an OutOfMemoryError. Clears the marker. */
    fun consumeLastCrashWasOOM(): Boolean {
        val marker = java.io.File(OOM_MARKER_PATH)
        return if (marker.exists()) {
            marker.delete()
            true
        } else false
    }
}
