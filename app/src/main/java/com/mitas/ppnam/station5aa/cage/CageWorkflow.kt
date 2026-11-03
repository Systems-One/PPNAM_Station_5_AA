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
            // TODO(Task 6): post-sign-in recovery of a pending request replaces this branch.
            pending.operatorSessionId == sessionId -> listOf(Effect.Publish(pending))
            else -> {
                outbox.clear()
                send(requests.readActive(sessionId))
            }
        }
    }

    /** Session ended: forget the session and the screen; the outbox is left for recovery. */
    fun stop() {
        step {
            sessionId = null
            state = CageUiState()
            emptyList()
        }
    }

    fun onStationOnline(online: Boolean): List<Effect> = step {
        state = state.copy(stationOnline = online)
        emptyList()
    }

    fun onRfid(tag: String): List<Effect> = step {
        val session = sessionId
        if (session == null || !acceptsScans() || UserTagPolicy.isUserTag(tag)) return@step emptyList()
        state = state.copy(notice = null)
        send(requests.select(session, tag.trim()))
    }

    fun onQr(scanned: String): List<Effect> = step {
        val session = sessionId
        if (session == null || !acceptsScans()) return@step emptyList()
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
        val pending = outbox.load() ?: return@step emptyList()
        if (json.optString("inResponseToMessageId") != pending.messageId) return@step emptyList()

        if (!json.optBoolean("accepted", false)) return@step onRejected(pending, json)

        outbox.clear()
        when (pending.intent) {
            RequestIntent.SELECT -> onSelectAccepted(json)
            RequestIntent.READ_ACTIVE -> onReadActiveAccepted(json)
            RequestIntent.ALLOCATE -> onAllocateAccepted(json)
            RequestIntent.READ_LOAD,
            RequestIntent.RECOVER_ALLOCATION,
            RequestIntent.RECOVER_COMPLETION,
            RequestIntent.CONFIRM -> onTask6Accepted(pending, json)
        }
    }

    fun onDismissRefusal(): List<Effect> = step {
        val refusal = state.refusal ?: return@step emptyList()
        state = when (refusal.after) {
            AfterRefusal.TO_IDLE -> state.copy(refusal = null, screen = CageScreen.Idle)
            AfterRefusal.STAY -> state.copy(refusal = null)
            // TODO(Task 6): re-read the load with cage_context_requested { loadId }.
            AfterRefusal.REFRESH_LOAD -> state.copy(refusal = null)
        }
        emptyList()
    }

    // TODO(Task 6): completion preview handling.
    fun onKeepLoading(): List<Effect> = step { emptyList() }

    // TODO(Task 6): send cage_complete_confirmed.
    fun onConfirmComplete(): List<Effect> = step { emptyList() }

    // TODO(Task 6): close the preview with "Summary expired".
    fun onPreviewExpired(): List<Effect> = step { emptyList() }

    // TODO(Task 6): identical-byte retries of the pending request.
    @Suppress("UNUSED_PARAMETER")
    fun onRetryTick(sessionId: String?): List<Effect> = step { emptyList() }

    fun pending(): PendingRequest? = outbox.load()

    // ---- responses ------------------------------------------------------------------------

    private fun onRejected(pending: PendingRequest, json: JSONObject): List<Effect> {
        val code = json.optString("errorCode")
        val reason = json.optString("reason")
        if (code in RefusalCatalog.TRANSIENT) return emptyList() // keep the outbox; Task 6 retries
        outbox.clear()
        if (code == LOGIN_REQUIRED) {
            sessionId = null
            state = CageUiState(stationOnline = state.stationOnline)
            return listOf(Effect.SignOut(SESSION_ENDED))
        }
        return when (pending.intent) {
            RequestIntent.SELECT -> refuse(RefusalCatalog.forSelection(code, reason))
            RequestIntent.ALLOCATE -> refuse(RefusalCatalog.forAllocation(code, reason, pending.bagId))
            RequestIntent.READ_ACTIVE, RequestIntent.READ_LOAD -> {
                state = state.copy(screen = CageScreen.Idle)
                emptyList()
            }
            RequestIntent.RECOVER_ALLOCATION,
            RequestIntent.RECOVER_COMPLETION,
            RequestIntent.CONFIRM -> onTask6Rejected(pending, code, reason)
        }
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

    // ---- Task 6 hooks ---------------------------------------------------------------------

    /** TODO(Task 6): SELECT answered with `nextAction:"confirm_completion"` → Preview. */
    @Suppress("UNUSED_PARAMETER")
    private fun onSelectionPreview(json: JSONObject): List<Effect> = emptyList()

    /** TODO(Task 6): accepted READ_LOAD / RECOVER_* / CONFIRM answers. */
    @Suppress("UNUSED_PARAMETER")
    private fun onTask6Accepted(pending: PendingRequest, json: JSONObject): List<Effect> = emptyList()

    /** TODO(Task 6): rejected RECOVER_* / CONFIRM answers (CONFIRM → RefusalCatalog.forCompletion). */
    @Suppress("UNUSED_PARAMETER")
    private fun onTask6Rejected(pending: PendingRequest, code: String, reason: String): List<Effect> = emptyList()

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
        const val CONFIRM_COMPLETION = "confirm_completion"
    }
}
