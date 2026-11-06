package com.mitas.ppnam.station5aa.cage

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

class CageWorkflowLoadingTest : CageWorkflowFixture() {
    private val scanTagFirst = Refusal(
        "Do NOT load this Pack yet", "Scan the cage tag first, then scan this Pack again.",
        "local_cage_scan_required", AfterRefusal.STAY,
    )

    @Before fun setUp() = signIn()

    private val loading get() = wf.state.screen as CageScreen.Loading

    private fun allocatedAnswer(bagId: String = "B9", packCount: Int = 3, totalKg: String = "75.5", kg: String = "25.1") =
        accepted("""{"accepted":true,"requiresCageScan":true,"nextAction":"select_cage",
            "load":${loadJson("CAGE-007", packCount, totalKg)},"pack":${allocatedPackJson(bagId, kg)}}""")

    @Test fun startSendsReadActive() {
        val wf2Outbox = MemoryOutboxStore()
        val wf2 = CageWorkflow(requests, wf2Outbox, { 0L })
        val effects = wf2.start("s-1")
        assertEquals(1, effects.size)
        val p = (effects.single() as Effect.Publish).request
        assertEquals(RequestIntent.READ_ACTIVE, p.intent)
        assertEquals("s-1", p.operatorSessionId)
        assertEquals(p, wf2Outbox.load())
        assertTrue(wf2.state.sending)
        assertEquals(CageScreen.Idle, wf2.state.screen)
    }

    @Test fun setUpLeavesIdleAndNotSending() {
        assertEquals(CageScreen.Idle, wf.state.screen)
        assertFalse(wf.state.sending)
        assertNull(outbox.load())
        assertTrue(wf.state.stationOnline)
    }

    @Test fun rfidSelectsCage() {
        val effects = wf.onRfid("E200ABC")
        val p = publishes(effects).single().request
        assertEquals(RequestIntent.SELECT, p.intent)
        assertEquals("E200ABC", JSONObject(p.body).getString("rfidTag"))
        assertEquals(p, outbox.load())
        assertTrue(wf.state.sending)
    }

    @Test fun badgeTagIgnored() {
        assertTrue(wf.onRfid("505055AAAA").isEmpty())
        assertNull(outbox.load())
        assertFalse(wf.state.sending)
    }

    @Test fun qrWithoutCageShowsRefusal() {
        val effects = wf.onQr(validQr)
        assertTrue(publishes(effects).isEmpty())
        assertTrue(effects.contains(Effect.ErrorFeedback))
        assertNull(outbox.load())
        assertEquals(scanTagFirst, wf.state.refusal)
        assertNull(wf.state.notice)
    }

    @Test fun unarmedQrRefusalBlocksScansUntilDismissed() {
        wf.onQr(validQr)
        assertTrue(wf.onRfid("E200ABC").isEmpty()) // blocked behind the refusal
        assertNull(outbox.load())
        wf.onDismissRefusal()
        assertNull(wf.state.refusal)
        assertEquals(CageScreen.Idle, wf.state.screen) // STAY
        assertEquals(RequestIntent.SELECT, publishes(wf.onRfid("E200ABC")).single().request.intent)
    }

    @Test fun unarmedQrOnLoadingReplacesNoticeWithRefusal() {
        toLoading()
        clockMs += 120_000
        wf.onArmExpired()
        assertNotNull(wf.state.notice)
        val effects = wf.onQr(validQr)
        assertEquals(listOf<Effect>(Effect.ErrorFeedback), effects)
        assertEquals(scanTagFirst, wf.state.refusal)
        assertNull(wf.state.notice)
        assertEquals("CAGE-007", loading.load.cageId)
        assertNull(outbox.load())
    }

    @Test fun acceptedScanClearsNotice() {
        toLoading()
        clockMs += 120_000
        wf.onArmExpired()
        assertNotNull(wf.state.notice)
        wf.onRfid("505055AAAA") // ignored scan keeps the notice
        assertNotNull(wf.state.notice)
        wf.onRfid("E200ABC")
        assertNull(wf.state.notice)
    }

    // ---- one cage tag arms one Pack (contract aef7bf7) ----------------------------------------

