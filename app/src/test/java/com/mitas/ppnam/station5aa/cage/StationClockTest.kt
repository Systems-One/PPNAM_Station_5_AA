package com.mitas.ppnam.station5aa.cage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StationClockTest {
    @Test fun remainingIsExpiryMinusStationNow() =
        assertEquals(120_000L, StationClock.remainingMs("2026-11-03T10:02:00.000000Z", "2026-11-03T10:00:00.000000Z"))

    @Test fun pastExpiryClampsToZero() =
        assertEquals(0L, StationClock.remainingMs("2026-11-03T10:00:00Z", "2026-11-03T10:00:05Z"))

    @Test fun nullOrUnparseableGivesNull() {
        assertNull(StationClock.remainingMs(null, "2026-11-03T10:00:00Z"))
        assertNull(StationClock.remainingMs("2026-11-03T10:02:00Z", null))
        assertNull(StationClock.remainingMs("soon", "2026-11-03T10:00:00Z"))
        assertNull(StationClock.remainingMs("2026-11-03T10:02:00Z", ""))
    }
}
