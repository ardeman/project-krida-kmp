// Smoke test for the relay: a host and two guests exchange frames, the host kicks one,
// and a stranger can't take the room. Run against `npm run dev` (default) or a deployed
// relay: `node scripts/smoke.mjs wss://krida-relay.<account>.workers.dev`.
// Needs Node 22+ (global WebSocket).

const base = (process.argv[2] ?? "ws://localhost:8787").replace(/\/$/, "");
const ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
const code = Array.from({ length: 6 }, () => ALPHABET[Math.floor(Math.random() * ALPHABET.length)]).join("");
const key = crypto.randomUUID().replaceAll("-", "");

const DATA = 1, CONNECTED = 2, DISCONNECTED = 3, KICK = 4;
const enc = new TextEncoder(), dec = new TextDecoder();

function open(query) {
  const ws = new WebSocket(`${base}/v1/rooms/${code}?${query}`);
  ws.binaryType = "arraybuffer";
  const inbox = [];
  const waiters = [];
  ws.onmessage = (e) => {
    const b = new Uint8Array(e.data);
    const msg = { kind: b[0], peer: b[1], text: dec.decode(b.subarray(2)) };
    const w = waiters.shift();
    w ? w(msg) : inbox.push(msg);
  };
  ws.closed = new Promise((r) => ws.addEventListener("close", (e) => r(e.code)));
  ws.next = () =>
    inbox.length
      ? Promise.resolve(inbox.shift())
      : timeout(new Promise((r) => waiters.push(r)), "message");
  ws.sendFrame = (kind, peer, text = "") => {
    const p = enc.encode(text);
    const out = new Uint8Array(2 + p.length);
    out[0] = kind; out[1] = peer; out.set(p, 2);
    ws.send(out);
  };
  return ws;
}

function timeout(p, what) {
  return Promise.race([p, new Promise((_, no) => setTimeout(() => no(new Error(`timed out: ${what}`)), 10000))]);
}

function expect(got, want, label) {
  for (const [k, v] of Object.entries(want)) {
    if (got[k] !== v) throw new Error(`${label}: ${k} = ${JSON.stringify(got[k])}, want ${JSON.stringify(v)}`);
  }
  console.log(`ok  ${label}`);
}

const early = open("role=guest");
expect({ code: await timeout(early.closed, "close") }, { code: 4004 }, "guest before host gets 4004");

const host = open(`role=host&key=${key}`);
await timeout(new Promise((r) => (host.onopen = r)), "host open");

const a = open("role=guest");
expect(await host.next(), { kind: CONNECTED, peer: 1 }, "host sees guest 1");
expect(await a.next(), { kind: CONNECTED, peer: 0 }, "guest 1 sees host");

const b = open("role=guest");
expect(await host.next(), { kind: CONNECTED, peer: 2 }, "host sees guest 2");
expect(await b.next(), { kind: CONNECTED, peer: 0 }, "guest 2 sees host");

a.sendFrame(DATA, 0, '{"type":"hello"}');
expect(await host.next(), { kind: DATA, peer: 1, text: '{"type":"hello"}' }, "guest → host tagged with id");

host.sendFrame(DATA, 2, '{"type":"welcome"}');
expect(await b.next(), { kind: DATA, peer: 0, text: '{"type":"welcome"}' }, "host → one guest");

host.sendFrame(DATA, 0, '{"type":"state"}');
expect(await a.next(), { kind: DATA, text: '{"type":"state"}' }, "broadcast reaches guest 1");
expect(await b.next(), { kind: DATA, text: '{"type":"state"}' }, "broadcast reaches guest 2");

host.sendFrame(KICK, 1);
expect({ code: await timeout(a.closed, "kick") }, { code: 4008 }, "kicked guest gets 4008");
expect(await host.next(), { kind: DISCONNECTED, peer: 1 }, "host sees guest 1 leave");

const stranger = open(`role=host&key=${"x".repeat(32)}`);
expect({ code: await timeout(stranger.closed, "close") }, { code: 4001 }, "wrong host key gets 4001");

host.close();
expect(await b.next(), { kind: DISCONNECTED, peer: 0 }, "guest sees host drop");

const back = open(`role=host&key=${key}`);
expect(await back.next(), { kind: CONNECTED, peer: 2 }, "returning host is told about guest 2");
expect(await b.next(), { kind: CONNECTED, peer: 0 }, "guest sees host return");

back.close();
b.close();
console.log("all ok");
process.exit(0);