    @Test fun tagArmsOnePack() {
        wf.onRfid("TAG-CAGE-007")
        wf.onResponse(selectArmed("CAGE-007", 2, "50.4"))
        assertEquals("CAGE-007", loading.load.cageId)
        assertEquals(clockMs + 120_000, loading.armExpiresAtMs)
        val p = publishes(wf.onQr(validQr)).single().request
        assertEquals(RequestIntent.ALLOCATE, p.intent)
        assertEquals("L-CAGE-007", JSONObject(p.body).getString("loadId"))
        assertEquals(validQr, JSONObject(p.body).getString("barcode"))
    }

    @Test fun secondQrAfterOneTagNotSent() {
        toLoading()
        wf.onQr(validQr)
        wf.onResponse(allocatedAnswer())
        wf.onResponse(accepted("""{"accepted":true,"isActive":true,"load":${loadJson("CAGE-007", 3, "75.5")}}""")
            .put("packs", packsArray(listOf("B9", "B1", "B2"))))
        assertNull(outbox.load())

        val effects = wf.onQr("510019399,O,B10")
        assertTrue(publishes(effects).isEmpty())
        assertTrue(effects.contains(Effect.ErrorFeedback))
        assertNull(outbox.load())
        assertEquals(scanTagFirst, wf.state.refusal)
        assertNull(wf.state.notice)
        assertNull(loading.armExpiresAtMs)
    }

    @Test fun noReadWhileArmed() {
        wf.onRfid("TAG-CAGE-007")
        val effects = wf.onResponse(selectArmed("CAGE-007", 2, "50.4"))
        assertTrue(publishes(effects).isEmpty())
        assertNull(outbox.load())
        assertFalse(wf.state.sending)
        assertNotNull(loading.armExpiresAtMs)
    }

    @Test fun requiresCageScanTrueDoesNotArm() {
        wf.onRfid("TAG-CAGE-007")
        val effects = wf.onResponse(accepted("""{"accepted":true,"requiresCageScan":true,"nextAction":"select_cage",
            "load":${loadJson("CAGE-007", 2, "50.4")}}"""))
        assertTrue(publishes(effects).isEmpty())
        assertEquals("CAGE-007", loading.load.cageId)
        assertNull(loading.armExpiresAtMs)
        val qr = wf.onQr(validQr)
        assertTrue(publishes(qr).isEmpty())
        assertEquals(scanTagFirst, wf.state.refusal)
    }

    @Test fun sameCageRescanReArms() {
        toLoading()
        wf.onQr(validQr)
        wf.onResponse(allocatedAnswer())
        wf.onResponse(accepted("""{"accepted":true,"isActive":true,"load":${loadJson("CAGE-007", 3, "75.5")}}""")
            .put("packs", packsArray(listOf("B9", "B1", "B2"))))

        clockMs += 30_000
        val p = publishes(wf.onRfid("TAG-CAGE-007")).single().request
        assertEquals(RequestIntent.SELECT, p.intent)
        val effects = wf.onResponse(selectArmed("CAGE-007", 3, "75.5",
            serverSentAtUtc = "2026-11-03T10:00:30.000000Z", expiresAtUtc = "2026-11-03T10:02:30.000000Z"))
        assertTrue(publishes(effects).isEmpty())
        val s = wf.state.screen
        assertTrue(s is CageScreen.Loading)
        s as CageScreen.Loading
        assertEquals(clockMs + 120_000, s.armExpiresAtMs)
        assertEquals(listOf("B9", "B1", "B2"), s.packs.map { it.bagId }) // same load: list kept
        assertEquals("B9", s.lastPack?.bagId)
        assertEquals(RequestIntent.ALLOCATE, publishes(wf.onQr("510019399,O,B10")).single().request.intent)
    }

