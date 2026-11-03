package com.mitas.ppnam.station5aa.cage

import com.mitas.ppnam.station5aa.UserTagPolicy
import org.json.JSONObject

sealed interface CageScreen {
    data object Idle : CageScreen
    data class Loading(val load: CageLoad, val packs: List<LoadedPack>, val lastPack: LoadedPack?) : CageScreen
    data class Preview(val load: CageLoad, val packs: List<LoadedPack>, val token: String, val expiresAtUtc: String) : CageScreen
    data class Completed(val load: CageLoad) : CageScreen
}

data class Notice(val text: String, val tone: Tone) { enum class Tone { INFO, SUCCESS, WARNING } }

data class CageUiState(
    val screen: CageScreen = CageScreen.Idle,
    val sending: Boolean = false,
    val refusal: Refusal? = null,
    val notice: Notice? = null,
    val stationOnline: Boolean = false,
)

sealed interface Effect {
    data class Publish(val request: PendingRequest) : Effect
    data object SuccessFeedback : Effect
    data object ErrorFeedback : Effect
    data class SignOut(val reason: String) : Effect
}

/**
 * The cage-loading state machine. Pure Kotlin: the host feeds it scans, station status and
 * correlated responses, and carries out the returned [Effect]s in order.
 *
 * Invariants: every outgoing request is saved to [outbox] before its [Effect.Publish] is returned;
 * after every public call `state.sending == (outbox.load() != null)`.
 */
