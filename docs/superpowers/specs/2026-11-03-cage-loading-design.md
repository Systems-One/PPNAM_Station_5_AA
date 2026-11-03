# Station 5 handheld: Cage Loading (Packs Rev 2) — design

Date: 2026-11-03 · App: `PPNAM_Station_5_AA` (currently v1.2.3, versionCode 5) · Target release: **v1.3.0**

## 1. Intent

The Station 5 Windows desktop (`PPNAM-Station-5/ppnam_station5_packs`) weighs and approves finished-goods Packs. The Zone 8 Android handheld's job is everything after that: pick a cage by RFID, load approved Packs into it by scanning their QR, and confirm the cage complete — which is the customer-approved trigger for the SAP Receipt from Production. Today the handheld app has the full shell (SCRAM login, settings, MQTT, presence, inactivity sign-out) but **no workflow screen**: after login it shows "no workflows available". This project adds the one workflow the contract grants it, `cage_loading`.

Success: an operator on a real handheld can load and complete cages against the live Station 5 desktop, several handhelds work at the same time, and the contract's Android commissioning checklist (MQTT_CONTRACT.md §8) passes.

## 2. One source of truth (user requirement)

**`PPNAM-Station-5/MQTT_CONTRACT.md` (revision 2 November 2026, wire `schemaVersion` `4.1`) is the only Station 5 wire contract.** It is what the desktop `HandheldMqttService` implements. The handheld is built to it, and every other document defers to it.

Changes, as part of this project:

| Where | Change |
|---|---|
| `PPNAM-Station-5/DOCS/Station5_MQTT_Contract.md` (v1.0.0, 2026-08-14) | Keep the file for history; add a top banner: *Superseded — historical only. The current contract is [MQTT_CONTRACT.md](../MQTT_CONTRACT.md) (2 Nov 2026, Packs Rev 2). Do not implement from this document.* |
| `PPNAM-Station-5/Designs.md` line ~29 | Repoint the MQTT contract reference to `MQTT_CONTRACT.md`. |
| `PPNAM-Station-5/DOCS/Station5_Finished_Goods_Requirements.md` FGH-INT-002 | Repoint to `MQTT_CONTRACT.md`. |
| `PPNAM-Station-5/DOCS/Station5_Traceability_Matrix.md` item 2 | Repoint to `MQTT_CONTRACT.md`. |
| `PPNAM-Station-5/DOCS/Station5_v1_README_Reference.md` | Mark the contract row historical (the file is already a v1 reference). |
| Android repo `README.md` (create if missing) and code comments | Link the contract by repo-relative path `../PPNAM-Station-5/MQTT_CONTRACT.md` and cite it by section ("MQTT_CONTRACT §4"). **No copy of the contract in the Android repo.** Replace stale citations ("contract v3", "v3.2.0 §4.5", "2026-08-17 restructure") in `MqttTopics.kt`, `AuthClient.kt`, `Schema41.kt`, `OperatorSession.kt`, `LoginActivity.kt`, `OperatorDirectory.kt` and their tests. |

Rule for the future: if Android and desktop disagree, the contract is corrected first, then both sides follow.

## 3. Who and how it's used

- Several handhelds work at once, each with its own provisioned `deviceId`, its own operator session and its own active cage.
- **An operator switches between cages during a shift.** Scanning another cage's RFID makes it active; the previous cage stays open on the station and resumes when its tag is scanned again.
- All three roles (Operator, Manager, Administrator) may load cages. Registration, QC, SAP retry/reconciliation and reprints stay on the desktop; the handheld has no commands for them.
- Hardware: Chainway handheld (as today). Cage RFID via the existing `com.rscja.scanner.action.scanner.RFID` broadcast (extra `data`); Pack QR via the Chainway barcode broadcast `com.scanner.broadcast` (extra `data`), as Station 1 AA and Station 2 AA already do.

## 4. Screens and behaviour (decided with the user via mockups)

### 4.1 After login → straight to "Scan a cage tag" *(builder's call: single workflow, so no home menu)*

Login success requires `allowedTabs` to contain `cage_loading` (fail-closed, as `OperatorSession.canShow` already does). The dashboard's "no workflows" empty state is replaced by the Cage Loading screen. Settings, sign-out and the connection pill remain reachable from the top bar as today.

