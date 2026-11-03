# Station 5 backend simulator

A stand-in for the Station 5 Windows desktop so the Android handheld can be tested end to end
without SQL, SAP or the desktop app. One Node process runs:

- an MQTT 3.1.1 broker ([aedes](https://github.com/moscajs/aedes)) on `0.0.0.0:1883`;
- a station that answers handheld requests the way `HandheldMqttService` does at desktop commit
  `aef7bf7` (wire contract `MQTT_CONTRACT.md`, schema `4.1`); same validation order, error codes,
  replay store, one-use cage scan, preview tokens and response field names;
- an HTTP control API on `127.0.0.1:8787` for test scenarios.

All state is in memory and seeded from `seed.json` on start and on `POST /reset`.

## Run

```bash
cd tools/station5-sim
npm install
npm test        # node:test: handler, SCRAM, broker and control API
npm start       # Ctrl+C publishes retained "offline" and stops
```

Node 22+ (developed on Node 24). On Windows, when you run it in the background from a script, start
`node src/index.js` directly: stopping an `npm start` wrapper can leave the `node` child running
and holding the ports.

## Connect the handheld (Chainway C72 over USB)

```bash
adb reverse tcp:1883 tcp:1883
```

App broker settings: host `127.0.0.1`, port `1883`, TCP (no TLS, no WebSocket), username `ppnam`,
password `ppnam-sim`. The app's device id (`scanner_<12 hex>`) is accepted automatically. The
simulator logs it the first time it sees it.

**Another broker on this PC.** `adb reverse` connects to `127.0.0.1` on the PC. On Windows a
process bound to `127.0.0.1:1883`, such as the auto-start **Mosquitto** service, wins over the
simulator's `0.0.0.0:1883`. The handheld would then reach Mosquitto, not the simulator. At startup the
simulator checks for this and prints a `WARNING`. Either stop that service, or move the simulator
and keep the app on 1883:

```bash
SIM_BROKER_PORT=1884 npm start
adb reverse tcp:1883 tcp:1884
```

## Credentials and seed data

| What | Value |
|---|---|
| Broker | `ppnam` / `ppnam-sim` (`SIM_BROKER_USER` / `SIM_BROKER_PASS`) |
| Operator | `sim` / `sim123` (role `Operator`) |
| Manager | `manager` / `manager123` (role `Manager`) |
| Cages | `CAGE-001`..`CAGE-003` = RFID `E2000017CAGE0001`..`E2000017CAGE0003`; `CAGE-009` / `E2000017CAGE0009` is **inactive** (`unknown_cage`) |

Packs use the labels from `PPNAM-Station-5/DOCS/Station5_QR_Testing_20261102.md`, scanned as
`ProductionOrderNumber,OuterCode,BagID`:

| Bag IDs | State | Expected result |
|---|---|---|
| `S5QA-20261102-S001`..`S006`, `S5QA-20261102-F001`..`F012` | Pass (18 Packs) | allocate |
| `S5QA-20261102-F013` (`510023066,9085000047,S5QA-20261102-F013`) | Underweight, QC pending | `qc_required` |
| `S5QA-20261102-F014` (`510023064,9086000015,S5QA-20261102-F014`) | legacy record, no production order | `production_order_required` |
| `S5QA-20261102-F015` (`510023063,9086000002,S5QA-20261102-F015`) | Overweight, Manager accepted | allocate (`ManagerAccepted`) |
| `S5QA-20261102-F016` (`510023059,9030000158,S5QA-20261102-F016`) | never weighed | `pack_not_found` |

Sample QR: `510023132,9030000104,S5QA-20261102-S001`. `GET /state` lists every Pack with its `qr`.

## Environment

| Variable | Default | Meaning |
|---|---|---|
| `SIM_DEVICE_IDS` | `*` | Comma list of allowed device ids (`HandheldDeviceIds`); `*` accepts any |
| `SIM_SCRAM_ITERATIONS` | `100000` | PBKDF2 iterations for the seeded users |
| `SIM_ALLOW_MIXED_OUTER_CODES` | `true` | `false` makes mixing refuse with `mixed_outer_code` |
| `SIM_BROKER_HOST` / `SIM_BROKER_PORT` | `0.0.0.0` / `1883` | Broker bind |
| `SIM_CONTROL_HOST` / `SIM_CONTROL_PORT` | `127.0.0.1` / `8787` | Control API bind |
| `SIM_BROKER_USER` / `SIM_BROKER_PASS` | `ppnam` / `ppnam-sim` | Broker credentials |

## Control API

Every response is JSON. Examples use curl from Git Bash:

```bash
curl -s localhost:8787/state                     # station flags, cages, loads + packs, Packs, sessions (no secrets)
curl -s localhost:8787/log                       # last 200 request/response summaries
curl -s -X POST localhost:8787/reset             # reseed everything and forget scans/tokens/challenges
curl -s -X POST localhost:8787/station/offline   # retained "offline" + stop answering requests
curl -s -X POST localhost:8787/station/online    # retained "online" + answer again
curl -s -X POST localhost:8787/drop-next-reply   # next reply is not published, but it is stored:
                                                 # an identical-bytes retry gets it with isDuplicate:true
curl -s -X POST localhost:8787/pause             # every new request -> processing_paused (not stored)
curl -s -X POST localhost:8787/resume
curl -s -X POST localhost:8787/restart-memory    # simulate a desktop restart: scan arms, preview tokens
                                                 # and SCRAM challenges/replies are lost; SQL state stays
curl -s -X POST localhost:8787/allocate-from-other-device \
     -d '{"loadId":"<loadId>","bagId":"S5QA-20261102-S006"}'   # another device adds a Pack -> cage_contents_changed
curl -s -X POST localhost:8787/advance-receipt -d '{"loadId":"<loadId>"}'
     # one receipt-worker step: GRV Pending -> Processing -> Succeeded (SIM-GRV-nnnnn), then print
     # Pending -> Processing -> Succeeded. Explicit states also work:
     # -d '{"loadId":"<loadId>","grvState":"OutcomeUnknown","error":"SAP timeout"}'
```

Errors: `400` bad body, `404` unknown route, `409` workflow refusal (`{"error": "<code>"}`).

## Behaviour notes

- Station status: retained raw `online` on `PPNAM/station_5/status` once the broker is up; `offline`
  on Ctrl+C / SIGTERM or `POST /station/offline`. Clients may not publish that topic.
- Requests must be QoS 1, not retained, at most 16,384 bytes, valid UTF-8 JSON objects, depth at most 16,
  with a `messageId`. Anything else, or an unknown device, gets **no reply** (as on the desktop).
- Replies are QoS 1, retain false, on `PPNAM/station_5/{deviceId}/res/{responseType}`. Unsupported
  request types answer `unsupported_request` on `res/request_rejected`, after the session check.
- The stdout log shows type, device, messageId, accepted and errorCode for every request. It never
  shows passwords, proofs, salts, signatures or session ids. Device presence messages are logged too.
- Timestamps in `load`/`packs` (`openedAtUtc`, `completedAtUtc`, `allocatedAtUtc`) use the .NET
  `DateTimeOffset` form with an explicit `+00:00` offset, not `Z`. Envelope timestamps use
  `yyyy-MM-ddTHH:mm:ss.ffffffZ`.
