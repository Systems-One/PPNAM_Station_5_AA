package com.mitas.ppnam.station5aa.cage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A transient refusal (processing_paused / confirmation_busy) keeps the request and resends it,
 * so the screen stays on "Sending…". Before the skill-run critique (2026-11-06, P2-7) nothing said
 * why: a paused station looked like a hung handheld.
 */
class CageWorkflowTransientNoticeTest : CageWorkflowFixture() {
    private val paused = Notice("Station 5 is paused — retrying…", Notice.Tone.WARNING)
    private val busy = Notice("Station 5 is busy — retrying…", Notice.Tone.WARNING)

    @Before fun setUp() = signIn()

    @Test fun pausedReplySaysWhySendingContinues() {
        toLoading()
        wf.onQr(validQr)
        val pending = outbox.load()
        wf.onResponse(rejected("processing_paused"))
        assertEquals(paused, wf.state.notice)
        assertTrue(wf.state.sending)
        assertEquals(pending, outbox.load())
    }

    @Test fun busyReplySaysWhySendingContinues() {
        toLoading()
        wf.onQr(validQr)
        wf.onResponse(rejected("confirmation_busy"))
        assertEquals(busy, wf.state.notice)
    }

    @Test fun pausedNoticeClearsWhenTheRequestIsFinallyAnswered() {
        toLoadingUnarmed()
        wf.onRfid("TAG-CAGE-007")
        wf.onResponse(rejected("processing_paused"))
        assertEquals(paused, wf.state.notice)
        wf.onResponse(selectArmed("CAGE-007", 2, "50.4"))
        assertNull(wf.state.notice)
    }

    @Test fun pausedNoticeClearsWhenTheRequestIsFinallyRefused() {
        toLoading()
        wf.onQr(validQr)
        wf.onResponse(rejected("processing_paused"))
        wf.onResponse(rejected("qc_required"))
        assertTrue(wf.state.notice?.text != paused.text)
        assertTrue(wf.state.refusal != null)
    }
}
