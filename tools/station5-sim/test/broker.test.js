import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { createStore } from '../src/store.js';
import { createStation } from '../src/station.js';
import { startBroker } from '../src/broker.js';
import { createControlHandler, startControl } from '../src/control.js';
import { connect } from './mqtt-client.js';
import { stamp } from './helpers.js';

const config = { deviceIds: '*', scramIterations: 4096, allowMixedOuterCodes: true };
let store, station, broker, control, port, controlUrl, client;
const logs = [];
const statusOnline = [];

before(async () => {
  store = createStore({ iterations: config.scramIterations });
  store.reset(Date.now());
  station = createStation({ store, config, log: (e) => logs.push(e) });
  broker = await startBroker({ station, host: '127.0.0.1', port: 0, username: 'ppnam', password: 'ppnam-sim', logger: () => {} });
  port = broker.server.address().port;
  await broker.publishStatus(true);
  control = await startControl(createControlHandler({
    station, store, config, recent: () => logs.slice(),
    setStationOnline: async (online) => { statusOnline.push(online); return broker.setStationOnline(online); },
  }), { host: '127.0.0.1', port: 0 });
  controlUrl = `http://127.0.0.1:${control.address().port}`;
  client = await connect({ port, clientId: 'ScannerApp_test', username: 'ppnam', password: 'ppnam-sim' });
  client.subscribe('PPNAM/station_5/status');
  client.subscribe('PPNAM/station_5/scanner_test/res/+');
});

after(async () => {
  await client?.end();
  control?.close();
  await broker?.close();
});

const contextRequest = (messageId) => JSON.stringify({
  messageId, schemaVersion: '4.1', deviceId: 'scanner_test', operatorSessionId: '11111111-1111-1111-1111-111111111111', timestampUtc: stamp(Date.now()),
});
const post = (path, body) => fetch(controlUrl + path, { method: 'POST', body: body ? JSON.stringify(body) : undefined }).then(async (r) => ({ status: r.status, json: await r.json() }));

test('loopback probe sees this broker answering 127.0.0.1', async () => {
  assert.equal(await broker.ownsLoopback(), true);
});

test('wrong broker credentials are refused (CONNACK 4)', async () => {
  const bad = await connect({ port, clientId: 'bad', username: 'ppnam', password: 'nope' });
  assert.equal(bad.returnCode, 4);
  await bad.end();
});

test('retained raw "online" is delivered on the station status topic', async () => {
  assert.equal(client.returnCode, 0);
  const status = await client.waitFor((m) => m.topic === 'PPNAM/station_5/status');
  assert.equal(status.payload, 'online');
  assert.equal(status.retain, true);
});

test('a QoS 1 request is answered at QoS 1, not retained; QoS 0 and retained requests are dropped', async () => {
  client.publish('PPNAM/station_5/scanner_test/req/cage_context_requested', contextRequest('b-1'));
  const reply = await client.waitFor((m) => m.topic.endsWith('/res/cage_context_result'));
  assert.ok(reply, 'reply received');
  assert.equal(reply.qos, 1);
  assert.equal(reply.retain, false);
  const res = JSON.parse(reply.payload);
  assert.equal(res.inResponseToMessageId, 'b-1');
  assert.equal(res.errorCode, 'login_required');
  client.publish('PPNAM/station_5/scanner_test/req/cage_context_requested', contextRequest('b-2'), { qos: 0 });
  client.publish('PPNAM/station_5/scanner_test/req/cage_context_requested', contextRequest('b-3'), { retain: true });
  assert.equal(await client.waitFor((m) => m.topic.endsWith('/res/cage_context_result'), 500), null);
});

