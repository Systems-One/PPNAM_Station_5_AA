// In-memory stand-in for the desktop SqlPackRepository (only what HandheldMqttService uses),
// plus seed data and a few test-control mutations.
import { readFileSync } from 'node:fs';
import { createVerifier } from './scram.js';
import { WorkflowError, newGuid, offsetStamp, parseInt32, isBlank } from './util.js';

const SEED_PATH = new URL('../seed.json', import.meta.url);
const fault = (code, message) => new WorkflowError(code, message);

/** SqlPackRepository.ProductionJob(saved, scanned). */
export function productionJob(saved, scanned) {
  const value = isBlank(scanned) ? (saved?.trim() ?? '') : scanned.trim();
  if (value.length === 0) throw fault('production_order_required', 'Enter the SAP Job Card number before allocating this Pack.');
  const number = parseInt32(value);
  if (number === null || number <= 0) throw fault('production_order_invalid', 'The Job Card must be the positive SAP production order DocumentNumber.');
  if (!isBlank(saved)) {
    const original = parseInt32(saved.trim());
    if (original === null || original !== number) throw fault('production_order_mismatch', 'This Pack already belongs to another Job Card. Use its recorded Job Card.');
  }
  return String(number);
}

const grams = (kg) => Math.round(kg * 1000);

