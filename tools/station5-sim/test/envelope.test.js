import { test } from 'node:test';
import assert from 'node:assert/strict';
import { harness, ROOT, stamp, T0, QR, RFID1 } from './helpers.js';

test('requests at another QoS, retained requests and oversized payloads get no reply', () => {
  const h = harness();
  const req = h.body('cage_context_requested', {});
  assert.equal(h.raw(req.topic, req.payload, { qos: 0, retain: false }), null);
  assert.equal(h.raw(req.topic, req.payload, { qos: 2, retain: false }), null);
  assert.equal(h.raw(req.topic, req.payload, { qos: 1, retain: true }), null);
  const big = h.body('cage_context_requested', { pad: 'x'.repeat(16_400) });
  assert.equal(h.raw(big.topic, big.payload), null);
});

test('unknown devices, malformed JSON, non-objects, missing/long messageId and deep nesting get no reply', () => {
  const h = harness();
  const unknown = h.body('cage_context_requested', {}, { device: 'HH-9' });
  assert.equal(h.raw(unknown.topic, unknown.payload), null);
  const t = `${ROOT}/HH-1/req/cage_context_requested`;
  assert.equal(h.raw(t, '{not json'), null);
  assert.equal(h.raw(t, '[1,2]'), null);
  assert.equal(h.raw(t, '{"schemaVersion":"4.1"}'), null);
  assert.equal(h.raw(t, JSON.stringify({ messageId: 'x'.repeat(101) })), null);
  let deep = '1';
  for (let i = 0; i < 16; i++) deep = `[${deep}]`;
  assert.equal(h.raw(t, JSON.stringify({ messageId: 'deep' }).replace('}', `,"d":${deep}}`)), null);
  assert.equal(h.raw(`${ROOT}/HH-1/res/cage_context_requested`, JSON.stringify({ messageId: 'a' })), null);
  assert.equal(h.raw(`${ROOT}/status`, 'online'), null);
});

test('wildcard device list accepts any device id and logs it on first sight', () => {
  const h = harness({ config: { deviceIds: '*' } });
  const r = h.send('cage_context_requested', {}, { device: 'scanner_a1b2c3d4e5f6' });
  assert.equal(r.topic, `${ROOT}/scanner_a1b2c3d4e5f6/res/cage_context_result`);
  assert.equal(r.res.errorCode, 'invalid_request');
  assert.ok(h.log.some((e) => e.event === 'device_first_seen' && e.device === 'scanner_a1b2c3d4e5f6'));
});

test('duplicate or case-colliding properties and plaintext passwords are invalid_request', () => {
  const h = harness();
  const t = `${ROOT}/HH-1/req/scram_start_requested`;
  const base = `"messageId":"m1","schemaVersion":"4.1","deviceId":"HH-1","timestampUtc":"${stamp(T0)}","purpose":"login","username":"sim","clientNonce":"abcdefghijklmnopqrstuvwx"`;
  assert.equal(h.raw(t, `{${base},"Username":"x"}`).res.errorCode, 'invalid_request');
  assert.equal(h.raw(t, `{${base.replace('m1', 'm2')},"password":"sim123"}`).res.errorCode, 'invalid_request');
  assert.equal(h.raw(t, `{${base.replace('m1', 'm3')},"ManagerPassword":"x"}`).res.errorCode, 'invalid_request');
});

test('schema version and payload deviceId must match: invalid_envelope', () => {
  const h = harness();
  const r = h.send('cage_context_requested', {}, {});
  const bad = JSON.parse(r.req.payload);
  bad.messageId = 'env-1'; bad.schemaVersion = '4.0';
  assert.equal(h.raw(r.req.topic, JSON.stringify(bad)).res.errorCode, 'invalid_envelope');
  bad.messageId = 'env-2'; bad.schemaVersion = '4.1'; bad.deviceId = 'HH-2';
  assert.equal(h.raw(r.req.topic, JSON.stringify(bad)).res.errorCode, 'invalid_envelope');
});

