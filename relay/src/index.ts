/**
 * Krida online relay: one Durable Object per room forwards bytes between the host phone
 * and up to four guest sockets. It knows no game rules; the host stays authoritative.
 * Framing is documented under "Online relay" in docs/protocol.md.
 */

export interface Env {
  ROOMS: DurableObjectNamespace;
}

/** Same alphabet as RoomCode in shared/; online codes are 6 chars. */
const CODE_RE = /^[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{6}$/;
const KEY_RE = /^[A-Za-z0-9_-]{16,64}$/;

const KIND_DATA = 0x01;
const KIND_CONNECTED = 0x02;
const KIND_DISCONNECTED = 0x03;
const KIND_KICK = 0x04;

const BROADCAST = 0;
const HOST_PEER = 0;

const MAX_GUESTS = 4;
const MAX_MESSAGE = 64 * 1024;
/** How long a room waits for its host to come back (the protocol's reconnect grace). */
const HOST_GRACE_MS = 60_000;

/** Token bucket per socket: bursts of 50 messages, 20 per second sustained. */
const RATE_BURST = 50;
const RATE_PER_SEC = 20;

const CLOSE = {
  badRequest: 4000,
  roomTaken: 4001,
  noRoom: 4004,
  kicked: 4008,
  full: 4009,
  hostGone: 4010,
  replaced: 4011,
  tooLarge: 4013,
  rateLimited: 4029,
} as const;

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    if (url.pathname === "/") return new Response("krida relay\n");

    const match = url.pathname.match(/^\/v1\/rooms\/([^/]+)$/);
    if (!match) return new Response("not found\n", { status: 404 });
    if (request.headers.get("Upgrade") !== "websocket") {
      return new Response("expected websocket\n", { status: 426 });
    }
    const code = match[1];
    const role = url.searchParams.get("role");
    if (!CODE_RE.test(code)) return rejectSocket(CLOSE.badRequest, "bad code");
    if (role !== "host" && role !== "guest") return rejectSocket(CLOSE.badRequest, "bad role");
    if (role === "host" && !KEY_RE.test(url.searchParams.get("key") ?? "")) {
      return rejectSocket(CLOSE.badRequest, "bad key");
    }
    return env.ROOMS.get(env.ROOMS.idFromName(code)).fetch(request);
  },
} satisfies ExportedHandler<Env>;

type Attachment = { role: "host" } | { role: "guest"; id: number };

interface Bucket {
  tokens: number;
  at: number;
}

export class Room implements DurableObject {
  /** In memory only; resets when the object hibernates, which is fine for a rate limit. */
  private buckets = new Map<WebSocket, Bucket>();

