// HTTP control API for test scenarios (loopback only).
import http from 'node:http';
import { WorkflowError, parseGuid } from './util.js';

/**
 * @param {{station: object, store: object, config: object, recent: () => object[],
 *          setStationOnline: (online: boolean) => Promise<number>, now?: () => number}} deps
 *          setStationOnline flips the station flag, publishes retained status and, when going online,
 *          processes held requests (returns how many were answered).
 */
export function createControlHandler({ station, store, config, recent, setStationOnline, now = Date.now }) {
  const routes = {
    'GET /state': () => ({
      station: { ...station.flags(), heldRequests: station.queuedCount() },
      config: { deviceIds: config.deviceIds, allowMixedOuterCodes: config.allowMixedOuterCodes, scramIterations: config.scramIterations },
      devicesSeen: station.seenDevices(),
      armedScans: station.armedScans(),
      ...store.snapshot(now()),
    }),
    'GET /log': () => recent(),
    'POST /reset': () => { store.reset(now()); station.clearMemory(); return { ok: true }; },
    'POST /station/online': async () => ({ online: true, processedHeldRequests: await setStationOnline(true) }),
    'POST /station/offline': async () => { await setStationOnline(false); return { online: false }; },
    'POST /drop-next-reply': () => { station.armDropNextReply(); return { dropNextReply: true }; },
    'POST /pause': () => { station.setPaused(true); return { paused: true }; },
    'POST /resume': () => { station.setPaused(false); return { paused: false }; },
    'POST /restart-memory': () => { station.clearMemory(); return { ok: true, note: 'scan arms, confirmation tokens and SCRAM state cleared (station restart)' }; },
    'POST /allocate-from-other-device': (body) => ({ load: store.allocateFromOtherDevice(requireLoad(body), requireString(body, 'bagId'), now()) }),
    'POST /advance-receipt': (body) => ({ load: store.advanceReceipt(requireLoad(body), body) }),
  };

  return async function handler(req, res) {
    const path = new URL(req.url, 'http://localhost').pathname.replace(/\/+$/, '') || '/';
    const route = routes[`${req.method} ${path}`];
    const send = (status, value) => {
      res.writeHead(status, { 'content-type': 'application/json; charset=utf-8' });
      res.end(`${JSON.stringify(value, null, 2)}\n`);
    };
    if (!route) return send(404, { error: 'not_found', routes: Object.keys(routes) });
    try {
      let body = {};
      if (req.method === 'POST') {
        const chunks = [];
        for await (const c of req) chunks.push(c);
        const text = Buffer.concat(chunks).toString('utf8').trim();
        if (text) body = JSON.parse(text);
      }
      send(200, await route(body));
    } catch (e) {
      if (e instanceof WorkflowError) return send(409, { error: e.code, reason: e.message });
      if (e instanceof SyntaxError) return send(400, { error: 'invalid_json', reason: e.message });
      if (e instanceof BadRequest) return send(400, { error: 'invalid_request', reason: e.message });
      send(500, { error: 'internal', reason: String(e?.message ?? e) });
    }
  };
}

class BadRequest extends Error {}
function requireString(body, name) {
  if (typeof body?.[name] !== 'string' || !body[name].trim()) throw new BadRequest(`${name} is required`);
  return body[name].trim();
}
function requireLoad(body) {
  const id = parseGuid(requireString(body, 'loadId'));
  if (!id) throw new BadRequest('loadId must be a UUID');
  return id;
}

export function startControl(handler, { host, port }) {
  const server = http.createServer(handler);
  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, host, () => { server.off('error', reject); resolve(server); });
  });
}
