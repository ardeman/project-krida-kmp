// Account smoke test: starts a local relay whose Apple key set and Google token endpoint
// point at a mock in this script, then signs in with both, signs out and deletes.
// Usage: node scripts/smoke-accounts.mjs   (from relay/; needs no network or credentials)
import { spawn } from "node:child_process";
import { createHash, generateKeyPairSync, randomBytes, sign, verify } from "node:crypto";
import { createServer, request } from "node:http";
import assert from "node:assert/strict";

const RELAY_PORT = 8791;
const MOCK_PORT = 8792;
const relay = `http://localhost:${RELAY_PORT}`;
const mock = `http://localhost:${MOCK_PORT}`;
const BUNDLE = "com.ardeman.krida";
const GOOGLE_CLIENT = "test-client";

const b64 = (value) => Buffer.from(typeof value === "string" ? value : JSON.stringify(value)).toString("base64url");
const { publicKey, privateKey } = generateKeyPairSync("rsa", { modulusLength: 2048 });
const other = generateKeyPairSync("rsa", { modulusLength: 2048 });

function appleToken(claims = {}, key = privateKey) {
  const now = Math.floor(Date.now() / 1000);
  const head = b64({ alg: "RS256", kid: "k1" });
  const payload = b64({ iss: "https://appleid.apple.com", aud: BUNDLE, sub: "apple-user-1", iat: now, exp: now + 600, ...claims });
  return `${head}.${payload}.${sign("sha256", Buffer.from(`${head}.${payload}`), key).toString("base64url")}`;
}

let googleSub = "google-user-1";
// APNs: what the relay posted, and the status the mock answers each device token with.
const apns = generateKeyPairSync("ec", { namedCurve: "P-256" });
const pushes = [];
const pushStatus = {};
const server = createServer((req, res) => {
  res.setHeader("Content-Type", "application/json");
  if (req.url.startsWith("/3/device/")) {
    let text = "";
    req.on("data", (d) => (text += d));
    req.on("end", () => {
      const token = req.url.slice("/3/device/".length);
      pushes.push({ token, headers: req.headers, body: JSON.parse(text) });
      res.statusCode = pushStatus[token] ?? 200;
      res.end(res.statusCode === 200 ? "" : JSON.stringify({ reason: "Unregistered" }));
    });
  } else if (req.url === "/keys") {
    res.end(JSON.stringify({ keys: [{ ...publicKey.export({ format: "jwk" }), kid: "k1", alg: "RS256", use: "sig" }] }));
  } else if (req.url === "/token") {
    const idToken = `${b64({ alg: "none" })}.${b64({
      iss: "https://accounts.google.com", aud: GOOGLE_CLIENT, sub: googleSub, given_name: "Budi",
      picture: "https://lh3.googleusercontent.com/a/budi",
    })}.x`;
    res.end(JSON.stringify({ id_token: idToken }));
  } else {
    res.statusCode = 404;
    res.end("{}");
  }
}).listen(MOCK_PORT);

const dev = spawn("npx", [
  "wrangler", "dev", "--port", String(RELAY_PORT), "--persist-to", `.wrangler/smoke-accounts-${Date.now()}`,
  "--var", `APPLE_JWKS_URL:${mock}/keys`, "--var", `GOOGLE_TOKEN_URL:${mock}/token`,
  "--var", `GOOGLE_CLIENT_ID:${GOOGLE_CLIENT}`, "--var", "GOOGLE_CLIENT_SECRET:test-secret",
  "--var", `APNS_URL:${mock}`, "--var", "APPLE_TEAM_ID:TEAM123456", "--var", "APNS_KEY_ID:KEY1234567",
  "--var", `APNS_PRIVATE_KEY:${apns.privateKey.export({ format: "der", type: "pkcs8" }).toString("base64")}`,
], { stdio: ["ignore", "pipe", "pipe"] });
let log = "";
dev.stdout.on("data", (d) => (log += d));
dev.stderr.on("data", (d) => (log += d));

async function call(method, path, { token, json } = {}) {
  const reply = await fetch(relay + path, {
    method,
    redirect: "manual",
    headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(json ? { "Content-Type": "application/json" } : {}) },
    body: json ? JSON.stringify(json) : undefined,
  });
  const text = await reply.text();
  let data = null;
  try { data = JSON.parse(text); } catch {}
  return { status: reply.status, data, text, location: reply.headers.get("location") };
}

