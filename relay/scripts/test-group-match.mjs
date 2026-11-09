import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import ts from 'typescript';

const source = await readFile(new URL('../src/group-matchmaker.ts', import.meta.url), 'utf8');
const compiled = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022 } }).outputText;
const { GroupMatchmaker } = await import(`data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`);

class Socket {
  attachment = null;
  messages = [];
  serializeAttachment(value) { this.attachment = structuredClone(value); }
  deserializeAttachment() { return structuredClone(this.attachment); }
  send(value) { this.messages.push(JSON.parse(value)); }
  close(code) { this.closeCode = code; }
  get last() { return this.messages.at(-1); }
}
function setup(t, target = 4) {
  let now = 1_000;
  t.mock.method(Date, 'now', () => now);
  const originalPair = globalThis.WebSocketPair, originalResponse = globalThis.Response;
  globalThis.WebSocketPair = class {
    constructor() { this[0] = new Socket(); this[1] = new Socket(); }
  };
  globalThis.Response = class {
    constructor(body, options) { Object.assign(this, options); }
  };
  t.after(() => { globalThis.WebSocketPair = originalPair; globalThis.Response = originalResponse; });
  const sockets = [];
  const ctx = {
    acceptWebSocket(ws) { sockets.push(ws); }, getWebSockets() { return sockets; },
    storage: { async setAlarm(at) { ctx.alarm = at; }, async deleteAlarm() { ctx.alarm = null; } },
  };
  let worker = new GroupMatchmaker(ctx);
  return {
    get worker() { return worker; }, ctx,
    async add() {
      await worker.fetch(new Request(`https://relay/v1/match/ular_tangga/${target}?flow=group`, { headers: { Upgrade: 'websocket' } }));
      return sockets.at(-1);
    },
    async advance(ms) { now += ms; await worker.alarm(); },
    async action(ws, type) { await worker.webSocketMessage(ws, JSON.stringify({ type, revision: ws.last.revision })); },
    wake() { worker = new GroupMatchmaker(ctx); },
  };
}
for (const target of [2, 3, 4]) test(`${target} humans required; guests wait for host readiness`, async t => {
  const f = setup(t, target), players = [];
  for (let i = 0; i < target; i++) {
    players.push(await f.add());
    if (i < target - 1) assert.equal(players[0].last.type, 'queue');
  }
  const host = players.find(ws => ws.last.type === 'prepare_host');
  assert.ok(host);
  assert.equal(host.last.humans, target);
  assert.equal(host.last.bots, 0);
  assert.ok(players.every(ws => ws.messages.every(m => m.type !== 'matched')));
  await f.action(players.find(ws => ws !== host), 'host_ready');
  assert.ok(players.every(ws => ws.last.type !== 'matched'));
  await f.action(host, 'host_ready');
  assert.equal(host.last.role, 'host');
  assert.equal(players.filter(ws => ws.last.role === 'guest').length, target - 1);
  assert.ok(players.every(ws => ws.last.code === host.last.code && ws.closeCode === 1000));
});
test('both humans must agree to two computers in a four-seat search', async t => {
  const f = setup(t), a = await f.add(), b = await f.add();
  await f.advance(20_000);
  assert.equal(a.last.offer, true);
  assert.equal(a.last.bots, 2);
  await f.action(a, 'accept_bots');
  assert.equal(a.last.accepted, true);
  assert.equal(b.last.agreed, 1);
  assert.equal(a.last.type, 'queue');
  await f.action(b, 'accept_bots');
  assert.equal(a.last.type, 'prepare_host');
  assert.equal(b.last.type, 'preparing');
  await f.action(a, 'host_ready');
  assert.equal(a.last.humans, 2);
  assert.equal(a.last.bots, 2);
});
test('cannot agree to computers before the offer', async t => {
  const f = setup(t), a = await f.add();
  await f.action(a, 'accept_bots');
  assert.equal(a.deserializeAttachment().consent, false);
  assert.equal(a.last.offer, false);
});
test('keep searching preserves the group and clears agreements', async t => {
  const f = setup(t), a = await f.add(), b = await f.add();
  await f.advance(20_000);
  await f.action(a, 'accept_bots');
  await f.action(b, 'keep_searching');
  assert.equal(a.last.humans, 2);
  assert.equal(a.last.offer, false);
  assert.equal(a.last.remaining, 20);
  assert.equal(a.last.agreed, 0);
  assert.equal(a.deserializeAttachment().group, b.deserializeAttachment().group);
});
test('cancellation clears consent; remaining player can choose a local bot game', async t => {
  const f = setup(t), a = await f.add(), b = await f.add();
  await f.advance(20_000);
  await f.action(a, 'accept_bots');
  await f.worker.webSocketClose(b);
  assert.equal(a.last.humans, 1);
  assert.equal(a.last.accepted, false);
  await f.action(a, 'accept_bots');
  assert.equal(a.last.role, 'solo');
  assert.equal(a.last.target, 4);
});
test('a new player invalidates earlier consent', async t => {
  const f = setup(t), a = await f.add();
  await f.add();
  await f.advance(20_000);
  await f.action(a, 'accept_bots');
  const c = await f.add();
  assert.equal(a.last.humans, 3);
  assert.equal(a.last.bots, 1);
  assert.equal(a.last.agreed, 0);
  assert.equal(c.last.offer, true);
});
test('hibernation preserves the queue and deadlines; missing host fails everyone', async t => {
  const f = setup(t), a = await f.add(), b = await f.add();
  f.wake();
  await f.advance(20_000);
  assert.equal(a.last.offer, true);
  await f.action(a, 'accept_bots'); await f.action(b, 'accept_bots');
  f.wake();
  await f.advance(15_000);
  assert.equal(a.last.type, 'match_error'); assert.equal(b.last.type, 'match_error');
  assert.equal(f.ctx.alarm, null);
});
test('cancelling room preparation fails peers; fresh searches use another group', async t => {
  const f = setup(t, 2), a = await f.add(), b = await f.add();
  await f.worker.webSocketClose(a);
  assert.equal(b.last.type, 'match_error');
  const c = await f.add();
  assert.equal(c.last.type, 'queue'); assert.equal(c.last.humans, 1);
  assert.notEqual(c.deserializeAttachment().group, b.deserializeAttachment().group);
});

test('agreement for an earlier composition cannot accept new computer seats', async t => {
  const f = setup(t), a = await f.add();
  await f.add(); await f.advance(20_000);
  const revision = a.last.revision;
  await f.add();
  await f.worker.webSocketMessage(a, JSON.stringify({ type: 'accept_bots', revision }));
  assert.equal(a.deserializeAttachment().consent, false);
});
