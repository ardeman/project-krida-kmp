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
};

export class GroupMatchmaker implements DurableObject {
  constructor(private ctx: DurableObjectState) {}

  async fetch(request: Request): Promise<Response> {
    if (request.headers.get("Upgrade") !== "websocket") return new Response("expected websocket", { status: 426 });
    const target = Number(new URL(request.url).pathname.split("/").pop());
    const [client, server] = Object.values(new WebSocketPair());
    this.ctx.acceptWebSocket(server);
    await this.expire();
    const waiting = this.members().find(ws => {
      const t = this.ticket(ws);
      return t.target === target && (t.phase === "searching" || t.phase === "offer") && this.group(t.group).length < target;
    });
    const first = waiting && this.ticket(waiting);
    server.serializeAttachment({
      group: first?.group ?? this.code(), target,
      deadline: first?.deadline ?? Date.now() + 20_000,
      phase: first?.phase ?? "searching", consent: false, revision: first?.revision ?? 0,
      maxPlayers: new URL(request.url).pathname.includes("/congklak/") ? 2 : 4,
    } satisfies Ticket);
    // Membership changes invalidate prior consent: everyone sees the new composition.
    this.resetConsent(this.ticket(server).group);
    this.update(this.ticket(server).group);
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
        this.update(current.group);
      } else if (action === "accept_humans" && revision === current.revision && this.group(current.group).length >= 2) {
        ws.serializeAttachment({ ...current, consent: false, humanConsent: true });
        this.update(current.group);
      } else if (action === "play_solo" && revision === current.revision && this.group(current.group).length === 1 &&
          Number.isInteger(seats) && seats >= 2 && seats <= (current.maxPlayers ?? current.target)) {
        this.send(ws, { type: "matched", role: "solo", target: current.target, seats });
        ws.serializeAttachment({ ...current, phase: "done" });
        ws.close(1000, "matched");
      } else if (action === "keep_searching") {
        for (const member of this.group(current.group)) {
          member.serializeAttachment({ ...this.ticket(member), phase: "searching", consent: false, humanConsent: false, revision: current.revision + 1, deadline: Date.now() + 20_000 });
        }
        this.update(current.group);
      }
    }
    await this.schedule();
  }

  async webSocketClose(ws: WebSocket): Promise<void> {
    const t = this.ticket(ws);
    if (t.phase === "done") return;
    ws.serializeAttachment({ ...t, phase: "done" });
    if (t.phase === "preparing") this.fail(t.group);
    else { this.resetConsent(t.group); this.update(t.group); }
    await this.schedule();
  }
  async webSocketError(ws: WebSocket): Promise<void> { await this.webSocketClose(ws); }
  async alarm(): Promise<void> { await this.expire(); await this.schedule(); }

  private ticket(ws: WebSocket): Ticket { return ws.deserializeAttachment() as Ticket; }
  private members(): WebSocket[] { return this.ctx.getWebSockets().filter(ws => ws.deserializeAttachment() && this.ticket(ws).phase !== "done"); }
  private group(id: string): WebSocket[] { return this.members().filter(ws => this.ticket(ws).group === id); }
  private resetConsent(id: string): void {
    const group = this.group(id);
    const revision = Math.max(0, ...group.map(ws => this.ticket(ws).revision)) + 1;
    for (const ws of group) ws.serializeAttachment({ ...this.ticket(ws), consent: false, humanConsent: false, revision });
  }
  private update(id: string): void {
    const group = this.group(id);
    if (!group.length) return;
    const t = this.ticket(group[0]);
    if (t.phase === "preparing") return;
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
      agreedHumans: group.filter(member => this.ticket(member).humanConsent).length });
  }
  private async expire(): Promise<void> {
    const groups = new Set(this.members().map(ws => this.ticket(ws).group));
    for (const id of groups) {
      const group = this.group(id), t = this.ticket(group[0]);
      if (t.deadline > Date.now()) continue;
      if (t.phase === "preparing") this.fail(id);
      else if (t.phase === "searching") {
        group.forEach(ws => ws.serializeAttachment({ ...this.ticket(ws), phase: "offer" }));
        this.update(id);
      }
    }
  }
  private fail(id: string): void {
    for (const ws of this.group(id)) {
      this.send(ws, { type: "match_error" });
      ws.serializeAttachment({ ...this.ticket(ws), phase: "done" });
      ws.close(1011, "room unavailable");
    }
  }
  private async schedule(): Promise<void> {
    const deadlines = this.members().map(ws => this.ticket(ws)).filter(t => t.phase !== "offer").map(t => t.deadline);
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