    @Test fun allocationAcceptedDisarmsAndRefreshes() {
        toLoading()
        val p = publishes(wf.onQr(validQr)).single().request
        assertEquals(RequestIntent.ALLOCATE, p.intent)
        assertTrue(wf.state.sending)

        val effects = wf.onResponse(allocatedAnswer())
        assertEquals(Effect.SuccessFeedback, effects.first())
        val read = publishes(effects).single().request
        assertEquals(RequestIntent.READ_LOAD, read.intent)
        assertEquals("cage_context_requested", read.requestType)
        assertEquals("L-CAGE-007", JSONObject(read.body).getString("loadId"))
        assertEquals(read, outbox.load())
        assertNull(loading.armExpiresAtMs)
        assertEquals(3, loading.load.packCount)
        assertEquals(BigDecimal("75.5"), loading.load.totalKg)
        assertEquals("B9", loading.lastPack?.bagId)
        assertEquals(BigDecimal("25.1"), loading.lastPack?.weightKg)
        assertEquals(listOf("B9", "B1", "B2"), loading.packs.map { it.bagId })

        wf.onResponse(accepted("""{"accepted":true,"isActive":true,"requiresCageScan":true,"load":${loadJson("CAGE-007", 4, "100.5")}}""")
            .put("packs", packsArray(listOf("B10", "B9", "B1", "B2"))))
        assertEquals(4, loading.load.packCount)
        assertEquals(listOf("B10", "B9", "B1", "B2"), loading.packs.map { it.bagId })
        assertEquals("B9", loading.lastPack?.bagId) // kept: same load
        assertNull(loading.armExpiresAtMs)
        assertNull(outbox.load())
        assertFalse(wf.state.sending)
    }

    @Test fun refreshReadRejectedStaysOnLoading() {
        toLoading()
        wf.onQr(validQr)
        wf.onResponse(allocatedAnswer())
        assertEquals(RequestIntent.READ_LOAD, outbox.load()?.intent)
        assertTrue(wf.onResponse(rejected("something_odd")).isEmpty())
        assertNull(wf.state.refusal)
        assertEquals("CAGE-007", loading.load.cageId)
        assertEquals("B9", loading.lastPack?.bagId)
        assertEquals(3, loading.packs.size)
        assertNull(outbox.load())
    }

    @Test fun readLoadWithOtherBagIdRejectedIsNotTreatedAsPackRefresh() {
        toLoadingUnarmed()
        // Only the local pack-refresh marker keeps the screen; any other bagId is an ordinary read.
        outbox.save(requests.readLoad("s-1", "L-CAGE-007", RequestIntent.READ_LOAD, "B5"))
        assertTrue(wf.onResponse(rejected("something_odd")).isEmpty())
        assertSame(CageScreen.Idle, wf.state.screen)
        assertNull(outbox.load())
    }

    @Test fun allocationRefusedDisarmsNoRead() {
        toLoading()
        wf.onQr(validQr)
        val effects = wf.onResponse(accepted("""{"accepted":false,"errorCode":"pack_allocated","reason":"x",
            "requiresCageScan":true,"nextAction":"select_cage"}"""))
        assertTrue(publishes(effects).isEmpty())
        assertTrue(effects.contains(Effect.ErrorFeedback))
        assertNull(outbox.load())
        assertEquals("pack_allocated", wf.state.refusal?.code)
        assertNull(loading.armExpiresAtMs)
        wf.onDismissRefusal()
        assertTrue(publishes(wf.onQr("510019399,O,B10")).isEmpty())
        assertEquals(scanTagFirst, wf.state.refusal)
    }

    @Test fun armExpiryDisarms() {
        toLoading()
        clockMs += 120_000
        val effects = wf.onArmExpired()
        assertTrue(effects.isEmpty())
        assertNull(outbox.load())
        assertNull(loading.armExpiresAtMs)
        assertEquals(Notice("Cage tag expired — scan it again", Notice.Tone.WARNING), wf.state.notice)
        assertEquals(listOf("B1", "B2"), loading.packs.map { it.bagId })
    }

    @Test fun armExpiryWhenNotArmedDoesNothing() {
        toLoadingUnarmed()
        val before = wf.state
        assertTrue(wf.onArmExpired().isEmpty())
        assertEquals(before, wf.state)
    }

    @Test fun qrAfterArmDeadlinePassedIsNotSent() {
        toLoading()
        clockMs += 120_001 // the host's tick has not fired yet
        val effects = wf.onQr(validQr)
        assertTrue(publishes(effects).isEmpty())
        assertTrue(effects.contains(Effect.ErrorFeedback))
        assertEquals(scanTagFirst, wf.state.refusal)
        assertNull(loading.armExpiresAtMs)
    }

    @Test fun countdownUsesStationClock() {
        clockMs = t0.toEpochMilli() + 180_000 // handheld clock three minutes fast
        wf.onRfid("TAG-CAGE-007")
        wf.onResponse(selectArmed("CAGE-007"))
        assertEquals(clockMs + 120_000, loading.armExpiresAtMs)
    }

