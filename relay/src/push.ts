/**
 * iPhone notifications for friend invites (online step 10c-2). An iPhone stores its APNs
 * device token with its session (`POST /v1/push-token`); an invite to that account is then
 * also sent through APNs, so it arrives while the app is closed or in the background.
 * Android gets no push (decision "No Firebase"). Without the APNs key nothing is sent.
 * Documented under "Accounts" in docs/protocol.md.
 */
import type { Env } from "./index";
import type { Invite } from "./inbox";

const APNS_URL = "https://api.push.apple.com";
const APNS_SANDBOX_URL = "https://api.sandbox.push.apple.com";
const DEVICE_TOKEN_RE = /^[0-9a-f]{64,200}$/;
/** iPhones of one account that get the notification; a further one replaces the oldest. */
const MAX_DEVICES = 4;
/** APNs takes a provider token for an hour and refuses ones renewed too often. */
const JWT_MS = 40 * 60_000;

export const PUSH_SCHEMA = [
  `CREATE TABLE IF NOT EXISTS push_tokens (
     token TEXT PRIMARY KEY, user_id TEXT NOT NULL, session TEXT NOT NULL,
     sandbox INTEGER NOT NULL, lang TEXT NOT NULL, created_at INTEGER NOT NULL)`,
  `CREATE INDEX IF NOT EXISTS push_tokens_user ON push_tokens (user_id)`,
];

const GAME_NAMES: Record<string, string> = {
  congklak: "Congklak", ular_tangga: "Ular Tangga", ludo: "Ludo", perang_kapal: "Perang Kapal", gaple: "Gaple",
};

let jwt: { at: number; kid: string; value: string } | undefined;

/**
 * Stores this session's device token; null when the body is not valid. A token seen
 * before moves to this account and session, since it names the phone, not the player.
 */
export async function savePushToken(db: D1Database, userId: string, session: string, input: Record<string, unknown>): Promise<boolean> {
  const token = typeof input.token === "string" ? input.token.toLowerCase() : "";
  if (!DEVICE_TOKEN_RE.test(token)) return false;
  await db.batch([
    db.prepare(
      "INSERT OR REPLACE INTO push_tokens (token, user_id, session, sandbox, lang, created_at) VALUES (?, ?, ?, ?, ?, ?)",
    ).bind(token, userId, session, input.env === "sandbox" ? 1 : 0, input.lang === "id" ? "id" : "en", Date.now()),
    db.prepare(
      `DELETE FROM push_tokens WHERE user_id = ?1 AND token NOT IN
         (SELECT token FROM push_tokens WHERE user_id = ?1 ORDER BY created_at DESC LIMIT ?2)`,
    ).bind(userId, MAX_DEVICES),
  ]);
  return true;
}

/** Notifies the invited account's iPhones. Best effort: the inbox already has the invite. */
export async function pushInvite(env: Env, db: D1Database, friendId: string, invite: Invite): Promise<void> {
  if (!env.APNS_KEY_ID || !env.APNS_PRIVATE_KEY || !env.APPLE_TEAM_ID || !env.APPLE_BUNDLE_ID) return;
  try {
    const { results } = await db.prepare("SELECT token, sandbox, lang FROM push_tokens WHERE user_id = ?")
      .bind(friendId).all<{ token: string; sandbox: number; lang: string }>();
    if (results.length === 0) return;
    const authorization = `bearer ${await providerToken(env)}`;
    const game = GAME_NAMES[invite.game] ?? "Krida";
    await Promise.all(results.map(async (device) => {
      const indonesian = device.lang === "id";
      const name = invite.from.name || (indonesian ? "Pemain" : "Player");
      const body = indonesian ? `${name} mengajakmu main ${game}` : `${name} invites you to play ${game}`;
      const reply = await fetch(`${env.APNS_URL ?? (device.sandbox ? APNS_SANDBOX_URL : APNS_URL)}/3/device/${device.token}`, {
        method: "POST",
        headers: {
          authorization,
          "apns-topic": env.APPLE_BUNDLE_ID!,
          "apns-push-type": "alert",
          "apns-priority": "10",
          // Not delivered once the invite is over.
          "apns-expiration": String(Math.floor(invite.expires / 1000)),
          "apns-collapse-id": invite.id,
        },
        body: JSON.stringify({ aps: { alert: { body }, sound: "default" }, code: invite.code, invite: invite.id }),
      });
      if (reply.ok) return;
      const reason = ((await reply.json().catch(() => ({}))) as { reason?: string }).reason;
      // The app was removed, or the token belongs to the other APNs environment.
      if (reply.status === 410 || reason === "BadDeviceToken" || reason === "DeviceTokenNotForTopic") {
        await db.prepare("DELETE FROM push_tokens WHERE token = ?").bind(device.token).run();
      } else {
        console.warn("apns", reply.status, reason);
      }
    }));
  } catch (e) {
    console.warn("apns", e);
  }
}

/** The ES256 JWT APNs takes from a provider, signed with the .p8 key and reused for a while. */
async function providerToken(env: Env): Promise<string> {
  const now = Date.now();
  if (jwt && jwt.kid === env.APNS_KEY_ID && now - jwt.at < JWT_MS) return jwt.value;
  const pem = (env.APNS_PRIVATE_KEY ?? "").replace(/-----[A-Z ]+-----/g, "").replace(/\s+/g, "");
  const key = await crypto.subtle.importKey(
    "pkcs8", Uint8Array.from(atob(pem), (c) => c.charCodeAt(0)), { name: "ECDSA", namedCurve: "P-256" }, false, ["sign"],
  );
  const encode = (value: unknown) => base64Url(new TextEncoder().encode(JSON.stringify(value)));
  const signed = `${encode({ alg: "ES256", kid: env.APNS_KEY_ID })}.${encode({ iss: env.APPLE_TEAM_ID, iat: Math.floor(now / 1000) })}`;
  const signature = await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, key, new TextEncoder().encode(signed));
  jwt = { at: now, kid: env.APNS_KEY_ID!, value: `${signed}.${base64Url(new Uint8Array(signature))}` };
  return jwt.value;
}

function base64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}