  constructor(
    private ctx: DurableObjectState,
    private env: Env,
  ) {}

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    return url.searchParams.get("role") === "host"
      ? this.acceptHost(url.searchParams.get("key")!)
      : this.acceptGuest();
  }

  private async acceptHost(key: string): Promise<Response> {
    const stored = await this.ctx.storage.get<string>("hostKey");
    if (stored !== undefined && stored !== key) return rejectSocket(CLOSE.roomTaken, "room taken");
    if (stored === undefined) await this.ctx.storage.put("hostKey", key);

    // A host reconnecting before its old socket timed out takes over from it.
    for (const old of this.hosts()) closeQuietly(old, CLOSE.replaced, "replaced");

    const [client, server] = pair();
    this.ctx.acceptWebSocket(server, ["host"]);
    server.serializeAttachment({ role: "host" } satisfies Attachment);
    await this.ctx.storage.deleteAlarm();

    for (const guest of this.guests()) {
      sendQuietly(server, frame(KIND_CONNECTED, guestId(guest)));
      sendQuietly(guest, frame(KIND_CONNECTED, HOST_PEER));
    }
    return new Response(null, { status: 101, webSocket: client });
  }

  private async acceptGuest(): Promise<Response> {
    if ((await this.ctx.storage.get<string>("hostKey")) === undefined) {
      return rejectSocket(CLOSE.noRoom, "no such room");
    }
    const guests = this.guests();
    if (guests.length >= MAX_GUESTS) return rejectSocket(CLOSE.full, "room full");

    const id = await this.nextGuestId(new Set(guests.map(guestId)));
    const [client, server] = pair();
    this.ctx.acceptWebSocket(server, ["guest"]);
    server.serializeAttachment({ role: "guest", id } satisfies Attachment);

    const hosts = this.hosts();
    for (const host of hosts) sendQuietly(host, frame(KIND_CONNECTED, id));
    if (hosts.length > 0) sendQuietly(server, frame(KIND_CONNECTED, HOST_PEER));
    return new Response(null, { status: 101, webSocket: client });
  }

  /** Ids 1..255, counting up so a fresh guest never reuses the id of one that just left. */
  private async nextGuestId(inUse: Set<number>): Promise<number> {
    let id = (await this.ctx.storage.get<number>("nextId")) ?? 1;
    while (inUse.has(id)) id = (id % 255) + 1;
    await this.ctx.storage.put("nextId", (id % 255) + 1);
    return id;
  }

  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
    if (typeof message === "string") return closeQuietly(ws, CLOSE.badRequest, "binary frames only");
    if (message.byteLength > MAX_MESSAGE) return closeQuietly(ws, CLOSE.tooLarge, "message too large");
    if (!this.allow(ws)) return closeQuietly(ws, CLOSE.rateLimited, "rate limited");

    const bytes = new Uint8Array(message);
    if (bytes.length < 2) return;
    const kind = bytes[0];
    const peer = bytes[1];
    const att = ws.deserializeAttachment() as Attachment;

    if (att.role === "guest") {
      if (kind !== KIND_DATA) return;
      for (const host of this.hosts()) sendQuietly(host, frame(KIND_DATA, att.id, bytes.subarray(2)));
      return;
    }

    if (kind === KIND_DATA) {
      const out = frame(KIND_DATA, HOST_PEER, bytes.subarray(2));
      for (const guest of this.guests()) {
        if (peer === BROADCAST || guestId(guest) === peer) sendQuietly(guest, out);
      }
    } else if (kind === KIND_KICK) {
      for (const guest of this.guests()) {
        if (guestId(guest) === peer) closeQuietly(guest, CLOSE.kicked, "kicked");
      }
    }
  }

  async webSocketClose(ws: WebSocket, code: number): Promise<void> {
    await this.left(ws, code === 1000);
  }

  async webSocketError(ws: WebSocket): Promise<void> {
    await this.left(ws);
  }

  /** [onPurpose]: the socket closed normally (1000), i.e. the app left the room. */
  private async left(ws: WebSocket, onPurpose = false): Promise<void> {
    this.buckets.delete(ws);
    closeQuietly(ws, 1000, "bye");
    const att = ws.deserializeAttachment() as Attachment | null;
    if (!att) return;

    if (att.role === "guest") {
      for (const host of this.hosts(ws)) sendQuietly(host, frame(KIND_DISCONNECTED, att.id));
    } else if (this.hosts(ws).length === 0 && onPurpose) {
      // The host left the room: end it now instead of making guests wait out the grace.
      for (const guest of this.guests(ws)) closeQuietly(guest, CLOSE.hostGone, "host left");
      await this.ctx.storage.deleteAll();
    } else if (this.hosts(ws).length === 0) {
      // Not a replaced socket: the host is really gone. Guests wait; the alarm ends the room.
      for (const guest of this.guests(ws)) sendQuietly(guest, frame(KIND_DISCONNECTED, HOST_PEER));
      await this.ctx.storage.setAlarm(Date.now() + HOST_GRACE_MS);
    }
  }

  /** The host stayed away past the grace: close the room and forget it. */
  async alarm(): Promise<void> {
    if (this.hosts().length > 0) return;
    for (const guest of this.guests()) closeQuietly(guest, CLOSE.hostGone, "host gone");
    await this.ctx.storage.deleteAll();
  }

  private allow(ws: WebSocket): boolean {
    const now = Date.now();
    const b = this.buckets.get(ws) ?? { tokens: RATE_BURST, at: now };
    b.tokens = Math.min(RATE_BURST, b.tokens + ((now - b.at) / 1000) * RATE_PER_SEC);
    b.at = now;
    this.buckets.set(ws, b);
    if (b.tokens < 1) return false;
    b.tokens -= 1;
    return true;
  }

  /** Open sockets with [tag], leaving out [except] (a socket that is closing). */
  private open(tag: string, except?: WebSocket): WebSocket[] {
    return this.ctx
      .getWebSockets(tag)
      .filter((s) => s !== except && s.readyState === WebSocket.READY_STATE_OPEN);
  }

  private hosts(except?: WebSocket): WebSocket[] {
    return this.open("host", except);
  }

  private guests(except?: WebSocket): WebSocket[] {
    return this.open("guest", except);
  }
}

function guestId(ws: WebSocket): number {
  return (ws.deserializeAttachment() as { id: number }).id;
}

function frame(kind: number, peer: number, payload?: Uint8Array): Uint8Array {
  const out = new Uint8Array(2 + (payload?.length ?? 0));
  out[0] = kind;
  out[1] = peer;
  if (payload) out.set(payload, 2);
  return out;
}

function pair(): [WebSocket, WebSocket] {
  const p = new WebSocketPair();
  return [p[0], p[1]];
}

/** Accepts and closes at once, so the client sees the close code instead of an HTTP error. */
function rejectSocket(code: number, reason: string): Response {
  const [client, server] = pair();
  server.accept();
  server.close(code, reason);
  return new Response(null, { status: 101, webSocket: client });
}

function sendQuietly(ws: WebSocket, data: Uint8Array): void {
  try {
    ws.send(data);
  } catch {
    // Socket already closing; its close handler cleans up.
  }
}

function closeQuietly(ws: WebSocket, code: number, reason: string): void {
  try {
    ws.close(code, reason);
  } catch {
    // Already closed.
  }
}
