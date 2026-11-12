#!/usr/bin/env node
// End-to-end Nearby Wi‑Fi room on two iOS simulators (Bonjour `_krida._tcp`).
// Usage: node scripts/test-ios-lan.mjs [hostSim] [guestSim]
import { spawn } from "node:child_process";
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const ios = join(root, "ios");
const hostName = process.argv[2] ?? "iPhone 17";
const guestName = process.argv[3] ?? "iPhone 18 Pro";
const codeFile = "/tmp/krida-lan-e2e-code";
const okFile = "/tmp/krida-lan-e2e-ok";
const dd = join(ios, "build/lan-e2e");

function run(cmd, args, opts = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(cmd, args, { stdio: opts.stdio ?? "inherit", cwd: opts.cwd, env: opts.env ?? process.env });
    child.on("error", reject);
    child.on("close", (code) => (code === 0 ? resolve() : reject(new Error(`${cmd} ${args.join(" ")} → ${code}`))));
  });
}

function xcodebuild(role, simulator, only) {
  const log = join(dd, `${role}.log`);
  return new Promise((resolve, reject) => {
    const child = spawn(
      "xcodebuild",
      [
        "test",
        "-project", "Krida.xcodeproj",
        "-scheme", "KridaUITests",
        "-destination", `platform=iOS Simulator,name=${simulator}`,
        "-derivedDataPath", dd,
        "-only-testing", only,
        "-resultBundlePath", join(dd, `${role}.xcresult`),
      ],
      {
        cwd: ios,
        env: { ...process.env, TEST_RUNNER_KRIDA_LAN_ROLE: role },
        stdio: ["ignore", "pipe", "pipe"],
      },
    );
    let output = "";
    child.stdout.on("data", (d) => (output += d));
    child.stderr.on("data", (d) => (output += d));
    child.on("close", (code) => {
      writeFileSync(log, output);
      const lines = output.split("\n").filter((l) =>
        /error:|XCTAssert|Test Case|\*\* TEST|failed|passed|skipped|LanRoom/i.test(l),
      );
      console.log(`--- ${role} (${simulator}) ---`);
      console.log(lines.slice(-50).join("\n") || `(no test lines; see ${log})`);
      if (code === 0) resolve();
      else reject(new Error(`${role} UI test failed (log ${log})`));
    });
  });
}

function waitForCode(timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  return new Promise((resolve, reject) => {
    const tick = () => {
      try {
        const code = readFileSync(codeFile, "utf8").trim();
        if (code.length === 4) return resolve(code);
      } catch {}
      if (Date.now() > deadline) return reject(new Error("host never wrote the room code"));
      setTimeout(tick, 400);
    };
    tick();
  });
}

rmSync(codeFile, { force: true });
rmSync(okFile, { force: true });
rmSync(dd, { force: true, recursive: true });
mkdirSync(dd, { recursive: true });

const javaHome =
  process.env.JAVA_HOME ||
  (existsSync("/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home")
    ? "/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
    : undefined);
if (javaHome) process.env.JAVA_HOME = javaHome;

await run("xcrun", ["simctl", "boot", hostName]).catch(() => {});
await run("xcrun", ["simctl", "boot", guestName]).catch(() => {});
await run("xcodegen", [], { cwd: ios });

console.log(`LAN e2e: host=${hostName}, guest=${guestName}`);
const host = xcodebuild("host", hostName, "KridaUITests/LanRoomTests/testLanHost");
try {
  const code = await waitForCode(180_000);
  console.log(`room code ${code}; starting guest`);
  await Promise.all([host, xcodebuild("guest", guestName, "KridaUITests/LanRoomTests/testLanGuest")]);
  console.log("ios lan e2e ok");
} catch (e) {
  console.error(String(e?.message ?? e));
  process.exit(1);
}
