import { test } from 'node:test';
import assert from 'node:assert/strict';
import { harness, scramClient, ROOT, stamp, T0 } from './helpers.js';

test('SCRAM round trip at the contract default of 100000 iterations, server signature verifies', () => {
  const h = harness({ config: { scramIterations: 100000 } });
  const client = scramClient('sim', 'sim123');
  const start = h.send('scram_start_requested', { purpose: 'login', username: 'sim', clientNonce: client.clientNonce });
  assert.equal(start.topic, `${ROOT}/HH-1/res/scram_challenge`);
  const c = start.res;
  assert.equal(c.accepted, true);
  assert.equal(c.nextAction, 'send_proof');
  assert.equal(c.iterations, 100000);
  assert.equal(c.expiresInSeconds, 60);
  assert.ok(c.serverNonce.startsWith(client.clientNonce) && c.serverNonce.length > client.clientNonce.length);
  assert.equal(c.serverFirstMessage, `r=${c.serverNonce},s=${c.salt},i=100000`);
  assert.equal(c.inResponseToMessageId, start.req.obj.messageId);
  assert.equal(c.schemaVersion, '4.1');
  assert.equal(c.isDuplicate, false);
  assert.match(c.timestampUtc, /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{6}Z$/);

  const p = client.prove(c);
  const proof = h.send('scram_proof_requested', {
    purpose: 'login', challengeId: c.challengeId, clientFinalWithoutProof: p.clientFinalWithoutProof, clientProof: p.clientProof,
  });
  assert.equal(proof.topic, `${ROOT}/HH-1/res/scram_proof_result`);
  const r = proof.res;
  assert.equal(r.accepted, true, r.reason);
  assert.equal(r.serverSignature, p.expectedServerSignature);
  assert.equal(r.operatorName, 'sim');
  assert.equal(r.role, 'Operator');
  assert.deepEqual(r.allowedTabs, ['cage_loading']);
  assert.equal(r.nextAction, 'select_cage');
  assert.match(r.operatorSessionId, /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);
  assert.equal(r.expiresAtUtc, stamp(T0 + 16 * 3600_000));
});

test('PBKDF2 uses the plain UTF-8 password (no NFKC), like Rfc2898DeriveBytes.Pbkdf2(string)', async () => {
  const { readFileSync } = await import('node:fs');
  const seed = JSON.parse(readFileSync(new URL('../seed.json', import.meta.url), 'utf8'));
  const wide = 'ｓｉｍ１２３'; // full-width "sim123"
  assert.equal(wide.normalize('NFKC'), 'sim123');
  seed.users.push({ name: 'wide', password: wide, role: 'Operator' });
  const h = harness({ seed });
  assert.match(h.login('wide', wide), /^[0-9a-f-]{36}$/);
  h.advance(3_000);
  assert.throws(() => h.login('wide', wide.normalize('NFKC')), /proof refused login_failed/);
});

test('wrong password is login_failed; used challenge is challenge_expired', () => {
  const h = harness();
  const client = scramClient('sim', 'not-the-password');
  const start = h.send('scram_start_requested', { purpose: 'login', username: 'sim', clientNonce: client.clientNonce });
  const p = client.prove(start.res);
  const fields = { purpose: 'login', challengeId: start.res.challengeId, clientFinalWithoutProof: p.clientFinalWithoutProof, clientProof: p.clientProof };
  const proof = h.send('scram_proof_requested', fields);
  assert.equal(proof.res.accepted, false);
  assert.equal(proof.res.errorCode, 'login_failed');
  assert.equal(proof.res.nextAction, 'retry_or_review');
  const again = h.send('scram_proof_requested', fields);
  assert.equal(again.res.errorCode, 'challenge_expired');
});

