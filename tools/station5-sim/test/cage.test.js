import { test } from 'node:test';
import assert from 'node:assert/strict';
import { harness, ROOT, stamp, QR, RFID1, RFID2 } from './helpers.js';

function ready(options) {
  const h = harness(options);
  const s = h.login();
  const select = (rfidTag = RFID1, opts = {}) => h.send('cage_select_requested', { rfidTag }, { session: s, ...opts });
  const allocate = (loadId, barcode, extra = {}, opts = {}) =>
    h.send('finished_good_allocate_requested', { loadId, barcode, ...extra }, { session: s, ...opts });
  const context = (fields = {}) => h.send('cage_context_requested', fields, { session: s });
  const preview = (loadId) => h.send('cage_complete_requested', { loadId }, { session: s });
  const confirm = (loadId, confirmationToken) => h.send('cage_complete_confirmed', { loadId, confirmationToken }, { session: s });
  return { h, s, select, allocate, context, preview, confirm };
}

test('select opens a load and arms exactly one Pack for two minutes', () => {
  const { h, select } = ready();
  const r = select();
  assert.equal(r.topic, `${ROOT}/HH-1/res/cage_context_result`);
  assert.equal(r.res.accepted, true);
  assert.equal(r.res.requiresCageScan, false);
  assert.equal(r.res.nextAction, 'scan_pack');
  assert.equal(r.res.cageScanExpiresAtUtc, stamp(h.clock.ms + 120_000));
  const load = r.res.load;
  assert.equal(load.cageId, 'CAGE-001');
  assert.equal(load.status, 'Open');
  assert.equal(load.packCount, 0);
  assert.equal(load.totalKg, 0);
  assert.equal(load.completedAtUtc, null);
  assert.equal(load.grvState, 'Not queued');
  assert.equal(load.printState, 'Not queued');
  assert.equal(load.grvReference, null);
  assert.equal(load.error, null);
  assert.match(load.loadId, /^[0-9a-f-]{36}$/);
  assert.ok('openedAtUtc' in load);
});

test('tag arms one Pack: allocation accepted with the desktop field names, then disarmed', () => {
  const { select, allocate } = ready();
  const loadId = select().res.load.loadId;
  const r = allocate(loadId, QR.S001);
  assert.equal(r.topic, `${ROOT}/HH-1/res/finished_good_allocation_result`);
  assert.equal(r.res.accepted, true, r.res.reason);
  assert.equal(r.res.requiresCageScan, true);
  assert.equal(r.res.nextAction, 'select_cage');
  assert.equal(r.res.load.packCount, 1);
  assert.deepEqual(Object.keys(r.res.pack), ['bagId', 'outerCode', 'productionOrderNumber', 'itemCode', 'itemName', 'actualKg', 'disposition']);
  assert.equal(r.res.pack.bagId, 'S5QA-20261102-S001');
  assert.equal(r.res.pack.productionOrderNumber, '510023132');
  assert.equal(r.res.pack.disposition, 'Pass');
  assert.equal(r.res.load.totalKg, r.res.pack.actualKg);
  assert.equal(r.res.alreadyAllocated, undefined);
});

test('second Pack without a new tag is cage_scan_required; a fresh tag allows it', () => {
  const { select, allocate } = ready();
  const loadId = select().res.load.loadId;
  assert.equal(allocate(loadId, QR.S001).res.accepted, true);
  const second = allocate(loadId, QR.S002);
  assert.equal(second.res.accepted, false);
  assert.equal(second.res.errorCode, 'cage_scan_required');
  assert.equal(second.res.requiresCageScan, true);
  assert.equal(second.res.nextAction, 'select_cage');
  const again = select();
  assert.equal(again.res.load.loadId, loadId, 'same cage resumes its open load');
  assert.equal(again.res.load.packCount, 1);
  const ok = allocate(loadId, QR.S002);
  assert.equal(ok.res.accepted, true);
  assert.equal(ok.res.load.packCount, 2);
});

