package com.mitas.ppnam.station5aa.cage

import org.junit.Assert.assertEquals
import org.junit.Test

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
        assertEquals("Whatever", StatusText.print("Whatever"))
    }
}