/** Runs the browser part of the Google flow and returns the code the app would receive. */
async function googleCode(verifier) {
  const challenge = createHash("sha256").update(verifier).digest("base64url");
  const start = await call("GET", `/v1/auth/google/start?challenge=${challenge}`);
  assert.equal(start.status, 302);
  const to = new URL(start.location);
  assert.equal(to.origin + to.pathname, "https://accounts.google.com/o/oauth2/v2/auth");
  // wrangler dev reports the route's host as the origin, so only the path is checked.
  assert.equal(new URL(to.searchParams.get("redirect_uri")).pathname, "/v1/auth/google/callback");
  const state = to.searchParams.get("state");
  const page = await call("GET", `/v1/auth/google/callback?code=from-google&state=${state}`);
  const code = page.text.match(/krida:\/\/auth\?code=([A-Za-z0-9_-]+)/)?.[1];
  assert.ok(code, "callback page links back to the app with a code");
  // A state works once.
  const again = await call("GET", `/v1/auth/google/callback?code=from-google&state=${state}`);
  assert.match(again.text, /krida:\/\/auth\?error=expired/);
  return code;
}

async function run() {
  for (let i = 0; i < 90; i++) {
    try { if ((await fetch(relay + "/")).ok) break; } catch {}
    await new Promise((r) => setTimeout(r, 500));
  }

  // Apple
  assert.equal((await call("GET", "/v1/me")).status, 401);
  assert.equal((await call("POST", "/v1/auth/apple", { json: { identityToken: appleToken({}, other.privateKey) } })).status, 401, "wrong key");
  assert.equal((await call("POST", "/v1/auth/apple", { json: { identityToken: appleToken({ aud: "com.other.app" }) } })).status, 401, "wrong audience");
  assert.equal((await call("POST", "/v1/auth/apple", { json: { identityToken: appleToken({ exp: 1 }) } })).status, 401, "expired");
  assert.equal((await call("POST", "/v1/auth/apple", { json: { identityToken: "junk" } })).status, 401, "junk");

  const apple = await call("POST", "/v1/auth/apple", { json: { identityToken: appleToken(), name: "  Ayu\n " } });
  assert.equal(apple.status, 200);
  assert.deepEqual(Object.keys(apple.data.user).sort(), ["id", "name", "provider"]);
  assert.equal(apple.data.user.name, "Ayu");
  assert.equal(apple.data.user.provider, "apple");
  // Apple gives the name only the first time; a later sign-in keeps it and the same id.
  const appleAgain = await call("POST", "/v1/auth/apple", { json: { identityToken: appleToken() } });
  assert.equal(appleAgain.data.user.id, apple.data.user.id);
  assert.equal(appleAgain.data.user.name, "Ayu");
  assert.notEqual(appleAgain.data.token, apple.data.token);
  assert.equal((await call("GET", "/v1/me", { token: apple.data.token })).data.user.id, apple.data.user.id);

  // Name and avatar (10d): Apple can set a mix, not Google's picture.
  assert.equal((await call("PATCH", "/v1/me", { token: appleAgain.data.token, json: { name: "" } })).data.error, "bad_name");
  assert.equal((await call("PATCH", "/v1/me", { token: appleAgain.data.token, json: { avatar: "g" } })).data.error, "bad_avatar");
  assert.equal((await call("PATCH", "/v1/me", { token: appleAgain.data.token, json: { avatar: "2-12-12" } })).data.error, "bad_avatar");
  const ayuProfile = await call("PATCH", "/v1/me", { token: appleAgain.data.token, json: { name: "Ayu W", avatar: "2-12-3" } });
  assert.equal(ayuProfile.status, 200);
  assert.equal(ayuProfile.data.user.name, "Ayu W");
  assert.equal(ayuProfile.data.user.avatar, "2-12-3");
  assert.equal((await call("GET", "/v1/me", { token: appleAgain.data.token })).data.user.avatar, "2-12-3");

  // Sign out ends that session only.
  assert.equal((await call("POST", "/v1/auth/signout", { token: apple.data.token })).status, 204);
  assert.equal((await call("GET", "/v1/me", { token: apple.data.token })).status, 401);
  assert.equal((await call("GET", "/v1/me", { token: appleAgain.data.token })).status, 200);

  // Google
  assert.equal((await call("GET", "/v1/auth/google/start?challenge=short")).status, 400);
  const verifier = randomBytes(32).toString("base64url");
  const stolen = await googleCode(verifier);
  const wrong = await call("POST", "/v1/auth/exchange", { json: { code: stolen, verifier: randomBytes(32).toString("base64url") } });
  assert.equal(wrong.status, 401, "a code without its verifier is useless");
  assert.equal((await call("POST", "/v1/auth/exchange", { json: { code: stolen, verifier } })).status, 401, "and is spent by the attempt");

  const code = await googleCode(verifier);
  const google = await call("POST", "/v1/auth/exchange", { json: { code, verifier } });
  assert.equal(google.status, 200);
  assert.equal(google.data.user.name, "Budi");
  assert.equal(google.data.user.provider, "google");
  assert.equal(google.data.user.picture, "https://lh3.googleusercontent.com/a/budi");
  assert.equal(google.data.user.avatar, undefined);
  assert.notEqual(google.data.user.id, apple.data.user.id);
  const budiPic = await call("PATCH", "/v1/me", { token: google.data.token, json: { avatar: "g" } });
  assert.equal(budiPic.status, 200);
  assert.equal(budiPic.data.user.avatar, "https://lh3.googleusercontent.com/a/budi");
  assert.equal((await call("POST", "/v1/auth/exchange", { json: { code, verifier } })).status, 401, "a code works once");

  // Delete: the account and every session go; signing in again makes a new account.
  const second = await call("POST", "/v1/auth/exchange", { json: { code: await googleCode(verifier), verifier } });
  assert.equal(second.data.user.id, google.data.user.id);
  assert.equal((await call("DELETE", "/v1/me")).status, 401);
  assert.equal((await call("DELETE", "/v1/me", { token: google.data.token })).status, 204);
  assert.equal((await call("GET", "/v1/me", { token: google.data.token })).status, 401);
  assert.equal((await call("GET", "/v1/me", { token: second.data.token })).status, 401);
  const reborn = await call("POST", "/v1/auth/exchange", { json: { code: await googleCode(verifier), verifier } });
  assert.notEqual(reborn.data.user.id, google.data.user.id);
  assert.equal((await call("GET", "/v1/me", { token: appleAgain.data.token })).status, 200, "other accounts are untouched");

  // Friends: a code per account; adding is instant and mutual, removing ends it for both.
  const ayu = appleAgain.data.token;
  const budi = reborn.data.token;
  assert.equal((await call("GET", "/v1/friends")).status, 401);
  const mine = await call("GET", "/v1/friends", { token: ayu });
  assert.match(mine.data.code, /^[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{8}$/);
  assert.deepEqual(mine.data.friends, []);
  assert.equal((await call("GET", "/v1/friends", { token: ayu })).data.code, mine.data.code, "the code does not change");
  const theirs = (await call("GET", "/v1/friends", { token: budi })).data.code;
  assert.notEqual(theirs, mine.data.code);
  assert.equal((await call("POST", "/v1/friends", { token: ayu, json: { code: "junk" } })).data.error, "code_not_found");
  assert.equal((await call("POST", "/v1/friends", { token: ayu, json: { code: mine.data.code } })).data.error, "own_code");
  const spaced = `${theirs.slice(0, 4).toLowerCase()} ${theirs.slice(4)}`;
  const added = await call("POST", "/v1/friends", { token: ayu, json: { code: spaced } });
  assert.equal(added.status, 200);
  assert.deepEqual(added.data.friend, { id: reborn.data.user.id, name: "Budi" });
  assert.equal((await call("POST", "/v1/friends", { token: budi, json: { code: mine.data.code } })).data.error, "already_friends");
  assert.deepEqual((await call("GET", "/v1/friends", { token: budi })).data.friends, [
    { id: apple.data.user.id, name: "Ayu W", avatar: "2-12-3" },
  ]);
  assert.equal((await call("DELETE", `/v1/friends/${apple.data.user.id}`, { token: budi })).status, 204);
  assert.deepEqual((await call("GET", "/v1/friends", { token: ayu })).data.friends, []);
  // Deleting an account takes it out of its friends' lists and frees nothing for reuse.
  assert.equal((await call("POST", "/v1/friends", { token: budi, json: { code: mine.data.code } })).status, 200);
  await invites(ayu, budi, apple.data.user.id, reborn.data.user.id);
  assert.equal((await call("DELETE", "/v1/me", { token: budi })).status, 204);
  assert.deepEqual((await call("GET", "/v1/friends", { token: ayu })).data.friends, []);
  assert.equal((await call("POST", "/v1/friends", { token: ayu, json: { code: theirs } })).data.error, "code_not_found");
  // Guessing codes stops after 20 well-formed tries in ten minutes (Ayu used three above).
  for (let i = 0; i < 17; i++) await call("POST", "/v1/friends", { token: ayu, json: { code: "AAAAAAAA" } });
  assert.equal((await call("POST", "/v1/friends", { token: ayu, json: { code: "AAAAAAAA" } })).status, 429);

  // Rooms still answer next to the account routes.
  assert.equal((await call("GET", "/v1/rooms/ABC234")).status, 426);
  console.log("accounts smoke ok");
}

/** A room socket (no headers needed), open. */
function roomSocket(query) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`ws://localhost:${RELAY_PORT}/v1/rooms/${query}`);
    ws.onopen = () => resolve(ws);
    ws.onerror = reject;
  });
}

