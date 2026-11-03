package com.mitas.ppnam.station5aa.cage

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CageWorkflowCompletionTest : CageWorkflowFixture() {
    @Before fun setUp() = signIn()

    private val preview get() = wf.state.screen as CageScreen.Preview
    private val loading get() = wf.state.screen as CageScreen.Loading
    private val completed get() = wf.state.screen as CageScreen.Completed

    private fun previewAnswer(load: String) = accepted("""{"accepted":true,"nextAction":"confirm_completion","confirmationToken":"tok-1",
        "confirmationExpiresAtUtc":"2026-11-03T10:02:00.000000Z","serverSentAtUtc":"2026-11-03T10:00:00.000000Z",
        "load":$load}""")

    @Test fun completeCageSendsPreview() {
        toLoading()
        val p = publishes(wf.onCompleteCage()).single().request
        assertEquals(RequestIntent.PREVIEW, p.intent)
        assertEquals("cage_complete_requested", p.requestType)
        assertEquals("cage_completion_result", p.responseType)
        assertEquals("L-CAGE-007", p.loadId)
        val body = JSONObject(p.body)
        assertEquals("L-CAGE-007", body.getString("loadId"))
        assertFalse(body.has("confirmationToken"))
        assertEquals(p, outbox.load())
        assertTrue(wf.state.sending)
        assertNull(loading.armExpiresAtMs) // the station discards an unused arm on a preview request
    }

    @Test fun completeCageDoesNotNeedArm() {
        toLoadingUnarmed()
        assertEquals(RequestIntent.PREVIEW, publishes(wf.onCompleteCage()).single().request.intent)
    }

    @Test fun completeCageNeedsPacks() {
        wf.onRfid("TAG-CAGE-007")
        wf.onResponse(selectArmed("CAGE-007", 0, "0"))
        assertTrue(wf.onCompleteCage().isEmpty())
        assertNull(outbox.load())
    }

    @Test fun completeCageOnlyFromLoadingAndNotWhileBusy() {
        assertTrue(wf.onCompleteCage().isEmpty()) // Idle
        toLoading()
        wf.onQr(validQr) // sending
        assertTrue(wf.onCompleteCage().isEmpty())
        wf.onResponse(rejected("pack_allocated")) // refusal shown
        assertTrue(wf.onCompleteCage().isEmpty())
        assertNull(outbox.load())
    }

    @Test fun previewAcceptedShowsSheet() {
        toLoading()
        wf.onCompleteCage()
        val effects = wf.onResponse(previewAnswer(loadJson("CAGE-007", 2, "50.4"))
            .put("packs", packsArray(listOf("B1", "B2"))))
        assertTrue(publishes(effects).isEmpty())
        assertEquals("CAGE-007", preview.load.cageId)
        assertEquals(listOf("B1", "B2"), preview.packs.map { it.bagId })
        assertEquals("tok-1", preview.token)
        assertEquals(clockMs + 120_000, preview.expiresAtMs)
        assertNull(outbox.load())
        assertFalse(wf.state.sending)
    }

    @Test fun previewCountdownUsesStationClock() {
        clockMs += 180_000 // handheld clock three minutes fast
        toLoading()
        wf.onCompleteCage()
        wf.onResponse(previewAnswer(loadJson("CAGE-007", 2, "50.4")))
        assertEquals(clockMs + 120_000, preview.expiresAtMs)
    }

    @Test fun previewRefusedUsesCompletionCatalog() {
        toLoading()
        wf.onCompleteCage()
        val effects = wf.onResponse(rejected("cage_not_active"))
        assertTrue(effects.contains(Effect.ErrorFeedback))
        assertEquals("cage_not_active", wf.state.refusal?.code)
        assertEquals(AfterRefusal.TO_IDLE, wf.state.refusal?.after)
        assertNull(outbox.load())
        wf.onDismissRefusal()
        assertEquals(CageScreen.Idle, wf.state.screen)
    }

    @Test fun previewRefusedUnknownStaysOnLoading() {
        toLoading()
        wf.onCompleteCage()
        wf.onResponse(rejected("something_new", "Station explains."))
        assertEquals("Station explains.", wf.state.refusal?.detail)
        wf.onDismissRefusal()
        assertEquals("CAGE-007", loading.load.cageId)
        assertNull(loading.armExpiresAtMs)
    }

    @Test fun previewRefusedCompletionBlockedAsksForPacks() {
        toLoading()
        wf.onCompleteCage()
        wf.onResponse(rejected("completion_blocked", "Select an open cage containing at least one Pack."))
        assertEquals("Cannot complete", wf.state.refusal?.headline)
        assertEquals("Add Packs before completing.", wf.state.refusal?.detail)
        wf.onDismissRefusal()
        assertEquals("CAGE-007", loading.load.cageId)
    }

    @Test fun rescanActiveCageNeverPreviews() {
        toLoading()
        wf.onRfid("TAG-CAGE-007")
        // Even a stray completion-shaped answer to a selection is not a preview.
        wf.onResponse(selectArmed("CAGE-007", 2, "50.4").put("confirmationToken", "tok-x"))
        assertTrue(wf.state.screen is CageScreen.Loading)
    }

    @Test fun keepLoadingSendsNothing() {
        toPreview()
        val effects = wf.onKeepLoading()
        assertTrue(effects.isEmpty())
        assertNull(outbox.load())
        assertEquals("CAGE-007", loading.load.cageId)
        assertEquals(listOf("B1", "B2"), loading.packs.map { it.bagId })
        assertNull(loading.armExpiresAtMs)
        assertTrue(publishes(wf.onQr(validQr)).isEmpty()) // a fresh cage tag is needed before the next Pack
    }

    @Test fun confirmCompletes() {
        toPreview()
        val p = publishes(wf.onConfirmComplete()).single().request
        assertEquals(RequestIntent.CONFIRM, p.intent)
        assertEquals("cage_complete_confirmed", p.requestType)
        val body = JSONObject(p.body)
        assertEquals("L-CAGE-007", body.getString("loadId"))
        assertEquals("tok-1", body.getString("confirmationToken"))
        assertEquals(p, outbox.load())
        assertTrue(wf.state.sending)

        val effects = wf.onResponse(accepted("""{"accepted":true,"nextAction":"select_cage",
            "load":${loadJson("CAGE-007", 2, "50.4", status = "Completed", grvState = "Pending")}}"""))
        assertTrue(effects.contains(Effect.SuccessFeedback))
        assertEquals("CAGE-007", completed.load.cageId)
        assertEquals("Pending", completed.load.grvState)
        assertNull(outbox.load())
        assertFalse(wf.state.sending)
    }

    @Test fun alreadyCompletedCountsAsCompleted() {
        toPreview()
        wf.onConfirmComplete()
        val effects = wf.onResponse(accepted("""{"accepted":true,"alreadyCompleted":true,"nextAction":"select_cage",
            "load":${loadJson("CAGE-007", 2, "50.4", status = "Completed")}}"""))
        assertTrue(effects.contains(Effect.SuccessFeedback))
        assertEquals("CAGE-007", completed.load.cageId)
    }

    @Test fun confirmOnlyFromPreview() {
        toLoading()
        assertTrue(wf.onConfirmComplete().isEmpty())
        assertNull(outbox.load())
    }

    @Test fun contentsChangedRefreshesLoad() {
        toPreview()
        wf.onConfirmComplete()
        val effects = wf.onResponse(rejected("cage_contents_changed"))
        assertTrue(effects.contains(Effect.ErrorFeedback))
        assertEquals("cage_contents_changed", wf.state.refusal?.code)
        assertEquals(AfterRefusal.REFRESH_LOAD, wf.state.refusal?.after)
        assertNull(outbox.load())

        val p = publishes(wf.onDismissRefusal()).single().request
        assertNull(wf.state.refusal)
        assertEquals(RequestIntent.READ_LOAD, p.intent)
        assertEquals("L-CAGE-007", p.loadId)
        assertEquals("L-CAGE-007", JSONObject(p.body).getString("loadId"))
        assertEquals(p, outbox.load())

        val bags = (1..15).map { "B$it" }
        wf.onResponse(accepted("""{"accepted":true,"isActive":true,"load":${loadJson("CAGE-007", 15, "375")}}""")
            .put("packs", packsArray(bags)))
        assertEquals(15, loading.load.packCount)
        assertEquals(15, loading.packs.size)
        assertNull(loading.lastPack)
        assertNull(loading.armExpiresAtMs)
        assertNull(outbox.load())
        assertEquals(Notice("Tap Complete cage again to see the new count.", Notice.Tone.INFO), wf.state.notice)
    }

    @Test fun readLoadOfCompletedLoadShowsCompleted() {
        toPreview()
        wf.onConfirmComplete()
        wf.onResponse(rejected("confirmation_expired"))
        wf.onDismissRefusal()
        wf.onResponse(accepted("""{"accepted":true,"isActive":false,"load":${loadJson("CAGE-007", 2, "50.4", status = "Completed")}}"""))
        assertEquals("CAGE-007", completed.load.cageId)
    }

    @Test fun readLoadRejectionGoesIdleWithoutRefusal() {
        toPreview()
        wf.onConfirmComplete()
        wf.onResponse(rejected("cage_contents_changed"))
        wf.onDismissRefusal()
        assertTrue(wf.onResponse(rejected("something_odd")).isEmpty())
        assertNull(wf.state.refusal)
        assertEquals(CageScreen.Idle, wf.state.screen)
        assertNull(outbox.load())
        assertNull(wf.state.notice)
    }

    @Test fun unknownCompletionRefusalStaysOnPreview() {
        toPreview()
        wf.onConfirmComplete()
        wf.onResponse(rejected("weird_code", "Station says no"))
        assertEquals("Cage not completed", wf.state.refusal?.headline)
        assertEquals("Station says no", wf.state.refusal?.detail)
        assertTrue(publishes(wf.onDismissRefusal()).isEmpty())
        assertTrue(wf.state.screen is CageScreen.Preview)
    }

    @Test fun previewExpiryReturnsToLoading() {
        toPreview()
        val effects = wf.onPreviewExpired()
        assertTrue(effects.isEmpty())
        assertEquals("CAGE-007", loading.load.cageId)
        assertEquals(2, loading.packs.size)
        assertNull(loading.armExpiresAtMs)
        assertEquals(Notice("Summary expired — tap Complete cage again", Notice.Tone.WARNING), wf.state.notice)
    }

    @Test fun previewExpiryIgnoredWhileConfirmIsPending() {
        toPreview()
        wf.onConfirmComplete()
        assertTrue(wf.onPreviewExpired().isEmpty())
        assertTrue(wf.state.screen is CageScreen.Preview)
        assertNull(wf.state.notice)
    }

    @Test fun qrIgnoredDuringPreview() {
        toPreview()
        val before = wf.state
        assertTrue(wf.onQr(validQr).isEmpty())
        assertTrue(wf.onRfid("TAG-CAGE-003").isEmpty())
        assertEquals(before, wf.state)
        assertNull(outbox.load())
    }

    private fun toCompleted() {
        toPreview()
        wf.onConfirmComplete()
        wf.onResponse(accepted("""{"accepted":true,"load":${loadJson("CAGE-007", 2, "50.4", status = "Completed")}}"""))
    }

    @Test fun completedQrShowsScanCageRefusal() {
        toCompleted()
        val effects = wf.onQr(validQr)
        assertTrue(publishes(effects).isEmpty())
        assertTrue(effects.contains(Effect.ErrorFeedback))
        assertEquals("local_cage_scan_required", wf.state.refusal?.code)
        assertEquals("Do NOT load this Pack yet", wf.state.refusal?.headline)
        assertNull(outbox.load())
        wf.onDismissRefusal()
        assertTrue(wf.state.screen is CageScreen.Completed) // STAY
    }

    @Test fun completedRfidSelectsAnyCage() {
        toCompleted()
        assertEquals(RequestIntent.SELECT, publishes(wf.onRfid("TAG-CAGE-007")).single().request.intent)
        wf.onResponse(rejected("cage_locked"))
        wf.onDismissRefusal()
        assertEquals(RequestIntent.SELECT, publishes(wf.onRfid("TAG-CAGE-003")).single().request.intent)
    }

    @Test fun refreshCompletedReadsLoad() {
        toCompleted()
        val p = publishes(wf.onRefreshCompleted()).single().request
        assertEquals(RequestIntent.READ_LOAD, p.intent)
        assertEquals("L-CAGE-007", p.loadId)
        assertEquals(p, outbox.load())
        wf.onResponse(accepted("""{"accepted":true,"load":${loadJson("CAGE-007", 2, "50.4", status = "Completed", grvState = "Succeeded")}}"""))
        assertEquals("Succeeded", completed.load.grvState)
    }

    @Test fun refreshOnlyFromCompleted() {
        toLoading()
        assertTrue(wf.onRefreshCompleted().isEmpty())
        assertNull(outbox.load())
    }

    // ---- decisions carried from Task 5 review ---------------------------------------------

    @Test fun stopKeepsStationOnline() {
        toLoading()
        wf.stop()
        assertTrue(wf.state.stationOnline)
        assertEquals(CageScreen.Idle, wf.state.screen)
    }

    @Test fun responsesIgnoredWithoutSession() {
        toLoading()
        wf.onQr(validQr)
        val pending = outbox.load()!!
        val r = accepted("""{"accepted":false,"errorCode":"pack_allocated","reason":"x"}""")
        wf.stop()
        assertTrue(wf.onResponse(r).isEmpty())
        assertSame(pending, outbox.load())
        assertNull(wf.state.refusal)
    }

    @Test fun blankScansIgnored() {
        toLoading()
        wf.onRfid("505055AAAA")
        assertTrue(wf.onRfid("   ").isEmpty())
        assertTrue(wf.onRfid("").isEmpty())
        assertTrue(wf.onQr(" \t").isEmpty())
        assertNull(wf.state.refusal)
        assertNull(outbox.load())

        wf.onKeepLoading() // no-op here
        wf.onQr("bad") // refusal
        wf.onDismissRefusal()
        wf.onRfid("TAG-X")
        wf.onResponse(rejected("unknown_cage"))
        wf.onDismissRefusal()
        // Idle: a QR shows the scan-the-cage-tag refusal; blank scans leave it alone
        wf.onQr(validQr)
        val refusal = wf.state.refusal
        assertEquals("local_cage_scan_required", refusal?.code)
        wf.onQr("  ")
        wf.onRfid(" ")
        assertEquals(refusal, wf.state.refusal)
    }
}
