package com.antivocale.app.transcription

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Idle-unload timer for backends that hold native memory (sherpa-onnx sessions).
 *
 * Why this exists (TASK-344 / issue #42): the ORT CPU arena backing a loaded
 * sherpa model never shrinks while the session lives, and a loaded Parakeet
 * session retains ~2.3GB of native heap after transcription. OfflineRecognizer
 * .release() provably frees the arena, so unloading when idle returns the
 * process to baseline. The keep-alive timeout used to be a no-op for every
 * sherpa backend; this timer is that missing implementation, mirroring the
 * one LlmManager has always had for the Gemma backend.
 *
 * Concurrency: [beginWork]/[endWork] bracket a native call; the timer never
 * fires while work is in flight (the flag is re-checked after the delay too,
 * so a fire that races with new work aborts without unloading).
 */
class NativeKeepAlive(
    private val scope: CoroutineScope,
    private val tag: String,
    private val defaultTimeoutMinutes: Int,
    private val onIdleUnload: () -> Unit,
) {
    private val timeoutMinutes = AtomicInteger(defaultTimeoutMinutes)
    private val workInFlight = AtomicInteger(0)
    private val timerActive = AtomicBoolean(false)
    private var job: Job? = null

    /** Stores the timeout; a running timer restarts with the new value. */
    fun setTimeout(minutes: Int) {
        timeoutMinutes.set(if (minutes > 0) minutes else defaultTimeoutMinutes)
        if (timerActive.get()) restart()
    }

    /** Starts the idle timer (call once after the backend initializes). */
    fun start() {
        timerActive.set(true)
        restart()
    }

    /** Stops the timer and forgets it (call on [TranscriptionBackend.unload]). */
    fun stop() {
        timerActive.set(false)
        job?.cancel()
        job = null
    }

    /** Must wrap every native inference call: pauses the idle timer. */
    inline fun <R> withWork(block: () -> R): R {
        beginWork()
        try {
            return block()
        } finally {
            endWork()
        }
    }

    fun beginWork() {
        workInFlight.incrementAndGet()
        job?.cancel()
    }

    fun endWork() {
        workInFlight.decrementAndGet()
        if (timerActive.get()) restart()
    }

    private fun restart() {
        job?.cancel()
        job = scope.launch {
            val minutes = timeoutMinutes.get()
            delay(minutes * 60_000L)
            // Re-check both flags: new work may have arrived during the delay.
            if (timerActive.get() && workInFlight.get() == 0) {
                android.util.Log.i(tag, "Idle timeout (${minutes}m) reached, unloading native backend")
                onIdleUnload()
            }
        }
    }
}
