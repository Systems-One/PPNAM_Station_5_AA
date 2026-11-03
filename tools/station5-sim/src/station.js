// Pure port of the desktop HandheldMqttService request handling at contract commit aef7bf7
// (HandleSerialAsync, AllocateScannedPack, DiscardPendingScan, PreviewCompletion, and the
// QoS/retain gate of RouteIncomingMessage). No broker here: handle() takes a topic, the UTF-8
// payload text and the MQTT flags, and returns the response to publish or null for "no reply".
import crypto from 'node:crypto';
import { createScramServer } from './scram.js';
import {
  WorkflowError, stamp, parseStamp, hasControl, isBlank, parseInt32, parseGuid, EMPTY_GUID,
  newGuid, newGuidN, sha256Hex, inspectJson,
} from './util.js';

export const ROOT = 'PPNAM/station_5';
const MAX_BYTES = 16384;
const SCAN_MS = 2 * 60_000;
const CONFIRM_MS = 2 * 60_000;
const AUTH_REPLY_MS = 60_000;
const MAX_QUEUED = 1000;

const RESPONSE_TYPES = {
  scram_start_requested: 'scram_challenge',
  scram_proof_requested: 'scram_proof_result',
  reader_logout_requested: 'operator_context',
  cage_select_requested: 'cage_context_result',
  cage_context_requested: 'cage_context_result',
  finished_good_allocate_requested: 'finished_good_allocation_result',
  cage_complete_requested: 'cage_completion_result',
  cage_complete_confirmed: 'cage_completion_result',
};

const own = (o, k) => Object.prototype.hasOwnProperty.call(o, k);

/** HandheldMqttService.S(root, name, required). */
function S(root, name, required = true) {
  if (!own(root, name) || typeof root[name] !== 'string') {
    if (!required) return '';
    throw new WorkflowError('invalid_request', `${name} is required.`);
  }
  const v = root[name];
  if (v.length > 300 || hasControl(v) || (required && isBlank(v))) throw new WorkflowError('invalid_request', `${name} is invalid.`);
  return v;
}
/** HandheldMqttService.Id(root, name). */
function Id(root, name) {
  const id = parseGuid(S(root, name));
  if (!id || id === EMPTY_GUID) throw new WorkflowError('invalid_request', `${name} must be a nonempty UUID.`);
  return id;
}
/** HandheldMqttService.ProductionOrder(value). */
function productionOrder(value) {
  if (isBlank(value)) return null;
  const n = parseInt32(value.trim());
  if (n === null || n <= 0) throw new WorkflowError('production_order_invalid', 'The production order must be the positive SAP DocumentNumber.');
  return String(n);
}
/** PackBarcode.Parse(raw). */
function parseBarcode(raw) {
  if (isBlank(raw) || raw.length > 212 || hasControl(raw))
    throw new WorkflowError('invalid_barcode', 'Scan exactly ProductionOrderNumber,OuterCode,BagID with no control characters.');
  const parts = raw.split(',');
  const order = parts.length === 3 ? parseInt32(parts[0].trim()) : null;
  if (parts.length !== 3 || parts.some((p) => isBlank(p) || p.trim().length > 100) || parts[0].trim().length > 10 || order === null || order <= 0)
    throw new WorkflowError('invalid_barcode', 'The Pack QR must contain a positive SAP production order number, Outer Code and Bag ID, in that order.');
  return { bagId: parts[2].trim(), outerCode: parts[1].trim(), productionOrderNumber: String(order) };
}

/**
 * @param {{store: ReturnType<import('./store.js').createStore>, config: {deviceIds: string[]|'*', scramIterations: number, allowMixedOuterCodes: boolean}, log?: (entry: object) => void}} deps
 */
