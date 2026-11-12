/**
 * Invites inbox (online step 10c): one Durable Object per account. The app keeps a
 * WebSocket open on `/v1/inbox` while it is signed in and shows a menu, and invites from
 * friends arrive on it. An invite is kept for two minutes, so an app that opens a menu in
 * that time still gets it. Sockets hibernate; an idle inbox costs nothing. Messages are
 * documented under "Invites" in docs/protocol.md.
 */

/** How long an invite lasts. */
export const INVITE_MS = 2 * 60_000;
/** Devices of one account listening at once; a further one replaces the oldest. */
const MAX_SOCKETS = 4;
const MAX_MESSAGE = 256;

export interface Invite {
  id: string;
  from: { id: string; name: string; avatar?: string };
  code: string;
  game: string;
  seats: number;
  players: number;
  expires: number;
}

export class Inbox implements DurableObject {
  constructor(private ctx: DurableObjectState) {}

  async fetch(request: Request): Promise<Response> {
    const path = new URL(request.url).pathname;
    // Internal routes, called by the Worker and by rooms; the /v1 router never exposes them.
    if (path === "/deliver") {
      const invite = (await request.json()) as Invite;
      await this.save([...(await this.invites()), invite]);
      this.send(inviteMessage(invite));
      return Response.json({ ok: true });
    }
    if (path === "/end") {
      await this.end(((await request.json()) as { id: string }).id);
      return Response.json({ ok: true });
    }
    if (path === "/clear") {
      for (const ws of this.ctx.getWebSockets()) closeQuietly(ws);
      await this.ctx.storage.deleteAll();
      return Response.json({ ok: true });
    }

    const sockets = this.ctx.getWebSockets();
    for (const old of sockets.slice(0, Math.max(0, sockets.length - MAX_SOCKETS + 1))) closeQuietly(old);
    const pair = new WebSocketPair();
    this.ctx.acceptWebSocket(pair[1]);
    for (const invite of await this.invites()) sendQuietly(pair[1], inviteMessage(invite));
    return new Response(null, { status: 101, webSocket: pair[0] });
  }

  /** The app answered an invite (Not now or Join): forget it on every device. */
  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
    if (typeof message !== "string" || message.length > MAX_MESSAGE) return;
    try {
      const value = JSON.parse(message) as { type?: unknown; id?: unknown };
      if (value.type === "dismiss" && typeof value.id === "string") await this.end(value.id, ws);
    } catch {
      // Not JSON; ignored.
    }
  }

  async webSocketClose(ws: WebSocket): Promise<void> {
    closeQuietly(ws);
  }

  async webSocketError(ws: WebSocket): Promise<void> {
    closeQuietly(ws);
  }

  /** Drops what expired, so an inbox nobody opens does not keep old invites. */
  async alarm(): Promise<void> {
    await this.save(await this.invites());
  }

  private async invites(): Promise<Invite[]> {
    const now = Date.now();
    return ((await this.ctx.storage.get<Invite[]>("invites")) ?? []).filter((i) => i.expires > now);
  }

  private async save(invites: Invite[]): Promise<void> {
    if (invites.length === 0) {
      await this.ctx.storage.deleteAll();
      return;
    }
    await this.ctx.storage.put("invites", invites);
    await this.ctx.storage.setAlarm(Math.max(...invites.map((i) => i.expires)));
  }

  private async end(id: string, except?: WebSocket): Promise<void> {
    const invites = await this.invites();
    if (!invites.some((i) => i.id === id)) return;
    await this.save(invites.filter((i) => i.id !== id));
    this.send(JSON.stringify({ type: "invite_end", id }), except);
  }

  private send(message: string, except?: WebSocket): void {
    for (const ws of this.ctx.getWebSockets()) if (ws !== except) sendQuietly(ws, message);
  }
}

/** `ttlMs` instead of the expiry time, so a phone with a wrong clock still times it right. */
function inviteMessage(invite: Invite): string {
  const { expires, ...rest } = invite;
  return JSON.stringify({ type: "invite", ...rest, ttlMs: Math.max(0, expires - Date.now()) });
}

function sendQuietly(ws: WebSocket, message: string): void {
  try {
    ws.send(message);
  } catch {
    // Socket already closing.
  }
}

function closeQuietly(ws: WebSocket): void {
  try {
    ws.close(1000, "bye");
  } catch {
    // Already closed.
  }
}
