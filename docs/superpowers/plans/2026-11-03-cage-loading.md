# Cage Loading (Packs Rev 2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the `cage_loading` workflow (select cage → allocate Packs → confirm completion) to the Station 5 handheld app, aligned to the single wire contract `PPNAM-Station-5/MQTT_CONTRACT.md`.

**Architecture:** All protocol and workflow logic lives in a new pure-Kotlin package `com.mitas.ppnam.station5aa.cage` (no Android imports, JVM-tested with `org.json`): wire models/requests, a one-entry durable outbox, a refusal catalogue, and a `CageWorkflow` state machine that turns scans/responses into a `CageUiState` plus a list of `Effect`s. A thin Android layer (`CageController` app singleton + `CageTransport` + MainActivity UI) publishes effects over the existing `MqttManager` and renders state.

**Tech Stack:** Kotlin 2.0, Android Views + ViewBinding, Material 1.12, HiveMQ MQTT 3 client, JUnit 4 + `org.json:json:20240303`.

**Spec:** `docs/superpowers/specs/2026-11-03-cage-loading-design.md` (read it; this plan argues from it). Wire contract: `../PPNAM-Station-5/MQTT_CONTRACT.md`. Desktop reference implementation: `../PPNAM-Station-5/ppnam_station5_packs/ppnam_station5_packs.Infrastructure/Packs/HandheldMqttService.cs` (lines 166-286).

## Global Constraints

- Topics: requests `PPNAM/station_5/{deviceId}/req/{type}`, responses `PPNAM/station_5/{deviceId}/res/{type}`, station status `PPNAM/station_5/status` (raw `online`/`offline`, retained), device presence `PPNAM/station_5/{deviceId}` (raw, retained, QoS 2).
- Workflow requests: QoS 1 (`MqttQos.AT_LEAST_ONCE`), retain false, ≤16,384 UTF-8 bytes, `schemaVersion` `"4.1"`, `timestampUtc` via `Schema41.timestampUtc()` (exactly six fractional digits), `messageId` ≤100 chars, `operatorSessionId` on every workflow request.
- A retry re-publishes the **identical stored body string** on the same topic. Never rebuild a body for a retry.
- Only `accepted:true` advances the UI. Pack count and kg shown are always the station's `load.packCount` / `load.totalKg`.
- The app never invents a `loadId` and never selects a cage the operator didn't scan; completion is never auto-confirmed.
- Rejections arrive on the **same** response topic (`accepted:false`, `errorCode`, `reason`); only unknown request types reply on `res/request_rejected`. Rejections carry no load data.
- Response field names (camelCase, from the desktop records): `load{loadId,cageId,status,packCount,totalKg,openedAtUtc,completedAtUtc,grvState,grvReference,printState,error}`, `packs[{bagId,outerCode,itemCode,itemName,jobCard,weightKg,disposition,allocatedBy,allocatedAtUtc}]`, allocation `pack{bagId,outerCode,productionOrderNumber,itemCode,itemName,actualKg,disposition}`, preview `confirmationToken`, `confirmationExpiresAtUtc`; `cage_context_result` also `isActive`; completion may carry `alreadyCompleted:true`.
- `cage_select_requested` returns `load` but **no `packs`**; the workflow follows an accepted select with `cage_context_requested` (no `loadId`) to fetch `packs`.
- Copy: operator-facing wording exactly as in spec §4.4/§4.5 tables (builder may polish only where the spec says so).
- Colours from `res/values/colors.xml`: banner `station_field`, refusal `danger` on `light_red`, success `success` on `light_green`, notice `warning` on `light_amber`.
- Views + ViewBinding only (no Compose). Portrait. Version `1.3.0`, `versionCode = 6`.
- Unit test command: `./gradlew --no-daemon testDebugUnitTest` (from repo root, Git Bash). Build command: `./gradlew --no-daemon assembleDebug`.

## Review Focus

1. **Double scan while sending** — a second QR/RFID during an in-flight request must be ignored, never queued or sent (test in Task 5: `qrWhileSendingIsIgnored`).
2. **Duplicate/late response** — a replayed response (`isDuplicate:true`) or a response for an older messageId must not double-apply or overwrite newer state (Task 5: `staleResponseIgnored`, Task 7 transport dedupe by response `messageId`).
3. **Retry after session change** — a pending entry from a previous session is never re-published (Task 6: `pendingFromOldSessionIsNotResent`).
4. **Badge tag read on the cage screen** — RFID with prefix `505055` must not select a cage (Task 5: `badgeTagIgnored`).
5. **QR with surrounding whitespace/CR from the scanner** — trim before the three-field check, send the trimmed value (Task 2: `qrIsTrimmed`).

---

### Task 1: Align the existing shell with the contract

