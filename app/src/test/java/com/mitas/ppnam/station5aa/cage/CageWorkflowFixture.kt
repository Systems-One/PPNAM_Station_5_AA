package com.mitas.ppnam.station5aa.cage

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Shared harness for the workflow tests: a movable clock, a memory outbox and JSON builders. */
abstract class CageWorkflowFixture {
    protected val t0: Instant = Instant.parse("2026-11-03T10:00:00Z")
    protected var clockMs: Long = t0.toEpochMilli()
    private var idSeq = 0
    protected val requests = CageRequests("scanner_ab12", { Instant.ofEpochMilli(clockMs) }, { "m-${++idSeq}" })
    protected val outbox = MemoryOutboxStore()
    protected val wf = CageWorkflow(requests, outbox, { clockMs })
    private var session = "s-1"

    protected val validQr = "510019399,O,B9"

    protected fun accepted(json: String): JSONObject = JSONObject(json).apply {
        put("inResponseToMessageId", outbox.load()!!.messageId)
        if (!has("accepted")) put("accepted", true)
    }

    protected fun rejected(code: String, reason: String = "x"): JSONObject =
        accepted("""{"accepted":false,"errorCode":"$code","reason":"$reason"}""")

    protected fun loadJson(
        cageId: String,
        packCount: Int = 0,
        totalKg: String = "0",
        loadId: String = "L-$cageId",
        status: String = "Open",
        grvState: String = "Not queued",
    ) = """
        {"loadId":"$loadId","cageId":"$cageId","status":"$status","packCount":$packCount,"totalKg":$totalKg,
         "completedAtUtc":null,"grvState":"$grvState","grvReference":null,"printState":"WaitingForGrv","error":null}
    """.trimIndent()

    protected fun packJson(bagId: String, kg: String = "25.0") = """
        {"bagId":"$bagId","outerCode":"O","itemCode":"FG1","itemName":"Item","jobCard":"510019399","weightKg":$kg,"disposition":"Pass"}
    """.trimIndent()

    protected fun allocatedPackJson(bagId: String, kg: String = "25.1") = """
        {"bagId":"$bagId","outerCode":"O","itemCode":"FG1","itemName":"Item","productionOrderNumber":"510019399","actualKg":$kg,"disposition":"Pass"}
    """.trimIndent()

    protected fun packsArray(bagIds: List<String>) = JSONArray(bagIds.map { JSONObject(packJson(it)) })

    protected fun publishes(effects: List<Effect>) = effects.filterIsInstance<Effect.Publish>()

    /** An accepted fresh selection (contract aef7bf7): arms one Pack until two minutes after the station's send time. */
    protected fun selectArmed(
        cageId: String,
        packCount: Int = 0,
        totalKg: String = "0",
        serverSentAtUtc: String = "2026-11-03T10:00:00.000000Z",
        expiresAtUtc: String = "2026-11-03T10:02:00.000000Z",
    ): JSONObject = accepted("""{"accepted":true,"nextAction":"scan_pack","requiresCageScan":false,
        "cageScanExpiresAtUtc":"$expiresAtUtc","serverSentAtUtc":"$serverSentAtUtc","timestampUtc":"$serverSentAtUtc",
        "load":${loadJson(cageId, packCount, totalKg)}}""")

    /** Signs in as [session] with no active cage and brings the station online. */
    protected fun signIn(session: String = "s-1") {
        this.session = session
        wf.start(session)
        wf.onResponse(accepted("""{"accepted":true,"load":null,"isActive":false}"""))
        wf.onStationOnline(true)
    }

    /** Shows [cageId] with [packs] (via a context read on reconnect), not armed. */
    protected fun toLoadingUnarmed(cageId: String = "CAGE-007", packs: List<String> = listOf("B1", "B2")) {
        wf.start(session) // same session, empty outbox -> READ_ACTIVE
        wf.onResponse(accepted("""{"accepted":true,"isActive":true,"requiresCageScan":true,"nextAction":"select_cage",
            "load":${loadJson(cageId, packs.size, "50.4")}}""").put("packs", packsArray(packs)))
    }

    /** Shows [cageId] with [packs], then scans its tag so exactly one Pack is armed. */
    protected fun toLoading(cageId: String = "CAGE-007", packs: List<String> = listOf("B1", "B2")) {
        toLoadingUnarmed(cageId, packs)
        wf.onRfid("TAG-$cageId")
        wf.onResponse(selectArmed(cageId, packs.size, "50.4"))
    }

    /** From Loading on [cageId], taps Complete cage and answers with a completion preview. */
    protected fun toPreview(cageId: String = "CAGE-007", packs: List<String> = listOf("B1", "B2")) {
        toLoading(cageId, packs)
        wf.onCompleteCage()
        wf.onResponse(accepted("""{"accepted":true,"nextAction":"confirm_completion","confirmationToken":"tok-1",
            "confirmationExpiresAtUtc":"2026-11-03T10:02:00.000000Z","serverSentAtUtc":"2026-11-03T10:00:00.000000Z",
            "load":${loadJson(cageId, packs.size, "50.4")}}""")
            .put("packs", packsArray(packs)))
    }
}
