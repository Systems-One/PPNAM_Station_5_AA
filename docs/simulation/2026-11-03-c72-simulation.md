# Station 5 handheld — device simulation on the Chainway C72 (2026-11-03)

**App:** `PPNAM_Station_5_AA` v1.3.0 (6), branch `feature/cage-loading`.
**Device:** Chainway C72 (`HC720DE260100322`), device id `scanner_5c64df8d86a8`.
**Backend:** `tools/station5-sim` (stand-in for the Station 5 desktop at commit `aef7bf7`, contract `MQTT_CONTRACT.md` revision 2 Nov 2026 + tag-per-Pack update), run with `SIM_BROKER_PORT=1884` and `adb reverse tcp:1883 tcp:1884` (this PC's Mosquitto service owns `127.0.0.1:1883`).
**Method:** broker settings entered through the app's Settings screen (supervisor PIN), sign-in through the Login screen, scans injected with the Chainway broadcasts (`com.rscja.scanner.action.scanner.RFID`, `com.scanner.broadcast`), simulator control API for station-side events. Screen state read with `uiautomator` and screenshots.

## Results — round 2 (after fix batch 1): all pass

| # | Scenario (contract §8) | Expected | Result |
|---|---|---|---|
| 1 | Broker settings, Test & Apply | Connected; subscribes only to own `res/+`, station `status` (+ own presence) | Pass |
| 2 | SCRAM sign-in `sim`/`sim123` | Server signature verified, session, idle "Scan a cage tag" | Pass ([img](img/s04-after-login.png)) |
| 3 | Cage tag → Pack QR | Armed "Scan ONE Pack QR now" + 2:00 station-clock countdown; Pack accepted, count/kg from station, back to "Scan the cage tag" | Pass ([img](img/s06-pack1.png)) |
| 4 | Pack QR without a fresh tag | Not sent; full-screen "Do NOT load this Pack yet" | Pass (fixed D2, [img](img/r2-d2-unarmed.png)) |
| 5 | Re-scan a Pack already in this cage | Counted once; "already in this cage", no success beep | Pass (fixed D3) |
| 6 | QC-pending / legacy no-order / unweighed / mismatched label / Pack in another cage | `qc_required`, `production_order_required`, `pack_not_found`, `outer_code_changed`, `pack_allocated` full-screen refusals; arm spent | Pass ([img](img/s09-qc.png)) |
| 7 | Malformed QR | Local "Label not recognised", nothing sent, arm kept | Pass |
| 8 | Unregistered/inactive cage | "Cage not recognised" → idle | Pass |
| 9 | Staff badge tag on cage screen | Ignored | Pass |
| 10 | Switch cages, Manager-accepted Pack | New banner; `Manager accepted` shown | Pass |
| 11 | Tag expiry (2 min) | Local disarm "Cage tag expired — scan it again"; late QR not sent | Pass |
| 12 | Complete cage → Keep loading → Complete cage → confirm | Sheet with count/kg/job/countdown; cancel sends nothing; confirm → Completed, SAP "Pending", print "Waiting for receipt" | Pass ([img](img/s11-sheet.png), [img](img/s12-completed.png)) |
| 13 | Another device adds a Pack after the summary | `cage_contents_changed` refusal, refresh to new count, "Tap Complete cage again…" | Pass ([img](img/s13-contents-changed.png)) |
| 14 | Station offline | Full-screen "Station offline — don't load Packs", scans blocked, summary sheet hidden and restored | Pass ([img](img/r2-d7-offline.png), [img](img/r2-d7-back.png)) |
| 15 | Lost reply (allocation, cage select) | "Sending…", identical-bytes retry, station replay `isDuplicate:true`, counted once | Pass ([img](img/s15-sending.png)) |
| 16 | Sign out while a Pack reply is missing — station had recorded it | Confirm dialog; after sign-in "… was recorded in CAGE-001"; old request not re-sent | Pass |
| 17 | Same, station never processed it | "Last Pack … was not confirmed. Rescan cage …, then rescan that Pack." | Pass ([img](img/s18-not-confirmed.png)) |
| 18 | App killed with a Pack reply missing | Saved request survives; recovery read after sign-in | Pass |
| 19 | Station loses the session | Sign-in screen "Your session ended. Sign in and rescan the cage." | Pass ([img](img/s19-session-lost.png)) |

## Defects found in round 1 and fixed (commits `88b3543`, `f4438ec`)

| ID | Defect | Fix |
|---|---|---|
| D1 | Complete cage button cut off on 1080×1920 | Tightened spacing; fully visible ([img](img/r2-d1-layout.png)) |
| D2 | Unarmed Pack QR only showed a small notice while the green "Added" card stayed visible | Full-screen stop |
| D3 | Re-scanning a Pack already in the cage showed "✓ Added" again | "already in this cage" notice, no success beep |
| D4 | "1 Packs" | Android plurals |
| D6 | App normalised passwords (NFKC) before SCRAM; desktop uses raw UTF-8 — non-ASCII passwords would fail on the real desktop | Raw UTF-8, with test vectors |
| D7 | Completion sheet stayed tappable above the offline screen | Hidden while offline, restored after |
| D8 | Notices could be off-screen when scrolled | Scroll to top on new notice |

## Final fix wave (commit `6cb1244`), verified on the C72

- A scan while "Sending…" now beeps and shows "Wait for Sending… to finish, then scan again." (nothing sent).
- The station-offline screen starts below the header, so sign-out and Settings stay reachable.

## Observation for the desktop owner

While the station is paused (`processing_paused`), an allocation still consumes the cage-tag arm (the desktop's catch block calls `DiscardPendingScan`), so the identical retry after resume returns `cage_scan_required`, although contract §6 describes `processing_paused` as transient with identical-byte retry. The app handles this safely (full-screen "Scan the cage tag first"); the contract or desktop should be aligned.

## Not covered by this simulation (still needed at the site)

- The real Station 5 desktop, SQL, SAP Receipt from Production and manifest printing (the simulator only models their states).
- The physical RFID and barcode triggers (scans were injected as the same broadcasts the Chainway scanner service sends).
- Broker TLS/ACLs on the production broker, the real device id registered in `HandheldDeviceIds`, real cage RFIDs and printed Pack labels.
- Badge login (`login_requested`) is offered on the login screen but is not in `MQTT_CONTRACT.md`; the desktop rejects it. Follow-up.
