package com.mitas.ppnam.station5aa.cage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CageWorkflowSendingScanTest : CageWorkflowFixture() {
    private val waitText = "Wait for Sending… to finish, then scan again."

    @Before fun setUp() = signIn()

    @Test fun qrWhileSendingBeepsAndWarns() {
        toLoading()
        wf.onQr(validQr) // now sending
        val pending = outbox.load()
        val effects = wf.onQr("510019399,O,B10")
        assertEquals(listOf<Effect>(Effect.ErrorFeedback), effects)
        assertEquals(Notice(waitText, Notice.Tone.WARNING), wf.state.notice)
        assertEquals(pending, outbox.load())
    }

    @Test fun rfidWhileSendingBeepsAndWarns() {
        toLoadingUnarmed()
        wf.onRfid("TAG-CAGE-007") // now sending
        val pending = outbox.load()
        val effects = wf.onRfid("TAG-OTHER")
        assertEquals(listOf<Effect>(Effect.ErrorFeedback), effects)
        assertEquals(Notice(waitText, Notice.Tone.WARNING), wf.state.notice)
        assertEquals(pending, outbox.load())
    }

    @Test fun scanWhileRefusalStillSilent() {
        toLoading()
        wf.onQr("garbage") // local refusal
        assertTrue(wf.state.refusal != null)
        assertTrue(wf.onQr(validQr).isEmpty())
        assertTrue(wf.onRfid("TAG-X").isEmpty())
        assertNull(outbox.load())
    }
}