    @Test fun countdownFallsBackToTimestampUtc() {
        wf.onRfid("TAG-CAGE-007")
        wf.onResponse(accepted("""{"accepted":true,"requiresCageScan":false,"cageScanExpiresAtUtc":"2026-11-03T10:02:00Z",
            "timestampUtc":"2026-11-03T10:01:00Z","load":${loadJson("CAGE-007")}}"""))
        assertEquals(clockMs + 60_000, loading.armExpiresAtMs)
    }

    @Test fun unparseableExpiryArmsForTwoMinutes() {
        wf.onRfid("TAG-CAGE-007")
        wf.onResponse(accepted("""{"accepted":true,"requiresCageScan":false,"cageScanExpiresAtUtc":"soon",
            "serverSentAtUtc":"2026-11-03T10:00:00Z","load":${loadJson("CAGE-007")}}"""))
        assertEquals(clockMs + 120_000, loading.armExpiresAtMs)
    }

    // ---- allocation ---------------------------------------------------------------------------

    @Test fun allocateIdempotentRescanDoesNotDuplicate() {
        toLoading(packs = listOf("B1", "B9"))
        wf.onQr(validQr)
        wf.onResponse(allocatedAnswer(packCount = 2, totalKg = "50.4", kg = "25.0"))
        assertEquals(listOf("B1", "B9"), loading.packs.map { it.bagId })
    }

    private val alreadyInCage = Notice("B9 is already in this cage.", Notice.Tone.INFO)

    /** Adds B9 (accepted, refreshed), then re-arms the same cage; lastPack is B9. */
    private fun afterFirstPack() {
        toLoading()
        wf.onQr(validQr)
        wf.onResponse(allocatedAnswer())
        wf.onResponse(accepted("""{"accepted":true,"isActive":true,"load":${loadJson("CAGE-007", 3, "75.5")}}""")
            .put("packs", packsArray(listOf("B9", "B1", "B2"))))
        wf.onRfid("TAG-CAGE-007")
        wf.onResponse(selectArmed("CAGE-007", 3, "75.5"))
    }

    @Test fun rescanOfPackInShownListIsNotANewPlacement() {
        afterFirstPack()
        wf.onQr("510019399,O,B1")
        val effects = wf.onResponse(allocatedAnswer(bagId = "B1", packCount = 3, totalKg = "75.5", kg = "25.0"))
        assertEquals(Effect.SuccessFeedback, effects.first()) // still accepted: success (S1)
        assertEquals(Notice("B1 is already in this cage.", Notice.Tone.INFO), wf.state.notice)
        assertEquals("B9", loading.lastPack?.bagId) // unchanged
        assertEquals(RequestIntent.READ_LOAD, publishes(effects).single().request.intent) // still refreshes
        assertEquals(listOf("B9", "B1", "B2"), loading.packs.map { it.bagId })
        // The notice survives the refresh answer.
        wf.onResponse(accepted("""{"accepted":true,"isActive":true,"load":${loadJson("CAGE-007", 3, "75.5")}}""")
            .put("packs", packsArray(listOf("B9", "B1", "B2"))))
        assertEquals(Notice("B1 is already in this cage.", Notice.Tone.INFO), wf.state.notice)
        assertEquals("B9", loading.lastPack?.bagId)
    }

    @Test fun packCountNotIncreasedIsNotANewPlacement() {
        // The select reply carries no packs, so the shown list may not hold the Pack yet.
        toLoadingUnarmed(packs = emptyList())
        wf.onRfid("TAG-CAGE-007")
        wf.onResponse(selectArmed("CAGE-007", 3, "75.5"))
        wf.onQr(validQr)
        val effects = wf.onResponse(allocatedAnswer(packCount = 3, totalKg = "75.5"))
        assertEquals(Effect.SuccessFeedback, effects.first())
        assertEquals(alreadyInCage, wf.state.notice)
        assertNull(loading.lastPack)
        assertEquals(RequestIntent.READ_LOAD, publishes(effects).single().request.intent)
    }

    @Test fun alreadyAllocatedFlagIsNotANewPlacement() {
        toLoadingUnarmed(packs = emptyList())
        wf.onRfid("TAG-CAGE-007")
        wf.onResponse(selectArmed("CAGE-007", 3, "75.5"))
        wf.onQr(validQr)
        val effects = wf.onResponse(allocatedAnswer(packCount = 4, totalKg = "100.6").put("alreadyAllocated", true))
        assertEquals(Effect.SuccessFeedback, effects.first())
        assertEquals(alreadyInCage, wf.state.notice)
        assertNull(loading.lastPack)
        assertEquals(4, loading.load.packCount) // the station's figures are still shown
    }