test('timestamp outside ±5 minutes is request_expired; bad format too', () => {
  const h = harness();
  const s = h.login();
  const old = h.send('cage_context_requested', {}, { session: s, timestampMs: T0 - 5 * 60_000 - 1 });
  assert.equal(old.res.errorCode, 'request_expired');
  const future = h.send('cage_context_requested', {}, { session: s, timestampMs: T0 + 5 * 60_000 + 1 });
  assert.equal(future.res.errorCode, 'request_expired');
  const edge = h.send('cage_context_requested', {}, { session: s, timestampMs: T0 - 5 * 60_000 });
  assert.equal(edge.res.accepted, true);
  const millis = h.send('cage_context_requested', {}, { session: s, timestampUtc: '2026-11-03T10:00:00.000Z' });
  assert.equal(millis.res.errorCode, 'request_expired');
});

test('exact replay returns the stored reply with isDuplicate:true, even after the timestamp window', () => {
  const h = harness();
  const s = h.login();
  const sel = h.send('cage_select_requested', { rfidTag: RFID1 }, { session: s });
  const alloc = h.send('finished_good_allocate_requested', { loadId: sel.res.load.loadId, barcode: QR.S001 }, { session: s });
  assert.equal(alloc.res.accepted, true);
  h.advance(10 * 60_000);
  const again = h.raw(alloc.req.topic, alloc.req.payload);
  assert.equal(again.res.isDuplicate, true);
  assert.equal(again.res.messageId, alloc.res.messageId);
  assert.equal(again.res.load.packCount, 1);
  assert.deepEqual({ ...again.res, isDuplicate: false }, alloc.res);
  assert.equal(h.store.manifest(sel.res.load.loadId).packs.length, 1);
});

test('changed body under the same messageId is message_id_conflict and does not mutate', () => {
  const h = harness();
  const s = h.login();
  const sel = h.send('cage_select_requested', { rfidTag: RFID1 }, { session: s });
  const alloc = h.send('finished_good_allocate_requested', { loadId: sel.res.load.loadId, barcode: QR.S001 }, { session: s });
  const changed = JSON.parse(alloc.req.payload);
  changed.barcode = QR.S002;
  const out = h.raw(alloc.req.topic, JSON.stringify(changed));
  assert.equal(out.res.errorCode, 'message_id_conflict');
  assert.equal(out.res.accepted, false);
  const reordered = h.raw(alloc.req.topic, alloc.req.payload.replace('{', '{ '));
  assert.equal(reordered.res.errorCode, 'message_id_conflict');
  assert.equal(h.store.manifest(sel.res.load.loadId).packs.length, 1);
});

test('unsupported request types answer unsupported_request on res/request_rejected', () => {
  const h = harness();
  const s = h.login();
  const r = h.send('login_requested', { badgeTag: 'X' }, { session: s });
  assert.equal(r.topic, `${ROOT}/HH-1/res/request_rejected`);
  assert.equal(r.res.errorCode, 'unsupported_request');
  assert.equal(r.res.nextAction, 'retry_or_review');
});

test('processing_paused is not stored as a replay: identical bytes succeed after resume', () => {
  const h = harness();
  const s = h.login();
  h.station.setPaused(true);
  const r = h.send('cage_context_requested', {}, { session: s });
  assert.equal(r.res.errorCode, 'processing_paused');
  h.station.setPaused(false);
  const again = h.raw(r.req.topic, r.req.payload);
  assert.equal(again.res.accepted, true);
  assert.equal(again.res.isDuplicate, false);
});