export function createStation({ store, config, log = () => {} }) {
  const auth = createScramServer({ findUser: store.findUser, createSession: store.createSession, iterations: config.scramIterations });
  let authReplies = new Map(); // device/type/message -> { fingerprint, reply, untilMs }
  let confirmations = new Map(); // token -> { session, load, packCount, untilMs }
  let cageScans = new Map(); // session -> { scanId, loadId, deviceId, untilMs }
  const seenDevices = new Set();
  const flags = { online: true, paused: false, dropNextReply: false };
  const queued = []; // requests held while the station is offline, in arrival order

  function deviceAllowed(device) {
    if (config.deviceIds === '*' || (Array.isArray(config.deviceIds) && config.deviceIds.includes('*'))) {
      if (device.length === 0) return false;
      if (!seenDevices.has(device)) { seenDevices.add(device); log({ event: 'device_first_seen', device }); }
      return true;
    }
    return config.deviceIds.includes(device);
  }

  function discardPendingScan(session) {
    const scan = cageScans.get(session);
    if (scan) { cageScans.delete(session); store.discardCageScan(session, scan.scanId); }
  }

  function allocateScannedPack(session, root, answer, nowMs) {
    const scan = cageScans.get(session) ?? null;
    cageScans.delete(session);
    try {
      const loadId = Id(root, 'loadId');
      if (scan && scan.untilMs < nowMs) throw new WorkflowError('cage_scan_required', 'Scan the cage RFID again, then scan one Pack QR within two minutes.');
      if (scan && scan.loadId !== loadId) throw new WorkflowError('cage_context_changed', 'The Pack must follow the cage RFID just scanned. Scan the intended cage RFID again.');
      for (const field of ['barcode', 'bagId', 'jobCard'])
        if (own(root, field) && typeof root[field] !== 'string') throw new WorkflowError('invalid_request', `${field} must be a string when supplied.`);
      const raw = S(root, 'barcode', false);
      let bag = S(root, 'bagId', false);
      const barcode = raw.length > 0 ? parseBarcode(raw) : null;
      if (barcode && bag.length > 0 && bag !== barcode.bagId) throw new WorkflowError('invalid_barcode', 'The Bag ID does not match the scanned composite label.');
      bag = barcode?.bagId ?? bag;
      if (bag.length === 0 || bag.length > 100) throw new WorkflowError('invalid_barcode', 'Scan the approved Pack label.');
      const pack = store.findPack(bag);
      if (!pack) throw new WorkflowError('pack_not_found', 'Weigh and approve the Pack before allocation.');
      if (barcode && barcode.outerCode !== pack.outerCode) throw new WorkflowError('outer_code_changed', 'This Bag ID is already paired with another Outer Code.');
      let jobCard = productionOrder(S(root, 'jobCard', false));
      const savedQrJob = productionOrder(pack.productionOrderNumber);
      const savedSpecificationJob = productionOrder(pack.specification.jobCard);
      if (savedQrJob !== null && savedSpecificationJob !== null && savedQrJob !== savedSpecificationJob)
        throw new WorkflowError('production_order_mismatch', 'The saved Pack has conflicting production order data. Review it on the desktop.');
      const savedJob = savedQrJob ?? savedSpecificationJob;
      if (barcode) {
        const qrJob = productionOrder(barcode.productionOrderNumber);
        if (savedJob === null) throw new WorkflowError('production_order_required', 'The saved Pack has no production order. Review it on the desktop before allocating this QR.');
        if (qrJob !== savedJob || (jobCard !== null && jobCard !== qrJob))
          throw new WorkflowError('production_order_mismatch', 'The QR, saved Pack and any supplied Job Card must identify the same production order.');
        jobCard = qrJob;
      } else if (jobCard !== null && savedJob !== null && jobCard !== savedJob) {
        throw new WorkflowError('production_order_mismatch', 'This Pack already belongs to another production order.');
      }
      jobCard ??= savedJob;
      let load;
      if (!scan || !barcode) {
        // Read-only recovery bridges a committed allocation whose reply was lost across a restart.
        const saved = store.manifest(loadId);
        if (!pack.allocated || !saved.packs.some((p) => p.bagId === bag && p.outerCode === pack.outerCode && productionOrder(p.jobCard) === jobCard))
          throw new WorkflowError(barcode ? 'cage_scan_required' : 'invalid_barcode',
            barcode ? 'Scan the cage RFID immediately before each Pack QR.' : 'Scan the full ProductionOrderNumber,OuterCode,BagID QR after the cage RFID.');
        load = saved.load;
        answer.alreadyAllocated = true;
      } else {
        load = store.allocate(session, loadId, bag, config.allowMixedOuterCodes, nowMs, jobCard, scan.scanId);
      }
      answer.load = load;
      answer.pack = {
        bagId: pack.bagId, outerCode: pack.outerCode, productionOrderNumber: jobCard, itemCode: pack.specification.itemCode,
        itemName: pack.specification.itemName, actualKg: pack.actualKg, disposition: pack.disposition,
      };
      answer.requiresCageScan = true;
      answer.nextAction = 'select_cage';
    } finally {
      if (scan) store.discardCageScan(session, scan.scanId);
    }
  }

  function previewCompletion(session, loadId, receivedMs, answer, nowMs) {
    const active = store.getActiveCage(session, nowMs);
    if (active?.loadId !== loadId) throw new WorkflowError('cage_not_active', 'Scan this cage RFID to make it active before completing it.');
    const manifest = store.manifest(loadId);
    if (manifest.load.status !== 'Open' || manifest.packs.length === 0) throw new WorkflowError('completion_blocked', 'Select an open cage containing at least one Pack.');
    for (const [token, c] of confirmations) if (c.untilMs < nowMs) confirmations.delete(token);
    if (confirmations.size >= 1000) throw new WorkflowError('confirmation_busy', 'Too many pending cage confirmations. Retry shortly.');
    const token = newGuidN();
    confirmations.set(token, { session, load: loadId, packCount: manifest.load.packCount, untilMs: receivedMs + CONFIRM_MS });
    answer.confirmationToken = token;
    answer.confirmationExpiresAtUtc = stamp(receivedMs + CONFIRM_MS);
    answer.load = manifest.load;
    answer.packs = manifest.packs;
    answer.nextAction = 'confirm_completion';
  }

  /**
   * @param {string} topic
   * @param {string} payload UTF-8 decoded request body (exact text; its bytes are the replay fingerprint)
   * @param {{qos: number, retain: boolean}} meta
   * @param {number} nowMs station clock
   * @returns {{topic: string, json: string} | null}
   */
  function handle(topic, payload, meta, nowMs) {
    // RouteIncomingMessage: requests must be QoS 1, not retained, at most 16 KiB.
    if (topic === `${ROOT}/status`) return null;
    if (meta?.retain || meta?.qos !== 1) return null;
    if (!flags.online) {
      // The desktop's broker session is persistent (WithCleanSession(false)): while it is away the
      // broker holds QoS 1 requests and delivers them in order when it reconnects.
      if (queued.length < MAX_QUEUED) queued.push({ topic, payload, meta: { qos: meta.qos, retain: meta.retain } });
      return null;
    }
    const started = performance.now();
    const parts = topic.split('/');
    if (parts.length !== 5 || parts[0] !== 'PPNAM' || parts[1] !== 'station_5' || parts[3] !== 'req' || !deviceAllowed(parts[2])) return null;
    const device = parts[2];
    const type = parts[4];
    const received = nowMs;
    if (Buffer.byteLength(payload, 'utf8') > MAX_BYTES) return null;
    const parsed = inspectJson(payload);
    if (!parsed) return null;
    const root = parsed.value;
    if (root === null || typeof root !== 'object' || Array.isArray(root)) return null;
    let message;
    try { message = S(root, 'messageId'); } catch { return null; }
    if (message.length > 100) return null;

    const responseType = RESPONSE_TYPES[type] ?? 'request_rejected';
    const responseTopic = `${ROOT}/${device}/res/${responseType}`;
    const fingerprint = sha256Hex(payload);
    const authRequest = type === 'scram_start_requested' || type === 'scram_proof_requested';
    const answer = {
      messageId: newGuid(), inResponseToMessageId: message, schemaVersion: '4.1', deviceId: device,
      serverReceivedAtUtc: stamp(received), isDuplicate: false,
    };
    const duplicate = (reply) => {
      const cached = JSON.parse(reply);
      cached.isDuplicate = true;
      const json = JSON.stringify(cached);
      record(type, device, message, cached, true);
      return { topic: responseTopic, json };
    };

    try {
      // processing.Enter() is the first statement of the desktop's try: a paused station refuses
      // before envelope checks, the auth cache, the replay lookup/reservation and the timestamp.
      if (flags.paused) throw new WorkflowError('processing_paused', 'Station 5 settings are being changed. Retry shortly.');
      const names = parsed.keys;
      if (new Set(names.map((n) => n.toUpperCase())).size !== names.length ||
          names.some((n) => n.toLowerCase() === 'password' || n.toLowerCase() === 'managerpassword'))
        throw new WorkflowError('invalid_request', 'Duplicate fields and plaintext credentials are forbidden.');
      if (S(root, 'schemaVersion') !== '4.1' || S(root, 'deviceId') !== device)
        throw new WorkflowError('invalid_envelope', 'Schema version and device identity must match the topic.');
      answer.operatorSessionId = S(root, 'operatorSessionId', false);
      answer.correlationKey = S(root, 'correlationKey', false);
      const authKey = `${device}/${type}/${message}`;
      const authReply = authRequest ? authReplies.get(authKey) : undefined;
      if (authReply && authReply.untilMs > nowMs) {
        if (authReply.fingerprint !== fingerprint) throw new WorkflowError('message_id_conflict', 'This message ID already has different authentication data.');
        return duplicate(authReply.reply);
      }
      if (!authRequest) {
        const replay = store.getReplay(device, type, message, fingerprint);
        if (replay !== null) return duplicate(replay);
      }
      const ts = parseStamp(S(root, 'timestampUtc'));
      if (ts === null || Math.abs(received - ts) / 60_000 > 5) throw new WorkflowError('request_expired', 'Send a new request with the current UTC time.');
      if (authRequest) {
        if (S(root, 'purpose') !== 'login') throw new WorkflowError('invalid_purpose', 'Use the Station 5 Manager QC screen for approval.');
        if (type === 'scram_start_requested') {
          const c = auth.begin(S(root, 'username'), device, S(root, 'clientNonce'), nowMs);
          Object.assign(answer, { challengeId: c.challengeId, serverNonce: c.serverNonce, salt: c.salt, iterations: c.iterations, serverFirstMessage: c.serverFirst, expiresInSeconds: 60 });
        } else {
          const signed = auth.finish(device, S(root, 'challengeId'), S(root, 'clientFinalWithoutProof'), S(root, 'clientProof'), nowMs);
          Object.assign(answer, {
            operatorSessionId: signed.identity.sessionId, operatorName: signed.identity.name, role: signed.identity.role,
            expiresAtUtc: stamp(signed.identity.expiresAtMs), serverSignature: signed.serverSignature, allowedTabs: ['cage_loading'],
          });
        }
        answer.nextAction = type === 'scram_start_requested' ? 'send_proof' : 'select_cage';
      } else {
        const session = Id(root, 'operatorSessionId');
        store.requireSession(session, nowMs, device);
        switch (type) {
          case 'reader_logout_requested':
            discardPendingScan(session);
            store.logout(session);
            answer.nextAction = 'login';
            break;
          case 'cage_select_requested': {
            const rfid = S(root, 'rfidTag');
            const selection = crypto.createHash('sha256').update(`${device}|${message}|${fingerprint}`, 'utf8').digest('hex').slice(0, 32);
            const prior = store.getSelectedCage(session, selection, nowMs);
            if (prior) {
              if (cageScans.get(session)?.scanId === selection) discardPendingScan(session);
              answer.load = prior; answer.requiresCageScan = true; answer.nextAction = 'select_cage';
              break;
            }
            discardPendingScan(session);
            const selected = store.selectCage(session, rfid, nowMs, selection);
            const scanUntil = nowMs + SCAN_MS;
            cageScans.set(session, { scanId: selection, loadId: selected.loadId, deviceId: device, untilMs: scanUntil });
            answer.load = selected; answer.requiresCageScan = false; answer.cageScanExpiresAtUtc = stamp(scanUntil); answer.nextAction = 'scan_pack';
            break;
          }
          case 'cage_context_requested': {
            discardPendingScan(session);
            const requestedLoad = S(root, 'loadId', false);
            const sessionLoad = store.getActiveCage(session, nowMs);
            const contextManifest = requestedLoad.length > 0 ? store.manifest(Id(root, 'loadId'))
              : sessionLoad ? store.manifest(sessionLoad.loadId) : null;
            const current = contextManifest?.load ?? null;
            answer.load = current;
            answer.isActive = current?.status === 'Open' && current.loadId === sessionLoad?.loadId;
            answer.requiresCageScan = true;
            answer.nextAction = 'select_cage';
            if (contextManifest) answer.packs = contextManifest.packs;
            break;
          }
          case 'finished_good_allocate_requested':
            allocateScannedPack(session, root, answer, nowMs);
            break;
          case 'cage_complete_requested':
            discardPendingScan(session);
            previewCompletion(session, Id(root, 'loadId'), received, answer, nowMs);
            break;
          case 'cage_complete_confirmed': {
            discardPendingScan(session);
            const confirmation = S(root, 'confirmationToken');
            const confirmedLoad = Id(root, 'loadId');
            const pending = confirmations.get(confirmation);
            if (!pending || pending.session !== session || pending.load !== confirmedLoad || pending.untilMs < nowMs) {
              const saved = store.manifest(confirmedLoad).load;
              if (saved.status === 'Completed') { answer.load = saved; answer.alreadyCompleted = true; answer.nextAction = 'select_cage'; break; }
              throw new WorkflowError('confirmation_expired', 'Rescan the cage and request completion again.');
            }
            answer.load = store.completeCage(session, pending.load, nowMs, pending.packCount);
            answer.nextAction = 'select_cage';
            break;
          }
          default:
            throw new WorkflowError('unsupported_request', 'This request is not supported by Station 5 Packs.');
        }
      }
      answer.accepted = true;
    } catch (e) {
      if (type === 'finished_good_allocate_requested') {
        const failed = typeof root.operatorSessionId === 'string' ? parseGuid(root.operatorSessionId) : null;
        const isConflict = e instanceof WorkflowError && e.code === 'message_id_conflict';
        if (!isConflict && failed && cageScans.get(failed)?.deviceId === device) discardPendingScan(failed);
      }
      if (!(e instanceof WorkflowError)) throw e; // desktop: logged, no acknowledgement
      answer.accepted = false;
      answer.errorCode = e.code;
      answer.reason = e.message;
      answer.nextAction = e.code === 'login_required' ? 'login' : type === 'finished_good_allocate_requested' ? 'select_cage' : 'retry_or_review';
      if (type === 'finished_good_allocate_requested') answer.requiresCageScan = true;
    }
    answer.timestampUtc = stamp(nowMs);
    answer.serverSentAtUtc = stamp(nowMs);
    answer.processingDurationMs = Math.round(performance.now() - started);
    const json = JSON.stringify(answer);
    if (!authRequest && answer.errorCode !== 'processing_paused' && answer.errorCode !== 'confirmation_busy')
      store.storeReplay(device, type, message, fingerprint, json);
    if (authRequest && answer.accepted) {
      for (const [k, v] of authReplies) if (v.untilMs < nowMs) authReplies.delete(k);
      if (authReplies.size < 1000) authReplies.set(`${device}/${type}/${message}`, { fingerprint, reply: json, untilMs: nowMs + AUTH_REPLY_MS });
    }
    record(type, device, message, answer, false);
    return { topic: responseTopic, json };
  }

  function record(type, device, messageId, answer, isDuplicate) {
    log({ event: 'request', at: new Date().toISOString(), type, device, messageId, accepted: answer.accepted, errorCode: answer.errorCode ?? null, isDuplicate });
  }

  /** Station restart: memory-only evidence (scan arms, confirmation tokens, SCRAM state) is lost. */
  function clearMemory() {
    authReplies = new Map(); confirmations = new Map(); cageScans = new Map(); auth.clear();
  }

  return {
    handle,
    clearMemory,
    setOnline: (v) => { flags.online = !!v; },
    /** Processes requests held while offline, in arrival order, at the current clock. Call after setOnline(true). */
    drainQueue(nowMs) {
      const out = [];
      while (flags.online && queued.length > 0) {
        const q = queued.shift();
        let r = null;
        try { r = handle(q.topic, q.payload, q.meta, nowMs); } catch { /* not acknowledged, as on the desktop */ }
        if (r) out.push(r);
      }
      return out;
    },
    queuedCount: () => queued.length,
    setPaused: (v) => { flags.paused = !!v; },
    armDropNextReply: () => { flags.dropNextReply = true; },
    takeDropNextReply: () => { const d = flags.dropNextReply; flags.dropNextReply = false; return d; },
    flags: () => ({ ...flags }),
    seenDevices: () => [...seenDevices],
    armedScans: () => [...cageScans.values()].map((s) => ({ loadId: s.loadId, deviceId: s.deviceId, expiresAtUtc: stamp(s.untilMs) })),
  };
}
