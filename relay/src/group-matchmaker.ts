import type { Env } from "./index";
/** Opt-in group matchmaking. Socket attachments survive Durable Object hibernation. */
type Ticket = {
  group: string;
  target: number;
  deadline: number;
  phase: "searching" | "offer" | "preparing" | "done";
  consent: boolean;
  revision: number;
  humanConsent?: boolean;
  maxPlayers?: number;
  seats?: number;
  host?: boolean;
  discover?: boolean;
  /** Game id from the match URL (`congklak`, `ular_tangga`, `ludo`). */
  game?: string;
  /** End of the vote once the offer is open to two or more humans; absent means no limit. */
  voteDeadline?: number;
};

/** How long a group of humans has to vote once the offer opens. */
const VOTE_MS = 15_000;

export class GroupMatchmaker implements DurableObject {
  constructor(private ctx: DurableObjectState, private env?: Env) {}

  private matching = new Set<string>();

  async fetch(request: Request): Promise<Response> {
    if (new URL(request.url).pathname === "/public-room") {
      const { code, available } = await request.json() as { code: string; available: boolean };
      if (available) await this.ctx.storage.put(`public:${code}`, true);
      else await this.ctx.storage.delete(`public:${code}`);
      // Do not call back into the publishing Room during this request.
      await this.ctx.storage.setAlarm(Date.now() + 1);
      return Response.json({ ok: true });
    }
    if (request.headers.get("Upgrade") !== "websocket") return new Response("expected websocket", { status: 426 });
    const discover = new URL(request.url).searchParams.get("public") === "1";
    const parts = new URL(request.url).pathname.split("/");
    const target = Number(parts.pop());
    const game = parts.pop() ?? "ular_tangga";
    const [client, server] = Object.values(new WebSocketPair());
    this.ctx.acceptWebSocket(server);
    await this.expire();
    const waiting = this.members().find(ws => {
      const t = this.ticket(ws);
      return t.discover === discover && t.target === target && t.game === game && (t.phase === "searching" || t.phase === "offer") && this.group(t.group).length < target;
    });
    const first = waiting && this.ticket(waiting);
    server.serializeAttachment({
      discover, group: first?.group ?? this.code(), target, game,
      deadline: first?.deadline ?? Date.now() + 20_000,
      phase: first?.phase ?? "searching", consent: false, revision: first?.revision ?? 0,
      maxPlayers: game === "congklak" || game === "perang_kapal" ? 2 : 4,
    } satisfies Ticket);
    // Membership changes invalidate prior consent: everyone sees the new composition.
    this.resetConsent(this.ticket(server).group);
    await this.update(this.ticket(server).group);
    await this.schedule();
    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(ws: WebSocket, data: string | ArrayBuffer): Promise<void> {
    const t = this.ticket(ws);
    if (t.phase === "done") return;
    let action: string;
    let revision: number;
    let seats: number;
    try {
      if (typeof data !== "string" && data.byteLength > 256 || typeof data === "string" && data.length > 256) throw new Error();
      const message = JSON.parse(typeof data === "string" ? data : new TextDecoder().decode(data));
      action = message.type;
      revision = message.revision;
      seats = message.seats;
    } catch { ws.close(4000, "bad request"); return; }
    await this.expire();
    const current = this.ticket(ws);
    if (action === "host_ready" && current.phase === "preparing" && current.host) {
      const group = this.group(current.group);
      for (const member of group) {
        const mt = this.ticket(member);
        this.send(member, { type: "matched", role: mt.host ? "host" : "guest", code: mt.group,
          humans: group.length, bots: (mt.seats ?? mt.target) - group.length, target: mt.target, seats: mt.seats ?? mt.target });
        member.serializeAttachment({ ...mt, phase: "done" });
        member.close(1000, "matched");
      }
    } else if (current.phase === "offer") {
      if (action === "accept_bots" && revision === current.revision) {
        ws.serializeAttachment({ ...current, consent: true, humanConsent: false });
        await this.update(current.group);
      } else if (action === "accept_humans" && revision === current.revision && this.group(current.group).length >= 2) {
        ws.serializeAttachment({ ...current, consent: false, humanConsent: true });
        await this.update(current.group);
      } else if (action === "play_solo" && revision === current.revision && this.group(current.group).length === 1 &&
          Number.isInteger(seats) && seats >= 2 && seats <= (current.maxPlayers ?? current.target)) {
        this.send(ws, { type: "matched", role: "solo", target: current.target, seats });
        ws.serializeAttachment({ ...current, phase: "done" });
        ws.close(1000, "matched");
      } else if (action === "keep_searching") {
        for (const member of this.group(current.group)) {
          member.serializeAttachment({ ...this.ticket(member), phase: "searching", consent: false, humanConsent: false, voteDeadline: undefined, revision: current.revision + 1, deadline: Date.now() + 20_000 });
        }
        await this.update(current.group);
      }
    }
    await this.schedule();
  }

  async webSocketClose(ws: WebSocket): Promise<void> {
    const t = this.ticket(ws);
    if (t.phase === "done") return;
    ws.serializeAttachment({ ...t, phase: "done" });
    if (t.phase === "preparing") this.fail(t.group);
    else { this.resetConsent(t.group); await this.update(t.group); }
    await this.schedule();
  }
  async webSocketError(ws: WebSocket): Promise<void> { await this.webSocketClose(ws); }
  async alarm(): Promise<void> {
    await this.expire();
    for (const id of new Set(this.members().filter(ws => this.ticket(ws).discover).map(ws => this.ticket(ws).group))) await this.update(id);
    await this.schedule();
  }

  private ticket(ws: WebSocket): Ticket { return ws.deserializeAttachment() as Ticket; }
  private members(): WebSocket[] { return this.ctx.getWebSockets().filter(ws => ws.deserializeAttachment() && this.ticket(ws).phase !== "done"); }
  private group(id: string): WebSocket[] { return this.members().filter(ws => this.ticket(ws).group === id); }
  private resetConsent(id: string): void {
    const group = this.group(id);
    const revision = Math.max(0, ...group.map(ws => this.ticket(ws).revision)) + 1;
    // A new composition gets a fresh vote: restart the timer, or drop it for a lone player.
    const voteDeadline = group.length >= 2 && group.some(ws => this.ticket(ws).phase === "offer") ? Date.now() + VOTE_MS : undefined;
    for (const ws of group) ws.serializeAttachment({ ...this.ticket(ws), consent: false, humanConsent: false, revision, voteDeadline });
  }
  private async update(id: string, restart?: "no_votes" | "tie"): Promise<void> {
    const group = this.group(id);
    if (!group.length) return;
    const t = this.ticket(group[0]);
    if (t.phase === "preparing" || this.matching.has(id)) return;
    if (t.discover && this.env) {
      this.matching.add(id);
      try {
        const listings = await this.ctx.storage.list<boolean>({ prefix: "public:" });
        for (const key of listings.keys()) {
          const code = key.slice(7);
          const room = this.env.ROOMS.get(this.env.ROOMS.idFromName(code));
          const game = t.game ?? (t.maxPlayers === 2 ? "congklak" : "ular_tangga");
          const response = await room.fetch(new Request("https://internal/reserve", { method: "POST",
            body: JSON.stringify({ game, seats: t.target, count: group.length }) }));
          const { tokens } = await response.json() as { tokens: string[] };
          if (tokens.length !== group.length) continue;
          const current = this.group(id);
          if (current.length !== group.length || !group.every(ws => current.includes(ws)) || this.ticket(group[0]).revision !== t.revision) {
            await room.fetch(new Request("https://internal/release", { method: "POST", body: JSON.stringify({ tokens }) }));
            return;
          }
          group.forEach((ws, i) => {
            this.send(ws, { type: "matched", role: "guest", code, reservation: tokens[i], target: t.target, seats: t.target });
            ws.serializeAttachment({ ...this.ticket(ws), phase: "done" });
            ws.close(1000, "matched");
          });
          return;
        }
      } catch {
        // Directory/room outages must not strand a queue; a later alarm retries discovery.
      } finally { this.matching.delete(id); }
      // Membership may have changed while the Room checked availability. Its next alarm retries.
      if (this.group(id).length !== group.length || this.ticket(group[0]).revision !== t.revision) return;
    }
    // Everyone has voted and one choice leads: no need to wait for the timer.
    if (t.phase === "offer" && group.length >= 2 && group.every(ws => this.ticket(ws).consent || this.ticket(ws).humanConsent)) {
      const bots = group.filter(ws => this.ticket(ws).consent).length;
      if (bots !== group.length - bots && bots !== 0 && bots !== group.length) { await this.closeVote(id); return; }
    }
    const humansOnly = group.length >= 2 && group.every(ws => this.ticket(ws).humanConsent);
    if (group.length === t.target || (t.phase === "offer" && (humansOnly || group.every(ws => this.ticket(ws).consent)))) {
      if (group.length === 1) {
        this.send(group[0], { type: "matched", role: "solo", target: t.target });
        group[0].serializeAttachment({ ...t, phase: "done" });
        group[0].close(1000, "matched");
        return;
      }
      const seats = humansOnly ? group.length : t.target;
      group.forEach((ws, i) => ws.serializeAttachment({ ...this.ticket(ws), seats, phase: "preparing", host: i === 0, deadline: Date.now() + 15_000 }));
      group.forEach((ws, i) => this.send(ws, { type: i === 0 ? "prepare_host" : "preparing", code: id,
        humans: group.length, bots: seats - group.length, target: t.target, seats }));
      return;
    }
    for (const ws of group) this.send(ws, { type: "queue", humans: group.length, target: t.target,
      bots: t.target - group.length, remaining: Math.max(0, Math.ceil((t.deadline - Date.now()) / 1000)),
      offer: t.phase === "offer", revision: t.revision, accepted: this.ticket(ws).consent,
      agreed: group.filter(member => this.ticket(member).consent).length,
      acceptedHumans: this.ticket(ws).humanConsent === true,
      agreedHumans: group.filter(member => this.ticket(member).humanConsent).length,
      ...(t.phase === "offer" && t.voteDeadline ? { voteRemaining: Math.max(0, Math.ceil((t.voteDeadline - Date.now()) / 1000)) } : {}),
      ...(restart ? { restart } : {}) });
  }
  private async expire(): Promise<void> {
    const groups = new Set(this.members().map(ws => this.ticket(ws).group));
    for (const id of groups) {
      const group = this.group(id);
      if (!group.length) continue;
      const t = this.ticket(group[0]);
      if (t.phase === "offer") {
        if (t.voteDeadline && t.voteDeadline <= Date.now()) await this.closeVote(id);
        continue;
      }
      if (t.deadline > Date.now()) continue;
      if (t.phase === "preparing") this.fail(id);
      else if (t.phase === "searching") {
        const voteDeadline = group.length >= 2 ? Date.now() + VOTE_MS : undefined;
        group.forEach(ws => ws.serializeAttachment({ ...this.ticket(ws), phase: "offer", voteDeadline }));
        await this.update(id);
      }
    }
  }
  /** Time is up: the choice with more votes starts for everyone; a tie or no votes searches on. */
  private async closeVote(id: string): Promise<void> {
    const group = this.group(id);
    const bots = group.filter(ws => this.ticket(ws).consent).length;
    const humans = group.filter(ws => this.ticket(ws).humanConsent).length;
    const revision = Math.max(0, ...group.map(ws => this.ticket(ws).revision)) + 1;
    for (const ws of group) {
      const t = this.ticket(ws);
      if (bots === humans) ws.serializeAttachment({ ...t, phase: "searching", consent: false, humanConsent: false, voteDeadline: undefined, revision, deadline: Date.now() + 20_000 });
      else ws.serializeAttachment({ ...t, consent: bots > humans, humanConsent: humans > bots, voteDeadline: undefined });
    }
    await this.update(id, bots === humans ? (bots === 0 ? "no_votes" : "tie") : undefined);
  }
  private fail(id: string): void {
    for (const ws of this.group(id)) {
      this.send(ws, { type: "match_error" });
      ws.serializeAttachment({ ...this.ticket(ws), phase: "done" });
      ws.close(1011, "room unavailable");
    }
  }
  private async schedule(): Promise<void> {
    const deadlines = this.members().map(ws => this.ticket(ws)).flatMap(t => t.phase !== "offer" ? [t.deadline] : t.voteDeadline ? [t.voteDeadline] : []);
    if (this.members().some(ws => this.ticket(ws).discover && this.ticket(ws).phase !== "preparing")) deadlines.push(Date.now() + 2_000);
    if (deadlines.length) await this.ctx.storage.setAlarm(Math.min(...deadlines));
    else await this.ctx.storage.deleteAlarm();
  }
  private send(ws: WebSocket, message: object): void { try { ws.send(JSON.stringify(message)); } catch { /* Close handler removes the ticket. */ } }
  private code(): string {
    const alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    let code: string;
    do { code = Array.from(crypto.getRandomValues(new Uint8Array(6)), n => alphabet[n % alphabet.length]).join(""); }
    while (this.members().some(ws => this.ticket(ws).group === code));
    return code;
  }
}