test('pause is checked first: exact replays, expired retries and bad envelopes all get processing_paused, nothing stored', () => {
  const h = harness();
  const s = h.login();
  const stored = h.send('cage_context_requested', { correlationKey: 'k1' }, { session: s });
  assert.equal(stored.res.accepted, true);
  h.station.setPaused(true);
  const replay = h.raw(stored.req.topic, stored.req.payload);
  assert.equal(replay.res.errorCode, 'processing_paused');
  assert.equal(replay.res.isDuplicate, false);
  assert.equal('operatorSessionId' in replay.res, false, 'refused before the envelope fields are read');
  assert.equal('correlationKey' in replay.res, false);
  const expired = h.send('cage_context_requested', {}, { session: s, timestampMs: h.clock.ms - 6 * 60_000 });
  assert.equal(expired.res.errorCode, 'processing_paused');
  const badEnvelope = h.raw(`${ROOT}/HH-1/req/cage_context_requested`, JSON.stringify({ messageId: 'pz-1', schemaVersion: '9' }));
  assert.equal(badEnvelope.res.errorCode, 'processing_paused');
  const auth = h.send('scram_start_requested', { purpose: 'login', username: 'sim', clientNonce: 'abcdefghijklmnopqrstuvwx' });
  assert.equal(auth.res.errorCode, 'processing_paused');
  h.station.setPaused(false);
  h.advance(3_000); // clear the 2 s SCRAM start rate limit left by login()
  // Nothing was stored or reserved while paused.
  const original = h.raw(stored.req.topic, stored.req.payload);
  assert.equal(original.res.isDuplicate, true);
  assert.equal(original.res.messageId, stored.res.messageId);
  assert.equal(h.raw(expired.req.topic, expired.req.payload).res.errorCode, 'request_expired');
  assert.equal(h.raw(`${ROOT}/HH-1/req/cage_context_requested`, JSON.stringify({ messageId: 'pz-1', schemaVersion: '9' })).res.errorCode, 'invalid_envelope', 'evaluated normally after resume');
  assert.equal(h.raw(auth.req.topic, auth.req.payload).res.accepted, true);
});

test('an offline station holds requests and processes them in order, at the current clock, when back online', () => {
  const h = harness();
  const s = h.login();
  const done = h.send('cage_context_requested', {}, { session: s });
  h.station.setOnline(false);
  const old = h.body('cage_context_requested', {}, { session: s });
  assert.equal(h.raw(old.topic, old.payload), null);
  h.advance(4 * 60_000);
  const fresh = h.body('cage_select_requested', { rfidTag: RFID1 }, { session: s });
  assert.equal(h.raw(fresh.topic, fresh.payload), null);
  assert.equal(h.raw(done.req.topic, done.req.payload), null, 'exact retry of an answered request');
  assert.equal(h.raw(fresh.topic, fresh.payload, { qos: 0, retain: false }), null, 'QoS 0 is not held');
  assert.equal(h.station.queuedCount(), 3);
  h.advance(2 * 60_000); // `old` is now 6 minutes old
  assert.deepEqual(h.station.drainQueue(h.clock.ms), [], 'nothing is processed while still offline');
  h.station.setOnline(true);
  const out = h.station.drainQueue(h.clock.ms).map((r) => JSON.parse(r.json));
  assert.deepEqual(out.map((r) => r.inResponseToMessageId), [old.obj.messageId, fresh.obj.messageId, done.req.obj.messageId]);
  assert.equal(out[0].errorCode, 'request_expired');
  assert.equal(out[1].accepted, true);
  assert.equal(out[1].cageScanExpiresAtUtc, stamp(h.clock.ms + 120_000));
  assert.equal(out[2].isDuplicate, true);
  assert.equal(h.station.queuedCount(), 0);
});

test('response envelope echoes correlation fields and never carries secrets in the log', () => {
  const h = harness();
  const s = h.login();
  const r = h.send('cage_context_requested', { correlationKey: 'screen-1' }, { session: s });
  assert.equal(r.res.correlationKey, 'screen-1');
  assert.equal(r.res.operatorSessionId, s);
  assert.equal(r.res.deviceId, 'HH-1');
  assert.equal(typeof r.res.processingDurationMs, 'number');
  assert.equal(r.res.serverSentAtUtc, stamp(T0));
  const text = JSON.stringify(h.log);
  for (const secret of ['sim123', 'clientProof', 'serverSignature', '"salt"', s]) assert.ok(!text.includes(secret), secret);
  const entry = h.log.find((e) => e.type === 'cage_context_requested');
  assert.deepEqual(Object.keys(entry).sort(), ['accepted', 'at', 'device', 'errorCode', 'event', 'isDuplicate', 'messageId', 'type'].sort());
});
