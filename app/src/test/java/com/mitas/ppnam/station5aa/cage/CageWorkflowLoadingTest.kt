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
import java.time.Instant

class CageWorkflowLoadingTest {
    private val t = Instant.parse("2026-11-03T10:00:00Z")
    private var idSeq = 0
    private val requests = CageRequests("scanner_ab12", { t }, { "m-${++idSeq}" })
    private val outbox = MemoryOutboxStore()
    private val wf = CageWorkflow(requests, outbox, { t.toEpochMilli() })

    private val validQr = "510019399,O,B9"

    private fun accepted(json: String): JSONObject = JSONObject(json).apply {
        put("inResponseToMessageId", outbox.load()!!.messageId)
        if (!has("accepted")) put("accepted", true)
    }

    private fun rejected(code: String, reason: String = "x"): JSONObject =
        accepted("""{"accepted":false,"errorCode":"$code","reason":"$reason"}""")

    private fun loadJson(cageId: String, packCount: Int = 0, totalKg: String = "0", loadId: String = "L-$cageId") = """
        {"loadId":"$loadId","cageId":"$cageId","status":"Open","packCount":$packCount,"totalKg":$totalKg,
         "completedAtUtc":null,"grvState":"Not queued","grvReference":null,"printState":"WaitingForGrv","error":null}
    """.trimIndent()

    private fun packJson(bagId: String, kg: String = "25.0") = """
        {"bagId":"$bagId","outerCode":"O","itemCode":"FG1","itemName":"Item","jobCard":"510019399","weightKg":$kg,"disposition":"Pass"}
    """.trimIndent()

    private fun publishes(effects: List<Effect>) = effects.filterIsInstance<Effect.Publish>()

    @Before fun setUp() {
        wf.start("s-1")
        wf.onResponse(accepted("""{"accepted":true,"load":null,"isActive":false}"""))
        wf.onStationOnline(true)
    }

    /** Selects CAGE-007 and loads two packs into the screen. */
    private fun toLoading(cageId: String = "CAGE-007", packs: List<String> = listOf("B1", "B2")) {
        wf.onRfid("TAG-$cageId")
        wf.onResponse(accepted("""{"accepted":true,"nextAction":"scan_pack","load":${loadJson(cageId, packs.size, "50.4")}}"""))
        val arr = JSONArray(packs.map { JSONObject(packJson(it)) })
        wf.onResponse(accepted("""{"accepted":true,"isActive":true,"nextAction":"scan_pack","load":${loadJson(cageId, packs.size, "50.4")}}""")
            .put("packs", arr))
    }

    private val loading get() = wf.state.screen as CageScreen.Loading

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

    @Test fun qrWithoutCageShowsNotice() {
        val effects = wf.onQr(validQr)
        assertTrue(publishes(effects).isEmpty())
        assertNull(outbox.load())
        assertEquals("Scan a cage tag first", wf.state.notice?.text)
    }

    @Test fun acceptedScanClearsNotice() {
        wf.onQr(validQr)
        assertNotNull(wf.state.notice)
        wf.onRfid("505055AAAA") // ignored scan keeps the notice
        assertNotNull(wf.state.notice)
        wf.onRfid("E200ABC")
        assertNull(wf.state.notice)
    }

    @Test fun acceptedSelectFetchesPacks() {
        wf.onRfid("E200ABC")
        val effects = wf.onResponse(accepted("""{"accepted":true,"nextAction":"scan_pack","load":${loadJson("CAGE-007", 2, "50.4")}}"""))
        assertEquals("CAGE-007", loading.load.cageId)
        assertTrue(loading.packs.isEmpty())
        assertNull(loading.lastPack)
        val p = publishes(effects).single().request
        assertEquals(RequestIntent.READ_ACTIVE, p.intent)
        assertEquals(p, outbox.load())
        assertTrue(wf.state.sending)

        val arr = JSONArray(listOf(JSONObject(packJson("B1")), JSONObject(packJson("B2"))))
        wf.onResponse(accepted("""{"accepted":true,"isActive":true,"nextAction":"scan_pack","load":${loadJson("CAGE-007", 2, "50.4")}}""")
            .put("packs", arr))
        assertEquals("CAGE-007", loading.load.cageId)
        assertEquals(2, loading.packs.size)
        assertFalse(wf.state.sending)
        assertNull(outbox.load())
    }