test('unknown user still receives a challenge (no account disclosure) and then fails', () => {
  const h = harness();
  const client = scramClient('ghost', 'whatever');
  const start = h.send('scram_start_requested', { purpose: 'login', username: 'ghost', clientNonce: client.clientNonce });
  assert.equal(start.res.accepted, true);
  const p = client.prove(start.res);
  const proof = h.send('scram_proof_requested', { purpose: 'login', challengeId: start.res.challengeId, clientFinalWithoutProof: p.clientFinalWithoutProof, clientProof: p.clientProof });
  assert.equal(proof.res.errorCode, 'login_failed');
});

test('challenge expires after 60 s', () => {
  const h = harness();
  const client = scramClient('sim', 'sim123');
  const start = h.send('scram_start_requested', { purpose: 'login', username: 'sim', clientNonce: client.clientNonce });
  h.advance(61_000);
  const p = client.prove(start.res);
  const proof = h.send('scram_proof_requested', { purpose: 'login', challengeId: start.res.challengeId, clientFinalWithoutProof: p.clientFinalWithoutProof, clientProof: p.clientProof });
  assert.equal(proof.res.errorCode, 'challenge_expired');
});

test('second start for the same device/user within 2 s is login_delayed; bad nonce is invalid_nonce', () => {
  const h = harness();
  const a = h.send('scram_start_requested', { purpose: 'login', username: 'sim', clientNonce: 'abcdefghijklmnopqrstuvwx' });
  assert.equal(a.res.accepted, true);
  const b = h.send('scram_start_requested', { purpose: 'login', username: 'SIM', clientNonce: 'abcdefghijklmnopqrstuvwx' });
  assert.equal(b.res.errorCode, 'login_delayed');
  h.advance(2_500);
  const c = h.send('scram_start_requested', { purpose: 'login', username: 'sim', clientNonce: 'short' });
  assert.equal(c.res.errorCode, 'invalid_nonce');
});

test('purpose other than login is invalid_purpose', () => {
  const h = harness();
  const r = h.send('scram_start_requested', { purpose: 'manager_approval', username: 'sim', clientNonce: 'abcdefghijklmnopqrstuvwx' });
  assert.equal(r.res.errorCode, 'invalid_purpose');
});

test('accepted auth replies replay for 60 s with isDuplicate:true', () => {
  const h = harness();
  const start = h.send('scram_start_requested', { purpose: 'login', username: 'sim', clientNonce: 'abcdefghijklmnopqrstuvwx' });
  const again = h.raw(start.req.topic, start.req.payload);
  assert.equal(again.res.isDuplicate, true);
  assert.equal(again.res.challengeId, start.res.challengeId);
  assert.equal(again.res.messageId, start.res.messageId);
});

test('manager logs in with role Manager; logout revokes and returns nextAction login', () => {
  const h = harness();
  const session = h.login('manager', 'manager123');
  const out = h.send('reader_logout_requested', {}, { session });
  assert.equal(out.topic, `${ROOT}/HH-1/res/operator_context`);
  assert.equal(out.res.accepted, true);
  assert.equal(out.res.nextAction, 'login');
  const after = h.send('cage_context_requested', {}, { session });
  assert.equal(after.res.errorCode, 'login_required');
  assert.equal(after.res.nextAction, 'login');
});

test('a session is bound to its device, and a new login on the device revokes the old session', () => {
  const h = harness();
  const s1 = h.login('sim', 'sim123', 'HH-1');
  const other = h.send('cage_context_requested', {}, { session: s1, device: 'HH-2' });
  assert.equal(other.res.errorCode, 'login_required');
  h.advance(3_000);
  const s2 = h.login('sim', 'sim123', 'HH-1');
  assert.notEqual(s1, s2);
  assert.equal(h.send('cage_context_requested', {}, { session: s1 }).res.errorCode, 'login_required');
  assert.equal(h.send('cage_context_requested', {}, { session: s2 }).res.accepted, true);
});

test('session expires after 16 h', () => {
  const h = harness();
  const s = h.login();
  h.advance(16 * 3600_000 + 1);
  assert.equal(h.send('cage_context_requested', {}, { session: s }).res.errorCode, 'login_required');
});