**Files:**
- Modify: `app/src/main/java/com/mitas/ppnam/station5aa/MqttTopics.kt` (header comment, `stationPresence`, remove `stationWildcard`/`stationBroadcast`)
- Modify: `app/src/main/java/com/mitas/ppnam/station5aa/MqttManager.kt:186-188, 218-266` (subscriptions, status parsing)
- Modify: `app/src/main/java/com/mitas/ppnam/station5aa/AuthClient.kt:146-171` (session parse moved out)
- Create: `app/src/main/java/com/mitas/ppnam/station5aa/OperatorSessionCodec.kt`
- Modify: `app/src/main/java/com/mitas/ppnam/station5aa/OperatorSession.kt` (`StationTab.CAGE_LOADING`, comments)
- Modify (citations only): `Schema41.kt`, `LoginActivity.kt`, `OperatorDirectory.kt`, `AuthClient.kt`, tests `Schema41Test.kt`, `OperatorListCodecTest.kt`, `OperatorSessionTest.kt`, `MqttTopicsTest.kt`
- Test: `app/src/test/java/com/mitas/ppnam/station5aa/MqttTopicsTest.kt`, `OperatorSessionCodecTest.kt` (new)

**Interfaces:**
- Produces: `MqttTopics.stationStatus(): String` = `"PPNAM/station_5/status"`; `MqttTopics.deviceResponseWildcard(deviceId): String` = `"PPNAM/station_5/{deviceId}/res/+"`; `StationTab.CAGE_LOADING = "cage_loading"`; `OperatorSessionCodec.fromProofResult(json: JSONObject): Result<OperatorSession>`; `MqttManager.parseStationStatus(payload: String): Boolean?` (companion, pure).

- [ ] **Step 1: Write failing tests**

In `MqttTopicsTest`: `stationStatusIsStatusSubtopic()` asserts `MqttTopics.stationStatus() == "PPNAM/station_5/status"`; `deviceResponseWildcard()` asserts `"PPNAM/station_5/scanner_ab12/res/+"` for `"scanner_ab12"`. Delete tests for removed functions.

New `OperatorSessionCodecTest`:
```kotlin
@Test fun readsOperatorNameAndTabs() {
    val json = JSONObject("""{"operatorSessionId":"s-1","operatorName":"Thabo M","role":"Operator","allowedTabs":["cage_loading"]}""")
    val s = OperatorSessionCodec.fromProofResult(json).getOrThrow()
    assertEquals("Thabo M", s.operatorName); assertTrue(s.canShow(StationTab.CAGE_LOADING))
}
@Test fun fallsBackToDisplayName() // {"operatorSessionId":"s","displayName":"X"} -> operatorName "X"
@Test fun blankSessionFails()      // {"operatorName":"X"} -> isFailure
@Test fun closedSessionFails()     // sessionState "closed" -> isFailure
```
Add to a new `StationStatusTest` (or `MqttTopicsTest`): `parseStationStatus(" Online\n") == true`, `("offline") == false`, `("{\"state\":\"online\"}") == null`.

- [ ] **Step 2: Run** `./gradlew --no-daemon testDebugUnitTest` — Expected: compile FAIL (missing symbols).

- [ ] **Step 3: Implement**
  - `MqttTopics`: `stationStatus()`, `deviceResponseWildcard(deviceId)`; header comment cites "MQTT_CONTRACT.md §1 (`../PPNAM-Station-5/MQTT_CONTRACT.md`, 2 Nov 2026)". Keep `stationPresence()` deleted; update callers.
  - `MqttManager`: on connect, subscribe to exactly `deviceResponseWildcard(ownId)` and `stationStatus()` (QoS 1), plus own presence node `devicePresence(ownId)` for `PresenceSelfHeal` — the broker ACL may refuse that last one; log and continue on its SUBACK failure. Station status uses `parseStationStatus` (trim + case-insensitive `online`/`offline`; anything else → `null`, ignored). Update the `subscribe()` KDoc (no global `PPNAM/#`).
  - `OperatorSessionCodec.fromProofResult`: the logic currently in `AuthClient.buildSession`, reading `operatorName` (fallback `displayName`). `AuthClient.buildSession` calls it then `OperatorSessionHolder.set`.
  - `StationTab`: `const val CAGE_LOADING = "cage_loading"`; update KDoc (Station 5 defines one tab).
  - Replace every "contract v3", "v3.2.0 §4.5", "Station1_MQTT_Contract", "2026-08-17 restructure" citation with the matching `MQTT_CONTRACT §n` (auth = §3, topics = §1, envelope = §2). For the operator directory say: "not yet in MQTT_CONTRACT; Phase 2 (spec §11) adds it. Until then the station rejects the request and the cached list / typed username is used." `OperatorDirectory.refresh` already ignores failures — verify, don't change behaviour.

- [ ] **Step 4: Run** `./gradlew --no-daemon testDebugUnitTest` — Expected: PASS (all existing + new).

- [ ] **Step 5: Commit** `git commit -am "fix: align topics, station status and login parsing with MQTT_CONTRACT (2 Nov 2026)"` (add new files first).

---

### Task 2: Wire layer — models, Pack QR, request builders

**Files:**
- Create: `app/src/main/java/com/mitas/ppnam/station5aa/cage/CageModels.kt`
- Create: `app/src/main/java/com/mitas/ppnam/station5aa/cage/PackQr.kt`
- Create: `app/src/main/java/com/mitas/ppnam/station5aa/cage/CageRequests.kt`
- Test: `app/src/test/java/com/mitas/ppnam/station5aa/cage/CageModelsTest.kt`, `PackQrTest.kt`, `CageRequestsTest.kt`

