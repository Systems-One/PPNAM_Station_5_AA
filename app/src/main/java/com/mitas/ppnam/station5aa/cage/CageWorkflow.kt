package com.mitas.ppnam.station5aa.cage

import com.mitas.ppnam.station5aa.UserTagPolicy
import org.json.JSONObject

sealed interface CageScreen {
    data object Idle : CageScreen
    /**
     * An open load. [armExpiresAtMs] (device clock, anchored at receipt) is non-null while one Pack
     * is armed by the last cage-tag scan; null means the next Pack needs a fresh cage tag.
     */
    data class Loading(
        val load: CageLoad,
        val packs: List<LoadedPack>,
        val lastPack: LoadedPack?,
        val armExpiresAtMs: Long? = null,
    ) : CageScreen
    /** Completion summary; [expiresAtMs] is the token's expiry on the device clock, measured with [StationClock]. */
    data class Preview(val load: CageLoad, val packs: List<LoadedPack>, val token: String, val expiresAtMs: Long) : CageScreen
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

    /** The last scan sent (trimmed): a repeat read of it while "Sending…" is the same scan (S3). */
    private var lastScan: String? = null

    /** The last value refused on the handheld and when (device clock), to quiet its repeat reads (S3). */
    private var lastLocalRefusal: Pair<String, Long>? = null

    fun start(sessionId: String): List<Effect> = step {
        this.sessionId = sessionId
        forgetScans()
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
            forgetScans()
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
        if (tag.isBlank() || session == null || UserTagPolicy.isUserTag(tag)) return@step emptyList()
        val value = tag.trim()
        if (!acceptsScans()) return@step scanNotTaken(value)
        state = state.copy(notice = null)
        lastScan = value
        send(requests.select(session, value))
    }

    fun onQr(scanned: String): List<Effect> = step {
        val session = sessionId
        if (scanned.isBlank() || session == null) return@step emptyList()
        val value = scanned.trim()
        if (!acceptsScans()) return@step scanNotTaken(value)
        val screen = state.screen
        if (screen !is CageScreen.Loading || !isArmed(screen)) {
            // One cage tag arms exactly one Pack: a QR without a live arm is never sent. It is a
            // full-screen stop (not a small notice) so the operator can't take it for an accepted Pack.
            if (screen is CageScreen.Loading) state = state.copy(screen = screen.copy(armExpiresAtMs = null))
            state = state.copy(notice = null)
            return@step refuseLocally(value, RefusalCatalog.scanCageTagFirst())
        }
        state = state.copy(notice = null)
        // A malformed label is refused locally; nothing is sent, so the station's arm is unspent.
        val qr = PackQr.parse(scanned) ?: return@step refuseLocally(value, RefusalCatalog.labelNotRecognised())
        lastScan = value
        send(requests.allocate(session, screen.load.loadId, qr))
    }

    fun onResponse(json: JSONObject): List<Effect> = step {
        // Without a session the answer is ignored and the outbox kept for start() to reconcile.
        if (sessionId == null) return@step emptyList()
        val pending = outbox.load() ?: return@step emptyList()
        if (json.optString("inResponseToMessageId") != pending.messageId) return@step emptyList()

        // "Sending…" ends with this reply, so its "wait" warning would now contradict the screen.
        if (state.notice?.text in SENDING_NOTICES) state = state.copy(notice = null)

        if (!json.optBoolean("accepted", false)) return@step onRejected(pending, json)

        outbox.clear()
        when (pending.intent) {
            RequestIntent.SELECT -> onSelectAccepted(json)
            RequestIntent.READ_ACTIVE -> onReadActiveAccepted(json)
            RequestIntent.ALLOCATE -> onAllocateAccepted(pending, json)
            RequestIntent.READ_LOAD -> onReadLoadAccepted(json)
            RequestIntent.RECOVER_ALLOCATION -> onRecoverAllocationAccepted(pending, json)
            RequestIntent.RECOVER_COMPLETION -> onRecoverCompletionAccepted(json)
            RequestIntent.PREVIEW -> onPreviewAccepted(json)
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
            AfterRefusal.REFRESH_LOAD -> {
                val effects = refreshShownLoad()
                if (refusal.code == CONTENTS_CHANGED) state = state.copy(notice = Notice(NEW_COUNT, Notice.Tone.INFO))
                effects
            }
        }
    }

