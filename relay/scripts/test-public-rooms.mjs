import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import ts from 'typescript';

async function load(file) {
  const source = (await readFile(new URL(`../src/${file}.ts`, import.meta.url), 'utf8'))
    .replace('export { GroupMatchmaker } from "./group-matchmaker";', '')
    // A data: module cannot resolve relative imports; these tests don't touch the account routes.
    .replace('import { handleAccounts } from "./accounts";', 'const handleAccounts = async () => null;');
  const code = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022 } }).outputText;
  return import(`data:text/javascript;base64,${Buffer.from(code).toString('base64')}`);
}
const { Room, default: router } = await load('index');
const { GroupMatchmaker } = await load('group-matchmaker');
class Socket {
  static READY_STATE_OPEN = 1;
  readyState = 1;
  messages = [];
  attachment = null;
  serializeAttachment(value) { this.attachment = structuredClone(value); }
  deserializeAttachment() { return structuredClone(this.attachment); }
  send(value) { this.messages.push(typeof value === 'string' ? JSON.parse(value) : value); }
  close(code) { this.closeCode = code; this.readyState = 3; }
  accept() {}
  get last() { return this.messages.at(-1); }
}
function fixture(t) {
  let now = 1_000;
  t.mock.method(Date, 'now', () => now);
  const saved = { WebSocket: globalThis.WebSocket, WebSocketPair: globalThis.WebSocketPair, Response: globalThis.Response };
  globalThis.WebSocket = Socket;
  globalThis.WebSocketPair = class { constructor() { this[0] = new Socket(); this[1] = new Socket(); this[0].peer = this[1]; this[1].peer = this[0]; } };
  globalThis.Response = class {
    constructor(body, options = {}) { this.body = body; Object.assign(this, options); }
    static json(value) { return new this(JSON.stringify(value), { status: 200 }); }
    async json() { return JSON.parse(this.body); }
  };
  t.after(() => Object.assign(globalThis, saved));
  function context() {
    const values = new Map(), sockets = [];
    let pending = Promise.resolve();
    return {
      values, sockets,
      blockConcurrencyWhile(action) { const result = pending.then(action); pending = result.catch(() => {}); return result; },
      acceptWebSocket(ws, tags = []) { ws.tags = tags; sockets.push(ws); },
      getWebSockets(tag) { return sockets.filter(ws => !tag || ws.tags.includes(tag)); },
      storage: {
        async get(key) { return structuredClone(values.get(key)); },
        async put(key, value) { values.set(key, structuredClone(value)); },
        async delete(key) { values.delete(key); }, async deleteAll() { values.clear(); },
        async list({ prefix }) { return new Map([...values].filter(([key]) => key.startsWith(prefix))); },
        async setAlarm(at) {}, async deleteAlarm() {},
      },
    };
  }
  const rooms = new Map(), directories = new Map();
  const env = {
    ROOMS: { idFromName: code => code, get: code => room(code).worker },
    GROUP_MATCHMAKERS: { idFromName: key => key, get: key => directory(key).worker },
  };
  function room(code = 'ABCDEF') {
    if (!rooms.has(code)) { const ctx = context(); rooms.set(code, { ctx, worker: new Room(ctx, env) }); }
    return rooms.get(code);
  }
  function directory(key = 'ular_tangga/4') {
    if (!directories.has(key)) { const ctx = context(); directories.set(key, { ctx, worker: new GroupMatchmaker(ctx, env) }); }
    return directories.get(key);
  }
  const request = url => new Request(url, { headers: { Upgrade: 'websocket' } });
  return {
    room, directory, env,
    async host({ code = 'ABCDEF', game = 'ular_tangga', seats = 4, publicRoom = true, key = 'abcdefghijklmnop' } = {}) {
      const r = room(code);
      const response = await r.worker.fetch(request(`https://relay/v1/rooms/${code}?role=host&key=${key}&game=${game}&seats=${seats}&public=${publicRoom ? 1 : 0}`));
      return { ...r, host: r.ctx.sockets.filter(ws => ws.tags.includes('host')).at(-1), response };
    },
    async reserve(code = 'ABCDEF', count = 1, seats = 4, game = 'ular_tangga') {
      return (await room(code).worker.fetch(new Request('https://internal/reserve', { method: 'POST', body: JSON.stringify({ count, seats, game }) }))).json();
    },
    async guest(code = 'ABCDEF', token) {
      const r = room(code);
      const response = await r.worker.fetch(request(`https://relay/v1/rooms/${code}?role=guest${token ? `&reservation=${token}` : ''}`));
      return { response, socket: r.ctx.sockets.at(-1) };
    },
    async search({ game = 'ular_tangga', seats = 4, discover = true } = {}) {
      const d = directory(`${game}/${seats}`);
      await d.worker.fetch(request(`https://relay/v1/match/${game}/${seats}?flow=group${discover ? '&public=1' : ''}`));
      return d.ctx.sockets.at(-1);
    },
    async status(r, bots, started = false) {
      const payload = new TextEncoder().encode(JSON.stringify({ bots, started }));
      const frame = new Uint8Array(payload.length + 2); frame[0] = 5; frame.set(payload, 2);
      await r.worker.webSocketMessage(r.host, frame.buffer);
    },
    advance(ms) { now += ms; },
  };
}

test('public rooms match strangers; private rooms and legacy searches stay private', async t => {
  const f = fixture(t);
  await f.host({ publicRoom: false });
  const queued = await f.search();
  assert.equal(queued.last.type, 'queue');
  await f.host({ code: 'BCDEFG' });
  await f.directory().worker.alarm();
  assert.equal(queued.last.code, 'BCDEFG');
  assert.equal(queued.last.role, 'guest');
  assert.ok(queued.last.reservation);
  const legacy = await f.search({ discover: false });
  assert.equal(legacy.last.type, 'queue');
  const joined = await f.guest('BCDEFG', queued.last.reservation);
  assert.equal(joined.response.status, 101);
  assert.equal(joined.response.webSocket.closeCode, undefined);
});

