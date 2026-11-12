/**
 * Accounts (online steps 10a–10d): sign in with Apple or Google, a session token, sign out,
 * account deletion, the friends list, invites, and the player's name and avatar. Users,
 * sessions and friends live in D1. Only the provider's user id, a display name, the chosen
 * avatar and (for Google) the picture URL are stored; no email. Endpoints are documented
 * under "Accounts" in docs/protocol.md.
 *
 * Google runs as a browser flow through the relay (the client secret stays here), bound to
 * the app with a PKCE-style challenge so another app catching the krida:// redirect cannot
 * use the code. Apple sends the identity token from the native sheet, verified against
 * Apple's keys.
 */
import type { Env } from "./index";
import { INVITE_MS, type Invite } from "./inbox";
import { PUSH_SCHEMA, pushInvite, savePushToken } from "./push";

const TOKEN_RE = /^[A-Za-z0-9_-]{43}$/;
const STATE_MS = 10 * 60_000;
const CODE_MS = 2 * 60_000;
const NAME_MAX = 24;
const APP_REDIRECT = "krida://auth";
const GOOGLE_AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth";
const GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token";
const GOOGLE_ISSUERS = ["https://accounts.google.com", "accounts.google.com"];
/** Only this host may appear as a Google picture URL returned to other phones. */
const GOOGLE_PICTURE_HOST = "lh3.googleusercontent.com";
const APPLE_ISSUER = "https://appleid.apple.com";
const APPLE_JWKS_URL = "https://appleid.apple.com/auth/keys";
const APPLE_TOKEN_URL = "https://appleid.apple.com/auth/token";
const APPLE_REVOKE_URL = "https://appleid.apple.com/auth/revoke";
const JWKS_TTL_MS = 60 * 60_000;
/** Same alphabet as RoomCode in shared/; friend codes are 8 chars. */
const CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
const FRIEND_CODE_RE = /^[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{8}$/;
/** Avatar mix: shape 0–11, colour 0–13, background ≠ symbol. Same rules as shared Avatar. */
const AVATAR_MIX_RE = /^(\d{1,2})-(\d{1,2})-(\d{1,2})$/;
const AVATAR_SHAPES = 12;
const AVATAR_COLOURS = 14;
const FRIENDS_MAX = 100;
/** Adds by code per account in one window, so codes cannot be guessed in bulk. */
const ADD_TRIES = 20;
const ADD_WINDOW_MS = 10 * 60_000;
/** Invites per account in one window. */
const INVITE_TRIES = 30;
const INVITE_WINDOW_MS = 10 * 60_000;
/** Same shapes as the room router in src/index.ts. */
const ROOM_CODE_RE = /^[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{6}$/;
const HOST_KEY_RE = /^[A-Za-z0-9_-]{16,64}$/;

const SCHEMA = [
  `CREATE TABLE IF NOT EXISTS users (
     id TEXT PRIMARY KEY, provider TEXT NOT NULL, sub TEXT NOT NULL, name TEXT NOT NULL,
     avatar TEXT NOT NULL DEFAULT '', picture TEXT, apple_refresh TEXT,
     created_at INTEGER NOT NULL, UNIQUE (provider, sub))`,
  `CREATE TABLE IF NOT EXISTS sessions (
     token_hash TEXT PRIMARY KEY, user_id TEXT NOT NULL, created_at INTEGER NOT NULL, last_used INTEGER)`,
  `CREATE INDEX IF NOT EXISTS sessions_user ON sessions (user_id)`,
  `CREATE TABLE IF NOT EXISTS auth_pending (
     id TEXT PRIMARY KEY, kind TEXT NOT NULL, challenge TEXT NOT NULL, user_id TEXT,
     expires INTEGER NOT NULL)`,
  `CREATE TABLE IF NOT EXISTS friend_codes (code TEXT PRIMARY KEY, user_id TEXT NOT NULL UNIQUE)`,
  // One row per pair, a < b.
  `CREATE TABLE IF NOT EXISTS friends (
     a TEXT NOT NULL, b TEXT NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY (a, b))`,
  `CREATE INDEX IF NOT EXISTS friends_b ON friends (b)`,
  `CREATE TABLE IF NOT EXISTS friend_tries (user_id TEXT PRIMARY KEY, started INTEGER NOT NULL, count INTEGER NOT NULL)`,
  `CREATE TABLE IF NOT EXISTS invite_tries (user_id TEXT PRIMARY KEY, started INTEGER NOT NULL, count INTEGER NOT NULL)`,
  ...PUSH_SCHEMA,
];

interface User {
  id: string;
  name: string;
  provider: string;
  avatar: string;
  picture: string | null;
}

const DAY_MS = 24 * 60 * 60 * 1000;
/** A session unused this long ends (docs/decisions.md, accounts). */
const SESSION_TTL_MS = 180 * DAY_MS;

let schemaReady: Promise<unknown> | undefined;
let appleKeys: { at: number; url: string; keys: JsonWebKey[] } | undefined;

/** Handles the account routes; null when the path is not one of them. */
export async function handleAccounts(request: Request, env: Env, url: URL): Promise<Response | null> {
  const path = url.pathname;
  const friends = path === "/v1/friends" || path.startsWith("/v1/friends/");
  const invites = path === "/v1/inbox" || path === "/v1/invites" || path === "/v1/push-token";
  if (path !== "/v1/me" && !path.startsWith("/v1/auth/") && !friends && !invites) return null;
  const db = env.DB;
  if (!db) return fail(503, "accounts_unavailable");
  try {
    schemaReady ??= ensureSchema(db);
    await schemaReady;
  } catch (e) {
    schemaReady = undefined;
    throw e;
  }

  const method = request.method;
  if (path === "/v1/auth/google/start" && method === "GET") return googleStart(env, db, url);
  if (path === "/v1/auth/google/callback" && method === "GET") return googleCallback(env, db, url);
  if (path === "/v1/auth/exchange" && method === "POST") return exchange(db, await body(request));
  if (path === "/v1/auth/apple" && method === "POST") return appleSignIn(env, db, await body(request));
  if (path === "/v1/auth/signout" && method === "POST") return signOut(db, request);
  if (path === "/v1/me" && method === "GET") {
    const user = await authed(db, request);
    return user ? json({ user: publicUser(user) }) : fail(401, "unauthorized");
  }
  if (path === "/v1/me" && method === "PATCH") return patchMe(db, request, await body(request));
  if (path === "/v1/me" && method === "DELETE") return deleteAccount(env, db, request);
  if (friends) {
    const user = await authed(db, request);
    if (!user) return fail(401, "unauthorized");
    if (path === "/v1/friends" && method === "GET") return listFriends(db, user);
    if (path === "/v1/friends" && method === "POST") return addFriend(db, user, await body(request));
    const id = path.slice("/v1/friends/".length);
    if (path !== "/v1/friends" && method === "DELETE") return removeFriend(db, user, id);
  }
  if (invites) {
    const user = await authed(db, request);
    if (!user) return fail(401, "unauthorized");
    if (path === "/v1/invites" && method === "POST") return sendInvite(env, db, user, await body(request));
    if (path === "/v1/push-token" && method === "POST") {
      const saved = await savePushToken(db, user.id, await sha256(bearer(request) ?? ""), await body(request));
      return saved ? new Response(null, { status: 204 }) : fail(400, "bad_request");
    }
    if (path === "/v1/inbox" && method === "GET") {
      if (request.headers.get("Upgrade") !== "websocket") return fail(426, "expected_websocket");
      return inbox(env, user.id).fetch(request);
    }
  }
  return fail(404, "not_found");
}

// Google

async function googleStart(env: Env, db: D1Database, url: URL): Promise<Response> {
  if (!env.GOOGLE_CLIENT_ID || !env.GOOGLE_CLIENT_SECRET) return fail(503, "google_unavailable");
  const challenge = url.searchParams.get("challenge") ?? "";
  if (!TOKEN_RE.test(challenge)) return fail(400, "bad_challenge");
  const now = Date.now();
  const state = randomToken();
  await db.batch([
    db.prepare("DELETE FROM auth_pending WHERE expires < ?").bind(now),
    db.prepare("INSERT INTO auth_pending (id, kind, challenge, expires) VALUES (?, 'state', ?, ?)")
      .bind(state, challenge, now + STATE_MS),
  ]);
  const to = new URL(GOOGLE_AUTH_URL);
  to.searchParams.set("client_id", env.GOOGLE_CLIENT_ID);
  to.searchParams.set("redirect_uri", googleRedirect(url));
  to.searchParams.set("response_type", "code");
  to.searchParams.set("scope", "openid profile");
  to.searchParams.set("state", state);
  to.searchParams.set("prompt", "select_account");
  return Response.redirect(to.toString(), 302);
}

async function googleCallback(env: Env, db: D1Database, url: URL): Promise<Response> {
  const code = url.searchParams.get("code");
  const state = url.searchParams.get("state") ?? "";
  if (!code || !TOKEN_RE.test(state)) return backToApp({ error: "cancelled" });
  const pending = await takePending(db, state, "state");
  if (!pending) return backToApp({ error: "expired" });

  const reply = await fetch(env.GOOGLE_TOKEN_URL ?? GOOGLE_TOKEN_URL, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      code,
      client_id: env.GOOGLE_CLIENT_ID ?? "",
      client_secret: env.GOOGLE_CLIENT_SECRET ?? "",
      redirect_uri: googleRedirect(url),
      grant_type: "authorization_code",
    }),
  });
  if (!reply.ok) return backToApp({ error: "failed" });
  // The token comes straight from Google over TLS, so its signature needs no second check.
  const idToken = ((await reply.json()) as { id_token?: string }).id_token;
  const claims = idToken ? jwtPart(idToken, 1) : null;
  if (
    !claims || typeof claims.sub !== "string" || claims.aud !== env.GOOGLE_CLIENT_ID ||
    !GOOGLE_ISSUERS.includes(String(claims.iss))
  ) {
    return backToApp({ error: "failed" });
  }
  const user = await upsertUser(db, "google", claims.sub, claims.given_name ?? claims.name, claims.picture);
  const appCode = randomToken();
  await db.prepare("INSERT INTO auth_pending (id, kind, challenge, user_id, expires) VALUES (?, 'code', ?, ?, ?)")
    .bind(appCode, pending.challenge, user.id, Date.now() + CODE_MS).run();
  return backToApp({ code: appCode });
}