    @Test fun newPackAfterRescanStillSucceeds() {
        afterFirstPack()
        wf.onQr("510019399,O,B10")
        val effects = wf.onResponse(allocatedAnswer(bagId = "B10", packCount = 4, totalKg = "100.6"))
        assertEquals(Effect.SuccessFeedback, effects.first())
        assertNull(wf.state.notice)
        assertEquals("B10", loading.lastPack?.bagId)
    }

    @Test fun qrWhileSendingSendsNothingMore() {
        toLoading()
        wf.onQr(validQr)
        val first = outbox.load()
        assertEquals(listOf<Effect>(Effect.ErrorFeedback), wf.onQr("510019399,O,B10"))
        assertSame(first, outbox.load())
        assertEquals(listOf<Effect>(Effect.ErrorFeedback), wf.onRfid("E200ABC"))
        assertSame(first, outbox.load())
    }

    @Test fun invalidQrRefusedLocallyKeepsArm() {
        toLoading()
        val armedUntil = loading.armExpiresAtMs
        val effects = wf.onQr("OUTER,BAG")
        assertTrue(publishes(effects).isEmpty())
        assertNull(outbox.load())
        assertEquals("local_invalid_qr", wf.state.refusal?.code)
        assertTrue(effects.contains(Effect.ErrorFeedback))
        wf.onDismissRefusal()
        assertEquals(armedUntil, loading.armExpiresAtMs) // nothing was sent, the station's arm is unspent
        assertEquals(RequestIntent.ALLOCATE, publishes(wf.onQr(validQr)).single().request.intent)
    }

    @Test fun allocationRefusalBlocksScans() {
        toLoading()
        wf.onQr(validQr)
        val effects = wf.onResponse(rejected("pack_allocated"))
        assertEquals("pack_allocated", wf.state.refusal?.code)
        assertTrue(wf.state.refusal!!.detail.startsWith("B9 "))
        assertTrue(effects.contains(Effect.ErrorFeedback))
        assertEquals(2, loading.load.packCount)
        assertNull(outbox.load())
        assertFalse(wf.state.sending)

        assertTrue(wf.onQr("510019399,O,B10").isEmpty())
        assertTrue(wf.onRfid("E200ABC").isEmpty())
        assertNull(outbox.load())

        wf.onDismissRefusal()
        assertNull(wf.state.refusal)
        assertEquals("CAGE-007", loading.load.cageId) // STAY
        wf.onRfid("TAG-CAGE-007")
        wf.onResponse(selectArmed("CAGE-007", 2, "50.4"))
        assertEquals(RequestIntent.ALLOCATE, publishes(wf.onQr("510019399,O,B10")).single().request.intent)
    }

    @Test fun cageScanRequiredStaysNotArmed() {
        toLoading()
        wf.onQr(validQr)
        wf.onResponse(rejected("cage_scan_required"))
        assertEquals("Scan the cage tag first", wf.state.refusal?.headline)
        wf.onDismissRefusal()
        assertEquals("CAGE-007", loading.load.cageId)
        assertNull(loading.armExpiresAtMs)
    }

    @Test fun cageLockedGoesIdleAfterDismiss() {
        toLoading()
        wf.onQr(validQr)
        wf.onResponse(rejected("cage_locked"))
        assertTrue(wf.state.screen is CageScreen.Loading)
        wf.onDismissRefusal()
        assertEquals(CageScreen.Idle, wf.state.screen)
        assertNull(wf.state.refusal)
    }

    @Test fun selectionRefusalUsesSelectionCatalog() {
        wf.onRfid("E200ABC")
        val effects = wf.onResponse(rejected("unknown_cage"))
        assertEquals("Cage not recognised", wf.state.refusal?.headline)
        assertTrue(effects.contains(Effect.ErrorFeedback))
        assertNull(outbox.load())
    }

    @Test fun selectionRefusalDisarms() {
        toLoading()
        wf.onRfid("TAG-OTHER")
        wf.onResponse(rejected("unknown_cage"))
        assertNull(loading.armExpiresAtMs) // the station discards an unused arm before selecting
    }