**Interfaces:**
- Consumes: `Schema41.timestampUtc(Instant)`, `Schema41.SCHEMA_VERSION`, `MqttTopics.deviceRequest/deviceResponse`.
- Produces:
```kotlin
data class CageLoad(val loadId: String, val cageId: String, val status: String, val packCount: Int,
    val totalKg: BigDecimal, val completedAtUtc: String?, val grvState: String, val grvReference: String?,
    val printState: String, val error: String?) { val isOpen: Boolean get() = status == "Open" }
data class LoadedPack(val bagId: String, val outerCode: String, val itemCode: String, val itemName: String,
    val jobCard: String?, val weightKg: BigDecimal, val disposition: String)
object CageJson {
    fun load(o: JSONObject?): CageLoad?          // null / JSONObject.NULL -> null
    fun packs(a: JSONArray?): List<LoadedPack>   // `packs` snapshot (weightKg, jobCard)
    fun allocatedPack(o: JSONObject?): LoadedPack? // allocation `pack` (actualKg, productionOrderNumber -> jobCard)
}
data class PackQr(val raw: String, val productionOrder: String, val outerCode: String, val bagId: String) {
    companion object { fun parse(scanned: String): PackQr? }
}
enum class RequestIntent { SELECT, READ_ACTIVE, READ_LOAD, RECOVER_ALLOCATION, RECOVER_COMPLETION, ALLOCATE, CONFIRM }
data class PendingRequest(val requestType: String, val responseType: String, val topic: String,
    val messageId: String, val body: String, val operatorSessionId: String, val intent: RequestIntent,
    val loadId: String?, val bagId: String?, val firstSentAtMs: Long)
class CageRequests(private val deviceId: String, private val now: () -> Instant, private val newId: () -> String) {
    fun select(sessionId: String, rfidTag: String): PendingRequest
    fun readActive(sessionId: String): PendingRequest
    fun readLoad(sessionId: String, loadId: String, intent: RequestIntent, bagId: String? = null): PendingRequest
    fun allocate(sessionId: String, loadId: String, qr: PackQr): PendingRequest
    fun confirm(sessionId: String, loadId: String, token: String): PendingRequest
}
```

- [ ] **Step 1: Write failing tests**

`PackQrTest`: 
- `parsesThreeFields()`: `"510019399,OUTER-001,BAG-20261102-0001"` → productionOrder `"510019399"`, outerCode `"OUTER-001"`, bagId `"BAG-20261102-0001"`, raw equals input.
- `qrIsTrimmed()`: `"  510019399,OUTER-001,BAG-1\r\n"` → raw `"510019399,OUTER-001,BAG-1"`.
- `rejectsTwoFields()` `"OUTER-001,BAG-1"` → null; `rejectsEmptyField()` `"510019399,,BAG-1"` → null; `rejectsFourFields()` → null; `rejectsOver212()` (213-char input with two commas) → null; `rejectsControlChar()` `"1,A,B\u0007"` → null.

`CageModelsTest`:
- `parsesLoad()`: `{"loadId":"L1","cageId":"CAGE-007","status":"Open","packCount":14,"totalKg":352.4,"completedAtUtc":null,"grvState":"Not queued","grvReference":null,"printState":"Not queued","error":null}` → packCount 14, `totalKg.compareTo(BigDecimal("352.4")) == 0`, isOpen, grvReference null.
- `nullLoadIsNull()`: `CageJson.load(JSONObject("""{"load":null}""").optJSONObject("load")) == null`.
- `allocatedPackMapsActualKgAndOrder()`: `{"bagId":"B1","outerCode":"O","productionOrderNumber":"510019399","itemCode":"FG","itemName":"N","actualKg":25.18,"disposition":"Pass"}` → jobCard `"510019399"`, weightKg 25.18.

`CageRequestsTest` (fixed `now = Instant.parse("2026-11-03T10:00:00.123456Z")`, `newId = { "m-1" }`, device `"scanner_ab12"`):
- `selectEnvelopeAndTopic()`: topic `"PPNAM/station_5/scanner_ab12/req/cage_select_requested"`, responseType `"cage_context_result"`; `JSONObject(body)` has messageId `"m-1"`, schemaVersion `"4.1"`, deviceId, operatorSessionId `"s-1"`, timestampUtc `"2026-11-03T10:00:00.123456Z"`, rfidTag `"E200ABC"`; intent SELECT.
- `readActiveHasNoLoadId()`: type `cage_context_requested`, body has no `loadId`.
- `readLoadCarriesLoadIdAndBag()`: `readLoad("s-1","L1",RECOVER_ALLOCATION,"BAG-1")` → body loadId `"L1"`, pending.bagId `"BAG-1"`, intent RECOVER_ALLOCATION, body has no `bagId`.
- `allocateSendsRawBarcodeOnly()`: body `barcode` = qr.raw, `loadId` = `"L1"`, `has("jobCard") == false`, `has("bagId") == false`; responseType `finished_good_allocation_result`; pending.bagId = qr.bagId.
- `confirmCarriesToken()`: type `cage_complete_confirmed`, response `cage_completion_result`, body `confirmationToken` `"tok"`.
- `bodyIsUnder16k()`: allocate with a 212-char QR → `body.toByteArray(UTF_8).size < 16384`.
- `firstSentAtIsClockMillis()`.

