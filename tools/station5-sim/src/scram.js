// Port of the desktop StationAuthentication SCRAM-SHA-256 server (BeginScramAsync / FinishScramAsync).
import crypto from 'node:crypto';
import { WorkflowError, newGuidN } from './util.js';

const hmac = (key, data) => crypto.createHmac('sha256', key).update(data).digest();
const sha256 = (data) => crypto.createHash('sha256').update(data).digest();

/** StationAuthentication.CreateVerifier: only salt, iterations, StoredKey and ServerKey are kept. */
export function createVerifier(name, password, role, iterations, { active = true, userId = crypto.randomUUID() } = {}) {
  const salt = crypto.randomBytes(16);
  const salted = crypto.pbkdf2Sync(Buffer.from(password.normalize('NFKC'), 'utf8'), salt, iterations, 32, 'sha256');
  const verifier = {
    userId, name: name.trim(), role, active, salt: salt.toString('base64'), iterations,
    storedKey: sha256(hmac(salted, 'Client Key')).toString('base64'),
    serverKey: hmac(salted, 'Server Key').toString('base64'),
  };
  salted.fill(0);
  return verifier;
}

/** Same shape as a real verifier but unusable, so a challenge never reveals whether an account exists. */
function syntheticVerifier(name, iterations) {
  return {
    userId: crypto.randomUUID(), name: name.trim(), role: 'Operator', active: false,
    salt: crypto.randomBytes(16).toString('base64'), iterations,
    storedKey: crypto.randomBytes(32).toString('base64'), serverKey: crypto.randomBytes(32).toString('base64'),
  };
}

const STRICT_BASE64 = /^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/;

export function createScramServer({ findUser, createSession, iterations }) {
  const failures = new Map(); // key (lowercase) -> untilMs
  const challenges = new Map(); // challengeId -> { user, device, first, server, untilMs }

  function checkRate(key, nowMs) {
    const k = key.toLowerCase();
    const until = failures.get(k);
    if (until !== undefined && until > nowMs) throw new WorkflowError('login_delayed', 'Wait a few seconds before trying this account again.');
    failures.set(k, nowMs + 2000);
    if (failures.size > 2000) for (const [key2, u] of failures) if (u < nowMs) failures.delete(key2);
  }

  function begin(username, device, clientNonce, nowMs) {
    checkRate(`${device}:${username}`, nowMs);
    if (clientNonce.length < 16 || clientNonce.length > 160 || [...clientNonce].some((c) => c.charCodeAt(0) < 33 || c.charCodeAt(0) > 126 || c === ','))
      throw new WorkflowError('invalid_nonce', 'A valid random SCRAM nonce is required.');
    for (const [id, c] of challenges) if (c.untilMs < nowMs) challenges.delete(id);
    if (challenges.size > 1000) throw new WorkflowError('auth_busy', 'Too many pending authentication requests.');
    const user = findUser(username) ?? syntheticVerifier(username, iterations);
    const nonce = clientNonce + crypto.randomBytes(24).toString('base64');
    const first = `n=${username.trim().replace(/=/g, '=3D').replace(/,/g, '=2C')},r=${clientNonce}`;
    const server = `r=${nonce},s=${user.salt},i=${user.iterations}`;
    const challengeId = newGuidN();
    challenges.set(challengeId, { user, device, first, server, untilMs: nowMs + 60_000 });
    return { challengeId, serverNonce: nonce, salt: user.salt, iterations: user.iterations, serverFirst: server };
  }

  function finish(device, challengeId, clientFinalWithoutProof, proof, nowMs) {
    const c = challenges.get(challengeId);
    challenges.delete(challengeId);
    if (!c || c.device !== device || c.untilMs < nowMs) throw new WorkflowError('challenge_expired', 'Start a new login challenge.');
    const nonce = c.server.split(',')[0].slice(2);
    if (clientFinalWithoutProof !== `c=biws,r=${nonce}`) throw new WorkflowError('invalid_proof', 'The SCRAM channel binding or nonce is incorrect.');
    const message = Buffer.from(`${c.first},${c.server},${clientFinalWithoutProof}`, 'utf8');
    if (!STRICT_BASE64.test(proof)) throw new WorkflowError('invalid_proof', 'The login proof is invalid.');
    const p = Buffer.from(proof, 'base64');
    const stored = Buffer.from(c.user.storedKey, 'base64');
    const signature = hmac(stored, message);
    if (p.length !== 32) throw new WorkflowError('invalid_proof', 'The login proof is invalid.');
    for (let i = 0; i < 32; i++) p[i] ^= signature[i];
    if (!crypto.timingSafeEqual(sha256(p), stored) || !c.user.active)
      throw new WorkflowError('login_failed', 'The username or password is incorrect, or the account is inactive.');
    const identity = createSession(c.user.userId, device, nowMs);
    return { identity, serverSignature: hmac(Buffer.from(c.user.serverKey, 'base64'), message).toString('base64') };
  }

  return { begin, finish, clear: () => { failures.clear(); challenges.clear(); } };
}