export function createStore({ iterations = 100000, seed = null } = {}) {
  const seedData = seed ?? JSON.parse(readFileSync(SEED_PATH, 'utf8'));
  // Verifiers are derived once; reset() reuses them so it stays fast at 100000 iterations.
  const verifiers = seedData.users.map((u) => createVerifier(u.name, u.password, u.role, iterations));
  let db;

  function reset(nowMs) {
    db = {
      users: verifiers.map((v) => ({ ...v })),
      cages: seedData.cages.map((c) => ({ ...c })),
      packs: new Map(seedData.packs.map((p) => [p.bagId, {
        bagId: p.bagId, outerCode: p.outerCode,
        productionOrderNumber: p.productionOrderNumber ?? null,
        specification: { itemCode: p.itemCode, itemName: p.itemName, jobCard: p.jobCard ?? null },
        disposition: p.disposition, actualKg: p.actualKg, allocated: false, qr: p.qr ?? null,
      }])),
      loads: new Map(), // loadId -> { loadId, cageId, status, openedAtMs, completedAtMs, completedBy }
      outbox: new Map(), // loadId -> { grv: {state, reference, error}, print: {state, error} }
      allocations: new Map(), // bagId -> { loadId, weightKg, record }
      sessions: new Map(), // sessionId -> { sessionId, userId, deviceId, expiresAtMs, revoked, activeLoadId, cageScanId }
      selections: new Map(), // selectionId -> loadId
      replay: new Map(), // device|type|messageId -> { fingerprint, response }
      grvSeq: 0,
      resetAtMs: nowMs,
    };
  }

  // ---- users / sessions -----------------------------------------------------------------
  // SQL Server's default collation is case-insensitive, so Name=@n matches regardless of case.
  const findUser = (username) => db.users.find((u) => u.active && u.name.toLowerCase() === username.trim().toLowerCase()) ?? null;
  const userById = (id) => db.users.find((u) => u.userId === id);

  function identity(s) {
    const u = userById(s.userId);
    return { sessionId: s.sessionId, userId: u.userId, name: u.name, role: u.role, expiresAtMs: s.expiresAtMs };
  }
  function session(sessionId, nowMs, device = null) {
    const s = db.sessions.get(sessionId);
    const u = s && userById(s.userId);
    if (!s || s.revoked || !(s.expiresAtMs > nowMs) || !u?.active || (device !== null && s.deviceId !== device))
      throw fault('login_required', 'Sign in with an active Station 5 account.');
    return s;
  }
  function createSession(userId, deviceId, nowMs) {
    if (isBlank(deviceId) || deviceId.length > 100) throw fault('invalid_device', 'Device identifier is required.');
    for (const s of db.sessions.values()) if (s.deviceId === deviceId) s.revoked = true;
    const u = userById(userId);
    if (!u?.active) throw fault('login_required', 'Sign in with an active Station 5 account.');
    const s = { sessionId: newGuid(), userId, deviceId, expiresAtMs: nowMs + 16 * 3600_000, revoked: false, activeLoadId: null, cageScanId: null };
    db.sessions.set(s.sessionId, s);
    return identity(s);
  }
  const requireSession = (sessionId, nowMs, device) => identity(session(sessionId, nowMs, device));
  function logout(sessionId) { const s = db.sessions.get(sessionId); if (s) s.revoked = true; }

  // ---- loads ------------------------------------------------------------------------------
  function loadDto(loadId) {
    const l = db.loads.get(loadId);
    if (!l) throw fault('load_not_found', 'Cage load was not found.');
    let count = 0, g = 0;
    for (const a of db.allocations.values()) if (a.loadId === loadId) { count++; g += grams(a.weightKg); }
    const o = db.outbox.get(loadId);
    return {
      loadId: l.loadId, cageId: l.cageId, status: l.status, packCount: count, totalKg: g / 1000,
      openedAtUtc: offsetStamp(l.openedAtMs), completedAtUtc: offsetStamp(l.completedAtMs),
      grvState: o?.grv.state ?? 'Not queued', grvReference: o?.grv.reference ?? null,
      printState: o?.print.state ?? 'Not queued', error: o ? (o.grv.error ?? o.print.error ?? null) : null,
    };
  }
  function manifest(loadId) {
    const load = loadDto(loadId);
    const packs = [...db.allocations.entries()].filter(([, a]) => a.loadId === loadId)
      .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)).map(([, a]) => ({ ...a.record }));
    return { load, packs };
  }
  function requireActive(sessionId, loadId) {
    if (db.sessions.get(sessionId)?.activeLoadId !== loadId)
      throw fault('cage_context_changed', 'Scan this cage RFID to select it for your session before allocating or completing it.');
  }
  function getActiveCage(sessionId, nowMs) {
    const s = session(sessionId, nowMs);
    return s.activeLoadId ? loadDto(s.activeLoadId) : null;
  }
  function getSelectedCage(sessionId, selectionId, nowMs) {
    session(sessionId, nowMs);
    const loadId = db.selections.get(selectionId);
    return loadId ? loadDto(loadId) : null;
  }
  function activate(s, loadId, scanId) { s.activeLoadId = loadId; s.cageScanId = scanId; return loadDto(loadId); }
  function selectCage(sessionId, rfid, nowMs, selectionId) {
    const s = session(sessionId, nowMs);
    const prior = db.selections.get(selectionId);
    if (prior) return loadDto(prior);
    // Rfid=@r under a case-insensitive collation.
    const cage = db.cages.find((c) => c.active && c.rfid.toLowerCase() === rfid.trim().toLowerCase());
    if (!cage) throw fault('unknown_cage', 'Scan a registered active cage RFID.');
    const open = [...db.loads.values()].find((l) => l.cageId === cage.cageId && l.status === 'Open');
    if (open) { db.selections.set(selectionId, open.loadId); return activate(s, open.loadId, selectionId); }
    const created = { loadId: newGuid(), cageId: cage.cageId, status: 'Open', openedAtMs: nowMs, completedAtMs: null, completedBy: null };
    db.loads.set(created.loadId, created);
    db.selections.set(selectionId, created.loadId);
    return activate(s, created.loadId, selectionId);
  }
  function discardCageScan(sessionId, scanId) {
    const s = db.sessions.get(sessionId);
    if (s && s.cageScanId === scanId) s.cageScanId = null;
  }
  const findPack = (bagId) => { const p = db.packs.get(bagId); return p ? structuredClone(p) : null; };
  const isApproved = (p) => p.disposition === 'Pass' || p.disposition === 'ManagerAccepted';

  function insertAllocation(p, loadId, job, allocatedBy, nowMs) {
    db.allocations.set(p.bagId, {
      loadId, weightKg: p.actualKg,
      record: {
        bagId: p.bagId, outerCode: p.outerCode, itemCode: p.specification.itemCode, itemName: p.specification.itemName,
        jobCard: job, weightKg: p.actualKg, disposition: p.disposition, allocatedBy, allocatedAtUtc: offsetStamp(nowMs),
      },
    });
    db.packs.get(p.bagId).allocated = true;
  }

  /** SqlPackRepository.AllocateAsync(session, load, bag, allowMixed, jobCard, cageScanId). */
  function allocate(sessionId, loadId, bagId, allowMixedOuterCodes, nowMs, jobCard = null, cageScanId = null) {
    const s = session(sessionId, nowMs);
    const u = userById(s.userId);
    const load = loadDto(loadId);
    if (load.status !== 'Open') throw fault('cage_locked', 'This cage load is complete and locked.');
    requireActive(sessionId, loadId);
    const existing = db.allocations.get(bagId);
    const armed = s.cageScanId;
    const scanned = armed !== null && (cageScanId === null || cageScanId === armed);
    if (!scanned && existing && existing.loadId === loadId) {
      if (!isBlank(jobCard)) productionJob(existing.record.jobCard, jobCard);
      return load;
    }
    if (!scanned) throw fault('cage_scan_required', 'Scan the cage RFID again before scanning this Pack.');
    s.cageScanId = null; // committed even when the allocation below is refused
    if (existing) {
      if (existing.loadId !== loadId) throw fault('pack_allocated', 'This Pack is already allocated to another cage load.');
      if (!isBlank(jobCard)) productionJob(existing.record.jobCard, jobCard);
      return load;
    }
    const p = findPack(bagId);
    if (!p) throw fault('pack_not_found', 'Weigh and approve the Pack before allocation.');
    if (!isApproved(p) || p.allocated) throw fault('qc_required', 'Only a passed or Manager accepted Pack may be allocated.');
    const recordedJob = productionJob(p.productionOrderNumber, p.specification.jobCard);
    const job = productionJob(recordedJob, jobCard);
    if (!allowMixedOuterCodes && [...db.allocations.values()].some((a) => a.loadId === loadId && (a.record.outerCode !== p.outerCode || (a.record.jobCard ?? '') !== job)))
      throw fault('mixed_outer_code', 'This cage is configured for one Outer Code and Job Card. Scan a matching cage.');
    insertAllocation(p, loadId, job, u.name, nowMs);
    return loadDto(loadId);
  }

  /** SqlPackRepository.CompleteCageAsync(session, load, expectedPackCount). */
  function completeCage(sessionId, loadId, nowMs, expectedPackCount = null) {
    const s = session(sessionId, nowMs);
    const u = userById(s.userId);
    const load = loadDto(loadId);
    if (expectedPackCount !== null && load.packCount !== expectedPackCount)
      throw fault('cage_contents_changed', 'Packs were added after the completion preview. Scan the cage again and confirm the updated contents.');
    if (load.status === 'Completed') return load;
    requireActive(sessionId, loadId);
    if (load.packCount === 0) throw fault('empty_cage', 'An empty cage cannot be completed.');
    for (const a of db.allocations.values()) if (a.loadId === loadId) productionJob(a.record.jobCard, null);
    const l = db.loads.get(loadId);
    l.status = 'Completed'; l.completedAtMs = nowMs; l.completedBy = u.name;
    db.outbox.set(loadId, { grv: { state: 'Pending', reference: null, error: null }, print: { state: 'WaitingForGrv', error: null } });
    for (const other of db.sessions.values()) if (other.activeLoadId === loadId) other.cageScanId = null;
    return loadDto(loadId);
  }

  // ---- replay (S5Replay) ------------------------------------------------------------------
  const replayKey = (d, t, m) => `${d}\u0000${t}\u0000${m}`;
  function getReplay(device, type, messageId, fingerprint) {
    const row = db.replay.get(replayKey(device, type, messageId));
    if (row) {
      if (row.fingerprint !== fingerprint) throw fault('message_id_conflict', 'This message ID was used with another payload.');
      return row.response;
    }
    db.replay.set(replayKey(device, type, messageId), { fingerprint, response: null }); // reserve before mutation
    return null;
  }
  function storeReplay(device, type, messageId, fingerprint, response) {
    const key = replayKey(device, type, messageId);
    const row = db.replay.get(key);
    if (!row) db.replay.set(key, { fingerprint, response });
    else if (row.fingerprint === fingerprint && row.response === null) row.response = response;
  }

  // ---- control-API mutations (stand-ins for desktop / other-device activity) ---------------
  /** Another handheld allocates a Pack into the load (no tag, no session): drives cage_contents_changed. */
  function allocateFromOtherDevice(loadId, bagId, nowMs) {
    const load = loadDto(loadId);
    if (load.status !== 'Open') throw fault('cage_locked', 'This cage load is complete and locked.');
    if (db.allocations.has(bagId)) throw fault('pack_allocated', 'This Pack is already allocated to a cage load.');
    const p = findPack(bagId);
    if (!p) throw fault('pack_not_found', 'Weigh and approve the Pack before allocation.');
    if (!isApproved(p)) throw fault('qc_required', 'Only a passed or Manager accepted Pack may be allocated.');
    insertAllocation(p, loadId, productionJob(p.productionOrderNumber, p.specification.jobCard), 'other-device', nowMs);
    return loadDto(loadId);
  }
  /**
   * One step of the receipt worker: GRV Pending -> Processing -> Succeeded (reference assigned,
   * print released), then print Pending -> Processing -> Succeeded. Optional explicit states
   * (e.g. grvState:"OutcomeUnknown") override the step.
   */
  function advanceReceipt(loadId, { grvState, printState, error } = {}) {
    loadDto(loadId);
    const o = db.outbox.get(loadId);
    if (!o) throw fault('not_completed', 'Only a completed load has a receipt to advance.');
    if (grvState || printState || error !== undefined) {
      if (grvState) o.grv.state = grvState;
      if (printState) o.print.state = printState;
      if (error !== undefined) o.grv.error = error;
      if (o.grv.state === 'Succeeded' && !o.grv.reference) o.grv.reference = `SIM-GRV-${String(++db.grvSeq).padStart(5, '0')}`;
      return loadDto(loadId);
    }
    if (o.grv.state === 'Pending' || o.grv.state === 'Reconcile') o.grv.state = 'Processing';
    else if (o.grv.state === 'Processing') {
      o.grv.state = 'Succeeded';
      o.grv.reference = `SIM-GRV-${String(++db.grvSeq).padStart(5, '0')}`;
      if (o.print.state === 'WaitingForGrv') o.print.state = 'Pending';
    } else if (o.grv.state === 'Succeeded' && o.print.state === 'Pending') o.print.state = 'Processing';
    else if (o.print.state === 'Processing') o.print.state = 'Succeeded';
    return loadDto(loadId);
  }

  function snapshot(nowMs) {
    return {
      cages: db.cages,
      loads: [...db.loads.keys()].map((id) => manifest(id)),
      packs: [...db.packs.values()].map((p) => ({
        bagId: p.bagId, outerCode: p.outerCode, productionOrderNumber: p.productionOrderNumber,
        disposition: p.disposition, actualKg: p.actualKg, allocated: p.allocated,
        loadId: db.allocations.get(p.bagId)?.loadId ?? null, qr: p.qr,
      })),
      sessions: [...db.sessions.values()].filter((s) => !s.revoked && s.expiresAtMs > nowMs).map((s) => ({
        session: `${s.sessionId.slice(0, 8)}…`, deviceId: s.deviceId, user: userById(s.userId)?.name,
        expiresAtUtc: offsetStamp(s.expiresAtMs), activeLoadId: s.activeLoadId, cageScanArmed: s.cageScanId !== null,
      })),
      users: db.users.map((u) => ({ name: u.name, role: u.role, active: u.active })),
      replayEntries: db.replay.size,
    };
  }

  return {
    reset, findUser, createSession, requireSession, logout,
    getActiveCage, getSelectedCage, selectCage, discardCageScan, findPack, allocate, completeCage,
    manifest, getReplay, storeReplay, allocateFromOtherDevice, advanceReceipt, snapshot,
  };
}