- [ ] **Step 2: Run tests** — Expected: compile FAIL.
- [ ] **Step 3: Implement** the three files. `PackQr.parse`: trim; reject length > 212, any `Char.isISOControl()`; `split(',')` must give exactly 3 non-blank parts; fields kept as-is after the outer trim (station trims/normalises). `CageJson` reads numbers with `BigDecimal(o.get(name).toString())`; strings via `optString` with `isNull` → null for nullable fields. Bodies built once with `JSONObject().put(...)` then `.toString()`.
- [ ] **Step 4: Run tests** — Expected: PASS.
- [ ] **Step 5: Commit** `feat(cage): wire models, Pack QR check and request builders`.

---

### Task 3: Durable one-entry outbox

**Files:**
- Create: `app/src/main/java/com/mitas/ppnam/station5aa/cage/Outbox.kt`
- Create: `app/src/main/java/com/mitas/ppnam/station5aa/cage/SharedPrefsOutboxStore.kt` (Android)
- Test: `app/src/test/java/com/mitas/ppnam/station5aa/cage/OutboxTest.kt`

**Interfaces:**
- Consumes: `PendingRequest`, `RequestIntent` (Task 2).
- Produces:
```kotlin
interface OutboxStore { fun load(): PendingRequest?; fun save(p: PendingRequest); fun clear() }
class MemoryOutboxStore : OutboxStore          // for tests
object PendingRequestCodec { fun encode(p: PendingRequest): String; fun decode(s: String?): PendingRequest? }
class SharedPrefsOutboxStore(context: Context) : OutboxStore  // prefs "cage_outbox", key "pending", commit() not apply()
```

- [ ] **Step 1: Failing tests:** `roundTripPreservesBodyExactly()` — a PendingRequest whose body is `"{\"b\":1,  \"a\":\"x\"}"` (odd spacing) decodes with `body` byte-identical and all other fields equal; `decodeGarbageIsNull()` (`"not json"` and `null` → null); `memoryStoreSaveLoadClear()`.
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement.** Codec stores `body` as a JSON string field (never re-parsed). Unknown intent name → null.
- [ ] **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `feat(cage): durable outbox for the in-flight request`.

---

### Task 4: Operator wording — refusals and status names

**Files:**
- Create: `app/src/main/java/com/mitas/ppnam/station5aa/cage/RefusalCatalog.kt`
- Create: `app/src/main/java/com/mitas/ppnam/station5aa/cage/StatusText.kt`
- Test: `app/src/test/java/com/mitas/ppnam/station5aa/cage/RefusalCatalogTest.kt`, `StatusTextTest.kt`

**Interfaces:**
- Produces:
```kotlin
enum class AfterRefusal { STAY, TO_IDLE, REFRESH_LOAD }
data class Refusal(val headline: String, val detail: String, val code: String, val after: AfterRefusal)
object RefusalCatalog {
    fun forAllocation(code: String, reason: String, bagId: String?): Refusal
    fun forSelection(code: String, reason: String): Refusal
    fun forCompletion(code: String, reason: String): Refusal
    fun labelNotRecognised(): Refusal     // local QR pre-check failure, code "local_invalid_qr", STAY
    val TRANSIENT: Set<String>            // processing_paused, confirmation_busy
}
object StatusText { fun grv(state: String): String; fun print(state: String): String }
```

- [ ] **Step 1: Failing tests** — one assertion per row of spec §4.5 and §4.4:
  - allocation: `pack_allocated` with bagId `"B1"` → headline `"Do NOT load this Pack"`, detail `"B1 is already in another cage. Put it aside and check it on the desktop."`, after STAY; `pack_not_found` → detail `"This Pack hasn't been weighed. Take it back to the weigh station."`; `qc_required` → `"This Pack is waiting for QC on the desktop."`; `invalid_barcode` and `outer_code_changed` → `"Label doesn't match the weighed Pack. Put it aside for checking."`; the three `production_order_*` → `"Job card on this label doesn't match. Put it aside for checking."`; `mixed_outer_code` → `"This Pack's outer code doesn't match the cage. Use a matching cage."`; `cage_locked` → `"This cage is already completed."`, TO_IDLE; `cage_not_active`/`cage_context_changed` → `"This cage is no longer active on this handheld. Rescan the cage tag."`, TO_IDLE.
  - selection: `unknown_cage` → headline `"Cage not recognised"`, detail `"This cage tag isn't registered — ask a Manager."`, TO_IDLE; `empty_cage`/`completion_blocked` → detail `"Add Packs before completing."`, STAY.
  - completion: `confirmation_expired` → detail `"The summary expired. Rescan the cage tag to complete."`, REFRESH_LOAD; `cage_contents_changed` → detail `"Another Pack was added to this cage. Check the new count and rescan the cage tag to complete."`, REFRESH_LOAD.
  - unknown code `"weird"` with reason `"Something"` → detail `"Something"`, code `"weird"`, STAY (headline `"Not accepted"`).
  - `message_id_conflict` (any) → detail contains `"contact support"` (case-insensitive), STAY.
  - `StatusText.grv`: `"Not queued"`→`"Not sent yet"`, `"Pending"`→`"Pending"`, `"Processing"`→`"Sending to SAP"`, `"Succeeded"`→`"Posted to SAP"`, `"Failed"`→`"Failed — check the desktop"`, `"OutcomeUnknown"`→`"Unconfirmed — check the desktop"`, `"Reconcile"`→`"Needs review on the desktop"`, unknown → returned as-is. `print`: `"WaitingForGrv"`→`"Waiting for receipt"`, `"Succeeded"`→`"Printed"`, `"NeedsReview"`→`"Needs review on the desktop"`, `"Failed"`→`"Failed — check the desktop"`, `"Pending"`→`"Pending"`.