    /** "Keep loading" on the completion summary: back to Loading (not armed), nothing is sent. */
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

    /**
     * "Complete cage" on the Loading screen: sends cage_complete_requested { loadId } for an open
     * load with at least one Pack. Arming is not required; the station discards any unused arm.
     */
    fun onCompleteCage(): List<Effect> = step {
        val loading = state.screen as? CageScreen.Loading
        val session = sessionId
        if (loading == null || session == null || state.sending || state.refusal != null) return@step emptyList()
        if (!loading.load.isOpen || loading.load.packCount < 1) return@step emptyList()
        state = state.copy(notice = null)
        send(requests.preview(session, loading.load.loadId))
    }

    /** The arm's countdown reached zero: disarm locally; nothing is sent. */
    fun onArmExpired(): List<Effect> = step {
        val loading = state.screen as? CageScreen.Loading
        if (loading?.armExpiresAtMs != null) {
            state = state.copy(
                screen = loading.copy(armExpiresAtMs = null),
                notice = Notice(TAG_EXPIRED, Notice.Tone.WARNING),
            )
        }
        emptyList()
    }

    fun pending(): PendingRequest? = outbox.load()

    // ---- responses ------------------------------------------------------------------------

    private fun onRejected(pending: PendingRequest, json: JSONObject): List<Effect> {
        val code = json.optString("errorCode")
        val reason = json.optString("reason")
        if (code in RefusalCatalog.TRANSIENT) {
            // Keep the outbox; onRetryTick resends. Say why "Sending…" goes on.
            val text = if (code == "processing_paused") STATION_PAUSED else STATION_BUSY
            state = state.copy(notice = Notice(text, Notice.Tone.WARNING))
            return emptyList()
        }
        outbox.clear()
        // Suite rule S1: only a scan's own request sounds; Complete cage and the reads never do.
        val scan = isScan(pending)
        if (code == LOGIN_REQUIRED) {
            sessionId = null
            forgetScans()
            state = CageUiState(stationOnline = state.stationOnline)
            return failIf(scan) + Effect.SignOut(SESSION_ENDED)
        }
        if (code == REQUEST_EXPIRED) return onRequestExpired(pending, scan)
        if (code == MESSAGE_ID_CONFLICT) {
            // Stop retrying and give support the message ID to trace.
            val r = RefusalCatalog.forSelection(code, reason)
            return refuse(r.copy(detail = "${r.detail} Ref: ${pending.messageId}"), sound = scan)
        }
        return when (pending.intent) {
            RequestIntent.SELECT -> refuse(RefusalCatalog.forSelection(code, reason))
            RequestIntent.ALLOCATE -> refuse(RefusalCatalog.forAllocation(code, reason, pending.bagId))
            RequestIntent.PREVIEW, RequestIntent.CONFIRM ->
                refuse(RefusalCatalog.forCompletion(code, reason), sound = false)
            RequestIntent.READ_LOAD -> {
                // The refresh after an accepted Pack: keep what is shown rather than dropping the cage.
                val shown = state.screen as? CageScreen.Loading
                if (!isPackRefresh(pending) || shown?.load?.loadId != pending.loadId) {
                    state = state.copy(screen = CageScreen.Idle, notice = null)
                }
                emptyList()
            }
            RequestIntent.READ_ACTIVE -> {
                state = state.copy(screen = CageScreen.Idle, notice = null)
                emptyList()
            }
            RequestIntent.RECOVER_ALLOCATION, RequestIntent.RECOVER_COMPLETION -> {
                state = state.copy(screen = CageScreen.Idle, notice = Notice(RECOVERY_FAILED, Notice.Tone.WARNING))
                emptyList()
            }
        }
    }