class CageWorkflow(
    private val requests: CageRequests,
    private val outbox: OutboxStore,
    private val nowMs: () -> Long,
) {
    var state: CageUiState = CageUiState(sending = outbox.load() != null)
        private set

    /** Operator session in use; null when signed out. All requests are built with it. */
    private var sessionId: String? = null

    fun start(sessionId: String): List<Effect> = step {
        this.sessionId = sessionId
        val pending = outbox.load()
        when {
            pending == null -> send(requests.readActive(sessionId))
            pending.operatorSessionId == sessionId -> listOf(Effect.Publish(pending))
            else -> {
                // Never replay a request built under an ended session: reconcile it instead.
                outbox.clear()
                reconcile(pending, sessionId)
            }
        }
    }

    /** Session ended: forget the session and the screen (station status is kept); the outbox is left for recovery. */
    fun stop() {
        step {
            sessionId = null
            state = CageUiState(stationOnline = state.stationOnline)
            emptyList()
        }
    }

    fun onStationOnline(online: Boolean): List<Effect> = step {
        state = state.copy(stationOnline = online)
        emptyList()
    }

    fun onRfid(tag: String): List<Effect> = step {
        val session = sessionId
        if (tag.isBlank() || session == null || !acceptsScans() || UserTagPolicy.isUserTag(tag)) return@step emptyList()
        state = state.copy(notice = null)
        send(requests.select(session, tag.trim()))
    }

    fun onQr(scanned: String): List<Effect> = step {
        val session = sessionId
        if (scanned.isBlank() || session == null || !acceptsScans()) return@step emptyList()
        val screen = state.screen
        if (screen !is CageScreen.Loading) {
            state = state.copy(notice = Notice(SCAN_CAGE_FIRST, Notice.Tone.INFO))
            return@step emptyList()
        }
        state = state.copy(notice = null)
        val qr = PackQr.parse(scanned) ?: return@step refuse(RefusalCatalog.labelNotRecognised())
        send(requests.allocate(session, screen.load.loadId, qr))
    }

    fun onResponse(json: JSONObject): List<Effect> = step {
        // Without a session the answer is ignored and the outbox kept for start() to reconcile.
        if (sessionId == null) return@step emptyList()
        val pending = outbox.load() ?: return@step emptyList()
        if (json.optString("inResponseToMessageId") != pending.messageId) return@step emptyList()

        if (!json.optBoolean("accepted", false)) return@step onRejected(pending, json)

        outbox.clear()
        when (pending.intent) {
            RequestIntent.SELECT -> onSelectAccepted(json)
            RequestIntent.READ_ACTIVE -> onReadActiveAccepted(json)
            RequestIntent.ALLOCATE -> onAllocateAccepted(json)
            RequestIntent.READ_LOAD -> onReadLoadAccepted(json)
            RequestIntent.RECOVER_ALLOCATION -> onRecoverAllocationAccepted(pending, json)
            RequestIntent.RECOVER_COMPLETION -> onRecoverCompletionAccepted(json)
            RequestIntent.CONFIRM -> onConfirmAccepted(json)
        }
    }

    fun onDismissRefusal(): List<Effect> = step {
        val refusal = state.refusal ?: return@step emptyList()
        when (refusal.after) {
            AfterRefusal.TO_IDLE -> {
                state = state.copy(refusal = null, screen = CageScreen.Idle)
                emptyList()
            }
            AfterRefusal.STAY -> {
                state = state.copy(refusal = null)
                emptyList()
            }
            AfterRefusal.REFRESH_LOAD -> refreshShownLoad()
        }
    }

    /** "Keep loading" on the completion summary: back to Loading, nothing is sent. */
    fun onKeepLoading(): List<Effect> = step {
        val preview = state.screen as? CageScreen.Preview
        if (preview != null && !state.sending) {
            state = state.copy(screen = CageScreen.Loading(preview.load, preview.packs, lastPack = null))
        }
        emptyList()
    }

    /** "Complete cage" on the completion summary: sends cage_complete_confirmed with the summary's token. */
    fun onConfirmComplete(): List<Effect> = step {
        val preview = state.screen as? CageScreen.Preview
        val session = sessionId
        if (preview == null || session == null || state.sending || state.refusal != null) return@step emptyList()
        send(requests.confirm(session, preview.load.loadId, preview.token))
    }

    /** The summary's token ran out. Ignored while a confirmation is in flight (its answer decides). */
    fun onPreviewExpired(): List<Effect> = step {
        val preview = state.screen as? CageScreen.Preview
        if (preview != null && !state.sending) {
            state = state.copy(
                screen = CageScreen.Loading(preview.load, preview.packs, lastPack = null),
                notice = Notice(SUMMARY_EXPIRED, Notice.Tone.WARNING),
            )
        }
        emptyList()
    }

    /** "Refresh" on the Completed card: re-reads that load with cage_context_requested { loadId }. */
    fun onRefreshCompleted(): List<Effect> = step {
        val completed = state.screen as? CageScreen.Completed
        val session = sessionId
        if (completed == null || session == null || state.sending) return@step emptyList()
        send(requests.readLoad(session, completed.load.loadId, RequestIntent.READ_LOAD))
    }

    /** Re-publishes the pending request unchanged, only while the session that built it is the live one. */
    fun onRetryTick(sessionId: String?): List<Effect> = step {
        val pending = outbox.load()
        if (sessionId == null || this.sessionId != sessionId || pending == null || pending.operatorSessionId != sessionId) {
            return@step emptyList()
        }
        listOf(Effect.Publish(pending))
    }

    fun pending(): PendingRequest? = outbox.load()

    // ---- responses ------------------------------------------------------------------------

    private fun onRejected(pending: PendingRequest, json: JSONObject): List<Effect> {
        val code = json.optString("errorCode")
        val reason = json.optString("reason")
        if (code in RefusalCatalog.TRANSIENT) return emptyList() // keep the outbox; onRetryTick resends
        outbox.clear()
        if (code == LOGIN_REQUIRED) {
            sessionId = null
            state = CageUiState(stationOnline = state.stationOnline)
            return listOf(Effect.SignOut(SESSION_ENDED))
        }
        if (code == REQUEST_EXPIRED) return onRequestExpired(pending)
        return when (pending.intent) {
            RequestIntent.SELECT -> refuse(RefusalCatalog.forSelection(code, reason))
            RequestIntent.ALLOCATE -> refuse(RefusalCatalog.forAllocation(code, reason, pending.bagId))
            RequestIntent.CONFIRM -> refuse(RefusalCatalog.forCompletion(code, reason))
            RequestIntent.READ_ACTIVE, RequestIntent.READ_LOAD -> {
                if (code == MESSAGE_ID_CONFLICT) return refuse(RefusalCatalog.forSelection(code, reason))
                state = state.copy(screen = CageScreen.Idle)
                emptyList()
            }
            RequestIntent.RECOVER_ALLOCATION, RequestIntent.RECOVER_COMPLETION -> {
                if (code == MESSAGE_ID_CONFLICT) return refuse(RefusalCatalog.forSelection(code, reason))
                state = state.copy(screen = CageScreen.Idle, notice = Notice(RECOVERY_FAILED, Notice.Tone.WARNING))
                emptyList()
            }
        }
    }

    /**
     * Ruling R3: a request first sent within the last five minutes means the handheld's clock is
     * wrong; an older one has an unknown outcome and is reconciled as after sign-in.
     */
    private fun onRequestExpired(pending: PendingRequest): List<Effect> {
        if (nowMs() - pending.firstSentAtMs <= CLOCK_WINDOW_MS) return refuse(CLOCK_REFUSAL)
        val session = sessionId ?: return emptyList()
        return reconcile(pending, session)
    }

    private fun onSelectAccepted(json: JSONObject): List<Effect> {
        if (json.optString("nextAction") == CONFIRM_COMPLETION) return onSelectionPreview(json)
        val load = CageJson.load(json.optJSONObject("load"))
        if (load == null || !load.isOpen) {
            state = state.copy(screen = CageScreen.Idle)
            return emptyList()
        }
        state = state.copy(screen = CageScreen.Loading(load, emptyList(), lastPack = null))
        val session = sessionId ?: return emptyList()
        return send(requests.readActive(session)) // the select answer carries no packs
    }

    /** Rescan of the active cage: the station answers with a completion summary and token. */
    private fun onSelectionPreview(json: JSONObject): List<Effect> {
        val load = CageJson.load(json.optJSONObject("load"))
        if (load == null) {
            state = state.copy(screen = CageScreen.Idle)
            return emptyList()
        }
        val packs = CageJson.packs(json.optJSONArray("packs"))
        val token = json.optString("confirmationToken")
        state = state.copy(
            screen = if (token.isBlank()) {
                CageScreen.Loading(load, packs, lastPack = null) // no token: nothing to confirm with
            } else {
                CageScreen.Preview(load, packs, token, json.optString("confirmationExpiresAtUtc"))
            },
        )
        return emptyList()
    }

    private fun onReadActiveAccepted(json: JSONObject): List<Effect> {
        val load = CageJson.load(json.optJSONObject("load"))
        if (load == null || !json.optBoolean("isActive", false) || !load.isOpen) {
            state = state.copy(screen = CageScreen.Idle)
            return emptyList()
        }
        val current = state.screen
        val lastPack = (current as? CageScreen.Loading)?.takeIf { it.load.loadId == load.loadId }?.lastPack
        val packs = CageJson.packs(json.optJSONArray("packs"))
        state = state.copy(screen = CageScreen.Loading(load, packs, lastPack))
        return emptyList()
    }

    private fun onAllocateAccepted(json: JSONObject): List<Effect> {
        val current = state.screen as? CageScreen.Loading
        val load = CageJson.load(json.optJSONObject("load")) ?: current?.load ?: return listOf(Effect.SuccessFeedback)
        val pack = CageJson.allocatedPack(json.optJSONObject("pack"))
        val previous = current?.takeIf { it.load.loadId == load.loadId }?.packs.orEmpty()
        val packs = if (pack == null) previous else listOf(pack) + previous.filter { it.bagId != pack.bagId }
        state = state.copy(screen = CageScreen.Loading(load, packs, pack))
        return listOf(Effect.SuccessFeedback)
    }

    /** cage_context_requested { loadId }: an open load → Loading, otherwise the Completed card. */
    private fun onReadLoadAccepted(json: JSONObject): List<Effect> {
        val load = CageJson.load(json.optJSONObject("load"))
        state = state.copy(
            screen = when {
                load == null -> CageScreen.Idle
                load.isOpen -> CageScreen.Loading(load, CageJson.packs(json.optJSONArray("packs")), lastPack = null)
                else -> CageScreen.Completed(load)
            },
        )
        return emptyList()
    }

    private fun onRecoverAllocationAccepted(pending: PendingRequest, json: JSONObject): List<Effect> {
        val load = CageJson.load(json.optJSONObject("load"))
        val bagId = pending.bagId
        val notice = when {
            load == null || bagId == null -> Notice(RECOVERY_FAILED, Notice.Tone.WARNING)
            CageJson.packs(json.optJSONArray("packs")).any { it.bagId == bagId } ->
                Notice("$bagId was recorded in ${load.cageId}", Notice.Tone.SUCCESS)
            else -> Notice(
                "Last Pack $bagId was not confirmed. Rescan cage ${load.cageId}, then rescan that Pack.",
                Notice.Tone.WARNING,
            )
        }
        state = state.copy(screen = CageScreen.Idle, notice = notice)
        return emptyList()
    }

    private fun onRecoverCompletionAccepted(json: JSONObject): List<Effect> {
        val load = CageJson.load(json.optJSONObject("load"))
        state = when {
            load == null -> state.copy(screen = CageScreen.Idle, notice = Notice(RECOVERY_FAILED, Notice.Tone.WARNING))
            load.isOpen -> state.copy(
                screen = CageScreen.Idle,
                notice = Notice("Cage ${load.cageId} is still open. Rescan the cage tag to review.", Notice.Tone.WARNING),
            )
            else -> state.copy(screen = CageScreen.Completed(load))
        }
        return emptyList()
    }

    /** CONFIRM accepted, including `alreadyCompleted:true`. */
    private fun onConfirmAccepted(json: JSONObject): List<Effect> {
        val load = CageJson.load(json.optJSONObject("load")) ?: (state.screen as? CageScreen.Preview)?.load
        state = state.copy(screen = if (load == null) CageScreen.Idle else CageScreen.Completed(load))
        return listOf(Effect.SuccessFeedback)
    }

    // ---- recovery -------------------------------------------------------------------------

    /**
     * Replaces a request of uncertain outcome (built under an ended session, or expired long ago)
     * with a read under [session]. The caller has already cleared the outbox.
     */
    private fun reconcile(pending: PendingRequest, session: String): List<Effect> {
        val loadId = pending.loadId
        return when (pending.intent) {
            RequestIntent.ALLOCATE, RequestIntent.RECOVER_ALLOCATION ->
                if (loadId == null) send(requests.readActive(session))
                else send(requests.readLoad(session, loadId, RequestIntent.RECOVER_ALLOCATION, pending.bagId))
            RequestIntent.CONFIRM, RequestIntent.RECOVER_COMPLETION ->
                if (loadId == null) send(requests.readActive(session))
                else send(requests.readLoad(session, loadId, RequestIntent.RECOVER_COMPLETION))
            RequestIntent.SELECT, RequestIntent.READ_ACTIVE, RequestIntent.READ_LOAD ->
                send(requests.readActive(session))
        }
    }

    /** After a REFRESH_LOAD refusal: close any summary and re-read the load the screen was showing. */
    private fun refreshShownLoad(): List<Effect> {
        val screen = state.screen
        val loadId = when (screen) {
            is CageScreen.Loading -> screen.load.loadId
            is CageScreen.Preview -> screen.load.loadId
            is CageScreen.Completed -> screen.load.loadId
            CageScreen.Idle -> null
        }
        state = state.copy(
            refusal = null,
            screen = if (screen is CageScreen.Preview) CageScreen.Loading(screen.load, screen.packs, lastPack = null) else screen,
        )
        val session = sessionId
        if (loadId == null || session == null) return emptyList()
        return send(requests.readLoad(session, loadId, RequestIntent.READ_LOAD))
    }

    // ---- helpers --------------------------------------------------------------------------

    private fun acceptsScans(): Boolean {
        val s = state
        return !s.sending && s.refusal == null && s.stationOnline && s.screen !is CageScreen.Preview
    }

    /** Saves [request] to the outbox, then returns its Publish effect. */
    private fun send(request: PendingRequest): List<Effect> {
        outbox.save(request)
        return listOf(Effect.Publish(request))
    }

    private fun refuse(refusal: Refusal): List<Effect> {
        state = state.copy(refusal = refusal)
        return listOf(Effect.ErrorFeedback)
    }

    /** Runs a public call and re-derives `sending` from the outbox afterwards. */
    private inline fun step(block: () -> List<Effect>): List<Effect> {
        val effects = block()
        state = state.copy(sending = outbox.load() != null)
        return effects
    }

    private companion object {
        const val SCAN_CAGE_FIRST = "Scan a cage tag first"
        const val SESSION_ENDED = "Your session ended. Sign in and rescan the cage."
        const val LOGIN_REQUIRED = "login_required"
        const val REQUEST_EXPIRED = "request_expired"
        const val MESSAGE_ID_CONFLICT = "message_id_conflict"
        const val CONFIRM_COMPLETION = "confirm_completion"
        const val SUMMARY_EXPIRED = "Summary expired — rescan the cage tag"
        const val RECOVERY_FAILED = "Couldn't confirm the last scan. Rescan the cage tag."
        const val CLOCK_WINDOW_MS = 300_000L
        val CLOCK_REFUSAL = Refusal(
            "Check the handheld's clock",
            "The handheld's time is wrong. Ask a supervisor to correct it, then scan again.",
            REQUEST_EXPIRED,
            AfterRefusal.STAY,
        )
    }
}