    @Test fun readActiveWithActiveLoadShowsLoadingOnStart() {
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
    }

    @Test fun allocateAccepted() {
        toLoading()
        val effects = wf.onQr(validQr)
        val p = publishes(effects).single().request
        assertEquals(RequestIntent.ALLOCATE, p.intent)
        assertEquals("L-CAGE-007", p.loadId)
        assertEquals("L-CAGE-007", JSONObject(p.body).getString("loadId"))
        assertEquals(validQr, JSONObject(p.body).getString("barcode"))
        assertTrue(wf.state.sending)

        val answer = wf.onResponse(accepted("""{"accepted":true,"load":${loadJson("CAGE-007", 3, "75.5")},
            "pack":{"bagId":"B9","outerCode":"O","itemCode":"FG1","itemName":"Item","productionOrderNumber":"510019399","actualKg":25.1,"disposition":"Pass"}}"""))
        assertEquals(3, loading.load.packCount)
        assertEquals(BigDecimal("75.5"), loading.load.totalKg)
        assertEquals("B9", loading.lastPack?.bagId)
        assertEquals(BigDecimal("25.1"), loading.lastPack?.weightKg)
        assertEquals("B9", loading.packs.first().bagId)
        assertEquals(3, loading.packs.size)
        assertTrue(answer.contains(Effect.SuccessFeedback))
        assertNull(outbox.load())
        assertFalse(wf.state.sending)
    }

    @Test fun allocateIdempotentRescanDoesNotDuplicate() {
        toLoading(packs = listOf("B1", "B9"))
        wf.onQr(validQr)
        wf.onResponse(accepted("""{"accepted":true,"load":${loadJson("CAGE-007", 2, "50.4")},
            "pack":{"bagId":"B9","outerCode":"O","itemCode":"FG1","itemName":"Item","actualKg":25.0,"disposition":"Pass"}}"""))
        assertEquals(listOf("B9", "B1"), loading.packs.map { it.bagId })
    }

    @Test fun qrWhileSendingIsIgnored() {
        toLoading()
        wf.onQr(validQr)
        val first = outbox.load()
        assertTrue(wf.onQr("510019399,O,B10").isEmpty())
        assertSame(first, outbox.load())
        assertTrue(wf.onRfid("E200ABC").isEmpty())
        assertSame(first, outbox.load())
    }

    @Test fun invalidQrRefusedLocally() {
        toLoading()
        val effects = wf.onQr("OUTER,BAG")
        assertTrue(publishes(effects).isEmpty())
        assertNull(outbox.load())
        assertEquals("local_invalid_qr", wf.state.refusal?.code)
        assertTrue(effects.contains(Effect.ErrorFeedback))
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
        assertEquals(RequestIntent.ALLOCATE, publishes(wf.onQr("510019399,O,B10")).single().request.intent)
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
        assertEquals(before, wf.state)
        assertSame(pending, outbox.load())
    }

    @Test fun switchCage() {
        toLoading()
        val p = publishes(wf.onRfid("TAG-003")).single().request
        assertEquals(RequestIntent.SELECT, p.intent)
        wf.onResponse(accepted("""{"accepted":true,"nextAction":"scan_pack","load":${loadJson("CAGE-003", 0, "0")}}"""))
        assertEquals("CAGE-003", loading.load.cageId)
        assertTrue(loading.packs.isEmpty())
    }

    @Test fun offlineBlocksScans() {
        wf.onStationOnline(false)
        assertFalse(wf.state.stationOnline)
        assertTrue(wf.onRfid("E200ABC").isEmpty())
        assertTrue(wf.onQr(validQr).isEmpty())
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
