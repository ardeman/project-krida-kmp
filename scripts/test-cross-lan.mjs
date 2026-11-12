#!/usr/bin/env node
// Cross-platform Nearby Wi‑Fi e2e: Android emulator host + iOS simulator guest.
// TCP via adb forward to the Mac; guest connects to 127.0.0.1 (NSD skipped on the emu).
// Usage: node scripts/test-cross-lan.mjs [avd] [iosSim]
import { spawn, spawnSync } from "node:child_process";
import { existsSync, mkdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const avd = process.argv[2] ?? "Pixel_XL";
const iosSim = process.argv[3] ?? "iPhone 17";
const sdk = process.env.ANDROID_HOME || join(process.env.HOME, "Library/Android/sdk");
const emu = join(sdk, "emulator/emulator");
const adb = join(sdk, "platform-tools/adb");
const forwardPort = 19877;
const e2eFile = "/sdcard/Android/data/com.ardeman.krida/files/krida-lan-e2e";
const okFile = "/sdcard/Android/data/com.ardeman.krida/files/krida-lan-e2e-ok";
const logDir = join(root, ".tmp/cross-lan-e2e");
const ios = join(root, "ios");
const dd = join(ios, "build/cross-lan-e2e");

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

function bootAvd(name) {
  const before = new Set(adbDevices());
  console.log(`booting ${name}…`);
  spawn(emu, ["-avd", name, "-no-snapshot-save", "-no-boot-anim"], {
    stdio: "ignore",
    detached: true,
  }).unref();
  const deadline = Date.now() + 180_000;
  while (Date.now() < deadline) {
    sleep(2000);
    for (const serial of adbDevices().filter((d) => !before.has(d) || before.size === 0)) {
      const boot = run(adb, ["-s", serial, "shell", "getprop", "sys.boot_completed"], { allowFail: true });
      if ((boot.stdout || "").trim() === "1") return serial;
    }
    // Already-running single emu
    for (const serial of adbDevices()) {
      const boot = run(adb, ["-s", serial, "shell", "getprop", "sys.boot_completed"], { allowFail: true });
      if ((boot.stdout || "").trim() === "1") return serial;
    }
  }
  throw new Error(`timeout booting ${name}`);
}

function waitAdbFile(serial, path, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const r = run(adb, ["-s", serial, "shell", `cat ${path} 2>/dev/null`], { allowFail: true });
    const text = (r.stdout || "").trim();
    if (r.status === 0 && text) return text;
    sleep(400);
  }
  throw new Error(`timeout waiting for ${path}`);
}

