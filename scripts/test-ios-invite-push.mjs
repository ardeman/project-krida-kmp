#!/usr/bin/env node
// End-to-end test of friend invites on an iOS simulator against the live relay, real APNs
// (sandbox) included: node scripts/test-ios-invite-push.mjs ["iPhone 17"]
//
// It makes two throwaway accounts in the relay's D1 (needs `wrangler` signed in), makes
// them friends, hosts three rooms as one of them, and runs the KridaUITests UI test, which
// signs the app in as the other (`-testSession`, debug builds only) and has the host
// invite it: app in front, in the background and closed. The test taps each notification;
// this script then checks that the app joined those rooms with the invite's id. The
// accounts are deleted at the end. Needs an Apple-silicon Mac (simulator push) and JDK 17-21.
import { execFileSync, spawn, spawnSync } from "node:child_process";
import { createHash, randomBytes } from "node:crypto";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const api = process.env.KRIDA_API ?? "https://krida-relay.ardeman.com";
const simulator = process.argv[2] ?? "iPhone 17";
const BUNDLE = "com.ardeman.krida";
const ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

const token = () => randomBytes(32).toString("base64url");
const hash = (text) => createHash("sha256").update(text).digest("base64url");
const roomCode = () => Array.from(randomBytes(6), (b) => ALPHABET[b % ALPHABET.length]).join("");

function d1(sql) {
  execFileSync("npx", ["wrangler", "d1", "execute", "krida", "--remote", "--command", sql], { cwd: join(root, "relay"), stdio: "pipe" });
}

async function call(method, path, session, json) {
  const reply = await fetch(api + path, {
    method,
    headers: { Authorization: `Bearer ${session}`, ...(json ? { "Content-Type": "application/json" } : {}) },
    body: json ? JSON.stringify(json) : undefined,
  });
  const text = await reply.text();
  let data = null;
  try { data = JSON.parse(text); } catch {}
  return { status: reply.status, data };
}

/** Hosts `code` and records each guest's `hello`. */
function host(code, key, hellos) {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(`${api.replace(/^http/, "ws")}/v1/rooms/${code}?role=host&key=${key}&game=congklak&seats=2`);
    socket.binaryType = "arraybuffer";
    socket.onopen = () => resolve(socket);
    socket.onerror = () => reject(new Error(`room ${code} did not open`));
    socket.onmessage = (event) => {
      const bytes = new Uint8Array(event.data);
      if (bytes[0] !== 1) return;
      try {
        const message = JSON.parse(new TextDecoder().decode(bytes.subarray(2)));
        if (message.type === "hello") hellos.push({ code, invite: message.invite ?? null });
      } catch {}
    };
  });
}

const run = randomBytes(4).toString("hex");
const guest = { id: `e2e-guest-${run}`, session: token() };
const inviter = { id: `e2e-host-${run}`, session: token() };
const key = randomBytes(18).toString("base64url");
const rooms = [roomCode(), roomCode(), roomCode()];
const hellos = [];
const sockets = [];
let failed = false;

try {
  const now = Date.now();
  d1(`INSERT INTO users (id, provider, sub, name, created_at) VALUES
        ('${guest.id}', 'test', '${guest.id}', 'E2E Guest', ${now}), ('${inviter.id}', 'test', '${inviter.id}', 'E2E Host', ${now});
      INSERT INTO sessions (token_hash, user_id, created_at) VALUES
        ('${hash(guest.session)}', '${guest.id}', ${now}), ('${hash(inviter.session)}', '${inviter.id}', ${now});`);
  const code = (await call("GET", "/v1/friends", guest.session)).data?.code;
  if (!code) throw new Error("no friend code for the test guest");
  const added = await call("POST", "/v1/friends", inviter.session, { code });
  if (added.status !== 200) throw new Error(`adding the friend failed: ${added.status}`);
  for (const room of rooms) sockets.push(await host(room, key, hellos));
  console.log(`accounts ${guest.id} and ${inviter.id}, rooms ${rooms.join(" ")}`);

  // A fresh install, so iOS has not been asked about notifications yet.
  spawnSync("xcrun", ["simctl", "boot", simulator], { stdio: "ignore" });
  spawnSync("xcrun", ["simctl", "uninstall", simulator, BUNDLE], { stdio: "ignore" });
  spawnSync("xcodegen", [], { cwd: join(root, "ios"), stdio: "ignore" });
  // Not spawnSync: the host sockets above must keep running while the test does.
  const child = spawn("xcodebuild", [
    "test", "-project", "Krida.xcodeproj", "-scheme", "KridaUITests",
    "-destination", `platform=iOS Simulator,name=${simulator}`,
    "-derivedDataPath", "build/sim", "-resultBundlePath", `build/e2e-${run}.xcresult`,
  ], {
    cwd: join(root, "ios"),
    stdio: ["ignore", "pipe", "pipe"],
    env: {
      ...process.env,
      TEST_RUNNER_KRIDA_API: api,
      TEST_RUNNER_KRIDA_SESSION: guest.session,
      TEST_RUNNER_KRIDA_HOST_SESSION: inviter.session,
      TEST_RUNNER_KRIDA_FRIEND_ID: guest.id,
      TEST_RUNNER_KRIDA_HOST_KEY: key,
      TEST_RUNNER_KRIDA_ROOMS: rooms.join(","),
    },
  });
  let output = "";
  child.stdout.on("data", (d) => (output += d));
  child.stderr.on("data", (d) => (output += d));
  const status = await new Promise((resolve) => child.on("close", resolve));
  const lines = output.split("\n");
  console.log(lines.filter((l) => /error:|XCTAssert|Test Case|\*\* TEST|skipped/i.test(l)).slice(-25).join("\n"));
  if (status !== 0) throw new Error(`the UI test failed (result in ios/build/e2e-${run}.xcresult)`);

  // The in-app card was answered Not now; each tapped notification joined its room.
  console.log("joins seen by the host:", JSON.stringify(hellos));
  if (hellos.some((h) => h.code === rooms[0])) throw new Error("the app joined the room it declined");
  for (const room of rooms.slice(1)) {
    if (!hellos.some((h) => h.code === room && h.invite)) throw new Error(`no join with an invite id in room ${room}`);
  }
  console.log("ios invite push e2e ok");
} catch (e) {
  failed = true;
  console.error(String(e?.message ?? e));
} finally {
  for (const socket of sockets) socket.close(1000);
  // Deletes the accounts with their sessions, friendship and push tokens.
  await call("DELETE", "/v1/me", guest.session).catch(() => {});
  await call("DELETE", "/v1/me", inviter.session).catch(() => {});
}
process.exit(failed ? 1 : 0);
