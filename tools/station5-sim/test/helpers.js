// Test harness: drives the pure station handler without a broker, and implements the
// handheld side of MQTT_CONTRACT §2-§3 independently of the server code under test.
import crypto from 'node:crypto';
import { createStation } from '../src/station.js';
import { createStore } from '../src/store.js';

export const T0 = Date.parse('2026-11-03T10:00:00.000Z');
export const ROOT = 'PPNAM/station_5';

export function stamp(ms) {
  return new Date(ms).toISOString().replace(/\.(\d{3})Z$/, '.$1000Z');
}

// ---- SCRAM-SHA-256 client, written from the contract text (§3) -------------------------
export function scramClient(username, password) {
  const clientNonce = crypto.randomBytes(24).toString('base64');
  const escaped = username.trim().replace(/=/g, '=3D').replace(/,/g, '=2C');
  const clientFirstBare = `n=${escaped},r=${clientNonce}`;
  return {
    clientNonce,
    prove(challenge) {
      const clientFinalWithoutProof = `c=biws,r=${challenge.serverNonce}`;
      const authMessage = `${clientFirstBare},${challenge.serverFirstMessage},${clientFinalWithoutProof}`;
      const salted = crypto.pbkdf2Sync(Buffer.from(password, 'utf8'),
        Buffer.from(challenge.salt, 'base64'), challenge.iterations, 32, 'sha256');
      const hmac = (key, data) => crypto.createHmac('sha256', key).update(data).digest();
      const clientKey = hmac(salted, 'Client Key');
      const storedKey = crypto.createHash('sha256').update(clientKey).digest();
      const clientSignature = hmac(storedKey, Buffer.from(authMessage, 'utf8'));
      const proof = Buffer.alloc(32);
      for (let i = 0; i < 32; i++) proof[i] = clientKey[i] ^ clientSignature[i];
      const serverKey = hmac(salted, 'Server Key');
      const expectedServerSignature = hmac(serverKey, Buffer.from(authMessage, 'utf8')).toString('base64');
      return { clientFinalWithoutProof, clientProof: proof.toString('base64'), expectedServerSignature };
    },
  };
}

// ---- Harness ---------------------------------------------------------------------------
export function harness(options = {}) {
  const clock = { ms: options.nowMs ?? T0 };
  const config = { deviceIds: ['HH-1', 'HH-2'], scramIterations: 4096, allowMixedOuterCodes: true, ...options.config };
  const store = createStore({ iterations: config.scramIterations, seed: options.seed });
  store.reset(clock.ms);
  const log = [];
  const station = createStation({ store, config, log: (entry) => log.push(entry) });

  let seq = 0;
  function body(type, fields = {}, opts = {}) {
    const device = opts.device ?? 'HH-1';
    const obj = {
      messageId: opts.messageId ?? `msg-${++seq}-${crypto.randomUUID()}`,
      schemaVersion: '4.1',
      deviceId: device,
      ...(opts.session ? { operatorSessionId: opts.session } : {}),
      timestampUtc: opts.timestampUtc ?? stamp(opts.timestampMs ?? clock.ms),
      ...fields,
    };
    return { topic: `${ROOT}/${device}/req/${type}`, payload: JSON.stringify(obj), obj };
  }
  function raw(topic, payload, meta = { qos: 1, retain: false }) {
    const out = station.handle(topic, payload, meta, clock.ms);
    return out ? { topic: out.topic, json: out.json, res: JSON.parse(out.json) } : null;
  }
  function send(type, fields, opts) {
    const req = body(type, fields, opts);
    const out = raw(req.topic, req.payload, opts?.meta);
    if (out) out.req = req;
    return out;
  }
  function login(username = 'sim', password = 'sim123', device = 'HH-1') {
    const client = scramClient(username, password);
    const start = send('scram_start_requested', { purpose: 'login', username, clientNonce: client.clientNonce }, { device });
    if (!start.res.accepted) throw new Error(`start refused ${start.res.errorCode}`);
    const p = client.prove(start.res);
    const proof = send('scram_proof_requested', {
      purpose: 'login', challengeId: start.res.challengeId,
      clientFinalWithoutProof: p.clientFinalWithoutProof, clientProof: p.clientProof,
    }, { device });
    if (!proof.res.accepted) throw new Error(`proof refused ${proof.res.errorCode}`);
    if (proof.res.serverSignature !== p.expectedServerSignature) throw new Error('server signature mismatch');
    return proof.res.operatorSessionId;
  }
  const advance = (ms) => { clock.ms += ms; };
  return { clock, store, station, log, body, raw, send, login, advance };
}

export const QR = {
  S001: '510023132,9030000104,S5QA-20261102-S001',
  S002: '510023102,9030000140,S5QA-20261102-S002',
  S003: '510023094,9000002084,S5QA-20261102-S003',
};
export const RFID1 = 'E2000017CAGE0001';
export const RFID2 = 'E2000017CAGE0002';