test('drop-next-reply drops one publish but stores the outcome; identical bytes then replay it', async () => {
  assert.equal((await post('/drop-next-reply')).status, 200);
  const body = contextRequest('b-drop');
  client.publish('PPNAM/station_5/scanner_test/req/cage_context_requested', body);
  assert.equal(await client.waitFor((m) => m.topic.endsWith('/res/cage_context_result'), 500), null);
  client.publish('PPNAM/station_5/scanner_test/req/cage_context_requested', body);
  const reply = await client.waitFor((m) => m.topic.endsWith('/res/cage_context_result'));
  const res = JSON.parse(reply.payload);
  assert.equal(res.inResponseToMessageId, 'b-drop');
  assert.equal(res.isDuplicate, true);
});

test('station offline publishes retained "offline", holds requests, and answers them in order once online', async () => {
  assert.deepEqual((await post('/station/offline')).json, { online: false });
  const off = await client.waitFor((m) => m.topic === 'PPNAM/station_5/status');
  assert.equal(off.payload, 'offline');
  client.publish('PPNAM/station_5/scanner_test/req/cage_context_requested', contextRequest('b-off-1'));
  client.publish('PPNAM/station_5/scanner_test/req/cage_context_requested', contextRequest('b-off-2'));
  assert.equal(await client.waitFor((m) => m.topic.endsWith('/res/cage_context_result'), 500), null);
  const state = await (await fetch(`${controlUrl}/state`)).json();
  assert.equal(state.station.heldRequests, 2);
  assert.deepEqual((await post('/station/online')).json, { online: true, processedHeldRequests: 2 });
  const on = await client.waitFor((m) => m.topic === 'PPNAM/station_5/status');
  assert.equal(on.payload, 'online');
  const first = await client.waitFor((m) => m.topic.endsWith('/res/cage_context_result'));
  const second = await client.waitFor((m) => m.topic.endsWith('/res/cage_context_result'));
  assert.deepEqual([JSON.parse(first.payload).inResponseToMessageId, JSON.parse(second.payload).inResponseToMessageId], ['b-off-1', 'b-off-2']);
  client.publish('PPNAM/station_5/scanner_test/req/cage_context_requested', contextRequest('b-on'));
  assert.ok(await client.waitFor((m) => m.topic.endsWith('/res/cage_context_result')));
});

test('clients cannot publish the station status topic', async () => {
  client.publish('PPNAM/station_5/status', 'offline', { retain: true });
  assert.equal(await client.waitFor((m) => m.topic === 'PPNAM/station_5/status', 400), null);
});

test('control API: state, pause/resume, other-device allocation, receipt advance, log, reset', async () => {
  const state = await (await fetch(`${controlUrl}/state`)).json();
  assert.equal(state.station.online, true);
  assert.ok(state.packs.length >= 14);
  assert.ok(state.cages.some((c) => c.cageId === 'CAGE-009' && c.active === false));
  assert.ok(!JSON.stringify(state).includes('storedKey') && !JSON.stringify(state).includes('sim123'));
  assert.deepEqual((await post('/pause')).json, { paused: true });
  assert.equal(station.flags().paused, true);
  await post('/resume');
  // Open a load through the store as a scanner would, then let "another device" add to it.
  const session = store.createSession(store.findUser('sim').userId, 'scanner_test', Date.now()).sessionId;
  const load = store.selectCage(session, 'E2000017CAGE0001', Date.now(), 'sel-1');
  const other = await post('/allocate-from-other-device', { loadId: load.loadId, bagId: 'S5QA-20261102-S004' });
  assert.equal(other.status, 200);
  assert.equal(other.json.load.packCount, 1);
  const twice = await post('/allocate-from-other-device', { loadId: load.loadId, bagId: 'S5QA-20261102-S004' });
  assert.equal(twice.status, 409);
  assert.equal(twice.json.error, 'pack_allocated');
  assert.equal((await post('/advance-receipt', { loadId: load.loadId })).json.error, 'not_completed');
  const log = await (await fetch(`${controlUrl}/log`)).json();
  assert.ok(log.some((e) => e.messageId === 'b-1'));
  assert.equal((await post('/reset')).status, 200);
  const after = await (await fetch(`${controlUrl}/state`)).json();
  assert.equal(after.loads.length, 0);
  assert.equal((await fetch(`${controlUrl}/nope`)).status, 404);
});