- [ ] **Step 2: Run** — FAIL. **Step 3: Implement** (plain `when`). **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `feat(cage): operator wording for refusals and receipt/print states`.

---

### Task 5: CageWorkflow — select, load, allocate

**Files:**
- Create: `app/src/main/java/com/mitas/ppnam/station5aa/cage/CageWorkflow.kt`
- Test: `app/src/test/java/com/mitas/ppnam/station5aa/cage/CageWorkflowLoadingTest.kt`

**Interfaces:**
- Consumes: Tasks 2-4; `UserTagPolicy.isUserTag(epc)`.
- Produces:
```kotlin
sealed interface CageScreen {
    data object Idle : CageScreen
    data class Loading(val load: CageLoad, val packs: List<LoadedPack>, val lastPack: LoadedPack?) : CageScreen
    data class Preview(val load: CageLoad, val packs: List<LoadedPack>, val token: String, val expiresAtUtc: String) : CageScreen
    data class Completed(val load: CageLoad) : CageScreen
}
data class Notice(val text: String, val tone: Tone) { enum class Tone { INFO, SUCCESS, WARNING } }
data class CageUiState(val screen: CageScreen = CageScreen.Idle, val sending: Boolean = false,
    val refusal: Refusal? = null, val notice: Notice? = null, val stationOnline: Boolean = false)
sealed interface Effect {
    data class Publish(val request: PendingRequest) : Effect
    data object SuccessFeedback : Effect
    data object ErrorFeedback : Effect
    data class SignOut(val reason: String) : Effect
}
class CageWorkflow(private val requests: CageRequests, private val outbox: OutboxStore, private val nowMs: () -> Long) {
    var state: CageUiState; private set
    fun start(sessionId: String): List<Effect>
    fun stop()                                    // session ended: state = CageUiState(), outbox untouched
    fun onStationOnline(online: Boolean): List<Effect>
    fun onRfid(tag: String): List<Effect>
    fun onQr(scanned: String): List<Effect>
    fun onResponse(json: JSONObject): List<Effect>   // already correlated by transport to outbox.load()
    fun onDismissRefusal(): List<Effect>
    fun onKeepLoading(): List<Effect>
    fun onConfirmComplete(): List<Effect>
    fun onPreviewExpired(): List<Effect>
    fun onRetryTick(sessionId: String?): List<Effect>
    fun pending(): PendingRequest?
}
```
Rules: every outgoing request is `outbox.save()`d **before** its `Publish` effect is returned; `sending = true` while `outbox.load() != null`; a response clears the outbox only if its `inResponseToMessageId` equals the pending `messageId` (else ignored, no state change). Scans are ignored when `sending`, `refusal != null`, `!stationOnline`, or screen is `Preview`.

- [ ] **Step 1: Failing tests** (helper `accepted(json)` builds a response with `inResponseToMessageId` = current pending id; fixed clock; `MemoryOutboxStore`; device `"scanner_ab12"`; after `start("s-1")` answer the READ_ACTIVE with `{"accepted":true,"load":null,"isActive":false}` and call `onStationOnline(true)`):
  - `startSendsReadActive()`: `start` returns one Publish with intent READ_ACTIVE; outbox holds it.
  - `rfidSelectsCage()`: `onRfid("E200ABC")` → Publish SELECT; `state.sending`.
  - `badgeTagIgnored()`: `onRfid("505055AAAA")` → empty effects, no outbox.
  - `qrWithoutCageShowsNotice()`: Idle + `onQr(valid)` → no Publish; `state.notice?.text == "Scan a cage tag first"`.
  - `acceptedSelectFetchesPacks()`: answer select with `load` (open, CAGE-007) and `nextAction:"scan_pack"` → screen Loading(CAGE-007) and next effect Publish READ_ACTIVE; answer that with `packs` of 2 → Loading.packs size 2.
  - `allocateAccepted()`: in Loading, `onQr("510019399,O,B9")` → Publish ALLOCATE with loadId of load; answer `{"accepted":true,"load":{...packCount:3,totalKg:75.5},"pack":{bagId:"B9",actualKg:25.1,...}}` → Loading.load.packCount 3, lastPack.bagId "B9", packs first = B9, effects contain SuccessFeedback, outbox empty.
  - `qrWhileSendingIsIgnored()`: after first `onQr`, a second `onQr` returns empty and outbox still holds the first.
  - `invalidQrRefusedLocally()`: `onQr("OUTER,BAG")` → no Publish, `refusal.code == "local_invalid_qr"`, ErrorFeedback.
  - `allocationRefusalBlocksScans()`: answer allocate with `{"accepted":false,"errorCode":"pack_allocated","reason":"x"}` → refusal set, ErrorFeedback, screen still Loading with unchanged packCount; `onQr` ignored until `onDismissRefusal()`.
  - `cageLockedGoesIdleAfterDismiss()`: `cage_locked` → after `onDismissRefusal()` screen Idle.
  - `staleResponseIgnored()`: a response with `inResponseToMessageId:"other"` → no change, outbox kept.
  - `switchCage()`: Loading(CAGE-007) + `onRfid("TAG-003")` → Publish SELECT; answer load CAGE-003 → Loading(CAGE-003).
  - `offlineBlocksScans()`: `onStationOnline(false)` then `onRfid` → empty.
  - `loginRequiredSignsOut()`: any response `errorCode:"login_required"` → effect SignOut with reason `"Your session ended. Sign in and rescan the cage."`; outbox cleared.