    /**
     * Ruling R3: a request first sent within the last five minutes means the handheld's clock is
     * wrong; an older one has an unknown outcome and is reconciled as after sign-in.
     */
    private fun onRequestExpired(pending: PendingRequest, scan: Boolean): List<Effect> {
        if (nowMs() - pending.firstSentAtMs <= CLOCK_WINDOW_MS) return refuse(CLOCK_REFUSAL, sound = scan)
        // The scan's request timed out, so the scan fails; the reconciling read that follows is silent.
        val session = sessionId ?: return failIf(scan)
        return failIf(scan) + reconcile(pending, session)
    }

    /**
     * cage_select_requested accepted. `requiresCageScan:false` arms exactly one Pack until
     * `cageScanExpiresAtUtc` (station clock); anything else shows the cage without arming. Never
     * previews, and never reads context afterwards (a read would cancel the arm on the station).
     */
    private fun onSelectAccepted(json: JSONObject): List<Effect> {
        val load = CageJson.load(json.optJSONObject("load"))
        if (load == null || !load.isOpen) {
            // Nothing to load into: the scan selected no cage.
            state = state.copy(screen = CageScreen.Idle)
            return listOf(Effect.ErrorFeedback)
        }
        // The select answer carries no packs: keep what is known for the same load.
        val same = (state.screen as? CageScreen.Loading)?.takeIf { it.load.loadId == load.loadId }
        val armed = !json.optBoolean("requiresCageScan", true)
        val armExpiresAtMs = if (armed) nowMs() + stationRemainingMs(json, "cageScanExpiresAtUtc") else null
        state = state.copy(
            screen = CageScreen.Loading(load, same?.packs.orEmpty(), same?.lastPack, armExpiresAtMs),
        )
        return listOf(Effect.SuccessFeedback)
    }

