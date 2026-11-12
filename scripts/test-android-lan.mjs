#!/usr/bin/env node
// Dual Android emulator Nearby Wi‑Fi e2e (TCP via adb forward; NSD is flaky on emulators).
// Usage: node scripts/test-android-lan.mjs [hostAvd] [guestAvd]
import { spawn, spawnSync } from "node:child_process";
import { existsSync, mkdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const hostAvd = process.argv[2] ?? "Pixel_XL";
const guestAvd = process.argv[3] ?? "Pixel_9a";
const sdk = process.env.ANDROID_HOME || join(process.env.HOME, "Library/Android/sdk");
const emu = join(sdk, "emulator/emulator");
const adb = join(sdk, "platform-tools/adb");
const forwardPort = 19876;
const e2eFile = "/sdcard/Android/data/com.ardeman.krida/files/krida-lan-e2e";
const okFile = "/sdcard/Android/data/com.ardeman.krida/files/krida-lan-e2e-ok";
const logDir = join(root, ".tmp/android-lan-e2e");

const javaHome =
  process.env.JAVA_HOME ||
  (existsSync("/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home")
    ? "/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
    : undefined);

function run(cmd, args, opts = {}) {
  const r = spawnSync(cmd, args, { encoding: "utf8", ...opts });
  if (r.status !== 0 && !opts.allowFail) {
    throw new Error(`${cmd} ${args.join(" ")} → ${r.status}\n${r.stderr || r.stdout}`);
  }
  return r;
}

function adbDevices() {
  return (run(adb, ["devices"]).stdout || "")
    .split("\n")
    .slice(1)
    .map((l) => l.trim().split("\t")[0])
    .filter((s) => s && !s.startsWith("*"));
}

function sleep(ms) {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
}

function waitBooted(serial, timeoutMs = 180_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const boot = run(adb, ["-s", serial, "shell", "getprop", "sys.boot_completed"], { allowFail: true });
    const pm = run(adb, ["-s", serial, "shell", "pm", "path", "android"], { allowFail: true });
    if ((boot.stdout || "").trim() === "1" && (pm.stdout || "").includes("package:")) return;
    sleep(2000);
  }
  throw new Error(`timeout waiting for ${serial} to finish booting`);
}

function bootAvd(name) {
  const before = new Set(adbDevices());
  console.log(`booting ${name}…`);
  spawn(emu, ["-avd", name, "-no-snapshot-save", "-no-boot-anim"], {
    stdio: "ignore",
    detached: true,
  }).unref();
  const deadline = Date.now() + 240_000;
  while (Date.now() < deadline) {
    sleep(2000);
    const added = adbDevices().filter((d) => !before.has(d));
    for (const serial of added) {
      try {
        waitBooted(serial, 60_000);
        return serial;
      } catch {
        /* still coming up */
      }
    }
  }
  throw new Error(`timeout booting ${name}`);
}

function waitFile(serial, path, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const r = run(adb, ["-s", serial, "shell", `cat ${path} 2>/dev/null`], { allowFail: true });
    const text = (r.stdout || "").trim();
    if (r.status === 0 && text) return text;
    Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 400);
  }
  throw new Error(`timeout waiting for ${path} on ${serial}`);
}

function instrument(serial, role, extras = {}) {
  const args = [
    "-s", serial, "shell", "am", "instrument", "-w",
    "-e", "role", role,
    ...Object.entries(extras).flatMap(([k, v]) => ["-e", k, String(v)]),
    "-e", "class",
    role === "host"
      ? "com.ardeman.krida.LanRoomAndroidTest#lanHost"
      : "com.ardeman.krida.LanRoomAndroidTest#lanGuest",
    "com.ardeman.krida.test/androidx.test.runner.AndroidJUnitRunner",
  ];
  console.log(`instrument ${role} on ${serial}`);
  return new Promise((resolve, reject) => {
    const child = spawn(adb, args, { stdio: ["ignore", "pipe", "pipe"] });
    let out = "";
    child.stdout.on("data", (d) => (out += d));
    child.stderr.on("data", (d) => (out += d));
    child.on("close", (code) => {
      writeFileSync(join(logDir, `${role}.log`), out);
      const lines = out.split("\n").filter((l) => /OK \(|FAILURE|Error|ASSERT|lanHost|lanGuest|Tests run/i.test(l));
      console.log(`--- ${role} ---`);
      console.log(lines.slice(-30).join("\n") || out.slice(-1500));
      if (code === 0 && /OK \(1 test\)/i.test(out)) resolve();
      else reject(new Error(`${role} failed (log ${logDir}/${role}.log)`));
    });
  });
}

mkdirSync(logDir, { recursive: true });
if (javaHome) process.env.JAVA_HOME = javaHome;

run(adb, ["start-server"], { allowFail: true });
const existing = adbDevices();
let hostSerial;
let guestSerial;
if (existing.length >= 2) {
  [hostSerial, guestSerial] = existing;
  console.log(`using devices ${hostSerial} (host), ${guestSerial} (guest)`);
} else {
  hostSerial = existing[0] || bootAvd(hostAvd);
  guestSerial = bootAvd(guestAvd);
  if (hostSerial === guestSerial) throw new Error("need two distinct emulators");
}

console.log("assemble androidTest…");
run(
  join(root, "gradlew"),
  [":androidApp:assembleDebug", ":androidApp:assembleDebugAndroidTest"],
  { cwd: root, stdio: "inherit", env: { ...process.env, JAVA_HOME: javaHome || process.env.JAVA_HOME } },
);

const apk = join(root, "androidApp/build/outputs/apk/debug/androidApp-debug.apk");
const testApk = join(
  root,
  "androidApp/build/outputs/apk/androidTest/debug/androidApp-debug-androidTest.apk",
);
function installRetry(serial, path) {
  let last;
  for (let i = 0; i < 5; i++) {
    waitBooted(serial, 60_000);
    last = run(adb, ["-s", serial, "install", "-r", path], { allowFail: true, stdio: "inherit" });
    if (last.status === 0) return;
    sleep(3000);
  }
  throw new Error(`install failed on ${serial}: ${last?.stderr || last?.stdout}`);
}

for (const serial of [hostSerial, guestSerial]) {
  waitBooted(serial);
  installRetry(serial, apk);
  installRetry(serial, testApk);
  run(adb, ["-s", serial, "shell", "rm", "-f", e2eFile, okFile], { allowFail: true });
}

run(adb, ["-s", hostSerial, "forward", "--remove", `tcp:${forwardPort}`], { allowFail: true });

const hostJob = instrument(hostSerial, "host");
try {
  const endpoint = await waitFile(hostSerial, e2eFile, 120_000);
  const [port, code] = endpoint.split("\n").map((s) => s.trim());
  console.log(`host listening port=${port} code=${code}; forwarding ${forwardPort}`);
  run(adb, ["-s", hostSerial, "forward", `tcp:${forwardPort}`, `tcp:${port}`]);
  const guestJob = instrument(guestSerial, "guest", {
    code,
    lanHost: "10.0.2.2",
    lanPort: forwardPort,
  });
  // Guest writes OK on its emu; host waits on its own — copy across.
  const syncOk = (async () => {
    await waitFile(guestSerial, okFile, 120_000);
    run(adb, ["-s", hostSerial, "shell", `echo ok > ${okFile}`]);
  })();
  await Promise.all([hostJob, guestJob, syncOk]);
  console.log("android lan e2e ok");
} catch (e) {
  console.error(String(e?.message ?? e));
  process.exit(1);
} finally {
  run(adb, ["-s", hostSerial, "forward", "--remove", `tcp:${forwardPort}`], { allowFail: true });
}
