// Minimal MQTT 3.1.1 client for broker tests (CONNECT/SUBSCRIBE/PUBLISH QoS 0-1/PUBACK only).
import net from 'node:net';

const str = (s) => { const b = Buffer.from(s, 'utf8'); return Buffer.concat([Buffer.from([b.length >> 8, b.length & 255]), b]); };
function remaining(n) {
  const out = [];
  do { let d = n % 128; n = Math.floor(n / 128); if (n > 0) d |= 128; out.push(d); } while (n > 0);
  return Buffer.from(out);
}
const packet = (first, body) => Buffer.concat([Buffer.from([first]), remaining(body.length), body]);

export function connect({ port, host = '127.0.0.1', clientId = 'test-client', username, password }) {
  return new Promise((resolve, reject) => {
    const socket = net.connect(port, host);
    const messages = [];
    const waiters = [];
    let buf = Buffer.alloc(0);
    let nextId = 1;
    let connack = null;
    const client = {
      messages,
      subscribe(topic, qos = 1) {
        const id = nextId++;
        socket.write(packet(0x82, Buffer.concat([Buffer.from([id >> 8, id & 255]), str(topic), Buffer.from([qos])])));
      },
      publish(topic, payload, { qos = 1, retain = false } = {}) {
        const parts = [str(topic)];
        if (qos > 0) { const id = nextId++; parts.push(Buffer.from([id >> 8, id & 255])); }
        parts.push(Buffer.from(payload, 'utf8'));
        socket.write(packet(0x30 | (qos << 1) | (retain ? 1 : 0), Buffer.concat(parts)));
      },
      /** Resolves with the first message (already received or future) matching pred, or null after ms. */
      waitFor(pred, ms = 1500) {
        const hit = messages.find(pred);
        if (hit) { messages.splice(messages.indexOf(hit), 1); return Promise.resolve(hit); }
        return new Promise((res) => {
          const w = { pred, res };
          waiters.push(w);
          setTimeout(() => { const i = waiters.indexOf(w); if (i >= 0) { waiters.splice(i, 1); res(null); } }, ms);
        });
      },
      end: () => new Promise((res) => { socket.end(() => res()); socket.destroy(); }),
    };
    function onPublish(first, body) {
      const qos = (first >> 1) & 3;
      const tlen = body.readUInt16BE(0);
      const topic = body.subarray(2, 2 + tlen).toString('utf8');
      let off = 2 + tlen;
      if (qos > 0) { const id = body.readUInt16BE(off); off += 2; socket.write(Buffer.from([qos === 1 ? 0x40 : 0x50, 2, id >> 8, id & 255])); }
      const msg = { topic, payload: body.subarray(off).toString('utf8'), qos, retain: (first & 1) === 1 };
      const w = waiters.find((x) => x.pred(msg));
      if (w) { waiters.splice(waiters.indexOf(w), 1); w.res(msg); } else messages.push(msg);
    }
    socket.on('data', (chunk) => {
      buf = Buffer.concat([buf, chunk]);
      for (;;) {
        if (buf.length < 2) return;
        let mult = 1, len = 0, i = 1, byte;
        do { if (i >= buf.length) return; byte = buf[i++]; len += (byte & 127) * mult; mult *= 128; } while (byte & 128);
        if (buf.length < i + len) return;
        const first = buf[0];
        const body = buf.subarray(i, i + len);
        buf = buf.subarray(i + len);
        const type = first >> 4;
        if (type === 2) { connack = body[1]; resolve({ ...client, returnCode: connack }); }
        else if (type === 3) onPublish(first, body);
      }
    });
    socket.on('error', (e) => { if (connack === null) reject(e); });
    socket.on('close', () => { if (connack === null) resolve({ ...client, returnCode: -1 }); });
    socket.on('connect', () => {
      let flags = 0x02;
      const payload = [str(clientId)];
      if (username !== undefined) { flags |= 0x80; payload.push(str(username)); }
      if (password !== undefined) { flags |= 0x40; payload.push(str(password)); }
      const vh = Buffer.concat([str('MQTT'), Buffer.from([4, flags, 0, 30])]);
      socket.write(packet(0x10, Buffer.concat([vh, ...payload])));
    });
  });
}