    @Test fun readActiveWithActiveLoadShowsLoadingNotArmed() {
        val ob = MemoryOutboxStore()
        val w = CageWorkflow(requests, ob, { 0L })
        w.start("s-1")
        val r = JSONObject("""{"accepted":true,"isActive":true,"load":${loadJson("CAGE-009", 1, "25")}}""")
            .put("packs", JSONArray(listOf(JSONObject(packJson("B5")))))
            .put("inResponseToMessageId", ob.load()!!.messageId)
        w.onResponse(r)
        val s = w.state.screen as CageScreen.Loading
        assertEquals("CAGE-009", s.load.cageId)
        assertEquals(1, s.packs.size)
        assertNull(s.lastPack)
        assertNull(s.armExpiresAtMs)
    }

    @Test fun readActiveRejectionGoesIdleWithoutRefusal() {
        toLoading()
        wf.start("s-1") // reconnect: same session, empty outbox -> READ_ACTIVE
        assertEquals(RequestIntent.READ_ACTIVE, outbox.load()?.intent)
        val effects = wf.onResponse(rejected("something_odd"))
        assertTrue(effects.isEmpty())
        assertNull(wf.state.refusal)
        assertEquals(CageScreen.Idle, wf.state.screen)
        assertNull(outbox.load())
    }

    @Test fun staleResponseIgnored() {
        wf.onRfid("E200ABC")
        val before = wf.state
        val pending = outbox.load()
        val effects = wf.onResponse(JSONObject("""{"accepted":true,"inResponseToMessageId":"other","nextAction":"scan_pack","load":${loadJson("CAGE-007")}}"""))
        assertTrue(effects.isEmpty())
        assertEquals(before, wf.state)
        assertSame(pending, outbox.load())
    }

    @Test fun transientCodeKeepsOutbox() {
        wf.onRfid("E200ABC")
        val before = wf.state
        val pending = outbox.load()
        assertTrue(wf.onResponse(rejected("processing_paused")).isEmpty())
        // Only the "paused — retrying" notice changes (skill-run critique 2026-11-06, P2-7).
        assertEquals(before.copy(notice = Notice("Station 5 is paused — retrying…", Notice.Tone.WARNING)), wf.state)
        assertSame(pending, outbox.load())
    }

    @Test fun switchCage() {
        toLoading()
        val p = publishes(wf.onRfid("TAG-003")).single().request
        assertEquals(RequestIntent.SELECT, p.intent)
        wf.onResponse(selectArmed("CAGE-003"))
        assertEquals("CAGE-003", loading.load.cageId)
        assertTrue(loading.packs.isEmpty())
        assertNull(loading.lastPack)
        assertNotNull(loading.armExpiresAtMs)
    }

    @Test fun offlineBlocksScans() {
        wf.onStationOnline(false)
        assertFalse(wf.state.stationOnline)
        assertEquals(listOf<Effect>(Effect.ErrorFeedback), wf.onRfid("E200ABC")) // refused locally (S1)
        assertEquals(listOf<Effect>(Effect.ErrorFeedback), wf.onQr(validQr))
        assertNull(wf.state.notice)
        assertNull(outbox.load())
    }

    @Test fun loginRequiredSignsOut() {
        toLoading()
        wf.onQr(validQr)
        val effects = wf.onResponse(rejected("login_required"))
        assertTrue(effects.contains(Effect.SignOut("Your session ended. Sign in and rescan the cage.")))
        assertNull(outbox.load())
        assertEquals(CageScreen.Idle, wf.state.screen)
        assertFalse(wf.state.sending)
    }

    @Test fun startRepublishesSameSessionPendingAndDropsOtherSession() {
        wf.onRfid("E200ABC")
        val pending = outbox.load()!!
        val again = wf.start("s-1")
        assertEquals(pending, publishes(again).single().request)
        assertSame(pending, outbox.load())

        wf.stop()
        assertEquals(CageScreen.Idle, wf.state.screen)
        assertSame(pending, outbox.load()) // stop leaves the outbox untouched
        assertTrue(wf.state.sending)

        val fresh = publishes(wf.start("s-2")).single().request
        assertEquals(RequestIntent.READ_ACTIVE, fresh.intent)
        assertEquals("s-2", fresh.operatorSessionId)
        assertEquals(fresh, outbox.load())
    }

    @Test fun scansIgnoredAfterStop() {
        wf.stop()
        wf.onStationOnline(true)
        assertTrue(wf.onRfid("E200ABC").isEmpty())
        assertNull(outbox.load())
    }
}
