// Embedded MQTT 3.1.1 broker (aedes over node:net) with the station attached as an internal
// subscriber, mirroring how the desktop's HandheldMqttService sits on the site broker.
import net from 'node:net';
import { Aedes } from 'aedes';
import { ROOT } from './station.js';

const REQUEST_TOPIC = /^PPNAM\/station_5\/[^/]+\/req\/[^/]+$/;
const PRESENCE_TOPIC = /^PPNAM\/station_5\/([^/]+)$/;
const STATUS_TOPIC = `${ROOT}/status`;
const strictUtf8 = new TextDecoder('utf-8', { fatal: true });

/**
 * @param {{station: ReturnType<import('./station.js').createStation>, host: string, port: number,
 *          username: string, password: string, logger: (line: string) => void, now?: () => number}} opts
 */
export async function startBroker({ station, host, port, username, password, logger, now = Date.now }) {
  const aedes = await Aedes.createBroker({
    maxClientsIdLength: 128,
    authenticate(client, user, pass, done) {
      if (user === username && pass?.toString('utf8') === password) return done(null, true);
      logger(`broker: refused connect from client ${client.id} (bad username/password)`);
      const err = new Error('Bad username or password');
      err.returnCode = 4;
      return done(err, false);
    },
    authorizePublish(client, packet, done) {
      // Only the station owns its presence topic.
      if (packet.topic === STATUS_TOPIC || packet.topic.startsWith('$SYS')) return done(new Error('reserved topic'));
      return done(null);
    },
    published(packet, client, done) {
      done(null);
      if (client) route(packet);
    },
  });

  function publish(topic, payload, qos, retain) {
    return new Promise((resolve, reject) => {
      aedes.publish({ cmd: 'publish', topic, payload: Buffer.from(payload, 'utf8'), qos, retain, dup: false }, (err) => (err ? reject(err) : resolve()));
    });
  }

  function route(packet) {
    const topic = packet.topic;
    const presence = PRESENCE_TOPIC.exec(topic);
    if (presence && presence[1] !== 'status') {
      const text = packet.payload.toString('utf8').trim().toLowerCase();
      const valid = packet.qos === 2 && (text === 'online' || text === 'offline');
      logger(`presence: ${presence[1]} -> ${JSON.stringify(text.slice(0, 32))} (qos ${packet.qos}${packet.retain ? ', retained' : ''}${valid ? '' : ', ignored: contract needs raw online/offline at QoS 2'})`);
      return;
    }
    if (!REQUEST_TOPIC.test(topic)) return;
    if (packet.retain || packet.qos !== 1 || packet.payload.length > 16384) {
      logger(`rejected transport envelope on ${topic} (qos ${packet.qos}, retain ${packet.retain}, ${packet.payload.length} bytes): no reply`);
      return;
    }
    let text;
    try { text = strictUtf8.decode(packet.payload); } catch { logger(`rejected non-UTF-8 payload on ${topic}: no reply`); return; }
    let result;
    try {
      result = station.handle(topic, text, { qos: packet.qos, retain: packet.retain }, now());
    } catch (e) {
      logger(`request on ${topic} was not acknowledged (${e?.name ?? 'Error'}); the caller must retry identical bytes`);
      return;
    }
    if (!result) {
      if (!station.flags().online) logger(`station offline: holding request on ${topic} (${station.queuedCount()} queued)`);
      return;
    }
    deliver(result);
  }

  function deliver(result) {
    if (station.takeDropNextReply()) {
      logger(`drop-next-reply: NOT publishing ${result.topic} (outcome recorded; retry identical bytes to get it with isDuplicate:true)`);
      return;
    }
    publish(result.topic, result.json, 1, false).catch((e) => logger(`publish failed on ${result.topic}: ${e.message}`));
  }

  aedes.on('client', (c) => logger(`broker: client connected ${c.id}`));
  aedes.on('clientDisconnect', (c) => logger(`broker: client disconnected ${c.id}`));
  aedes.on('subscribe', (subs, c) => logger(`broker: ${c?.id} subscribed ${subs.map((s) => `${s.topic}@${s.qos}`).join(', ')}`));

  const server = net.createServer(aedes.handle);
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, host, () => { server.off('error', reject); resolve(); });
  });

  return {
    server,
    aedes,
    /**
     * True when a TCP connection to 127.0.0.1:port reaches this server. On Windows a process bound
     * to 127.0.0.1 (e.g. a Mosquitto service) wins over our 0.0.0.0 bind, and `adb reverse`
     * connects to 127.0.0.1, so the handheld would silently talk to the other broker.
     */
    ownsLoopback() {
      return new Promise((resolve) => {
        let seen = false;
        const onConn = (sock) => { if (sock.remoteAddress?.endsWith('127.0.0.1')) seen = true; };
        server.on('connection', onConn);
        const probe = net.connect(server.address().port, '127.0.0.1');
        const finish = () => { server.off('connection', onConn); probe.destroy(); resolve(seen); };
        probe.once('connect', () => setTimeout(finish, 150));
        probe.once('error', finish);
      });
    },
    publishStatus: (online) => publish(STATUS_TOPIC, online ? 'online' : 'offline', 1, true),
    /**
     * Station going offline publishes retained "offline" and holds requests; coming back publishes
     * retained "online" and then processes the held requests in arrival order at the current clock.
     */
    async setStationOnline(online) {
      station.setOnline(online);
      await publish(STATUS_TOPIC, online ? 'online' : 'offline', 1, true);
      if (!online) return 0;
      const results = station.drainQueue(now());
      if (results.length) logger(`station online: processing ${results.length} held request(s)`);
      for (const r of results) deliver(r);
      return results.length;
    },
    async close() {
      await new Promise((resolve) => aedes.close(resolve));
      await new Promise((resolve) => server.close(() => resolve()));
    },
  };
}
