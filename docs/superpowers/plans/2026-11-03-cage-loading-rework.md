# Cage Loading Rework (contract aef7bf7) + Simulator Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Rework the handheld's cage-loading workflow to the current contract (one cage tag arms one Pack; explicit Complete cage), fix the final-review defects, build a Station 5 backend simulator, and prove the app end-to-end on the Chainway C72.

**Architecture:** Same as `2026-11-03-cage-loading.md`: pure-Kotlin `cage` package (state machine + catalog) driven by a thin Android layer. New: `tools/station5-sim/` — a Node.js process embedding an MQTT broker (aedes) and a stand-in station that ports the desktop `HandheldMqttService.HandleAsync` rules at commit `aef7bf7`, with a small HTTP control API for test scenarios.

**Tech Stack:** Kotlin 2.0 / Views; Node 24 (`aedes`, `node:test`); adb.

**Spec:** `docs/superpowers/specs/2026-11-03-cage-loading-design.md` (revised 2026-11-03 for contract aef7bf7 — §4.1-§4.5 are binding). Contract: `../PPNAM-Station-5/MQTT_CONTRACT.md` (aef7bf7). Desktop reference: `../PPNAM-Station-5/ppnam_station5_packs/ppnam_station5_packs.Infrastructure/Packs/HandheldMqttService.cs` (HandleAsync ~166-290, AllocateScannedPack ~290-340, DiscardPendingScan ~340, PreviewCompletion ~345-360). Final-review findings: `.superpowers/sdd/2026-11-03-cage-loading/progress.md` (C1, I1-I3, M1-M7).

## Global Constraints

- Floor routine is **cage tag → one Pack QR**, repeated per Pack. A select reply with `requiresCageScan:false` arms exactly one allocation until `cageScanExpiresAtUtc`; every allocation reply (accepted or refused) spends it; any `cage_context_requested`, `cage_complete_requested` or `cage_complete_confirmed` also spends it on the station.
- The app must never send `cage_context_requested` while armed. It may send one after an allocation reply (between cycles) and at session start.
- A QR while not armed is never sent: error feedback + notice `"Scan the cage tag before each Pack"` (WARNING).
- Rescanning a cage tag (same or different) is always `cage_select_requested`; it never previews.
- Completion: Complete cage button → `cage_complete_requested {loadId}` → sheet → `cage_complete_confirmed {loadId, confirmationToken}`.
- Countdowns use station time: remaining = expiryUtc − `serverSentAtUtc` (fallback `timestampUtc`), anchored at local receipt time.
- Everything from the original plan's Global Constraints still holds (topics, QoS 1/retain false, identical-byte retries, outbox before publish, only `accepted:true` advances, station's counts, no invented loadId, no auto-confirm).
- Unit tests: `./gradlew --no-daemon testDebugUnitTest`; build: `./gradlew --no-daemon assembleDebug` (Android repo root, Git Bash). Simulator tests: `npm test` in `tools/station5-sim`.

## Review Focus

1. QR scanned twice after one tag — the second must not be sent (R1 `secondQrAfterOneTagNotSent`).
2. Arm expiry while the operator hesitates — local disarm, no send (R1 `armExpiryDisarms`).
3. No context read is ever emitted between an accepted select and the next allocation (R1 `noReadWhileArmed`).
4. Handheld clock 3 minutes fast — preview and arm countdowns still ~2 minutes (R1 `countdownUsesStationClock`).
5. Broker drop mid-shift — "Station offline" shown and scans blocked (R2 manual check + `parseStationStatus` unchanged).

---

### Task 1: Workflow + catalog rework (contract aef7bf7) and I1

**Files:** Modify `app/src/main/java/com/mitas/ppnam/station5aa/cage/CageWorkflow.kt`, `CageRequests.kt`, `RefusalCatalog.kt`; tests `app/src/test/java/com/mitas/ppnam/station5aa/cage/CageWorkflow*Test.kt`, `RefusalCatalogTest.kt`, `CageRequestsTest.kt`.