test('a refused allocation still spends the tag', () => {
  const { select, allocate } = ready();
  const loadId = select().res.load.loadId;
  assert.equal(allocate(loadId, '510023132,9030000104').res.errorCode, 'invalid_barcode');
  assert.equal(allocate(loadId, QR.S001).res.errorCode, 'cage_scan_required');
});

test('context read cancels the arm and reports isActive / packs', () => {
  const { select, allocate, context } = ready();
  const loadId = select().res.load.loadId;
  const c = context();
  assert.equal(c.res.accepted, true);
  assert.equal(c.res.requiresCageScan, true);
  assert.equal(c.res.nextAction, 'select_cage');
  assert.equal(c.res.isActive, true);
  assert.deepEqual(c.res.packs, []);
  assert.equal(c.res.load.loadId, loadId);
  assert.equal(allocate(loadId, QR.S001).res.errorCode, 'cage_scan_required');
});

test('context read with no selected cage returns load:null and no packs', () => {
  const { context } = ready();
  const c = context();
  assert.equal(c.res.load, null);
  assert.equal(c.res.isActive, false);
  assert.equal('packs' in c.res, false);
});

test('arm expires after two minutes', () => {
  const { h, select, allocate } = ready();
  const loadId = select().res.load.loadId;
  h.advance(120_001);
  assert.equal(allocate(loadId, QR.S001).res.errorCode, 'cage_scan_required');
  select();
  h.advance(119_000);
  assert.equal(allocate(loadId, QR.S001).res.accepted, true);
});

test('allocation for a different load than the scanned tag is cage_context_changed', () => {
  const { select, allocate } = ready();
  const first = select(RFID1).res.load.loadId;
  select(RFID2);
  const r = allocate(first, QR.S001);
  assert.equal(r.res.errorCode, 'cage_context_changed');
});

test('lost-reply recovery: same Bag on the same load without a tag returns alreadyAllocated read-only', () => {
  const { h, select, allocate } = ready();
  const loadId = select().res.load.loadId;
  allocate(loadId, QR.S001);
  const r = allocate(loadId, QR.S001);
  assert.equal(r.res.accepted, true);
  assert.equal(r.res.alreadyAllocated, true);
  assert.equal(r.res.load.packCount, 1);
  const legacy = allocate(loadId, undefined, { bagId: 'S5QA-20261102-S001' });
  assert.equal(legacy.res.alreadyAllocated, true);
  assert.equal(h.store.manifest(loadId).packs.length, 1);
});

test('Pack validation codes', () => {
  const { h, select, allocate } = ready();
  const loadId = select().res.load.loadId;
  const tryQr = (qr, extra) => { select(); return allocate(loadId, qr, extra).res.errorCode ?? null; };
  assert.equal(tryQr('510023132,9030000104,S5QA-20261102-F016'), 'pack_not_found', 'unweighed Bag ID');
  assert.equal(tryQr('510023066,9085000047,S5QA-20261102-F013'), 'qc_required', 'QC-pending Pack');
  assert.equal(tryQr('510023064,9086000015,S5QA-20261102-F014'), 'production_order_required', 'legacy Pack');
  assert.equal(tryQr('510023132,9999999999,S5QA-20261102-S001'), 'outer_code_changed');
  assert.equal(tryQr('510023999,9030000104,S5QA-20261102-S001'), 'production_order_mismatch');
  assert.equal(tryQr(QR.S001, { jobCard: '510023999' }), 'production_order_mismatch');
  assert.equal(tryQr(QR.S001, { jobCard: 'abc' }), 'production_order_invalid');
  assert.equal(tryQr(QR.S001, { jobCard: 510023132 }), 'invalid_request');
  assert.equal(tryQr('0,9030000104,S5QA-20261102-S001'), 'invalid_barcode');
  assert.equal(tryQr(QR.S001, { bagId: 'OTHER' }), 'invalid_barcode');
  assert.equal(tryQr(undefined, { bagId: 'S5QA-20261102-S002' }), 'invalid_barcode', 'bagId alone cannot place');
  assert.equal(h.store.manifest(loadId).packs.length, 0);
  assert.equal(tryQr(QR.S001, { jobCard: '0510023132' }), null, 'matching jobCard (normalized) is accepted');
  assert.equal(h.store.manifest(loadId).packs.length, 1);
  assert.equal(allocate(loadId, QR.S002).res.errorCode, 'cage_scan_required');
});

