package com.mitas.ppnam.station5aa

import android.content.Context

/**
 * Supervisor PIN gate for Settings: five wrong attempts lock the gate for 30 s. Pure
 * Kotlin — the caller supplies the clock — so it is unit-testable and so its state can be
 * persisted (UI audit group (c): leaving the screen used to reset the counter).
 */
class PinGate(
    private val correctPin: String = SUPERVISOR_PIN,
    private val maxAttempts: Int = MAX_ATTEMPTS,
    private val lockoutMs: Long = LOCKOUT_MS,
    var failedAttempts: Int = 0,
    var lockedUntilMs: Long = 0L,
) {
    sealed class Outcome {
        /** Nothing typed — not an attempt. */
        object Blank : Outcome()
        object Unlocked : Outcome()
        data class Wrong(val attemptsLeft: Int) : Outcome()
        data class LockedOut(val remainingMs: Long) : Outcome()
    }

    fun isLocked(nowMs: Long): Boolean = nowMs < lockedUntilMs

    fun remainingMs(nowMs: Long): Long = (lockedUntilMs - nowMs).coerceAtLeast(0L)

    fun submit(pin: String, nowMs: Long): Outcome {
        if (isLocked(nowMs)) return Outcome.LockedOut(remainingMs(nowMs))
        if (pin.isBlank()) return Outcome.Blank
        if (pin == correctPin) {
            failedAttempts = 0
            lockedUntilMs = 0L
            return Outcome.Unlocked
        }
        failedAttempts++
        if (failedAttempts >= maxAttempts) {
            failedAttempts = 0
            lockedUntilMs = nowMs + lockoutMs
            return Outcome.LockedOut(lockoutMs)
        }
        return Outcome.Wrong(maxAttempts - failedAttempts)
    }

    companion object {
        // Ported from Station 2's SettingsViewModel so every app's supervisor lock behaves identically.
        const val SUPERVISOR_PIN = "079545"
        const val MAX_ATTEMPTS = 5
        const val LOCKOUT_MS = 30_000L
    }
}

/** Persists the gate's counter and lockout deadline so Back/reopen or a restart cannot bypass it. */
class PinGateStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("pin_gate", Context.MODE_PRIVATE)

    fun load(): PinGate = PinGate(
        failedAttempts = prefs.getInt(KEY_ATTEMPTS, 0),
        lockedUntilMs = prefs.getLong(KEY_LOCKED_UNTIL, 0L),
    )

    fun save(gate: PinGate) {
        prefs.edit()
            .putInt(KEY_ATTEMPTS, gate.failedAttempts)
            .putLong(KEY_LOCKED_UNTIL, gate.lockedUntilMs)
            .apply()
    }

    private companion object {
        const val KEY_ATTEMPTS = "failed_attempts"
        const val KEY_LOCKED_UNTIL = "locked_until_ms"
    }
}
