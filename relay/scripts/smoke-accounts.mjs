// Account smoke test: starts a local relay whose Apple key set and Google token endpoint
// point at a mock in this script, then signs in with both, signs out and deletes.
// Usage: node scripts/smoke-accounts.mjs   (from relay/; needs no network or credentials)
import { spawn } from "node:child_process";
import { createHash, generateKeyPairSync, randomBytes, sign } from "node:crypto";
import { createServer } from "node:http";
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
const server = createServer((req, res) => {
  res.setHeader("Content-Type", "application/json");
  if (req.url === "/keys") {
    res.end(JSON.stringify({ keys: [{ ...publicKey.export({ format: "jwk" }), kid: "k1", alg: "RS256", use: "sig" }] }));
  } else if (req.url === "/token") {
    const idToken = `${b64({ alg: "none" })}.${b64({ iss: "https://accounts.google.com", aud: GOOGLE_CLIENT, sub: googleSub, given_name: "Budi" })}.x`;
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
  assert.notEqual(google.data.user.id, apple.data.user.id);
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

  // Rooms still answer next to the account routes.
  assert.equal((await call("GET", "/v1/rooms/ABC234")).status, 426);
  console.log("accounts smoke ok");
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
