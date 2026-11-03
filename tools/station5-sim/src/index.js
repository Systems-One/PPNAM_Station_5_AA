// Station 5 simulator entrypoint: broker on 1883, control API on 127.0.0.1:8787.
import { createStore } from './store.js';
import { createStation } from './station.js';
import { startBroker } from './broker.js';
import { createControlHandler, startControl } from './control.js';

const env = process.env;
const deviceList = (env.SIM_DEVICE_IDS ?? '*').split(',').map((s) => s.trim()).filter(Boolean);
const config = {
  deviceIds: deviceList.length === 0 || deviceList.includes('*') ? '*' : deviceList,
  scramIterations: Number.parseInt(env.SIM_SCRAM_ITERATIONS ?? '100000', 10),
  allowMixedOuterCodes: (env.SIM_ALLOW_MIXED_OUTER_CODES ?? 'true').toLowerCase() !== 'false',
};
const brokerHost = env.SIM_BROKER_HOST ?? '0.0.0.0';
const brokerPort = Number.parseInt(env.SIM_BROKER_PORT ?? '1883', 10);
const controlHost = env.SIM_CONTROL_HOST ?? '127.0.0.1';
const controlPort = Number.parseInt(env.SIM_CONTROL_PORT ?? '8787', 10);
const brokerUser = env.SIM_BROKER_USER ?? 'ppnam';
const brokerPass = env.SIM_BROKER_PASS ?? 'ppnam-sim';
if (!Number.isInteger(config.scramIterations) || config.scramIterations < 1) throw new Error('SIM_SCRAM_ITERATIONS must be a positive integer');

const RECENT_MAX = 200;
const recentLog = [];
const logLine = (line) => console.log(`${new Date().toISOString()} ${line}`);
function onStationEvent(entry) {
  // Entries carry only type/device/messageId/outcome: never passwords, proofs, salts or session ids.
  recentLog.push(entry);
  if (recentLog.length > RECENT_MAX) recentLog.shift();
  if (entry.event === 'device_first_seen') return logLine(`device first seen: ${entry.device} (SIM_DEVICE_IDS=*)`);
  logLine(`${entry.type} device=${entry.device} messageId=${entry.messageId} accepted=${entry.accepted}` +
    `${entry.errorCode ? ` errorCode=${entry.errorCode}` : ''}${entry.isDuplicate ? ' isDuplicate=true' : ''}`);
}

const t0 = performance.now();
const store = createStore({ iterations: config.scramIterations });
store.reset(Date.now());
const station = createStation({ store, config, log: onStationEvent });

const broker = await startBroker({ station, host: brokerHost, port: brokerPort, username: brokerUser, password: brokerPass, logger: logLine });
await broker.publishStatus(true);
logLine(`MQTT broker listening on ${brokerHost}:${brokerPort} (user ${brokerUser}); retained "online" on PPNAM/station_5/status`);
if (!(await broker.ownsLoopback())) {
  logLine(`WARNING: 127.0.0.1:${brokerPort} is answered by ANOTHER process (e.g. a Mosquitto service). ` +
    `"adb reverse tcp:1883 tcp:${brokerPort}" would reach that broker, not this simulator. ` +
    `Stop it, or start with SIM_BROKER_PORT=1884 and use "adb reverse tcp:1883 tcp:1884".`);
}

const control = await startControl(createControlHandler({
  station, store, config, recent: () => recentLog.slice(),
  setStationOnline: async (online) => {
    logLine(`station status -> ${online ? 'online' : 'offline'} (retained)${online ? '' : '; holding requests until online'}`);
    return broker.setStationOnline(online);
  },
}), { host: controlHost, port: controlPort });
logLine(`control API on http://${controlHost}:${controlPort} (GET /state, GET /log, POST /reset, /station/online, /station/offline, /drop-next-reply, /pause, /resume, /restart-memory, /allocate-from-other-device, /advance-receipt)`);
logLine(`devices: ${config.deviceIds === '*' ? '* (any)' : config.deviceIds.join(', ')}; SCRAM iterations ${config.scramIterations}; AllowMixedOuterCodes ${config.allowMixedOuterCodes}; ready in ${Math.round(performance.now() - t0)} ms`);

let stopping = false;
async function shutdown(signal) {
  if (stopping) return;
  stopping = true;
  logLine(`${signal}: publishing retained "offline" and stopping`);
  try { await broker.publishStatus(false); } catch { /* clients see the broker drop anyway */ }
  control.close();
  await broker.close();
  process.exit(0);
}
process.on('SIGINT', () => shutdown('SIGINT'));
process.on('SIGTERM', () => shutdown('SIGTERM'));