test('Manager-accepted Packs allocate with disposition ManagerAccepted', () => {
  const { select, allocate } = ready();
  const loadId = select().res.load.loadId;
  const r = allocate(loadId, '510023063,9086000002,S5QA-20261102-F015');
  assert.equal(r.res.accepted, true, r.res.reason);
  assert.equal(r.res.pack.disposition, 'ManagerAccepted');
});

test('pack_allocated when the Bag is in another load; unknown_cage for unknown or inactive RFIDs', () => {
  const { select, allocate } = ready();
  const a = select(RFID1).res.load.loadId;
  allocate(a, QR.S001);
  const b = select(RFID2).res.load.loadId;
  assert.equal(allocate(b, QR.S001).res.errorCode, 'pack_allocated');
  assert.equal(select('E2000017CAGE0009').res.errorCode, 'unknown_cage');
  assert.equal(select('NOT-A-CAGE').res.errorCode, 'unknown_cage');
});

test('mixed outer codes are allowed by default and refused when disabled', () => {
  const on = ready();
  let load = on.select().res.load.loadId;
  on.allocate(load, QR.S001); on.select();
  assert.equal(on.allocate(load, QR.S002).res.accepted, true);
  const off = ready({ config: { allowMixedOuterCodes: false } });
  load = off.select().res.load.loadId;
  off.allocate(load, QR.S001); off.select();
  assert.equal(off.allocate(load, QR.S002).res.errorCode, 'mixed_outer_code');
});

test('preview and confirm: token, packs, completed load with Pending GRV and WaitingForGrv print', () => {
  const { h, select, allocate, preview, confirm, context } = ready();
  const loadId = select().res.load.loadId;
  allocate(loadId, QR.S001); select(); allocate(loadId, QR.S002);
  // A second handheld arms the same cage before this one completes it.
  h.advance(3_000);
  const s2 = h.login('manager', 'manager123', 'HH-2');
  assert.equal(h.send('cage_select_requested', { rfidTag: RFID1 }, { session: s2, device: 'HH-2' }).res.load.loadId, loadId);
  const p = preview(loadId);
  assert.equal(p.topic, `${ROOT}/HH-1/res/cage_completion_result`);
  assert.equal(p.res.accepted, true, p.res.reason);
  assert.equal(p.res.nextAction, 'confirm_completion');
  assert.match(p.res.confirmationToken, /^[0-9a-f]{32}$/);
  assert.equal(p.res.confirmationExpiresAtUtc, stamp(h.clock.ms + 120_000));
  assert.equal(p.res.load.packCount, 2);
  assert.equal(p.res.packs.length, 2);
  assert.deepEqual(Object.keys(p.res.packs[0]), ['bagId', 'outerCode', 'itemCode', 'itemName', 'jobCard', 'weightKg', 'disposition', 'allocatedBy', 'allocatedAtUtc']);
  assert.equal(p.res.packs[0].allocatedBy, 'sim');
  const c = confirm(loadId, p.res.confirmationToken);
  assert.equal(c.res.accepted, true, c.res.reason);
  assert.equal(c.res.nextAction, 'select_cage');
  assert.equal(c.res.load.status, 'Completed');
  assert.equal(c.res.load.grvState, 'Pending');
  assert.equal(c.res.load.printState, 'WaitingForGrv');
  assert.notEqual(c.res.load.completedAtUtc, null);
  // A completed load blocks further allocation; re-selecting the cage opens a new load.
  const locked = h.send('finished_good_allocate_requested', { loadId, barcode: QR.S003 }, { session: s2, device: 'HH-2' });
  assert.equal(locked.res.errorCode, 'cage_locked');
  assert.equal(allocate(loadId, QR.S003).res.errorCode, 'cage_scan_required', 'no tag: refused before the lock check');
  const fresh = select().res.load;
  assert.notEqual(fresh.loadId, loadId);
  assert.equal(fresh.packCount, 0);
  const read = context({ loadId });
  assert.equal(read.res.load.status, 'Completed');
  assert.equal(read.res.isActive, false);
});

