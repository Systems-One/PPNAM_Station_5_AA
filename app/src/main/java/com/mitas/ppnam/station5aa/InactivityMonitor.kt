package com.mitas.ppnam.station5aa

/**
 * Inactivity auto-logout timer (copied from Station 1). Pure Kotlin: the caller supplies a
 * monotonic clock and a scheduler, so production uses SystemClock.elapsedRealtime plus a
 * main-thread Handler while tests drive time by hand.
 *
 * The deadline is wall-clock from the last activity, so time spent in the background
 * still counts; hosts call [checkNow] on resume to catch a deadline that passed while
 * no Handler was running. [onExpired] fires at most once per [start].
 */
class InactivityMonitor(
    private val now: () -> Long,
    private val schedule: (Long, Runnable) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val onExpired: () -> Unit,
) {
    private var timeoutMs = 0L
    private var lastActivity = 0L
    private var pending: Runnable? = null

    val isRunning: Boolean get() = timeoutMs > 0

    fun start(timeoutMs: Long) {
        stop()
        if (timeoutMs <= 0) return
        this.timeoutMs = timeoutMs
        lastActivity = now()
        scheduleCheck(timeoutMs)
    }

    fun touch() {
        if (!isRunning) return
        lastActivity = now()
    }

    fun checkNow() {
        if (!isRunning) return
        val remaining = timeoutMs - (now() - lastActivity)
        if (remaining <= 0) {
            stop()
            onExpired()
        } else {
            scheduleCheck(remaining)
        }
    }

    fun stop() {
        timeoutMs = 0
        pending?.let(cancel)
        pending = null
    }

    private fun scheduleCheck(delayMs: Long) {
        pending?.let(cancel)
        val r = Runnable {
            pending = null
            checkNow()
        }
        pending = r
        schedule(delayMs, r)
    }
}