- [ ] **Step 2: Run** — FAIL. **Step 3: Implement** the class (completion/recovery handlers may be stubs returning `emptyList()` until Task 6 — but `onResponse` must branch by `pending.intent`). **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** `feat(cage): workflow state machine for selecting and loading`.

---

### Task 6: CageWorkflow — completion, transient errors, retries and recovery

**Files:**
- Modify: `app/src/main/java/com/mitas/ppnam/station5aa/cage/CageWorkflow.kt`
- Test: `app/src/test/java/com/mitas/ppnam/station5aa/cage/CageWorkflowCompletionTest.kt`, `CageWorkflowRecoveryTest.kt`

**Interfaces:** same class; no new public API.

Behaviour (spec §4.4, §5):
- SELECT response with `nextAction:"confirm_completion"` → `Preview(load, packs, confirmationToken, confirmationExpiresAtUtc)`.
- `onKeepLoading()` → back to `Loading` with same load/packs, no Publish. `onConfirmComplete()` → Publish CONFIRM (only from Preview). `onPreviewExpired()` → Loading + notice `"Summary expired — rescan the cage tag"` (WARNING).
- CONFIRM accepted (incl. `alreadyCompleted:true`) → `Completed(load)`, SuccessFeedback. In Completed, RFID selects (any cage), QR → notice "Scan a cage tag first".
- REFRESH_LOAD refusals → after dismiss, Publish READ_LOAD(loadId) and on its answer show `Loading(load, packs)` if `load.isOpen` else `Completed(load)`.
- `Completed` screen "Refresh" uses `onRefreshCompleted()` → Publish READ_LOAD. *(add this fun)*
- Transient codes (`RefusalCatalog.TRANSIENT`): keep outbox, no refusal, no state change; next `onRetryTick` re-publishes.
- `onRetryTick(sessionId)`: if pending exists **and** `pending.operatorSessionId == sessionId` → `Publish(pending)` with the identical object (same body); otherwise nothing.
- `request_expired`: if `nowMs() - pending.firstSentAtMs <= 300_000` → refusal headline `"Check the handheld's clock"`, clear outbox; else treat as unconfirmed → recovery below (using the current session).
- `message_id_conflict` → refusal (Task 4), clear outbox.
- **Recovery on `start(sessionId)`** when `outbox.load()` is from a different session:
  - ALLOCATE → clear, Publish `readLoad(sessionId, loadId, RECOVER_ALLOCATION, bagId)`. On answer: if `packs` contain bagId → notice `"$bagId was recorded in ${load.cageId}"` (SUCCESS); else refusal-style amber notice `Notice("Last Pack $bagId was not confirmed. Rescan cage ${load.cageId}, then rescan that Pack.", WARNING)`. Screen Idle either way.
  - CONFIRM or RECOVER_COMPLETION → clear, Publish `readLoad(..., RECOVER_COMPLETION)`. Answer: completed → `Completed(load)`; open → Idle + notice `"Cage ${cageId} is still open. Rescan the cage tag to review."`.
  - RECOVER_ALLOCATION → re-issue the same recovery read under the new session.
  - SELECT / READ_* → clear silently, then normal READ_ACTIVE.
  - Same-session pending (process alive) → `Publish(pending)` unchanged.

- [ ] **Step 1: Failing tests**
  - Completion: `rescanActiveCageShowsPreview()`, `keepLoadingSendsNothing()`, `confirmCompletes()` (Completed, SuccessFeedback, grvState shown from load), `alreadyCompletedCountsAsCompleted()`, `contentsChangedRefreshesLoad()` (refusal → dismiss → Publish READ_LOAD → answer packCount 15 → Loading packCount 15), `previewExpiryReturnsToLoading()`, `qrIgnoredDuringPreview()`.
  - Transient/retry: `processingPausedKeepsPendingAndRetries()` (no refusal; `onRetryTick("s-1")` returns Publish whose `request` `==` original, body identical), `pendingFromOldSessionIsNotResent()` (`onRetryTick("s-2")` → empty), `requestExpiredRecentShowsClockMessage()`, `requestExpiredOldIsReconciled()` (clock advanced 6 min → Publish RECOVER_ALLOCATION).
  - Recovery: pre-seed outbox with ALLOCATE from session `"s-old"` (bagId "B7", loadId "L1"); `start("s-new")` → single Publish RECOVER_ALLOCATION carrying `operatorSessionId:"s-new"`, `loadId:"L1"`. Answer with packs containing B7 → notice `"B7 was recorded in CAGE-007"`; second test without B7 → warning notice exact text above. `recoverCompletionCompleted()`, `recoverCompletionStillOpen()`, `oldSelectIsDroppedSilently()` (start → Publish READ_ACTIVE only).
