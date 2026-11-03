package com.mitas.ppnam.station5aa.cage

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CageWorkflowRecoveryTest : CageWorkflowFixture() {
    private val warnRescan = Notice("Couldn't confirm the last scan. Rescan the cage tag.", Notice.Tone.WARNING)

    private fun seedAllocate(session: String = "s-old", loadId: String = "L1", bagId: String = "B7"): PendingRequest =
        requests.allocate(session, loadId, PackQr.parse("510019399,O,$bagId")!!).also { outbox.save(it) }

    private fun seedConfirm(session: String = "s-old", loadId: String = "L1"): PendingRequest =
        requests.confirm(session, loadId, "tok-old").also { outbox.save(it) }

    // ---- transient codes and retries ------------------------------------------------------

    @Test fun processingPausedKeepsPendingAndRetries() {
        signIn()
        toLoading()
        val original = publishes(wf.onQr(validQr)).single().request
        val before = wf.state
        assertTrue(wf.onResponse(rejected("processing_paused")).isEmpty())
        assertNull(wf.state.refusal)
        assertEquals(before, wf.state)
        assertSame(original, outbox.load())

        val retry = publishes(wf.onRetryTick("s-1")).single().request
        assertEquals(original, retry)
        assertEquals(original.body, retry.body)
        assertEquals(original.messageId, retry.messageId)
    }

    @Test fun confirmationBusyKeepsPendingConfirm() {
        signIn()
        toPreview()
        val original = publishes(wf.onConfirmComplete()).single().request
        assertTrue(wf.onResponse(rejected("confirmation_busy")).isEmpty())
        assertSame(original, outbox.load())
        assertEquals(original, publishes(wf.onRetryTick("s-1")).single().request)
    }

    @Test fun pendingFromOldSessionIsNotResent() {
        signIn()
        wf.onRfid("E200ABC")
        assertTrue(wf.onRetryTick("s-2").isEmpty())
    }

    @Test fun retryTickWithoutPendingDoesNothing() {
        signIn()
        assertTrue(wf.onRetryTick("s-1").isEmpty())
    }

    @Test fun retryTickWithoutSessionDoesNothing() {
        signIn()
        wf.onRfid("E200ABC")
        assertTrue(wf.onRetryTick(null).isEmpty())
        wf.stop()
        assertTrue(wf.onRetryTick(null).isEmpty())
        assertTrue(wf.onRetryTick("s-1").isEmpty()) // signed out: nothing is re-published
    }

    // ---- request_expired --------------------------------------------------------------------

    @Test fun requestExpiredRecentShowsClockMessage() {
        signIn()
        toLoading()
        wf.onQr(validQr)
        clockMs += 60_000
        val effects = wf.onResponse(rejected("request_expired"))
        assertTrue(effects.contains(Effect.ErrorFeedback))
        assertTrue(publishes(effects).isEmpty())
        val r = wf.state.refusal!!
        assertEquals("Check the handheld's clock", r.headline)
        assertEquals("The handheld's time is wrong. Ask a supervisor to correct it, then scan again.", r.detail)
        assertEquals("request_expired", r.code)
        assertEquals(AfterRefusal.STAY, r.after)
        assertNull(outbox.load())
        assertFalse(wf.state.sending)
    }

    @Test fun requestExpiredAtExactlyFiveMinutesIsStillRecent() {
        signIn()
        wf.onRfid("E200ABC")
        clockMs += 300_000
        wf.onResponse(rejected("request_expired"))
        assertEquals("Check the handheld's clock", wf.state.refusal?.headline)
    }

    @Test fun requestExpiredOldIsReconciled() {
        signIn()
        toLoading()
        wf.onQr(validQr)
        clockMs += 6 * 60_000
        val effects = wf.onResponse(rejected("request_expired"))
        val p = publishes(effects).single().request
        assertNull(wf.state.refusal)
        assertEquals(RequestIntent.RECOVER_ALLOCATION, p.intent)
        assertEquals("L-CAGE-007", p.loadId)
        assertEquals("B9", p.bagId)
        assertEquals("s-1", p.operatorSessionId)
        assertEquals(p, outbox.load())
    }

    @Test fun requestExpiredOldConfirmReadsCompletion() {
        signIn()
        toPreview()
        wf.onConfirmComplete()
        clockMs += 6 * 60_000
        val p = publishes(wf.onResponse(rejected("request_expired"))).single().request
        assertEquals(RequestIntent.RECOVER_COMPLETION, p.intent)
        assertEquals("L-CAGE-007", p.loadId)
    }

    @Test fun requestExpiredOldSelectReadsActive() {
        signIn()
        wf.onRfid("E200ABC")
        clockMs += 6 * 60_000
        val p = publishes(wf.onResponse(rejected("request_expired"))).single().request
        assertEquals(RequestIntent.READ_ACTIVE, p.intent)
        assertNull(wf.state.refusal)
    }

    @Test fun messageIdConflictShowsRefusalAndClears() {
        signIn()
        toLoading()
        wf.onQr(validQr)
        val effects = wf.onResponse(rejected("message_id_conflict"))
        assertTrue(effects.contains(Effect.ErrorFeedback))
        assertEquals("message_id_conflict", wf.state.refusal?.code)
        assertNull(outbox.load())
        assertTrue(wf.onRetryTick("s-1").isEmpty())
    }

    @Test fun messageIdConflictOnReadShowsRefusal() {
        signIn()
        wf.start("s-1") // READ_ACTIVE
        wf.onResponse(rejected("message_id_conflict"))
        assertEquals("message_id_conflict", wf.state.refusal?.code)
        assertNull(outbox.load())
    }

    // ---- recovery on start ------------------------------------------------------------------

    @Test fun recoverAllocationRecorded() {
        seedAllocate()
        val p = publishes(wf.start("s-new")).single().request
        assertEquals(RequestIntent.RECOVER_ALLOCATION, p.intent)
        assertEquals("s-new", p.operatorSessionId)
        assertEquals("s-new", JSONObject(p.body).getString("operatorSessionId"))
        assertEquals("L1", p.loadId)
        assertEquals("L1", JSONObject(p.body).getString("loadId"))
        assertEquals("B7", p.bagId)
        assertEquals(p, outbox.load())
        assertTrue(wf.state.sending)

        wf.onResponse(accepted("""{"accepted":true,"isActive":false,"load":${loadJson("CAGE-007", 3, loadId = "L1")}}""")
            .put("packs", packsArray(listOf("B1", "B7", "B2"))))
        assertEquals(Notice("B7 was recorded in CAGE-007", Notice.Tone.SUCCESS), wf.state.notice)
        assertEquals(CageScreen.Idle, wf.state.screen)
        assertNull(outbox.load())
        assertFalse(wf.state.sending)
    }

    @Test fun recoverAllocationNotRecorded() {
        seedAllocate()
        wf.start("s-new")
        wf.onResponse(accepted("""{"accepted":true,"isActive":false,"load":${loadJson("CAGE-007", 2, loadId = "L1")}}""")
            .put("packs", packsArray(listOf("B1", "B2"))))
        assertEquals(
            Notice("Last Pack B7 was not confirmed. Rescan cage CAGE-007, then rescan that Pack.", Notice.Tone.WARNING),
            wf.state.notice,
        )
        assertEquals(CageScreen.Idle, wf.state.screen)
        assertNull(outbox.load())
    }

    @Test fun recoverAllocationIsReissuedUnderNewSession() {
        outbox.save(requests.readLoad("s-old", "L1", RequestIntent.RECOVER_ALLOCATION, "B7"))
        val p = publishes(wf.start("s-new")).single().request
        assertEquals(RequestIntent.RECOVER_ALLOCATION, p.intent)
        assertEquals("s-new", p.operatorSessionId)
        assertEquals("L1", p.loadId)
        assertEquals("B7", p.bagId)
    }

    @Test fun recoverCompletionCompleted() {
        seedConfirm()
        val p = publishes(wf.start("s-new")).single().request
        assertEquals(RequestIntent.RECOVER_COMPLETION, p.intent)
        assertEquals("s-new", p.operatorSessionId)
        assertEquals("L1", p.loadId)
        assertFalse(JSONObject(p.body).has("confirmationToken"))
        wf.onResponse(accepted("""{"accepted":true,"isActive":false,"load":${loadJson("CAGE-007", 2, loadId = "L1", status = "Completed")}}"""))
        assertEquals("CAGE-007", (wf.state.screen as CageScreen.Completed).load.cageId)
        assertNull(outbox.load())
    }

    @Test fun recoverCompletionStillOpen() {
        seedConfirm()
        wf.start("s-new")
        wf.onResponse(accepted("""{"accepted":true,"isActive":false,"load":${loadJson("CAGE-007", 2, loadId = "L1")}}"""))
        assertEquals(CageScreen.Idle, wf.state.screen)
        assertEquals("Cage CAGE-007 is still open. Rescan the cage tag to review.", wf.state.notice?.text)
    }

    @Test fun pendingRecoverCompletionIsReissued() {
        outbox.save(requests.readLoad("s-old", "L1", RequestIntent.RECOVER_COMPLETION))
        val p = publishes(wf.start("s-new")).single().request
        assertEquals(RequestIntent.RECOVER_COMPLETION, p.intent)
        assertEquals("s-new", p.operatorSessionId)
    }

    @Test fun oldSelectIsDroppedSilently() {
        outbox.save(requests.select("s-old", "E200ABC"))
        val p = publishes(wf.start("s-new")).single().request
        assertEquals(RequestIntent.READ_ACTIVE, p.intent)
        assertEquals("s-new", p.operatorSessionId)
        assertNull(wf.state.notice)
        assertNull(wf.state.refusal)
    }

    @Test fun oldReadLoadIsDroppedSilently() {
        outbox.save(requests.readLoad("s-old", "L1", RequestIntent.READ_LOAD))
        assertEquals(RequestIntent.READ_ACTIVE, publishes(wf.start("s-new")).single().request.intent)
    }

    @Test fun sameSessionPendingIsRepublishedUnchanged() {
        val seeded = seedAllocate(session = "s-1")
        assertSame(seeded, publishes(wf.start("s-1")).single().request)
        assertSame(seeded, outbox.load())
    }

    @Test fun recoveryReadRejectedGoesIdleWithNotice() {
        seedAllocate()
        wf.start("s-new")
        assertTrue(wf.onResponse(rejected("something_odd")).isEmpty())
        assertEquals(CageScreen.Idle, wf.state.screen)
        assertNull(wf.state.refusal)
        assertEquals(warnRescan, wf.state.notice)
        assertNull(outbox.load())
    }

    @Test fun completionRecoveryRejectedGoesIdleWithNotice() {
        seedConfirm()
        wf.start("s-new")
        wf.onResponse(rejected("something_odd"))
        assertEquals(warnRescan, wf.state.notice)
        assertEquals(CageScreen.Idle, wf.state.screen)
    }
}