test('preview spends the arm; preview of an inactive or empty cage is refused', () => {
  const { select, allocate, preview } = ready();
  const a = select(RFID1).res.load.loadId;
  assert.equal(preview(a).res.errorCode, 'completion_blocked', 'empty cage');
  assert.equal(allocate(a, QR.S001).res.errorCode, 'cage_scan_required', 'preview spent the arm');
  select(RFID1); allocate(a, QR.S001);
  select(RFID2);
  assert.equal(preview(a).res.errorCode, 'cage_not_active');
});

test('cage_contents_changed when another device adds a Pack after the preview', () => {
  const { h, select, allocate, preview, confirm } = ready();
  const loadId = select().res.load.loadId;
  allocate(loadId, QR.S001);
  const p = preview(loadId);
  h.store.allocateFromOtherDevice(loadId, 'S5QA-20261102-S003', h.clock.ms);
  const c = confirm(loadId, p.res.confirmationToken);
  assert.equal(c.res.errorCode, 'cage_contents_changed');
  assert.equal(h.store.manifest(loadId).load.status, 'Open');
  const p2 = preview(loadId);
  assert.equal(p2.res.load.packCount, 2);
  assert.equal(confirm(loadId, p2.res.confirmationToken).res.accepted, true);
});

test('expired token: confirmation_expired while open, alreadyCompleted once completed', () => {
  const { h, select, allocate, preview, confirm } = ready();
  const loadId = select().res.load.loadId;
  allocate(loadId, QR.S001);
  const p = preview(loadId);
  h.advance(120_001);
  assert.equal(confirm(loadId, p.res.confirmationToken).res.errorCode, 'confirmation_expired');
  const p2 = preview(loadId);
  assert.equal(confirm(loadId, p2.res.confirmationToken).res.accepted, true);
  const late = confirm(loadId, 'no-such-token');
  assert.equal(late.res.accepted, true);
  assert.equal(late.res.alreadyCompleted, true);
  assert.equal(late.res.load.status, 'Completed');
});

test('receipt advance moves GRV and print through their states', () => {
  const { h, select, allocate, preview, confirm } = ready();
  const loadId = select().res.load.loadId;
  allocate(loadId, QR.S001);
  confirm(loadId, preview(loadId).res.confirmationToken);
  const seen = [];
  for (let i = 0; i < 6; i++) { const l = h.store.advanceReceipt(loadId); seen.push(`${l.grvState}/${l.printState}`); }
  assert.deepEqual(seen.slice(0, 4), ['Processing/WaitingForGrv', 'Succeeded/Pending', 'Succeeded/Processing', 'Succeeded/Succeeded']);
  assert.match(h.store.manifest(loadId).load.grvReference, /^SIM-GRV-/);
});

test('allocate failures before allocation (e.g. request_expired) still discard the arm', () => {
  const { h, select, allocate } = ready();
  const loadId = select().res.load.loadId;
  const stale = allocate(loadId, QR.S001, {}, { timestampMs: h.clock.ms - 10 * 60_000 });
  assert.equal(stale.res.errorCode, 'request_expired');
  assert.equal(stale.res.requiresCageScan, true);
  assert.equal(allocate(loadId, QR.S001).res.errorCode, 'cage_scan_required');
});