- [ ] **Step 2: Run** — FAIL. **Step 3: Implement.** **Step 4: Run** — PASS (Task 5 tests still pass).
- [ ] **Step 5: Commit** `feat(cage): completion, retry and post-sign-in recovery`.

---

### Task 7: Android wiring and the Cage Loading screen

**Files:**
- Create: `app/src/main/java/com/mitas/ppnam/station5aa/cage/CageTransport.kt`
- Create: `app/src/main/java/com/mitas/ppnam/station5aa/cage/CageController.kt`
- Create: `app/src/main/java/com/mitas/ppnam/station5aa/cage/ScanFeedback.kt`
- Create: `app/src/main/res/layout/view_cage_loading.xml`, `app/src/main/res/layout/item_cage_pack.xml`
- Modify: `app/src/main/res/layout/activity_main.xml` (replace `cardNoWorkflows` with `<include layout="@layout/view_cage_loading" android:id="@+id/cage"/>`; keep `layoutStationOffline`)
- Modify: `app/src/main/java/com/mitas/ppnam/station5aa/MainActivity.kt`, `ScannerApp.kt`, `SessionGuard.kt` (none if not needed), `app/src/main/res/values/strings.xml`
- Test: `app/src/test/java/com/mitas/ppnam/station5aa/cage/ResponseFilterTest.kt`

**Interfaces:**
- Consumes: `CageWorkflow` (Tasks 5-6), `MqttManager.subscribe/unsubscribe/publish/isConnected/addConnectionListener/addStationStatusListener/isStationOnline`, `OperatorSessionHolder.addListener`, `SessionGuard.signOut(reason)`, `SessionGuard.touch()`, `MqttTopics.deviceResponseWildcard`.
- Produces:
```kotlin
class ResponseFilter(private val deviceId: String, capacity: Int = 64) {
    /** True when [json] is for this device, answers [pendingMessageId], and its own messageId was not seen before. */
    fun accept(json: JSONObject, pendingMessageId: String?): Boolean
}
object CageController {
    fun install(app: Application)                 // from ScannerApp.onCreate after SessionGuard.install
    val state: CageUiState
    fun addListener(l: (CageUiState) -> Unit); fun removeListener(l: (CageUiState) -> Unit)
    fun onRfid(tag: String); fun onQr(data: String)
    fun dismissRefusal(); fun keepLoading(); fun confirmComplete(); fun previewExpired(); fun refreshCompleted()
    fun hasPending(): Boolean
}
```

- [ ] **Step 1: Failing test** `ResponseFilterTest`: `acceptsMatching()`; `rejectsOtherDevice()` (`deviceId:"scanner_zz"`); `rejectsWrongCorrelation()`; `rejectsRepeatResponseMessageId()` (same response `messageId` twice → second false); `rejectsWhenNothingPending()`.
- [ ] **Step 2: Run** — FAIL. **Step 3: Implement `ResponseFilter`** (LinkedHashSet LRU). **Step 4: Run** — PASS.
- [ ] **Step 5: Implement the Android layer** (no unit tests; verified in Step 6):
  - `CageController.install`: builds `CageWorkflow(CageRequests(DeviceIdentity.deviceId(app), Instant::now, { UUID.randomUUID().toString() }), SharedPrefsOutboxStore(app), System::currentTimeMillis)`; subscribes `CageTransport` once; on session set → `workflow.start(id)`, on session cleared → `workflow.stop()`; station status → `onStationOnline`; on broker connect → `onRetryTick(currentSessionId)`; a main-thread `Handler` ticks `onRetryTick` every **10 s** while `pending() != null`. Executes effects: `Publish` → `MqttManager.publish(topic, body, retain=false, qos=AT_LEAST_ONCE)` (publish failure just waits for the next tick); `SignOut` → `SessionGuard.signOut(reason)`; feedback → `ScanFeedback`. All workflow calls on the main thread.
  - `CageTransport`: `mqtt.subscribe(MqttTopics.deviceResponseWildcard(id))`, parse UTF-8 JSON, `ResponseFilter.accept(json, workflow.pending()?.messageId)` → `workflow.onResponse(json)`.
  - `ScanFeedback.success(ctx)` short 40 ms vibration; `error(ctx)` vibration pattern `[0,200,100,200]` + `ToneGenerator(STREAM_NOTIFICATION, 100).startTone(TONE_PROP_NACK, 300)`. Add `<uses-permission android:name="android.permission.VIBRATE"/>`.
  - `MainActivity`: register receivers in `onResume`/unregister in `onPause` for `com.rscja.scanner.action.scanner.RFID` and `com.scanner.broadcast` (extra `"data"`), call `SessionGuard.touch()` then `CageController.onRfid/onQr`. The settings RFID shortcut (`ScannerApp.SETTINGS_RFID`) must not also select a cage: expose it as `ScannerApp.SETTINGS_RFID` const and skip it in MainActivity. Render `CageUiState`:
    - Idle: big prompt "Scan a cage tag".
    - Loading: amber banner `LOADING CAGE` / `cageId` / "Job {jobCard} · {outerCode}" from `lastPack ?: packs.first()` (hidden when no packs); tiles packCount and `totalKg` formatted `"%.2f"`; green last-Pack card "✓ Added {bagId} · {kg} kg · {Pass|Manager accepted}"; dashed prompt "Scan next Pack QR" / "Rescan cage tag to complete"; `RecyclerView` (or `LinearLayout` of `item_cage_pack`) newest first.
    - Preview: Material bottom sheet (`BottomSheetDialog`, not cancelable by outside tap) "Complete {cageId}?", "{n} Packs · {kg} kg", "Once completed, no more Packs can be added.", buttons Keep loading / Complete cage, countdown from `expiresAtUtc` → `previewExpired()` at zero.
    - Completed: green card "✓ {cageId} completed · {n} Packs · {kg} kg", rows "SAP receipt: {StatusText.grv}", "Manifest print: {StatusText.print}", note "Completed on the station. SAP and printing are handled by the desktop.", Refresh button, prompt "Scan the next cage tag".
    - `sending`: small "Sending…" row with progress under the banner.
    - `refusal`: full-screen red overlay (`danger` border on `light_red`), ✕, headline, detail, "Got it" button → `dismissRefusal()`.
    - `notice`: banner coloured by tone; cleared on next scan (workflow does this).
  - Logout dialog: when `CageController.hasPending()`, message "A scan is still being sent. Sign out anyway?". Idle sign-out unchanged.
  - Remove the "no workflows" strings/card; if session lacks `cage_loading` in `allowedTabs` keep showing the old no-workflows message (fail-closed).
