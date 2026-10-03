package com.mitas.ppnam.station5aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingSignedOutReasonTest {
    @Test
    fun takeReturnsReasonOnceThenNull() {
        PendingSignedOutReason.set("Signed out after 5 minutes of inactivity")
        assertEquals("Signed out after 5 minutes of inactivity", PendingSignedOutReason.take())
        assertNull(PendingSignedOutReason.take())
    }

    @Test
    fun blankReasonIsNotPending() {
        PendingSignedOutReason.set("   ")
        assertNull(PendingSignedOutReason.take())
    }

    @Test
    fun latestReasonWins() {
        PendingSignedOutReason.set("first")
        PendingSignedOutReason.set("second")
        assertEquals("second", PendingSignedOutReason.take())
    }
}