    /** cage_complete_requested accepted: show the summary with a station-clock countdown. */
    private fun onPreviewAccepted(json: JSONObject): List<Effect> {
        val shown = state.screen as? CageScreen.Loading
        val load = CageJson.load(json.optJSONObject("load")) ?: shown?.load
        if (load == null) {
            state = state.copy(screen = CageScreen.Idle)
            return emptyList()
        }
        val packs = if (json.has("packs")) CageJson.packs(json.optJSONArray("packs")) else shown?.packs.orEmpty()
        val token = json.optString("confirmationToken")
        state = state.copy(
            screen = if (json.optString("nextAction") != CONFIRM_COMPLETION || token.isBlank()) {
                CageScreen.Loading(load, packs, shown?.lastPack) // no token: nothing to confirm with
            } else {
                CageScreen.Preview(load, packs, token, nowMs() + stationRemainingMs(json, "confirmationExpiresAtUtc"))
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

    /**
     * An accepted allocation. A Pack already in this load (idempotent rescan: its bagId is in the
     * shown list, the station's packCount did not rise, or `alreadyAllocated:true`) is not a new
     * placement: an INFO notice, and the last-Pack card is left as it was. The station still
     * accepted the scan, so it sounds success like a new Pack (suite rule S1).
     */
    private fun onAllocateAccepted(pending: PendingRequest, json: JSONObject): List<Effect> {
        val current = state.screen as? CageScreen.Loading
        val load = CageJson.load(json.optJSONObject("load")) ?: current?.load ?: return listOf(Effect.SuccessFeedback)
        val pack = CageJson.allocatedPack(json.optJSONObject("pack"))
        val same = current?.takeIf { it.load.loadId == load.loadId }
        val previous = same?.packs.orEmpty()
        val bagId = pack?.bagId ?: pending.bagId
        val already = json.optBoolean("alreadyAllocated", false) ||
            (bagId != null && previous.any { it.bagId == bagId }) ||
            (same != null && load.packCount <= same.load.packCount)
        val packs = when {
            pack == null -> previous
            previous.any { it.bagId == pack.bagId } -> previous // already listed: keep its place
            else -> listOf(pack) + previous
        }
        // Every allocation reply spends the arm; the screen is left not armed.
        val feedback = if (already) {
            state = state.copy(
                screen = CageScreen.Loading(load, packs, same?.lastPack),
                notice = Notice("${bagId ?: "This Pack"} is already in this cage.", Notice.Tone.INFO),
            )
            listOf(Effect.SuccessFeedback)
        } else {
            state = state.copy(screen = CageScreen.Loading(load, packs, pack))
            listOf(Effect.SuccessFeedback)
        }
        val session = sessionId ?: return feedback
        // Between cycles a context read is allowed: refresh the list from the station.
        return feedback + send(requests.readLoad(session, load.loadId, RequestIntent.READ_LOAD, PACK_REFRESH))
    }

    /**
     * cage_context_requested { loadId }: an open load → Loading (not armed; the last Pack is kept for
     * the same load), otherwise the Completed card. A notice only survives on the Loading screen.
     */
    private fun onReadLoadAccepted(json: JSONObject): List<Effect> {
        val load = CageJson.load(json.optJSONObject("load"))
        val lastPack = (state.screen as? CageScreen.Loading)?.takeIf { it.load.loadId == load?.loadId }?.lastPack
        state = when {
            load == null -> state.copy(screen = CageScreen.Idle, notice = null)
            load.isOpen -> state.copy(screen = CageScreen.Loading(load, CageJson.packs(json.optJSONArray("packs")), lastPack))
            else -> state.copy(screen = CageScreen.Completed(load), notice = null)
        }
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

    /** CONFIRM accepted, including `alreadyCompleted:true`. A button tap, not a scan: no sound (S1). */
    private fun onConfirmAccepted(json: JSONObject): List<Effect> {
        val load = CageJson.load(json.optJSONObject("load")) ?: (state.screen as? CageScreen.Preview)?.load
        state = state.copy(screen = if (load == null) CageScreen.Idle else CageScreen.Completed(load))
        return emptyList()
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
            RequestIntent.SELECT, RequestIntent.READ_ACTIVE, RequestIntent.READ_LOAD, RequestIntent.PREVIEW ->
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

    /**
     * A scan that can't be taken (suite rules S1 and S3). Silent while a refusal or the completion
     * summary is up (those screens take no scans), for a repeat read of the scan in flight, and for
     * a repeat of a value refused here within [REPEAT_WINDOW_MS]. Otherwise it is refused locally:
     * fail once, plus the "wait" notice when it is "Sending…" alone that blocks it.
     */
    private fun scanNotTaken(value: String): List<Effect> {
        val s = state
        if (s.refusal != null || s.screen is CageScreen.Preview) return emptyList()
        if (s.sending && value == lastScan) return emptyList()
        if (isRepeatOfLocalRefusal(value)) return emptyList()
        lastLocalRefusal = value to nowMs()
        if (s.sending && s.stationOnline) state = s.copy(notice = Notice(WAIT_SENDING, Notice.Tone.WARNING))
        return listOf(Effect.ErrorFeedback)
    }

    /** A refusal decided on the handheld; a repeat read of the same value within the window is silent (S3). */
    private fun refuseLocally(value: String, refusal: Refusal): List<Effect> {
        val repeat = isRepeatOfLocalRefusal(value)
        lastLocalRefusal = value to nowMs()
        return refuse(refusal, sound = !repeat)
    }

    private fun isRepeatOfLocalRefusal(value: String): Boolean {
        val (refused, at) = lastLocalRefusal ?: return false
        return refused == value && nowMs() - at < REPEAT_WINDOW_MS
    }

    private fun forgetScans() {
        lastScan = null
        lastLocalRefusal = null
    }

    /** Requests a scan sent, as opposed to reads, recovery and the Complete cage buttons. */
    private fun isScan(pending: PendingRequest): Boolean =
        pending.intent == RequestIntent.SELECT || pending.intent == RequestIntent.ALLOCATE

    private fun failIf(scan: Boolean): List<Effect> = if (scan) listOf(Effect.ErrorFeedback) else emptyList()

    private fun isArmed(screen: CageScreen.Loading): Boolean {
        val until = screen.armExpiresAtMs ?: return false
        return nowMs() < until
    }

    /** The READ_LOAD sent after an accepted Pack carries the local-only [PACK_REFRESH] marker in bagId (never sent). */
    private fun isPackRefresh(pending: PendingRequest): Boolean = pending.bagId == PACK_REFRESH

    /** Remaining time of [expiryField] by the station's clock; two minutes when unreadable (the station still enforces it). */
    private fun stationRemainingMs(json: JSONObject, expiryField: String): Long {
        val serverNow = stringOrNull(json, "serverSentAtUtc") ?: stringOrNull(json, "timestampUtc")
        return StationClock.remainingMs(stringOrNull(json, expiryField), serverNow) ?: DEFAULT_WINDOW_MS
    }

    private fun stringOrNull(json: JSONObject, name: String): String? =
        if (!json.has(name) || json.isNull(name)) null else json.optString(name)

    /**
     * Saves [request] to the outbox, then returns its Publish effect. Any request spends an unused
     * arm on the station (a new select replaces it, a read/preview/confirm cancels it, an allocation
     * consumes it), so the shown arm is dropped as it is sent.
     */
    private fun send(request: PendingRequest): List<Effect> {
        val screen = state.screen
        if (screen is CageScreen.Loading && screen.armExpiresAtMs != null) {
            state = state.copy(screen = screen.copy(armExpiresAtMs = null))
        }
        outbox.save(request)
        return listOf(Effect.Publish(request))
    }

    /** Shows [refusal]; it sounds fail when it answers a scan ([sound]), never for a button tap. */
    private fun refuse(refusal: Refusal, sound: Boolean = true): List<Effect> {
        state = state.copy(refusal = refusal)
        return failIf(sound)
    }

    /** Runs a public call and re-derives `sending` from the outbox afterwards. */
    private inline fun step(block: () -> List<Effect>): List<Effect> {
        val effects = block()
        state = state.copy(sending = outbox.load() != null)
        return effects
    }

    private companion object {
        const val TAG_EXPIRED = "Cage tag expired — scan it again"
        const val NEW_COUNT = "Tap Complete cage again to see the new count."
        const val CONTENTS_CHANGED = "cage_contents_changed"
        const val PACK_REFRESH = "pack-refresh"
        const val DEFAULT_WINDOW_MS = 120_000L
        const val SESSION_ENDED = "Your session ended. Sign in and rescan the cage."
        const val LOGIN_REQUIRED = "login_required"
        const val REQUEST_EXPIRED = "request_expired"
        const val MESSAGE_ID_CONFLICT = "message_id_conflict"
        const val CONFIRM_COMPLETION = "confirm_completion"
        const val SUMMARY_EXPIRED = "Summary expired — tap Complete cage again"
        const val WAIT_SENDING = "Wait for Sending… to finish, then scan again."
        // Why "Sending…" keeps going after a transient refusal (skill-run critique 2026-11-06, P2-7).
        const val STATION_PAUSED = "Station 5 is paused — retrying…"
        const val STATION_BUSY = "Station 5 is busy — retrying…"
        /** Notices about the request in flight: stale once that request is answered. */
        val SENDING_NOTICES = setOf(WAIT_SENDING, STATION_PAUSED, STATION_BUSY)
        const val RECOVERY_FAILED = "Couldn't confirm the last scan. Rescan the cage tag."
        const val CLOCK_WINDOW_MS = 300_000L
        /** Suite rule S3: a repeat of a value refused here within this window is the same scan. */
        const val REPEAT_WINDOW_MS = 1_500L
        val CLOCK_REFUSAL = Refusal(
            "Check the handheld's clock",
            "The handheld's time is wrong. Ask a supervisor to correct it, then scan again.",
            REQUEST_EXPIRED,
            AfterRefusal.STAY,
        )
    }
}