- [ ] **Step 6: Build and test** `./gradlew --no-daemon testDebugUnitTest assembleDebug` — Expected: BUILD SUCCESSFUL. If an emulator/device is attached (`adb devices`), install and smoke-test with `adb shell am broadcast -a com.rscja.scanner.action.scanner.RFID --es data E200ABC` and `adb shell am broadcast -a com.scanner.broadcast --es data "510019399,OUTER-001,BAG-1"` against a running Station 5 desktop; otherwise record "device smoke test not run".
- [ ] **Step 7: Commit** `feat(cage): Cage Loading screen, transport and scan handling`.

---

### Task 8: One source of truth in the desktop repo

**Repo:** `../PPNAM-Station-5` (separate git repo, branch `main`). Create branch `docs/single-mqtt-contract` there.

**Files:**
- Modify: `DOCS/Station5_MQTT_Contract.md` (insert banner after the H1)
- Modify: `Designs.md` (~line 29), `DOCS/Station5_Finished_Goods_Requirements.md` (FGH-INT-002), `DOCS/Station5_Traceability_Matrix.md` (item 2), `DOCS/Station5_v1_README_Reference.md` (contract row)
- Modify: `MQTT_CONTRACT.md` — add one line under the title: "This is the single Station 5 wire contract. `DOCS/Station5_MQTT_Contract.md` (v1.0.0) is superseded."
- Android repo: `README.md` (create) — purpose, build/test commands, "Wire contract: `../PPNAM-Station-5/MQTT_CONTRACT.md` — the only source of truth; do not copy it here".

- [ ] **Step 1:** Banner text exactly: `> **Superseded — historical only.** The current Station 5 wire contract is [MQTT_CONTRACT.md](../MQTT_CONTRACT.md) (2 November 2026, Packs Rev 2). Do not implement from this document.`
- [ ] **Step 2:** Repoint the four references to `MQTT_CONTRACT.md` (relative paths correct from each file).
- [ ] **Step 3: Verify** `grep -rn "Station5_MQTT_Contract" --include=*.md .` in the desktop repo — Expected: only the banner'd file itself, the v1 README reference row marked historical, and the MQTT_CONTRACT supersede line. In the Android repo `grep -rniE "contract v3|v3\.2\.0|Station1_MQTT_Contract|2026-08-17" app/src` — Expected: no matches.
- [ ] **Step 4: Commit** both repos: desktop `docs: make MQTT_CONTRACT.md the single Station 5 wire contract`; Android `docs: README pointing at the single wire contract`.

---

### Task 9: Release 1.3.0

**Files:** `app/build.gradle.kts` (`versionCode = 6`, `versionName = "1.3.0"`).

- [ ] **Step 1:** Bump version.
- [ ] **Step 2: Verify** `./gradlew --no-daemon testDebugUnitTest assembleDebug` — Expected: BUILD SUCCESSFUL, all tests pass.
- [ ] **Step 3: Commit** `chore(release): v1.3.0 (6)`. Do **not** tag or push; tagging `v1.3.0` triggers the GitHub release and is the user's call.

---

## Out of scope / follow-ups (report to user, don't build)

- Phase 2 operator directory on the desktop (spec §11) — separate plan.
- Badge login (`login_requested`) is not in MQTT_CONTRACT and the desktop rejects it; the login screen still offers badge scans. Flag to the user.