function androidHost(serial) {
  const args = [
    "-s", serial, "shell", "am", "instrument", "-w",
    "-e", "role", "host",
    "-e", "class", "com.ardeman.krida.LanRoomAndroidTest#lanHost",
    "com.ardeman.krida.test/androidx.test.runner.AndroidJUnitRunner",
  ];
  return new Promise((resolve, reject) => {
    const child = spawn(adb, args, { stdio: ["ignore", "pipe", "pipe"] });
    let out = "";
    child.stdout.on("data", (d) => (out += d));
    child.stderr.on("data", (d) => (out += d));
    child.on("close", (code) => {
      writeFileSync(join(logDir, "android-host.log"), out);
      console.log("--- android host ---");
      console.log(out.split("\n").filter((l) => /OK \(|FAILURE|Error|ASSERT|Tests run/i.test(l)).slice(-20).join("\n"));
      if (code === 0 && /OK \(1 test\)/i.test(out)) resolve();
      else reject(new Error(`android host failed`));
    });
  });
}

function iosGuest(code) {
  return new Promise((resolve, reject) => {
    const child = spawn(
      "xcodebuild",
      [
        "test",
        "-project", "Krida.xcodeproj",
        "-scheme", "KridaUITests",
        "-destination", `platform=iOS Simulator,name=${iosSim}`,
        "-derivedDataPath", dd,
        "-only-testing", "KridaUITests/LanRoomTests/testLanGuest",
        "-resultBundlePath", join(dd, "guest.xcresult"),
      ],
      {
        cwd: ios,
        env: {
          ...process.env,
          JAVA_HOME: javaHome || process.env.JAVA_HOME,
          TEST_RUNNER_KRIDA_LAN_ROLE: "guest",
          TEST_RUNNER_KRIDA_E2E_LAN_HOST: "127.0.0.1",
          TEST_RUNNER_KRIDA_E2E_LAN_PORT: String(forwardPort),
        },
        stdio: ["ignore", "pipe", "pipe"],
      },
    );
    // Guest UI test waits for /tmp/krida-lan-e2e-code — write it for the iOS guest.
    writeFileSync("/tmp/krida-lan-e2e-code", code);
    let out = "";
    child.stdout.on("data", (d) => (out += d));
    child.stderr.on("data", (d) => (out += d));
    child.on("close", (codeStatus) => {
      writeFileSync(join(logDir, "ios-guest.log"), out);
      console.log("--- ios guest ---");
      console.log(out.split("\n").filter((l) => /error:|XCTAssert|Test Case|\*\* TEST|passed|failed/i.test(l)).slice(-40).join("\n"));
      if (codeStatus === 0) resolve();
      else reject(new Error("ios guest failed"));
    });
  });
}

mkdirSync(logDir, { recursive: true });
mkdirSync(dd, { recursive: true });
if (javaHome) process.env.JAVA_HOME = javaHome;

run(adb, ["start-server"], { allowFail: true });
let serial = adbDevices()[0];
if (!serial) serial = bootAvd(avd);
console.log(`android host device: ${serial}`);

run(adb, ["-s", serial, "shell", "rm", "-f", e2eFile, okFile], { allowFail: true });
run(adb, ["-s", serial, "forward", "--remove", `tcp:${forwardPort}`], { allowFail: true });

console.log("build android…");
run(join(root, "gradlew"), [":androidApp:assembleDebug", ":androidApp:assembleDebugAndroidTest"], {
  cwd: root,
  stdio: "inherit",
  env: { ...process.env, JAVA_HOME: javaHome || process.env.JAVA_HOME },
});
const apk = join(root, "androidApp/build/outputs/apk/debug/androidApp-debug.apk");
const testApk = join(root, "androidApp/build/outputs/apk/androidTest/debug/androidApp-debug-androidTest.apk");
run(adb, ["-s", serial, "install", "-r", apk], { stdio: "inherit" });
run(adb, ["-s", serial, "install", "-r", testApk], { stdio: "inherit" });

run("xcrun", ["simctl", "boot", iosSim], { allowFail: true });
run("xcodegen", [], { cwd: ios, stdio: "inherit" });

console.log("cross lan e2e: android host → ios guest");
const hostJob = androidHost(serial);
try {
  const endpoint = await waitAdbFile(serial, e2eFile, 120_000);
  const [port, code] = endpoint.split("\n").map((s) => s.trim());
  console.log(`android port=${port} code=${code}; adb forward ${forwardPort}`);
  run(adb, ["-s", serial, "forward", `tcp:${forwardPort}`, `tcp:${port}`]);
  const guestJob = iosGuest(code);
  // iOS guest writes /tmp/krida-lan-e2e-ok; Android host waits on-device — copy across.
  const syncOk = (async () => {
    const deadline = Date.now() + 120_000;
    while (Date.now() < deadline) {
      if (existsSync("/tmp/krida-lan-e2e-ok")) {
        run(adb, ["-s", serial, "shell", `echo ok > ${okFile}`]);
        return;
      }
      sleep(400);
    }
    throw new Error("ios guest never wrote /tmp/krida-lan-e2e-ok");
  })();
  await Promise.all([hostJob, guestJob, syncOk]);
  console.log("cross lan e2e ok");
} catch (e) {
  console.error(String(e?.message ?? e));
  process.exit(1);
} finally {
  run(adb, ["-s", serial, "forward", "--remove", `tcp:${forwardPort}`], { allowFail: true });
}