**Interfaces (changes only):**
```kotlin
enum class RequestIntent { SELECT, READ_ACTIVE, READ_LOAD, RECOVER_ALLOCATION, RECOVER_COMPLETION, ALLOCATE, PREVIEW, CONFIRM }
fun CageRequests.preview(sessionId: String, loadId: String): PendingRequest  // cage_complete_requested -> cage_completion_result, body {loadId}
data class Loading(val load: CageLoad, val packs: List<LoadedPack>, val lastPack: LoadedPack?, val armExpiresAtMs: Long? = null) : CageScreen  // armed iff non-null
data class Preview(val load: CageLoad, val packs: List<LoadedPack>, val token: String, val expiresAtMs: Long) : CageScreen
fun CageWorkflow.onCompleteCage(): List<Effect>   // from Loading with open load, packCount>0, not sending -> PREVIEW
fun CageWorkflow.onArmExpired(): List<Effect>     // Loading armed -> disarm + notice "Cage tag expired — scan it again" (WARNING)
object StationClock { fun remainingMs(expiryUtc: String?, serverNowUtc: String?): Long? }  // pure; null if unparseable
```
Behaviour (spec §4.1-§4.5):
- SELECT accepted: `requiresCageScan:false` → Loading(load, packs = previous packs if same loadId else empty, lastPack kept only if same loadId, armExpiresAtMs = nowMs() + remainingMs(cageScanExpiresAtUtc, serverSentAtUtc ?: timestampUtc), default 120_000 if unparseable). `requiresCageScan:true` → Loading not armed. **No READ_ACTIVE after select.** Remove the same-cage preview path entirely.
- QR: armed → ALLOCATE; not armed (any screen) → ErrorFeedback + WARNING notice, no send.
- ALLOCATE accepted → Loading(load, packs with new pack prepended/deduped, lastPack, not armed), SuccessFeedback, then send READ_LOAD(loadId) to refresh; its answer keeps `lastPack` when same loadId. ALLOCATE refused → refusal, not armed, no read.
- PREVIEW accepted (`confirm_completion`) → Preview(expiresAtMs from `confirmationExpiresAtUtc` via StationClock). PREVIEW refused → `forCompletion`. After-preview flows (keep loading, confirm, expiry, contents_changed) as before but notices say "tap Complete cage again".
- Recovery: PREVIEW pending from old session → dropped silently (then READ_ACTIVE).
- message_id_conflict refusal detail gets `" Ref: {messageId}"` appended (M1).
- RefusalCatalog (I1 + new codes): `production_order_required`, `production_order_invalid` join `production_order_mismatch` (keep old two harmlessly); `cage_scan_required` → headline "Scan the cage tag first", detail "Scan the cage tag again, then scan this Pack within two minutes.", STAY; `forCompletion` falls back to the allocation table (so `cage_not_active`/`cage_locked` → TO_IDLE) before the generic fallback.

- [ ] Step 1: Failing tests — new: `tagArmsOnePack`, `secondQrAfterOneTagNotSent` (notice text exact, ErrorFeedback, no Publish), `noReadWhileArmed` (after accepted select, effects contain no READ_*), `requiresCageScanTrueDoesNotArm`, `sameCageRescanReArms` (never Preview), `allocationAcceptedDisarmsAndRefreshes` (READ_LOAD published, lastPack kept after its answer), `allocationRefusedDisarmsNoRead`, `armExpiryDisarms`, `completeCageSendsPreview` (type `cage_complete_requested`, body loadId), `completeCageNeedsPacks`, `previewAcceptedShowsSheet`, `countdownUsesStationClock` (serverSentAtUtc 10:00:00, cageScanExpiresAtUtc 10:02:00, device nowMs arbitrary → armExpiresAtMs == nowMs+120_000), `oldPreviewDroppedOnStart`, `conflictShowsRef`; catalog: `productionOrderRequiredAndInvalidMapped`, `cageScanRequired`, `completionFallsBackToAllocationTable`. Rewrite old tests that encoded rescan-to-complete or post-select READ_ACTIVE.
- [ ] Step 2: Run — FAIL. Step 3: Implement. Step 4: Run full suite — PASS.
- [ ] Step 5: Commit `feat(cage): one cage tag per Pack and explicit Complete cage (contract aef7bf7)`.

---

### Task 2: Android screen rework, station offline (I3), countdowns (I2), minors

**Files:** Modify `MainActivity.kt`, `activity_main.xml`, `view_cage_loading.xml`, `sheet_cage_complete.xml`, `strings.xml`, `cage/CageController.kt`, `MqttManager.kt`, `AuthClient.kt` (KDoc), `cage/StatusText.kt` (+ test).

- Step prompt per spec §4.2: not armed "Scan the cage tag" / "Scan the cage tag before each Pack"; armed "Scan ONE Pack QR now" + countdown m:ss from `armExpiresAtMs` vs `System.currentTimeMillis()`; at zero → `CageController.armExpired()` → `onArmExpired()`.
- Complete cage button (amber outline) on Loading when load open, packCount ≥ 1, not sending → `CageController.completeCage()` → `onCompleteCage()`. Sheet countdown uses `Preview.expiresAtMs`.
- I3: `MqttManager.isStationOnline` starts `false`, is `@Volatile`, and is set false (with listener notification) when the client disconnects or a connect attempt fails.
- M7: offline overlay text "Station offline — don't load Packs" / "Waiting for the Station 5 desktop…".
- M2: `StatusText.print` adds `Not queued`→"Not printed yet", `Processing`→"Printing", `OutcomeUnknown`→"Unconfirmed — check the desktop", `Reconcile`→"Needs review on the desktop" (tests).
- M3: Completed card shows completion local time `HH:mm` from `completedAtUtc`.
- M6: AuthClient KDoc no longer lists `login_requested` as a contract route (note it is not in MQTT_CONTRACT and the desktop rejects it).
- Pack list label when the list is empty but packCount > 0: "Pack list updates after the next Pack".
- [ ] Steps: implement; `./gradlew --no-daemon testDebugUnitTest assembleDebug` PASS; commit `feat(cage): tag-per-Pack screen, Complete cage button, station offline state`.

