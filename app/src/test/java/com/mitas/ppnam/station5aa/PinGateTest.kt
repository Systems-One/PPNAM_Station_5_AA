package com.mitas.ppnam.station5aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Supervisor PIN gate (UI audit group (c)): five wrong attempts lock the gate for 30 s, the
 * counter survives leaving the screen (it is constructed from persisted values), a blank
 * submit is not an attempt, and the lockout counts down from a caller-supplied clock.
 */
class PinGateTest {

    private val t0 = 1_700_000_000_000L

    private fun gate(attempts: Int = 0, lockedUntil: Long = 0L) =
        PinGate(correctPin = "079545", failedAttempts = attempts, lockedUntilMs = lockedUntil)

    @Test
    fun `correct pin unlocks and resets the counter`() {
        val g = gate(attempts = 3)
        assertEquals(PinGate.Outcome.Unlocked, g.submit("079545", t0))
        assertEquals(0, g.failedAttempts)
        assertEquals(0L, g.lockedUntilMs)
    }

    @Test
    fun `wrong pin counts down the attempts left`() {
        val g = gate()
        assertEquals(PinGate.Outcome.Wrong(4), g.submit("000000", t0))
        assertEquals(PinGate.Outcome.Wrong(3), g.submit("000000", t0))
        assertEquals(2, g.failedAttempts)
    }

    @Test
    fun `fifth wrong pin locks the gate for thirty seconds`() {
        val g = gate(attempts = 4)
        assertEquals(PinGate.Outcome.LockedOut(30_000L), g.submit("000000", t0))
        assertTrue(g.isLocked(t0 + 29_999))
        assertEquals(t0 + 30_000, g.lockedUntilMs)
        assertEquals(0, g.failedAttempts)
    }

    @Test
    fun `while locked even the correct pin is refused with the remaining time`() {
        val g = gate(lockedUntil = t0 + 30_000)
        assertEquals(PinGate.Outcome.LockedOut(12_000L), g.submit("079545", t0 + 18_000))
        assertEquals(12_000L, g.remainingMs(t0 + 18_000))
    }

    @Test
    fun `after the lockout elapses the correct pin unlocks`() {
        val g = gate(lockedUntil = t0 + 30_000)
        assertFalse(g.isLocked(t0 + 30_000))
        assertEquals(0L, g.remainingMs(t0 + 31_000))
        assertEquals(PinGate.Outcome.Unlocked, g.submit("079545", t0 + 30_000))
    }

    @Test
    fun `blank submit is not an attempt`() {
        val g = gate(attempts = 2)
        assertEquals(PinGate.Outcome.Blank, g.submit("", t0))
        assertEquals(PinGate.Outcome.Blank, g.submit("   ", t0))
        assertEquals(2, g.failedAttempts)
    }

    @Test
    fun `a persisted counter carries across reopening the gate`() {
        // Simulates Cancel/Back and reopen: a new gate built from stored values.
        val g = gate(attempts = 4)
        assertEquals(PinGate.Outcome.LockedOut(30_000L), g.submit("111111", t0))
    }
}
