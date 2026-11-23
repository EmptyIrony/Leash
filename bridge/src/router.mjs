// Routes phone clients across several agent hubs (Antigravity, Claude Code, …).
import { RpcError } from './acp.mjs';

export class Router {
  constructor({ hubs, log = () => {} }) {
    if (!hubs?.length) throw new Error('router needs at least one hub');
    this.hubs = new Map(hubs.map(h => [h.id, h]));
    this.order = hubs.map(h => h.id);
    this.defaultId = this.order[0];
    this.clients = new Set();
    this.nextClientId = 1;
    this.owner = new Map();          // sessionId -> hub id
    this.log = log;
  }

  get agentState() { return this.hubs.get(this.defaultId).agentState; }

  addClient(send, meta = {}) {
    const client = { id: this.nextClientId++, send, subs: new Set(), ...meta };
    this.clients.add(client);
    for (const h of this.hubs.values()) h.attachClient(client);
    send({ op: 'ready', ...this.info() });
    return client;
  }

  removeClient(client) {
    this.clients.delete(client);
    for (const h of this.hubs.values()) h.removeClient(client);
  }

  info() {
    const hubs = this.order.map(id => this.hubs.get(id));
    const first = hubs[0].info();
    return {
      bridge: { ...first.bridge, multiAgent: true },
      agent: first.agent,                              // legacy single-agent field
      agents: hubs.map(h => h.agentSummary()),
      pending: hubs.flatMap(h => [...h.pending.values()].map(p => h.pendingMsg(p))),
      running: hubs.flatMap(h => [...h.sessions.values()].filter(s => s.running).map(s => s.sessionId)),
      quota: first.quota,
    };
  }

  hubFor(params = {}) {
    const sid = params.sessionId;
    // the hub that already knows a session wins over a client's guess
    if (sid) {
      const known = this.owner.get(sid);
      if (known) return this.hubs.get(known);
      for (const h of this.hubs.values()) if (h.ownsSession(sid)) return h;
    }
    if (params.agent) {
      const h = this.hubs.get(params.agent);
      if (!h) throw new RpcError(-32010, 'unknown agent ' + params.agent);
      return h;
    }
    return this.hubs.get(this.defaultId);
  }

  async handleCall(client, method, params = {}) {
    if (method === 'bridge/info') return this.info();
    if (method === 'bridge/agents') return { agents: this.order.map(id => this.hubs.get(id).agentSummary()) };
    const { agent, ...rest } = params ?? {};

    if (method === 'session/list' && !agent) return this.listAll(client, rest);

    const hub = this.hubFor(params);
    const r = await hub.handleCall(client, method, rest);
    if (method === 'session/new' && r?.sessionId) this.owner.set(r.sessionId, hub.id);
    if (method === 'session/list') for (const s of r?.sessions ?? []) this.owner.set(s.sessionId, hub.id);
    if ((method === 'bridge/attach' || method === 'session/load') && rest.sessionId) this.owner.set(rest.sessionId, hub.id);
    return r;
  }

  /** Merged session list across agents (newest first); an agent that is down just reports an error. */
  async listAll(client, params) {
    const results = await Promise.all(this.order.map(async id => {
      const h = this.hubs.get(id);
      if (h.agentState !== 'ready') return { id, error: h.agentState + (h.agentError ? ': ' + h.agentError : '') };
      try { return { id, r: await h.handleCall(client, 'session/list', params) }; }
      catch (e) { return { id, error: e.message }; }
    }));
    const sessions = [];
    const errors = {};
    for (const { id, r, error } of results) {
      if (error) { errors[id] = error; continue; }
      for (const s of r?.sessions ?? []) { this.owner.set(s.sessionId, id); sessions.push(s); }
    }
    sessions.sort((a, b) => String(b.updatedAt ?? '').localeCompare(String(a.updatedAt ?? '')));
    return { sessions, nextCursor: null, _leash: { errors } };
  }

  reply(client, key, result, error) {
    const id = String(key).split(':')[0];
    const hub = this.hubs.get(id) ?? this.hubs.get(this.defaultId);
    return hub.reply(client, key, result, error);
  }

  stop() { for (const h of this.hubs.values()) h.stop(); }
}