---

### Task 3: Station 5 backend simulator

**Files:** Create `tools/station5-sim/` — `package.json` (deps `aedes`; scripts `start`, `test`), `src/broker.js`, `src/station.js` (pure request handler: `handle(topic, payloadString, nowMs) -> {topic, json} | null`), `src/scram.js`, `src/store.js` (in-memory state + seed), `src/control.js` (HTTP control API), `src/index.js`, `seed.json`, `test/*.test.js`, `README.md`.

Requirements:
- Broker: TCP on `0.0.0.0:1883`; username/password auth (`SIM_BROKER_USER`/`SIM_BROKER_PASS`, defaults `ppnam`/`ppnam-sim`); station publishes retained raw `online` to `PPNAM/station_5/status` at QoS 1 and sets it `offline` on shutdown; logs device presence on `PPNAM/station_5/{deviceId}`.
- Station handler ports `HandleAsync` at aef7bf7 for: topic/device checks (allowed device IDs from `SIM_DEVICE_IDS` comma list, or `*` = accept any and log it), QoS 1/retain false/size checks, envelope checks, duplicate/case-colliding property and plaintext password rejection, exact-bytes replay `(deviceId,type,messageId)`+SHA-256 with `isDuplicate:true` and `message_id_conflict`, ±5 min timestamp → `request_expired`, SCRAM-SHA-256 start/proof (iterations 100000 configurable via `SIM_SCRAM_ITERATIONS`, real server signature, 60 s challenge), sessions (16 h, one per device, logout revokes), all five cage request types with the one-use scan marker (2 min), `cage_scan_required`, `alreadyAllocated`, completion preview tokens (2 min, bound to session/load/pack count) and `cage_contents_changed`, `alreadyCompleted`, Pack validation (`invalid_barcode`, `pack_not_found`, `qc_required`, `outer_code_changed`, `production_order_*`, `pack_allocated`, `cage_locked`, `unknown_cage`, `completion_blocked`), `AllowMixedOuterCodes` default true, unsupported types → `unsupported_request` on `res/request_rejected`. Response field names exactly as the desktop (`load`, `packs`, `pack`, camelCase). Completed loads get `grvState:"Pending"`, `printState:"WaitingForGrv"`; a control endpoint advances them.
- Seed: users `sim`/`sim123` (Operator) and `manager`/`manager123` (Manager); cages `CAGE-001`..`CAGE-003` with RFIDs `E2000017CAGE0001`..`E2000017CAGE0003`, plus inactive `CAGE-009`/`E2000017CAGE0009`; Packs using the labels in `../PPNAM-Station-5/DOCS/Station5_QR_Testing_20261102.md` (at least 12 Pass, 1 QC-pending, 1 legacy without production order); a QR for an unweighed Bag ID is simply absent.
- Control API on `127.0.0.1:8787`: `GET /state`, `POST /reset`, `POST /station/online`, `POST /station/offline`, `POST /drop-next-reply`, `POST /pause` / `POST /resume` (processing_paused), `POST /allocate-from-other-device {loadId, bagId}`, `POST /advance-receipt {loadId}`, `GET /log` (last 200 request/response summaries, no secrets).
- `node:test` tests: SCRAM round trip with a JS client implementation of the contract §3 algorithm (including server-signature verification); tag arms one Pack; second Pack without tag → `cage_scan_required`; context read cancels arm; arm expiry; preview/confirm; contents_changed after other-device allocation; exact replay and conflict; timestamp window.
- README: how to run, connect the handheld (`adb reverse tcp:1883 tcp:1883`, host `127.0.0.1`), credentials, control API with curl examples.
- [ ] Steps: TDD the handler; `npm install && npm test` PASS; `npm start` starts cleanly; commit `feat(sim): Station 5 backend simulator (contract aef7bf7)`.

---

### Task 4: Install and full device simulation on the C72

Controller-run (not a code task): start the simulator, `adb reverse tcp:1883 tcp:1883`, install the debug APK, provision broker settings and sign in via the UI (adb input / uiautomator), then walk contract §8 items 2-6 against the simulator by injecting scans (`adb shell am broadcast -a com.rscja.scanner.action.scanner.RFID --es data <tag>` and `-a com.scanner.broadcast --es data "<qr>"`), capturing screenshots and simulator logs. Every defect found goes back through a fix task with review; repeat until the full scenario list passes. Produce `docs/simulation/2026-11-03-c72-simulation.md` with the scenario table, results and screenshots.
