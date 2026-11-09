// Actual Workers runtime check: run against a local `npm run dev`, never starts a game.
import assert from 'node:assert/strict';
const base = process.argv[2] ?? 'ws://localhost:8787';
const sockets = [];
async function connect(target) {
  const ws = new WebSocket(`${base}/v1/match/ular_tangga/${target}?flow=group`);
  sockets.push(ws);
  const inbox = [], waiters = [];
  ws.addEventListener('message', ({ data }) => {
    const message = JSON.parse(data);
    const waiter = waiters.shift();
    if (waiter) waiter(message); else inbox.push(message);
  });
  ws.next = () => new Promise((resolve, reject) => {
    if (inbox.length) { resolve(inbox.shift()); return; }
    const timeout = setTimeout(() => reject(new Error('Missing group event')), 25_000);
    waiters.push(message => { clearTimeout(timeout); resolve(message); });
  });
  ws.until = async predicate => { for (;;) { const event = await ws.next(); if (predicate(event)) return event; } };
  ws.action = (type, revision) => ws.send(JSON.stringify({ type, revision }));
  await new Promise((resolve, reject) => { ws.addEventListener('open', resolve, { once: true }); ws.addEventListener('error', reject, { once: true }); });
  return ws;
}
try {
  for (const target of [2, 3, 4]) {
    const group = [];
    for (let i = 0; i < target; i++) group.push(await connect(target));
    const preparations = await Promise.all(group.map(ws => ws.until(e => e.type === 'prepare_host' || e.type === 'preparing')));
    const hostIndex = preparations.findIndex(e => e.type === 'prepare_host');
    assert.notEqual(hostIndex, -1);
    const host = group[hostIndex], prepared = preparations[hostIndex];
    assert.equal(prepared.humans, target); assert.equal(prepared.bots, 0);
    host.action('host_ready');
    const matches = await Promise.all(group.map(ws => ws.until(e => e.type === 'matched')));
    assert.equal(matches.filter(e => e.role === 'guest').length, target - 1);
    assert.ok(matches.every(e => e.code === prepared.code));
    console.log(`ok  full ${target}-player group and host readiness`);
  }
  const a = await connect(4), b = await connect(4);
  const offer = await a.until(e => e.offer === true);
  assert.equal(offer.humans, 2); assert.equal(offer.bots, 2);
  a.action('accept_bots', offer.revision);
  const agreed = await b.until(e => e.agreed === 1);
  assert.equal(agreed.type, 'queue');
  b.action('keep_searching');
  const resumed = await a.until(e => e.type === 'queue' && e.offer === false && e.humans === 2 && e.remaining === 20);
  assert.equal(resumed.agreed, 0);
  console.log('ok  real 20-second alarm, partial group, consent and keep searching');
} finally { for (const ws of sockets) ws.close(); }
