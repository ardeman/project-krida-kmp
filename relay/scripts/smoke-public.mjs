// Local Workers runtime check for the combined public-room and group-search flow.
import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
const base = process.argv[2] ?? 'ws://localhost:8788';
const sockets = [];
const alphabet = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
const code = () => [...randomBytes(6)].map(n => alphabet[n % alphabet.length]).join('');
async function connect(path) {
  const ws = new WebSocket(`${base}${path}`);
  sockets.push(ws);
  const inbox = [], waiters = [];
  ws.addEventListener('message', ({ data }) => {
    const message = typeof data === 'string' ? JSON.parse(data) : data;
    const waiter = waiters.shift();
    if (waiter) waiter(message); else inbox.push(message);
  });
  ws.next = () => new Promise((resolve, reject) => {
    if (inbox.length) return resolve(inbox.shift());
    const timer = setTimeout(() => reject(new Error('Missing relay event')), 10_000);
    waiters.push(message => { clearTimeout(timer); resolve(message); });
  });
  ws.until = async predicate => { for (;;) { const event = await ws.next(); if (predicate(event)) return event; } };
  await new Promise((resolve, reject) => {
    ws.addEventListener('open', resolve, { once: true });
    ws.addEventListener('error', reject, { once: true });
  });
  return ws;
}
function status(host, started) {
  const json = new TextEncoder().encode(JSON.stringify({ bots: 0, started }));
  const frame = new Uint8Array(json.length + 2); frame[0] = 5; frame.set(json, 2); host.send(frame);
}
try {
  const privateCode = code();
  await connect(`/v1/rooms/${privateCode}?role=host&key=${randomBytes(16).toString('hex')}&game=ular_tangga&seats=4&public=0`);
  const a = await connect('/v1/match/ular_tangga/4?flow=group&public=1');
  const initial = await a.until(e => e.type === 'queue');
  assert.equal(initial.humans, 1);
  const b = await connect('/v1/match/ular_tangga/4?flow=group&public=1');
  await a.until(e => e.type === 'queue' && e.humans === 2);
  const publicCode = code();
  const host = await connect(`/v1/rooms/${publicCode}?role=host&key=${randomBytes(16).toString('hex')}&game=ular_tangga&seats=4&public=1`);
  const matches = await Promise.all([a, b].map(ws => ws.until(e => e.type === 'matched')));
  assert.ok(matches.every(e => e.code === publicCode && e.role === 'guest' && e.reservation));
  assert.notEqual(matches[0].reservation, matches[1].reservation);
  const guests = await Promise.all(matches.map(m => connect(`/v1/rooms/${m.code}?role=guest&reservation=${m.reservation}`)));
  await Promise.all(guests.map(ws => ws.next())); // Host-connected frame.
  console.log('ok  private room excluded; waiting group joins a newly public room using reserved seats');
  status(host, true);
  // A barrier through the host socket ensures its started status was processed.
  const payload = new TextEncoder().encode('{"type":"ping","t":1}');
  const frame = new Uint8Array(payload.length + 2); frame[0] = 1; frame.set(payload, 2); host.send(frame);
  await guests[0].next();
  const later = await connect('/v1/match/ular_tangga/4?flow=group&public=1');
  assert.equal((await later.until(e => e.type === 'queue')).humans, 1);
  console.log('ok  started room excluded from new matches');
} finally { for (const ws of sockets) ws.close(); }
