package com.mitas.ppnam.station5aa.cage

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Shared harness for the Task 6 workflow tests: a movable clock, a memory outbox and JSON builders. */
abstract class CageWorkflowFixture {
    protected val t0: Instant = Instant.parse("2026-11-03T10:00:00Z")
    protected var clockMs: Long = t0.toEpochMilli()
    private var idSeq = 0
    protected val requests = CageRequests("scanner_ab12", { Instant.ofEpochMilli(clockMs) }, { "m-${++idSeq}" })
    protected val outbox = MemoryOutboxStore()
    protected val wf = CageWorkflow(requests, outbox, { clockMs })

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

    protected fun packsArray(bagIds: List<String>) = JSONArray(bagIds.map { JSONObject(packJson(it)) })

    protected fun publishes(effects: List<Effect>) = effects.filterIsInstance<Effect.Publish>()

    /** Signs in as [session] with no active cage and brings the station online. */
    protected fun signIn(session: String = "s-1") {
        wf.start(session)
        wf.onResponse(accepted("""{"accepted":true,"load":null,"isActive":false}"""))
        wf.onStationOnline(true)
    }

    /** Selects [cageId] and loads [packs] into the screen. */
    protected fun toLoading(cageId: String = "CAGE-007", packs: List<String> = listOf("B1", "B2")) {
        wf.onRfid("TAG-$cageId")
        wf.onResponse(accepted("""{"accepted":true,"nextAction":"scan_pack","load":${loadJson(cageId, packs.size, "50.4")}}"""))
        wf.onResponse(accepted("""{"accepted":true,"isActive":true,"nextAction":"scan_pack","load":${loadJson(cageId, packs.size, "50.4")}}""")
            .put("packs", packsArray(packs)))
    }

    /** From Loading on [cageId], rescans its tag and answers with a completion preview. */
    protected fun toPreview(cageId: String = "CAGE-007", packs: List<String> = listOf("B1", "B2")) {
        toLoading(cageId, packs)
        wf.onRfid("TAG-$cageId")
        wf.onResponse(accepted("""{"accepted":true,"nextAction":"confirm_completion","confirmationToken":"tok-1",
            "confirmationExpiresAtUtc":"2026-11-03T10:02:00.000000Z","load":${loadJson(cageId, packs.size, "50.4")}}""")
            .put("packs", packsArray(packs)))
    }
}
