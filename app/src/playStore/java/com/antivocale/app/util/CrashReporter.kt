package com.antivocale.app.util

import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName

/**
 * Central point for reporting exceptions to Firebase Crashlytics.
 *
 * Provides both a [CoroutineExceptionHandler] for coroutine scopes and a
 * standalone [report] method for thread-level uncaught exceptions.
 *
 * Usage with CoroutineScope:
 *   val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CrashReporter.handler)
 *
 * Usage from UncaughtExceptionHandler:
 *   CrashReporter.report(throwable, "Uncaught on ${thread.name}")
 */
object CrashReporter {

    private const val TAG = "CrashReporter"
    private const val KEY_CONTEXT = "crash_context"

    val handler = CoroutineExceptionHandler { context, throwable ->
        val name = context[CoroutineName]?.name ?: "unnamed"
        report(throwable, "Uncaught exception in coroutine [$name]")
    }

    fun report(throwable: Throwable, context: String) {
        Log.e(TAG, context, throwable)
        markOomIfOOM(throwable)
        try {
            FirebaseCrashlytics.getInstance().apply {
                setCustomKey(KEY_CONTEXT, context)
                recordException(throwable)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to report to Crashlytics", e)
        }
    }

    /** TASK-396 pt.2: persist an OOM marker readable at the next cold start. */
    private fun markOomIfOOM(throwable: Throwable) {
        if (throwable is OutOfMemoryError) {
            runCatching {
                java.io.File("/data/data/com.antivocale.app/files/last_crash_oom").writeText("1")
            }
        }
    }

    /** True when the previous process died on an OutOfMemoryError. Clears the marker. */
    fun consumeLastCrashWasOOM(): Boolean {
        val marker = java.io.File("/data/data/com.antivocale.app/files/last_crash_oom")
        return if (marker.exists()) {
            marker.delete()
            true
        } else false
    }
}