async function exchange(db: D1Database, input: Record<string, unknown>): Promise<Response> {
  const code = String(input.code ?? "");
  const verifier = String(input.verifier ?? "");
  if (!TOKEN_RE.test(code) || !TOKEN_RE.test(verifier)) return fail(400, "bad_request");
  const pending = await takePending(db, code, "code");
  if (!pending || !pending.user_id || (await sha256(verifier)) !== pending.challenge) {
    return fail(401, "bad_code");
  }
  const user = await db.prepare(
    "SELECT id, name, provider, avatar, picture FROM users WHERE id = ?",
  ).bind(pending.user_id).first<User>();
  return user ? json({ token: await newSession(db, user.id), user: publicUser(user) }) : fail(401, "bad_code");
}

function googleRedirect(url: URL): string {
  return `${url.origin}/v1/auth/google/callback`;
}

/** A page that sends the browser back to the app, with a button in case it does not go by itself. */
function backToApp(params: Record<string, string>): Response {
  const link = `${APP_REDIRECT}?${new URLSearchParams(params)}`;
  const html = `<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Krida</title>
<body style="font:600 18px system-ui;display:grid;place-items:center;height:90vh;margin:0;background:#fbf3e4">
<a href="${link}" style="padding:14px 24px;border-radius:14px;background:#c2553a;color:#fff;text-decoration:none">Kembali ke Krida · Back to Krida</a>
<script>location.replace(${JSON.stringify(link)})</script>`;
  return new Response(html, { headers: { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store" } });
}

// Apple

async function appleSignIn(env: Env, db: D1Database, input: Record<string, unknown>): Promise<Response> {
  if (!env.APPLE_BUNDLE_ID) return fail(503, "apple_unavailable");
  const claims = await verifyAppleToken(env, String(input.identityToken ?? ""));
  if (!claims) return fail(401, "bad_token");
  const user = await upsertUser(db, "apple", claims.sub, input.name);
  // With the Sign in with Apple key configured, keep a refresh token so deleting the
  // account can revoke the app's access at Apple, as the App Store asks.
  const authCode = typeof input.authorizationCode === "string" ? input.authorizationCode : "";
  if (authCode && appleKeyConfigured(env)) {
    try {
      const reply = await fetch(APPLE_TOKEN_URL, {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        body: new URLSearchParams({
          client_id: env.APPLE_BUNDLE_ID,
          client_secret: await appleClientSecret(env),
          code: authCode,
          grant_type: "authorization_code",
        }),
      });
      const refresh = reply.ok ? ((await reply.json()) as { refresh_token?: string }).refresh_token : undefined;
      if (refresh) await db.prepare("UPDATE users SET apple_refresh = ? WHERE id = ?").bind(refresh, user.id).run();
    } catch {
      // Sign-in still succeeds; only the revoke on deletion is skipped.
    }
  }
  return json({ token: await newSession(db, user.id), user: publicUser(user) });
}

async function verifyAppleToken(env: Env, token: string): Promise<{ sub: string } | null> {
  const parts = token.split(".");
  if (parts.length !== 3) return null;
  const header = jwtPart(token, 0);
  const claims = jwtPart(token, 1);
  if (!header || !claims || header.alg !== "RS256" || typeof header.kid !== "string") return null;
  const jwk = (await appleJwks(env)).find((k) => (k as { kid?: string }).kid === header.kid);
  if (!jwk) return null;
  const algorithm = { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" };
  const key = await crypto.subtle.importKey("jwk", jwk, algorithm, false, ["verify"]);
  const signed = new TextEncoder().encode(`${parts[0]}.${parts[1]}`);
  if (!(await crypto.subtle.verify(algorithm, key, fromBase64Url(parts[2]), signed))) return null;
  const audiences = Array.isArray(claims.aud) ? claims.aud : [claims.aud];
  if (
    claims.iss !== APPLE_ISSUER || !audiences.includes(env.APPLE_BUNDLE_ID) ||
    typeof claims.exp !== "number" || claims.exp * 1000 < Date.now() ||
    typeof claims.sub !== "string" || !claims.sub
  ) {
    return null;
  }
  return { sub: claims.sub };
}

async function appleJwks(env: Env): Promise<JsonWebKey[]> {
  const url = env.APPLE_JWKS_URL ?? APPLE_JWKS_URL;
  if (appleKeys && appleKeys.url === url && Date.now() - appleKeys.at < JWKS_TTL_MS) return appleKeys.keys;
  const reply = await fetch(url);
  if (!reply.ok) return appleKeys?.keys ?? [];
  const keys = ((await reply.json()) as { keys?: JsonWebKey[] }).keys ?? [];
  appleKeys = { at: Date.now(), url, keys };
  return keys;
}

function appleKeyConfigured(env: Env): boolean {
  return Boolean(env.APPLE_TEAM_ID && env.APPLE_KEY_ID && env.APPLE_PRIVATE_KEY);
}

/** The short-lived ES256 JWT Apple takes as a client secret, signed with the .p8 key. */
async function appleClientSecret(env: Env): Promise<string> {
  const pem = (env.APPLE_PRIVATE_KEY ?? "").replace(/-----[A-Z ]+-----/g, "").replace(/\s+/g, "");
  const algorithm = { name: "ECDSA", namedCurve: "P-256" };
  const key = await crypto.subtle.importKey("pkcs8", Uint8Array.from(atob(pem), (c) => c.charCodeAt(0)), algorithm, false, ["sign"]);
  const now = Math.floor(Date.now() / 1000);
  const encode = (value: unknown) => toBase64Url(new TextEncoder().encode(JSON.stringify(value)));
  const head = encode({ alg: "ES256", kid: env.APPLE_KEY_ID });
  const claims = encode({ iss: env.APPLE_TEAM_ID, iat: now, exp: now + 300, aud: APPLE_ISSUER, sub: env.APPLE_BUNDLE_ID });
  const signature = await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, key, new TextEncoder().encode(`${head}.${claims}`));
  return `${head}.${claims}.${toBase64Url(new Uint8Array(signature))}`;
}

// Sessions and users

async function signOut(db: D1Database, request: Request): Promise<Response> {
  const token = bearer(request);
  if (token) {
    const hash = await sha256(token);
    await db.batch([
      db.prepare("DELETE FROM sessions WHERE token_hash = ?").bind(hash),
      db.prepare("DELETE FROM push_tokens WHERE session = ?").bind(hash),
    ]);
  }
  return new Response(null, { status: 204 });
}

async function deleteAccount(env: Env, db: D1Database, request: Request): Promise<Response> {
  const user = await authed(db, request);
  if (!user) return fail(401, "unauthorized");
  if (user.provider === "apple" && env.APPLE_BUNDLE_ID && appleKeyConfigured(env)) {
    const row = await db.prepare("SELECT apple_refresh FROM users WHERE id = ?").bind(user.id).first<{ apple_refresh: string | null }>();
    if (row?.apple_refresh) {
      try {
        await fetch(APPLE_REVOKE_URL, {
          method: "POST",
          headers: { "Content-Type": "application/x-www-form-urlencoded" },
          body: new URLSearchParams({
            client_id: env.APPLE_BUNDLE_ID,
            client_secret: await appleClientSecret(env),
            token: row.apple_refresh,
            token_type_hint: "refresh_token",
          }),
        });
      } catch {
        // The account is still deleted here; the player can also revoke in iOS Settings.
      }
    }
  }
  await db.batch([
    db.prepare("DELETE FROM sessions WHERE user_id = ?").bind(user.id),
    db.prepare("DELETE FROM auth_pending WHERE user_id = ?").bind(user.id),
    db.prepare("DELETE FROM friends WHERE a = ? OR b = ?").bind(user.id, user.id),
    db.prepare("DELETE FROM friend_codes WHERE user_id = ?").bind(user.id),
    db.prepare("DELETE FROM friend_tries WHERE user_id = ?").bind(user.id),
    db.prepare("DELETE FROM invite_tries WHERE user_id = ?").bind(user.id),
    db.prepare("DELETE FROM push_tokens WHERE user_id = ?").bind(user.id),
    db.prepare("DELETE FROM users WHERE id = ?").bind(user.id),
  ]);
  await inbox(env, user.id).fetch(new Request("https://internal/clear", { method: "POST" })).catch(() => undefined);
  return new Response(null, { status: 204 });
}

async function authed(db: D1Database, request: Request): Promise<User | null> {
  const token = bearer(request);
  if (!token) return null;
  const hash = await sha256(token);
  const row = await db.prepare(
    `SELECT u.id, u.name, u.provider, u.avatar, u.picture, COALESCE(s.last_used, s.created_at) AS used
     FROM sessions s JOIN users u ON u.id = s.user_id WHERE s.token_hash = ?`,
  ).bind(hash).first<User & { used: number }>();
  if (!row) return null;
  // At most one write per session a day keeps the expiry clock without a write per request.
  const now = Date.now();
  if (now - row.used > DAY_MS) await db.prepare("UPDATE sessions SET last_used = ? WHERE token_hash = ?").bind(now, hash).run();
  const { used: _, ...user } = row;
  return user;
}

/**
 * Daily cron (wrangler.toml): ends sessions unused for SESSION_TTL_MS, with their push
 * tokens, and drops expired sign-in challenges. Another sign-in starts a new session.
 */
export async function cleanUpAccounts(env: Env): Promise<void> {
  const db = env.DB;
  if (!db) return;
  schemaReady ??= ensureSchema(db);
  await schemaReady;
  const now = Date.now();
  const ttl = Number(env.SESSION_TTL_MS) || SESSION_TTL_MS;
  await db.batch([
    db.prepare("DELETE FROM sessions WHERE COALESCE(last_used, created_at) < ?").bind(now - ttl),
    db.prepare("DELETE FROM push_tokens WHERE session NOT IN (SELECT token_hash FROM sessions)"),
    db.prepare("DELETE FROM auth_pending WHERE expires < ?").bind(now),
  ]);
}

/** Saves name and/or avatar; both are optional. Empty name is refused. */
async function patchMe(db: D1Database, request: Request, input: Record<string, unknown>): Promise<Response> {
  const user = await authed(db, request);
  if (!user) return fail(401, "unauthorized");
  const hasName = Object.prototype.hasOwnProperty.call(input, "name");
  const hasAvatar = Object.prototype.hasOwnProperty.call(input, "avatar");
  if (!hasName && !hasAvatar) return fail(400, "bad_request");

  let name = user.name;
  if (hasName) {
    name = cleanName(input.name);
    if (!name) return fail(400, "bad_name");
  }

  let avatar = user.avatar ?? "";
  if (hasAvatar) {
    const raw = input.avatar;
    if (raw === "g") {
      if (user.provider !== "google" || !googlePicture(user.picture)) return fail(400, "bad_avatar");
      avatar = "g";
    } else if (typeof raw === "string" && parseMix(raw)) {
      avatar = raw;
    } else {
      return fail(400, "bad_avatar");
    }
  }

  const row = await db.prepare(
    "UPDATE users SET name = ?, avatar = ? WHERE id = ? RETURNING id, name, provider, avatar, picture",
  ).bind(name, avatar, user.id).first<User>();
  return row ? json({ user: publicUser(row) }) : fail(500, "failed");
}

function bearer(request: Request): string | null {
  const token = (request.headers.get("Authorization") ?? "").replace(/^Bearer /, "");
  return TOKEN_RE.test(token) ? token : null;
}

/** Only the token's hash is stored, so a copy of the database cannot sign in as anyone. */
async function newSession(db: D1Database, userId: string): Promise<string> {
  const token = randomToken();
  const now = Date.now();
  await db.prepare("INSERT INTO sessions (token_hash, user_id, created_at, last_used) VALUES (?, ?, ?, ?)")
    .bind(await sha256(token), userId, now, now).run();
  return token;
}

async function upsertUser(
  db: D1Database, provider: string, sub: string, rawName: unknown, rawPicture?: unknown,
): Promise<User> {
  const name = cleanName(rawName);
  const picture = provider === "google" ? googlePicture(rawPicture) : null;
  const user = await db.prepare(
    `INSERT INTO users (id, provider, sub, name, avatar, picture, created_at) VALUES (?, ?, ?, ?, '', ?, ?)
     ON CONFLICT (provider, sub) DO UPDATE SET
       name = CASE WHEN excluded.name != '' THEN excluded.name ELSE users.name END,
       picture = CASE WHEN excluded.picture IS NOT NULL THEN excluded.picture ELSE users.picture END
     RETURNING id, name, provider, avatar, picture`,
  ).bind(randomToken(16), provider, sub, name, picture, Date.now()).first<User>();
  if (!user) throw new Error("user upsert returned nothing");
  return user;
}

async function takePending(db: D1Database, id: string, kind: string): Promise<{ challenge: string; user_id: string | null } | null> {
  const row = await db.prepare("DELETE FROM auth_pending WHERE id = ? AND kind = ? RETURNING challenge, user_id, expires")
    .bind(id, kind).first<{ challenge: string; user_id: string | null; expires: number }>();
  return row && row.expires >= Date.now() ? row : null;
}

// Friends

interface FriendRow {
  id: string;
  name: string;
  avatar: string;
  picture: string | null;
}

async function listFriends(db: D1Database, user: User): Promise<Response> {
  const rows = await db.prepare(
    `SELECT u.id, u.name, u.avatar, u.picture FROM friends f JOIN users u ON u.id = CASE WHEN f.a = ?1 THEN f.b ELSE f.a END
     WHERE f.a = ?1 OR f.b = ?1 ORDER BY u.name COLLATE NOCASE, u.id`,
  ).bind(user.id).all<FriendRow>();
  return json({
    code: await friendCode(db, user.id),
    friends: (rows.results ?? []).map(publicFriend),
  });
}

/** Adding is instant and mutual: one row makes each player the other's friend. */
async function addFriend(db: D1Database, user: User, input: Record<string, unknown>): Promise<Response> {
  const code = typeof input.code === "string" ? input.code.toUpperCase().replace(/[\s-]/g, "") : "";
  if (!FRIEND_CODE_RE.test(code)) return fail(404, "code_not_found");
  const now = Date.now();
  const tries = await db.prepare(
    `INSERT INTO friend_tries (user_id, started, count) VALUES (?1, ?2, 1)
     ON CONFLICT (user_id) DO UPDATE SET
       count = CASE WHEN started < ?3 THEN 1 ELSE count + 1 END,
       started = CASE WHEN started < ?3 THEN ?2 ELSE started END
     RETURNING count`,
  ).bind(user.id, now, now - ADD_WINDOW_MS).first<{ count: number }>();
  if ((tries?.count ?? 0) > ADD_TRIES) return fail(429, "too_many_tries");

  const friend = await db.prepare(
    "SELECT u.id, u.name, u.avatar, u.picture FROM friend_codes c JOIN users u ON u.id = c.user_id WHERE c.code = ?",
  ).bind(code).first<FriendRow>();
  if (!friend) return fail(404, "code_not_found");
  if (friend.id === user.id) return fail(400, "own_code");
  const [a, b] = user.id < friend.id ? [user.id, friend.id] : [friend.id, user.id];
  if (await db.prepare("SELECT 1 FROM friends WHERE a = ? AND b = ?").bind(a, b).first()) return fail(409, "already_friends");
  const counts = await db.prepare(
    `SELECT (SELECT COUNT(*) FROM friends WHERE a = ?1 OR b = ?1) AS mine,
            (SELECT COUNT(*) FROM friends WHERE a = ?2 OR b = ?2) AS theirs`,
  ).bind(user.id, friend.id).first<{ mine: number; theirs: number }>();
  if (!counts || counts.mine >= FRIENDS_MAX || counts.theirs >= FRIENDS_MAX) return fail(409, "friends_full");
  await db.prepare("INSERT OR IGNORE INTO friends (a, b, created_at) VALUES (?, ?, ?)").bind(a, b, now).run();
  return json({ friend: publicFriend(friend) });
}

/** Removes the pair for both players; removing someone who is not a friend also answers 204. */
async function removeFriend(db: D1Database, user: User, id: string): Promise<Response> {
  const [a, b] = user.id < id ? [user.id, id] : [id, user.id];
  await db.prepare("DELETE FROM friends WHERE a = ? AND b = ?").bind(a, b).run();
  return new Response(null, { status: 204 });
}

/** The account's friend code, made on first use and never changed. */
async function friendCode(db: D1Database, userId: string): Promise<string> {
  for (let attempt = 0; attempt < 5; attempt++) {
    const code = Array.from(crypto.getRandomValues(new Uint8Array(8)), (n) => CODE_ALPHABET[n % CODE_ALPHABET.length]).join("");
    // A taken code leaves nothing inserted and the read finds no row; an existing row wins.
    await db.prepare("INSERT OR IGNORE INTO friend_codes (code, user_id) VALUES (?, ?)").bind(code, userId).run();
    const row = await db.prepare("SELECT code FROM friend_codes WHERE user_id = ?").bind(userId).first<{ code: string }>();
    if (row) return row.code;
  }
  throw new Error("no free friend code");
}

// Invites

function inbox(env: Env, userId: string): DurableObjectStub {
  return env.INBOXES.get(env.INBOXES.idFromName(userId));
}

/**
 * The host of an open online room invites a friend. The room checks the host key and the
 * open seat, and allows one live invite per friend; the invite then goes to the friend's
 * inbox for two minutes, and to their iPhones as a notification (src/push.ts).
 */
async function sendInvite(env: Env, db: D1Database, user: User, input: Record<string, unknown>): Promise<Response> {
  const friendId = typeof input.friendId === "string" ? input.friendId : "";
  const code = typeof input.code === "string" ? input.code : "";
  const key = typeof input.key === "string" ? input.key : "";
  if (!friendId || !ROOM_CODE_RE.test(code) || !HOST_KEY_RE.test(key)) return fail(400, "bad_request");
  const now = Date.now();
  const tries = await db.prepare(
    `INSERT INTO invite_tries (user_id, started, count) VALUES (?1, ?2, 1)
     ON CONFLICT (user_id) DO UPDATE SET
       count = CASE WHEN started < ?3 THEN 1 ELSE count + 1 END,
       started = CASE WHEN started < ?3 THEN ?2 ELSE started END
     RETURNING count`,
  ).bind(user.id, now, now - INVITE_WINDOW_MS).first<{ count: number }>();
  if ((tries?.count ?? 0) > INVITE_TRIES) return fail(429, "too_many_invites");
  const [a, b] = user.id < friendId ? [user.id, friendId] : [friendId, user.id];
  if (!(await db.prepare("SELECT 1 FROM friends WHERE a = ? AND b = ?").bind(a, b).first())) return fail(403, "not_friends");

  const id = randomToken(16);
  const reply = await env.ROOMS.get(env.ROOMS.idFromName(code)).fetch(new Request("https://internal/invite", {
    method: "POST", body: JSON.stringify({ key, id, to: friendId }),
  }));
  const room = (await reply.json()) as { error?: string; game: string; seats: number; players: number };
  if (room.error) return fail(room.error === "not_host" ? 403 : 409, room.error);
  const from = publicFriend(user);
  const invite: Invite = {
    id, from: { id: from.id, name: from.name, ...(from.avatar ? { avatar: from.avatar } : {}) }, code,
    game: room.game, seats: room.seats, players: room.players, expires: now + INVITE_MS,
  };
  await inbox(env, friendId).fetch(new Request("https://internal/deliver", { method: "POST", body: JSON.stringify(invite) }));
  await pushInvite(env, db, friendId, invite);
  return json({ id, ttlMs: INVITE_MS });
}

// Helpers

/** Creates tables, then adds columns that older D1 databases predate. */
async function ensureSchema(db: D1Database): Promise<void> {
  await db.batch(SCHEMA.map((sql) => db.prepare(sql)));
  const info = await db.prepare("PRAGMA table_info(users)").all<{ name: string }>();
  const cols = new Set((info.results ?? []).map((r) => r.name));
  if (!cols.has("avatar")) await db.prepare("ALTER TABLE users ADD COLUMN avatar TEXT NOT NULL DEFAULT ''").run();
  if (!cols.has("picture")) await db.prepare("ALTER TABLE users ADD COLUMN picture TEXT").run();
  const sessionInfo = await db.prepare("PRAGMA table_info(sessions)").all<{ name: string }>();
  if (!(sessionInfo.results ?? []).some((r) => r.name === "last_used")) {
    await db.prepare("ALTER TABLE sessions ADD COLUMN last_used INTEGER").run();
  }
}

/** API shape for the signed-in user: resolved avatar, plus picture for the Google picker. */
function publicUser(user: User): Record<string, string> {
  const out: Record<string, string> = { id: user.id, name: user.name, provider: user.provider };
  const avatar = resolvedAvatar(user.avatar, user.picture);
  if (avatar) out.avatar = avatar;
  const picture = googlePicture(user.picture);
  if (user.provider === "google" && picture) out.picture = picture;
  return out;
}

/** Friend / invite `from`: id, name, and resolved avatar when set. */
function publicFriend(row: { id: string; name: string; avatar?: string; picture?: string | null }): { id: string; name: string; avatar?: string } {
  const out: { id: string; name: string; avatar?: string } = { id: row.id, name: row.name };
  const avatar = resolvedAvatar(row.avatar ?? "", row.picture ?? null);
  if (avatar) out.avatar = avatar;
  return out;
}

/** Wire avatar: the mix string, or the Google picture URL when the player chose `g`. */
function resolvedAvatar(avatar: string, picture: string | null): string | undefined {
  if (avatar === "g") return googlePicture(picture) ?? undefined;
  return parseMix(avatar) ? avatar : undefined;
}

function parseMix(value: string): string | null {
  const m = AVATAR_MIX_RE.exec(value);
  if (!m) return null;
  const shape = Number(m[1]);
  const background = Number(m[2]);
  const symbol = Number(m[3]);
  if (shape >= AVATAR_SHAPES || background >= AVATAR_COLOURS || symbol >= AVATAR_COLOURS) return null;
  if (background === symbol) return null;
  return value;
}

function googlePicture(raw: unknown): string | null {
  if (typeof raw !== "string" || !raw.startsWith("https://")) return null;
  try {
    if (new URL(raw).hostname !== GOOGLE_PICTURE_HOST) return null;
  } catch {
    return null;
  }
  return raw;
}

function cleanName(raw: unknown): string {
  if (typeof raw !== "string") return "";
  return [...raw.replace(/[\u0000-\u001f\u007f]/g, "").trim()].slice(0, NAME_MAX).join("");
}

async function body(request: Request): Promise<Record<string, unknown>> {
  try {
    const value = await request.json();
    return value && typeof value === "object" ? (value as Record<string, unknown>) : {};
  } catch {
    return {};
  }
}

function json(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), {
    status,
    headers: { "Content-Type": "application/json", "Cache-Control": "no-store" },
  });
}

function fail(status: number, error: string): Response {
  return json({ error }, status);
}

function randomToken(bytes = 32): string {
  return toBase64Url(crypto.getRandomValues(new Uint8Array(bytes)));
}

async function sha256(text: string): Promise<string> {
  return toBase64Url(new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text))));
}

function toBase64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function fromBase64Url(text: string): Uint8Array {
  const binary = atob(text.replace(/-/g, "+").replace(/_/g, "/"));
  return Uint8Array.from(binary, (c) => c.charCodeAt(0));
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function jwtPart(token: string, index: number): Record<string, any> | null {
  try {
    const value = JSON.parse(new TextDecoder().decode(fromBase64Url(token.split(".")[index] ?? "")));
    return value && typeof value === "object" ? value : null;
  } catch {
    return null;
  }
}