test('atomic reservations prevent competing manual joins from taking held seats', async t => {
  const f = fixture(t); await f.host({ seats: 2 });
  const results = await Promise.all([f.reserve('ABCDEF', 1, 2), f.reserve('ABCDEF', 1, 2)]);
  assert.equal(results.flatMap(r => r.tokens).length, 1);
  const manual = await f.guest();
  assert.equal(manual.response.webSocket.peer.closeCode, 4009);
  assert.equal(f.room().ctx.sockets.filter(ws => ws.tags.includes('guest')).length, 0);
  const token = results.flatMap(r => r.tokens)[0];
  await f.guest('ABCDEF', token);
  assert.equal(f.room().ctx.sockets.filter(ws => ws.tags.includes('guest')).length, 1);
  await f.guest('ABCDEF', token);
  assert.equal(f.room().ctx.sockets.filter(ws => ws.tags.includes('guest')).length, 1);
});

test('reservations expire and explicit cancellation releases capacity', async t => {
  const f = fixture(t); await f.host({ seats: 2 });
  let { tokens } = await f.reserve('ABCDEF', 1, 2);
  await f.room().worker.fetch(new Request('https://internal/release', { method: 'POST', body: JSON.stringify({ tokens }) }));
  assert.equal((await f.reserve('ABCDEF', 1, 2)).tokens.length, 1);
  f.advance(15_001);
  assert.equal((await f.reserve('ABCDEF', 1, 2)).tokens.length, 1);
});

test('public discovery respects game, selected capacity, and computer seats', async t => {
  const f = fixture(t); const r = await f.host();
  assert.deepEqual((await f.reserve('ABCDEF', 1, 2)).tokens, []);
  assert.deepEqual((await f.reserve('ABCDEF', 1, 4, 'congklak')).tokens, []);
  await f.status(r, 2);
  assert.deepEqual((await f.reserve('ABCDEF', 2)).tokens, []);
  assert.equal((await f.reserve()).tokens.length, 1);
});

test('waiting groups join newly published rooms together and are never split', async t => {
  const f = fixture(t);
  const a = await f.search(), b = await f.search();
  const r = await f.host();
  await f.status(r, 2); // Only one open seat, insufficient for the two-person group.
  await f.directory().worker.alarm();
  assert.equal(a.last.type, 'queue'); assert.equal(b.last.type, 'queue');
  await f.status(r, 1);
  await f.directory().worker.alarm();
  assert.equal(a.last.code, 'ABCDEF'); assert.equal(b.last.code, 'ABCDEF');
  assert.notEqual(a.last.reservation, b.last.reservation);
});

test('started, disconnected, and closed rooms reject discovery; reconnect restores only waiting public rooms', async t => {
  const f = fixture(t); let r = await f.host();
  r.host.close(1006); await r.worker.webSocketClose(r.host, 1006);
  assert.deepEqual((await f.reserve()).tokens, []);
  r = await f.host();
  assert.equal((await f.reserve()).tokens.length, 1);
  await f.status(r, 0, true);
  assert.deepEqual((await f.reserve()).tokens, []);
  r = await f.host();
  await f.status(r, 0, false); // Reconnect cannot turn a running game back into a lobby.
  assert.deepEqual((await f.reserve()).tokens, []);
  const closed = await f.host({ code: 'BCDEFG' });
  closed.host.close(1000); await closed.worker.webSocketClose(closed.host, 1000);
  assert.deepEqual((await f.reserve('BCDEFG')).tokens, []);
});

test('guest connections cannot publish a private room or change its setup', async t => {
  const f = fixture(t); const r = await f.host({ publicRoom: false });
  const { socket } = await f.guest();
  await f.status({ ...r, host: socket }, 0, false);
  assert.equal(r.ctx.values.get('listing').public, false);
  await f.host({ key: 'differenthostkey1' });
  assert.equal(r.ctx.sockets.filter(ws => ws.tags.includes('host')).length, 1);
  assert.equal(r.ctx.values.get('listing').public, false);
  assert.deepEqual((await f.reserve()).tokens, []);
  assert.equal((await router.fetch(new Request('https://relay/reserve'), f.env)).status, 404);
});

test('cancelling during reservation releases held seats before a later search', async t => {
  const f = fixture(t);
  const a = await f.search();
  await f.host();
  const originalGet = f.env.ROOMS.get;
  let complete, reserved;
  const pending = new Promise(resolve => { complete = resolve; });
  const reached = new Promise(resolve => { reserved = resolve; });
  f.env.ROOMS.get = id => ({ async fetch(request) {
    const result = await originalGet(id).fetch(request);
    if (new URL(request.url).pathname === '/reserve') { reserved(); await pending; }
    return result;
  } });
  const alarm = f.directory().worker.alarm();
  await reached;
  await f.directory().worker.webSocketClose(a);
  complete(); await alarm;
  assert.equal(f.room().ctx.values.get('reservations').length, 0);
  assert.equal(a.messages.some(m => m.type === 'matched'), false);
});

test('hibernation preserves public listings and waiting discovery tickets', async t => {
  const f = fixture(t);
  const a = await f.search(); await f.host();
  const d = f.directory();
  d.worker = new GroupMatchmaker(d.ctx, f.env);
  await d.worker.alarm();
  assert.equal(a.last.type, 'matched');
  assert.equal(a.last.code, 'ABCDEF');
});
