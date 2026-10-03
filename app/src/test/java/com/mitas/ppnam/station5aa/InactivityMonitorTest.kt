package com.mitas.ppnam.station5aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Inactivity auto-logout timer (copied from Station 1). Time and scheduling are injected so
 * the tests are deterministic: `scheduled` holds the pending runnable (at most one) and
 * `fireScheduled()` advances the clock to its due time and runs it.
 */
class InactivityMonitorTest {

    private var now = 1_000_000L
    private var scheduled: Pair<Long, Runnable>? = null
    private var expired = 0

    private val monitor = InactivityMonitor(
        now = { now },
        schedule = { delay, r -> scheduled = (now + delay) to r },
        cancel = { r -> if (scheduled?.second === r) scheduled = null },
        onExpired = { expired++ },
    )

    private fun fireScheduled() {
        val (due, r) = scheduled ?: error("nothing scheduled")
        scheduled = null
        now = maxOf(now, due)
        r.run()
    }

    @Test
    fun `expires once the timeout elapses without activity`() {
        monitor.start(60_000)
        assertTrue(monitor.isRunning)
        fireScheduled()
        assertEquals(1, expired)
        assertFalse(monitor.isRunning)
    }

    @Test
    fun `touch defers the deadline`() {
        monitor.start(60_000)
        now += 40_000
        monitor.touch()
        // The original deadline arrives: only 20s since the touch, so no expiry yet.
        fireScheduled()
        assertEquals(0, expired)
        assertTrue(monitor.isRunning)
        assertEquals(now + 40_000, scheduled!!.first)
        fireScheduled()
        assertEquals(1, expired)
    }

    @Test
    fun `stop cancels the pending deadline and never fires`() {
        monitor.start(60_000)
        monitor.stop()
        assertFalse(monitor.isRunning)
        assertNull(scheduled)
        assertEquals(0, expired)
    }

    @Test
    fun `checkNow after a long gap fires immediately`() {
        monitor.start(60_000)
        now += 3_600_000 // app was in the background for an hour
        monitor.checkNow()
        assertEquals(1, expired)
        assertNull(scheduled)
    }

    @Test
    fun `checkNow before the deadline does nothing`() {
        monitor.start(60_000)
        now += 10_000
        monitor.checkNow()
        assertEquals(0, expired)
        assertTrue(monitor.isRunning)
    }

    @Test
    fun `zero or negative timeout disables the monitor`() {
        monitor.start(0)
        assertFalse(monitor.isRunning)
        assertNull(scheduled)
        monitor.touch()
        monitor.checkNow()
        assertEquals(0, expired)
    }

    @Test
    fun `touch and checkNow are no-ops when stopped`() {
        monitor.touch()
        monitor.checkNow()
        assertEquals(0, expired)
        assertNull(scheduled)
    }

    @Test
    fun `restart replaces the previous timeout`() {
        monitor.start(60_000)
        monitor.start(5_000)
        assertEquals(now + 5_000, scheduled!!.first)
        fireScheduled()
        assertEquals(1, expired)
    }
}
