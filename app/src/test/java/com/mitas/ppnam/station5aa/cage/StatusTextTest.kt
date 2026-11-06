package com.mitas.ppnam.station5aa.cage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

class StatusTextTest {
    @Test fun grv() {
        assertEquals("Not sent yet", StatusText.grv("Not queued"))
        assertEquals("Pending", StatusText.grv("Pending"))
        assertEquals("Sending to SAP", StatusText.grv("Processing"))
        assertEquals("Posted to SAP", StatusText.grv("Succeeded"))
        assertEquals("Failed — check the desktop", StatusText.grv("Failed"))
        assertEquals("Unconfirmed — check the desktop", StatusText.grv("OutcomeUnknown"))
        assertEquals("Needs review on the desktop", StatusText.grv("Reconcile"))
        assertEquals("Whatever", StatusText.grv("Whatever"))
    }

    @Test fun print() {
        assertEquals("Waiting for receipt", StatusText.print("WaitingForGrv"))
        assertEquals("Printed", StatusText.print("Succeeded"))
        assertEquals("Needs review on the desktop", StatusText.print("NeedsReview"))
        assertEquals("Failed — check the desktop", StatusText.print("Failed"))
        assertEquals("Pending", StatusText.print("Pending"))
        assertEquals("Not printed yet", StatusText.print("Not queued"))
        assertEquals("Printing", StatusText.print("Processing"))
        assertEquals("Unconfirmed — check the desktop", StatusText.print("OutcomeUnknown"))
        assertEquals("Needs review on the desktop", StatusText.print("Reconcile"))
        assertEquals("Whatever", StatusText.print("Whatever"))
    }

    @Test fun completedTimeIsLocalHourMinute() {
        assertEquals("12:05", StatusText.completedTime("2026-11-03T10:05:59.123456Z", ZoneId.of("Africa/Windhoek")))
        assertEquals("10:05", StatusText.completedTime("2026-11-03T10:05:00Z", ZoneId.of("UTC")))
        assertEquals("10:42", StatusText.completedTime("2026-11-03T10:42:00.123+02:00", ZoneId.of("Africa/Windhoek")))
        assertEquals("08:42", StatusText.completedTime("2026-11-03T10:42:00.123+02:00", ZoneId.of("UTC")))
        assertNull(StatusText.completedTime(null))
        assertNull(StatusText.completedTime(""))
        assertNull(StatusText.completedTime("not a time"))
    }

    // Skill-run critique 2026-11-06, P2-8: a receipt/print problem sat in plain white on the green
    // "completed" card, so it read as part of the success.
    @Test fun needsAttentionOnlyForProblemStates() {
        for (s in listOf("Failed", "OutcomeUnknown", "Reconcile", "NeedsReview")) {
            assertEquals(s, true, StatusText.needsAttention(s))
        }
        for (s in listOf("Not queued", "Pending", "Processing", "Succeeded", "WaitingForGrv", "Whatever")) {
            assertEquals(s, false, StatusText.needsAttention(s))
        }
    }
}
