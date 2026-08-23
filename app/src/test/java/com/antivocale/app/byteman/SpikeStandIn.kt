package com.antivocale.app.byteman

/**
 * TASK-387 stand-in: same shape as the real windows (publish partial state,
 * windowPoint, complete). The Byteman rule freezes windowPoint() AT ENTRY when
 * raceMode is set, proving the freeze+volatile-flag+spin pattern works under
 * coroutines in this codebase before pointing the rules at real classes (TASK-388+).
 */
class SpikeStandIn {
    @Volatile var raceMode = false

    /** Set by the RULE before blocking: doubles as the test's health assertion. */
    @Volatile var writerFrozen = false

    @Volatile var state: String = "initial" // partial state published by windowPoint

    /** Publishes the PARTIAL state (the first half of the window). */
    fun publish() {
        state = "partial"
    }

    /**
     * The rule target: the second half of the window, between publish() and
     * complete(). Freezing AT ENTRY here means a reader spinning on
     * writerFrozen is guaranteed to observe the partial state.
     */
    fun windowPoint() {
        // Intentionally empty: it exists to be the injection point.
    }

    fun complete() {
        state = "complete"
    }
}