> **Revision 2026-11-03 (contract `aef7bf7`).** The Station 5 desktop changed the contract on 3 Nov 09:37: **one cage-tag scan arms exactly one Pack** (for two minutes, consumed by that Pack's allocation attempt whether accepted or refused); **any `cage_context_requested` cancels an unused arm**; rescanning a cage tag never previews or completes; completion uses an explicit **Complete cage** action (`cage_complete_requested` → preview → `cage_complete_confirmed`). The user approved reworking the app to this contract. §4.1–§4.4 below describe the reworked flow.

On entry after login the app sends one `cage_context_requested` **without** `loadId` to show the session's selected cage (if any) — this never arms a Pack. The app never sends a context read while a Pack is armed, and never selects a cage by itself.

### 4.2 Loading screen — **"active cage only" (user chose option A)**

- Large amber banner (`station_field` #A85F14): "LOADING CAGE", `cageId`, job / outer code of the Packs in it.
- Two stat tiles: Pack count and total kg — **always the station's `load.packCount` / `load.totalKg`**, never counted locally.
- "Last Pack" card: green ✓ Added, Bag ID, `actualKg`, disposition (Pass / Manager accepted).
- **Step prompt** that tells the operator the one next physical action:
  - not armed: "Scan the cage tag" / "Scan the cage tag before each Pack";
  - armed: "Scan ONE Pack QR now" with a countdown to the arm's expiry ("1:52").
- **Complete cage** button (amber outline) when the shown load is open, has ≥1 Pack, and nothing is sending.
- Scrolling list of Packs in the load, newest first. A cage-tag reply carries no `packs`; the list comes from the last context read for that load and from accepted allocations.
- **No list of other open cages.** The physical cage is the memory; to return to a cage, scan its tag.

Idle state (no selected cage): the same layout with the banner replaced by a large "Scan a cage tag" prompt.

### 4.3 Scan routing (the floor routine is **cage tag → Pack QR**, repeated for every Pack)

| Scan | State | Request |
|---|---|---|
| RFID, user badge (`UserTagPolicy`, prefix `505055`) | any | ignored on this screen |
| RFID, other | any (including the cage already shown) | `cage_select_requested { rfidTag }` → open/resume/switch and **arm one Pack** (`requiresCageScan:false`, `cageScanExpiresAtUtc`) |
| QR | armed | `finished_good_allocate_requested { loadId, barcode }` — raw three-field QR, trimmed only; no `jobCard`, no `bagId` |
| QR | not armed (no cage, arm used, or expired) | not sent; error beep + amber notice "Scan the cage tag before each Pack" |

After **every** allocation reply (accepted or refused) the arm is spent; the prompt returns to "Scan the cage tag". After an accepted Pack the app then refreshes the Pack list with one `cage_context_requested { loadId }` (allowed between cycles). A select reply with `requiresCageScan:true` (replayed/interrupted selection) shows the cage but does **not** arm. When the arm's countdown reaches zero the app disarms locally with notice "Cage tag expired — scan it again".

Countdowns (arm and completion preview) are measured from the moment the reply arrived, using the station's own clock: remaining = expiry − `serverSentAtUtc` (fallback `timestampUtc`), so handheld clock skew cannot shorten or lengthen them.

Local pre-check of a QR before sending: exactly two commas, no empty field, ≤212 chars. A failure shows the refusal screen (§4.5) as "Label not recognised" without sending (and does not spend the station's arm, since nothing was sent). All real validation is the station's.

The app never invents a `loadId`; it always uses the latest server-returned `load.loadId`.

### 4.4 Completing a cage

The operator taps **Complete cage**. The app sends `cage_complete_requested { loadId }` (this discards any unused arm); the reply carries `nextAction:"confirm_completion"`, `confirmationToken`, `confirmationExpiresAtUtc`, `load` and `packs`. The app shows a bottom sheet over a dimmed loading screen:

- "Complete CAGE-007?" · "14 Packs · 352.40 kg" · job / outer code · "Once completed, no more Packs can be added."
- Buttons **Keep loading** (sends nothing; cage stays open, not armed) and **Complete cage** (amber).
- Countdown to token expiry; when it expires the sheet closes with "Summary expired — tap Complete cage again".
- Never auto-confirmed. Scans are ignored while the sheet is open.

**Complete cage** sends `cage_complete_confirmed { loadId, confirmationToken }` with a fresh message ID. On acceptance the screen shows a green "✓ CAGE-007 completed · 14 Packs · 352.40 kg · time" card, then **SAP receipt** and **Manifest print** status rows with friendly text for `grvState`/`printState` (Not queued, Pending, Processing, Succeeded, Failed, OutcomeUnknown, Reconcile, WaitingForGrv, NeedsReview), plus the line "Completed on the station. SAP and printing are handled by the desktop." It then prompts "Scan the next cage tag". It never says SAP posted unless `grvState` is Succeeded. A "Refresh" button re-reads that load with `cage_context_requested { loadId }`.

`cage_contents_changed` (another handheld added a Pack after the preview) is only returned when **Complete cage** is tapped in the sheet; the station does not push it. The app then closes the sheet, re-reads the load with `cage_context_requested { loadId }` to show the updated count, and asks the operator to tap Complete cage again for a fresh summary. Preview rejections (`cage_not_active`, `completion_blocked`, `empty_cage`, `cage_locked`) use the refusal screen; `cage_not_active`/`cage_locked` return to idle.

### 4.5 Refused Pack / rejection — **full-screen stop (user chose option A)**

Any rejected allocation shows a red full-screen takeover (`danger` #E25C5C on `light_red` #452024) with an error vibration and beep: ✕, a headline ("Do NOT load this Pack"), a plain explanation, and a single **Got it** button. Scanning is blocked until Got it is tapped.

Operator text by `errorCode` (headline / detail; *builder may polish wording*):

| errorCode | Detail shown |
|---|---|
| `pack_allocated` | "{bagId} is already in another cage. Put it aside and check it on the desktop." (rejections carry only `errorCode`/`reason`/`nextAction`, so the other cage isn't known) |
| `pack_not_found` | "This Pack hasn't been weighed. Take it back to the weigh station." |
| `qc_required` | "This Pack is waiting for QC on the desktop." |
| `invalid_barcode`, `outer_code_changed` | "Label doesn't match the weighed Pack. Put it aside for checking." |
| `production_order_mismatch`, `production_order_required`, `production_order_invalid` | "Job card on this label doesn't match. Put it aside for checking." |
| `mixed_outer_code` | "This Pack's outer code doesn't match the cage. Use a matching cage." |
| `cage_locked` | "This cage is already completed." → idle |
| `cage_not_active`, `cage_context_changed` | "This cage is no longer active on this handheld. Rescan the cage tag." → idle |
| `cage_scan_required` | headline "Scan the cage tag first", detail "Scan the cage tag again, then scan this Pack within two minutes." → stay (not armed) |

Cage-selection rejections (`unknown_cage`: "This cage tag isn't registered — ask a Manager", `completion_blocked`, `empty_cage`: "Add Packs before completing") use the same full-screen pattern. Session errors (`login_required`) clear the workflow and return to Login with a reason, using the existing `PendingSignedOutReason` mechanism. Unknown codes show the station's `reason` text with the code underneath.

## 5. Reliability rules (from the contract; implementation is the builder's)

- **One request in flight per handheld.** While waiting for a reply, show "Sending…" and ignore further scans.
- **Durable outbox.** Before publishing, persist the exact UTF-8 bytes, topic, request type, message ID and screen intent (e.g. SharedPreferences or a small file; one pending entry is enough given one-in-flight). A missing reply is retried with the **identical bytes** (same message ID and timestamp) on a timer and after reconnect/app restart. Clear only on a correlated response (`inResponseToMessageId`, correct device/topic). Deduplicate responses by response `messageId`.
- Only `accepted:true` from the station advances the screen; PUBACK never does.
- `processing_paused`, `confirmation_busy`: keep the pending entry and retry identical bytes shortly.
- `message_id_conflict`: stop retrying; keep the bytes for support; show contact-support screen with IDs.
- `request_expired` on an initial request: show "Check the handheld's clock", drop the entry; a new scan creates a new intent.
- A pending **completion** of uncertain outcome after restart: do not resend a new confirmation; send `cage_context_requested { loadId }` and show the result (completed → completed card; open → idle with "Rescan the cage tag to review").
- **Pending request when the session ends** *(builder's call, from review)*. The pending entry records the `operatorSessionId` it was built with. Identical-byte retries happen only while that same session is alive. The desktop revokes a device's earlier sessions on every login and on logout (`SqlPackRepository.cs` ~288, `LogoutAsync`), and an unprocessed retry under a dead session would only return `login_required`/`request_expired`. So on sign-out (manual or idle), app restart, or any new login, a pending entry from an old session is **not replayed**. After the next login, before the scan screen accepts input, the app reconciles it:
  - pending **allocation**: send `cage_context_requested { loadId }` (the saved load). If `packs` contains the Bag ID → nothing to do, just show "{bagId} was recorded in {cageId}". Otherwise show an amber full-screen notice: "Last Pack {bagId} was not confirmed. Rescan cage {cageId}, then rescan that Pack." (A rescan is safe: an already-allocated Bag ID in the same open load is an idempotent read and never counts twice.)
  - pending **completion preview** (`cage_complete_requested`): drop silently; nothing was changed.
  - pending **completion**: as above, read the load by `loadId` and show completed or still-open.
  - pending **cage selection**: drop it silently; the fresh session has no active cage, and the idle screen already says "Scan a cage tag".
  Then clear the entry. A manual sign-out while a request is pending first asks "A scan is still being sent. Sign out anyway?"; idle sign-out is not delayed.
- `request_expired` shows "Check the handheld's clock" only for a request first sent within the last five minutes by device time; otherwise it is treated as unconfirmed and reconciled as above.
- Station offline (no retained `online` on `PPNAM/station_5/status`) or broker down: amber/red banner "Station offline — don't load Packs"; scanning blocked; pending entry resumes when back.

## 6. Contract-alignment fixes to the existing shell (found during design)

1. **Station presence topic is wrong.** The app treats `PPNAM/station_5` as the station's presence topic; the contract and desktop use **`PPNAM/station_5/status`** (raw `online`/`offline`, retained). As built, the app would always show the station offline. Fix `MqttTopics.stationPresence()` and its test.
2. **Subscriptions too broad.** The app subscribes to `PPNAM/station_5/#`; the contract's ACLs allow only `PPNAM/station_5/{deviceId}/res/+` and `PPNAM/station_5/status`. Subscribe to exactly those two.
3. **Operator dropdown has no server yet.** `operator_list_requested` (ported from Station 1) is not in the Station 5 contract, and today the desktop replies `unsupported_request` on `res/request_rejected` (`HandheldMqttService.cs` ~180/270). **User decision: keep the dropdown, and add desktop support after the Android work (Phase 2, §11).** In Phase 1 the login screen must treat that rejection as "no list": keep the cached list (or an empty dropdown) and allow a typed username, with no error shown, matching Station 1 contract v3 §4.5's fallback.

Device presence (`PPNAM/station_5/{deviceId}`, raw online/offline, retained QoS 2, last will offline, graceful offline before disconnect) already matches; keep it and add a test.

## 7. Non-goals

- No cage registration, QC, reprint, SAP retry/reconciliation, manager approvals, weights or bin/batch data from the handheld.
- No list or history of cages on the handheld (user chose "active cage only").
- No Rolls workflow (separate future application).
- In Phase 1, no change to the desktop application or the contract's wire format (Phase 2, §11, adds the operator directory to both).
- No migration to Jetpack Compose; stay with the app's existing Views/ViewBinding style and palette.

## 8. Anti-goals (would be failure even if it "works")

- The UI ever shows a Pack as loaded, or a count/total, that the station hasn't accepted.
- A refused Pack can be missed because the operator kept scanning.
- The app ever selects a cage the operator didn't physically scan, or completes a cage without a human tap.
- Any text implies SAP posted or the manifest printed when the status says otherwise.
- A second contract copy or contradictory spec appears again.

## 9. Testing

- JVM unit tests (existing style, `org.json`): request builders (envelope, exact timestamp format, barcode pre-check), response parsing for `load`/`pack`/`packs`, scan routing table, errorCode → screen mapping, outbox persist/retry/clear/dedupe, topic builders.
- Manual/device: walk the MQTT_CONTRACT §8 commissioning checklist against a running desktop, including two handhelds, cage switching, stale preview, and drop/restart retry. Inject scans with `adb shell am broadcast -a com.scanner.broadcast --es data "<qr>"` and the RFID action, as Station 1's test campaign does.
- Physical hardware, broker ACLs, SAP and printer acceptance remain site commissioning items.

## 10. Builder's calls (made in this design, open to change)

- Straight-to-workflow after login; no home menu.
- Outbox storage choice and retry timing.
- Exact operator wording and the friendly names for GRV/print states.
- Version 1.3.0 via the existing GitHub release workflow.
- Pending-request handling across sign-out/login (§5).

## 11. Phase 2 — operator directory on the Station 5 desktop (after Phase 1 ships)

User decision: once the Android app is built, add `operator_list_requested` support to the main (desktop) app. Order, following §2's rule that the contract changes first:

1. **Contract.** Add `operator_list_requested` → `operator_list` to `MQTT_CONTRACT.md` §3, adopting Station 1 contract v3 §4.5 (`Station_1/PPNAM_Station_1_AA/docs/Station1_MQTT_Contract_v3.md`) adapted to Station 5 topics: topic `PPNAM/station_5/{deviceId}/req/operator_list_requested`; plain pre-login envelope (no `operatorSessionId`, no extra fields); accepted response on `res/operator_list` with `operators: [{ username, displayName }]`. Only active password (SCRAM) users, sorted by `displayName`; no roles, tabs, badge tags or secrets; display-only, grants nothing; exact replay returns the stored list. Bump the contract revision date.
2. **Desktop.** Implement it in `ppnam_station5_packs.Infrastructure/Packs/HandheldMqttService.cs` from the existing user store, only for configured `HandheldDeviceIds`, with unit tests in `ppnam_station5_packs.Tests` following the existing handheld-service test style.
3. **Android.** No change expected beyond confirming the dropdown fills against the real desktop; update the code comments' contract citation to the new MQTT_CONTRACT section.

Phase 2 gets its own short plan when Phase 1 is done.