/** The inbox socket by hand, since Node's WebSocket cannot send an Authorization header. */
function inboxSocket(token) {
  return new Promise((resolve, reject) => {
    const req = request(`${relay}/v1/inbox`, {
      headers: {
        Connection: "Upgrade", Upgrade: "websocket", "Sec-WebSocket-Version": "13",
        "Sec-WebSocket-Key": randomBytes(16).toString("base64"), ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
    });
    req.on("response", (res) => resolve({ status: res.statusCode }));
    req.on("error", reject);
    req.on("upgrade", (_res, socket, head) => {
      const messages = [];
      let waiting = null;
      let buffer = head;
      const read = (chunk) => {
        buffer = Buffer.concat([buffer, chunk]);
        // Server text frames are unmasked and short enough here for the 7-bit or 16-bit length.
        while (buffer.length >= 2) {
          const long = (buffer[1] & 0x7f) === 126;
          const start = long ? 4 : 2;
          const length = long ? buffer.readUInt16BE(2) : buffer[1] & 0x7f;
          if (buffer.length < start + length) break;
          if ((buffer[0] & 0x0f) === 1) messages.push(JSON.parse(buffer.subarray(start, start + length).toString()));
          buffer = buffer.subarray(start + length);
        }
        if (waiting && messages.length) waiting(messages.shift());
      };
      socket.on("data", read);
      read(Buffer.alloc(0));
      resolve({
        status: 101,
        next: () => new Promise((done, fail) => {
          if (messages.length) return done(messages.shift());
          const timer = setTimeout(() => fail(new Error("no inbox message")), 5000);
          waiting = (m) => { clearTimeout(timer); waiting = null; done(m); };
        }),
        send: (value) => {
          const payload = Buffer.from(JSON.stringify(value));
          const mask = randomBytes(4);
          socket.write(Buffer.concat([Buffer.from([0x81, 0x80 | payload.length]), mask, payload.map((b, i) => b ^ mask[i % 4])]));
        },
        close: () => socket.destroy(),
      });
    });
    req.end();
  });
}

/** Invites: only the host of an open room invites, only friends, one live invite each. */
async function invites(ayu, budi, ayuId, budiId) {
  const key = "host-key-0123456789";
  const host = await roomSocket(`ABC234?role=host&key=${key}&game=ludo&seats=3`);
  const invite = (token, json) => call("POST", "/v1/invites", { token, json });
  assert.equal((await inboxSocket()).status, 401);
  assert.equal((await invite(undefined, { friendId: budiId, code: "ABC234", key })).status, 401);
  assert.equal((await invite(ayu, { friendId: budiId, code: "abc", key })).data.error, "bad_request");
  assert.equal((await invite(ayu, { friendId: "nobody", code: "ABC234", key })).data.error, "not_friends");
  assert.equal((await invite(ayu, { friendId: budiId, code: "ABC234", key: "another-key-0123456789" })).data.error, "not_host");
  assert.equal((await invite(ayu, { friendId: budiId, code: "ABC239", key })).data.error, "room_closed");

  // iPhone notifications: Budi has two iPhones, one of which no longer has the app.
  const iphone = "ab".repeat(32);
  const gone = "cd".repeat(32);
  const pushToken = (token, json) => call("POST", "/v1/push-token", { token, json });
  assert.equal((await pushToken(undefined, { token: iphone })).status, 401);
  assert.equal((await pushToken(budi, { token: "short" })).data.error, "bad_request");
  assert.equal((await pushToken(budi, { token: iphone.toUpperCase(), env: "sandbox", lang: "id" })).status, 204);
  assert.equal((await pushToken(budi, { token: gone })).status, 204);
  pushStatus[gone] = 410;

  const phone = await inboxSocket(budi);
  assert.equal(phone.status, 101);
  const sent = await invite(ayu, { friendId: budiId, code: "ABC234", key });
  assert.equal(sent.status, 200);
  assert.deepEqual(pushes.map((p) => p.token).sort(), [iphone, gone]);
  const push = pushes.find((p) => p.token === iphone);
  assert.deepEqual(push.body, { aps: { alert: { body: "Ayu W mengajakmu main Ludo" }, sound: "default" }, code: "ABC234", invite: sent.data.id });
  assert.equal(push.headers["apns-topic"], BUNDLE);
  assert.equal(push.headers["apns-push-type"], "alert");
  assert.equal(push.headers["apns-collapse-id"], sent.data.id);
  const [head, claims, signature] = push.headers.authorization.replace(/^bearer /, "").split(".");
  assert.deepEqual(JSON.parse(Buffer.from(head, "base64url")), { alg: "ES256", kid: "KEY1234567" });
  assert.equal(JSON.parse(Buffer.from(claims, "base64url")).iss, "TEAM123456");
  assert.ok(verify("sha256", Buffer.from(`${head}.${claims}`), { key: apns.publicKey, dsaEncoding: "ieee-p1363" }, Buffer.from(signature, "base64url")), "APNs token signature");
  const got = await phone.next();
  assert.ok(got.ttlMs > 100_000 && got.ttlMs <= 120_000);
  delete got.ttlMs;
  assert.deepEqual(got, {
    type: "invite", id: sent.data.id,
    from: { id: ayuId, name: "Ayu W", avatar: "2-12-3" },
    code: "ABC234", game: "ludo", seats: 3, players: 1,
  });
  assert.equal((await invite(ayu, { friendId: budiId, code: "ABC234", key })).data.error, "already_invited");
  // A second device gets the waiting invite on connect; answering on one ends it on the other.
  const tablet = await inboxSocket(budi);
  assert.equal((await tablet.next()).id, sent.data.id);
  phone.send({ type: "dismiss", id: sent.data.id });
  assert.deepEqual(await tablet.next(), { type: "invite_end", id: sent.data.id });
  tablet.close();

  // A room that fills ends its invites, and takes no new ones.
  const full = await roomSocket(`ABC235?role=host&key=${key}&game=congklak&seats=2`);
  pushes.length = 0;
  const second = await invite(ayu, { friendId: budiId, code: "ABC235", key });
  assert.deepEqual(pushes.map((p) => p.token), [iphone], "a token APNs calls unregistered is dropped");
  assert.equal(pushes[0].body.aps.alert.body, "Ayu W mengajakmu main Congklak");
  assert.equal((await phone.next()).id, second.data.id);
  const guest = await roomSocket("ABC235?role=guest");
  assert.deepEqual(await phone.next(), { type: "invite_end", id: second.data.id });
  assert.equal((await invite(ayu, { friendId: budiId, code: "ABC235", key })).data.error, "room_closed");
  for (const socket of [host, full, guest]) socket.close(1000);
  phone.close();
}

let failed = false;
try {
  await run();
} catch (e) {
  failed = true;
  console.error(e);
  console.error(log.split("\n").slice(-30).join("\n"));
} finally {
  dev.kill();
  server.close();
}
process.exit(failed ? 1 : 0);
