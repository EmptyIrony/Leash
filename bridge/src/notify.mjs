// Optional Bark notifications (https://github.com/Finb/Bark). Leash has no APNs of its own, so when an agent
// needs you or finishes while the app is not on screen, the bridge asks the Bark app to alert the phone
// (iOS mirrors it to a paired Apple Watch). Configure with `"bark": {"key": "…"}` in ~/.leash/config.json
// or `node src/index.mjs bark <key>`. Nothing here may throw into the hub: every path catches and logs.
import path from 'node:path';

// The app sends a WebSocket ping every 15 s while it runs; a client silent for this long is suspended or gone.
export const AWAY_MS = 25000;

const TEXT = {
  zh: {
    permission: l => `${l} 需要批准`, question: l => `${l} 有问题要问你`, done: l => `${l} 完成了`,
    error: l => `${l} 出错了`, stopped: l => `${l} 停下了`, open: '点开 Leash 查看',
    took: d => '用时 ' + d, min: m => m + ' 分', sec: s => s + ' 秒', test: 'Leash 测试通知', testBody: 'Bark 已连上 bridge',
  },
  en: {
    permission: l => `${l} needs approval`, question: l => `${l} has a question`, done: l => `${l} finished`,
    error: l => `${l} hit an error`, stopped: l => `${l} stopped`, open: 'Open Leash to respond',
    took: d => 'Took ' + d, min: m => m + ' min', sec: s => s + ' s', test: 'Leash test', testBody: 'Bark is connected to the bridge',
  },
};

/** Normalised settings from the config's `bark` block, or null when notifications are off. Never throws. */
export function barkSettings(raw) {
  if (!raw || typeof raw !== 'object' || typeof raw.key !== 'string' || !raw.key.trim()) return null;
  let server = typeof raw.server === 'string' && raw.server.trim() ? raw.server.trim() : 'https://api.day.app';
  server = server.replace(/\/+$/, '');
  return {
    key: raw.key.trim(),
    server,
    always: raw.when === 'always',             // default "away": only while no Leash client is on screen
    detail: raw.detail === true,               // include session / tool titles (they pass through the Bark server)
    attention: raw.attention !== false,
    turnEnd: raw.turnEnd !== false,
    sound: typeof raw.sound === 'string' && raw.sound ? raw.sound : null,
    url: typeof raw.url === 'string' ? raw.url : 'leash://open',
    text: TEXT[raw.lang] ?? TEXT.zh,
  };
}

/** Accept a bare device key or the URL the Bark app shows (https://api.day.app/<key>/…). */
export function parseBarkArg(arg, server) {
  const a = String(arg ?? '').trim();
  if (/^https?:\/\//i.test(a)) {
    const u = new URL(a);
    const key = u.pathname.split('/').filter(Boolean)[0];
    if (!key) throw new Error('no device key in that URL');
    return { key: decodeURIComponent(key), server: server || u.origin };
  }
  if (!a) throw new Error('missing device key');
  return { key: a, ...(server ? { server } : {}) };
}

function duration(ms, t) {
  const s = Math.max(0, Math.round(ms / 1000));
  return s >= 60 ? t.min(Math.floor(s / 60)) + (s % 60 ? ' ' + t.sec(s % 60) : '') : t.sec(s);
}

export class BarkNotifier {
  constructor({ settings, clients = () => [], fetch = globalThis.fetch, log = () => {}, awayMs = AWAY_MS, timeoutMs = 10000 }) {
    this.s = settings;
    this.clients = clients;
    this.fetch = fetch;
    this.log = log;
    this.awayMs = awayMs;
    this.timeoutMs = timeoutMs;
  }

  /** Listen to a hub's attention / turn_end events. */
  watch(hub) {
    if (this.s.attention) hub.on('attention', p => { try { this.#attention(hub, p); } catch (e) { this.log('bark: ' + e.message); } });
    if (this.s.turnEnd) hub.on('turn_end', s => { try { this.#turnEnd(hub, s); } catch (e) { this.log('bark: ' + e.message); } });
  }

  /** Latest time any connected client was heard from (0 when none). */
  #lastSeen() {
    let t = 0;
    for (const c of this.clients() ?? []) t = Math.max(t, c.lastSeen ?? 0);
    return t;
  }

  /**
   * Run send() now if nobody is watching; if a client looks live, wait one ping period and send only if it went
   * quiet (the app was backgrounded right before the event). still() can veto a late send.
   */
  #whenAway(send, still = () => true) {
    const t = Date.now();
    if (this.s.always || this.#lastSeen() < t - this.awayMs) { send(); return; }
    const timer = setTimeout(() => {
      try { if (this.#lastSeen() <= t && still()) send(); } catch (e) { this.log('bark: ' + e.message); }
    }, this.awayMs);
    timer.unref?.();
  }

  #sessionName(hub, sessionId) {
    const s = hub.sessions?.get?.(sessionId);
    return s?.title || (s?.cwd ? path.basename(s.cwd) : '') || '';
  }

  #attention(hub, p) {
    const t = this.s.text;
    const label = hub.label ?? hub.id ?? 'Agent';
    const title = p.method === 'elicitation/create' ? t.question(label) : t.permission(label);
    let body = t.open;
    if (this.s.detail) {
      const parts = [this.#sessionName(hub, p.sessionId), p.params?.toolCall?.title ?? p.params?.message ?? ''].filter(Boolean);
      if (parts.length) body = parts.join(' · ');
    }
    this.#whenAway(() => { this.push({ title, body, level: 'timeSensitive', group: label }); }, () => hub.pending?.has?.(p.key) ?? true);
  }

  #turnEnd(hub, s) {
    const reason = s.lastStopReason;
    if (reason === 'cancelled') return;          // you pressed Stop yourself
    // turn_end fires before a queued follow-up is flushed; decide once that has happened
    const timer = setTimeout(() => {
      try {
        if (s.running) return;                   // a queued message continued the turn
        const t = this.s.text;
        const label = hub.label ?? hub.id ?? 'Agent';
        const title = reason === 'error' ? t.error(label) : reason === 'end_turn' || !reason ? t.done(label) : t.stopped(label);
        const took = s.turnStart && s.turnEndedAt ? t.took(duration(s.turnEndedAt - s.turnStart, t)) : '';
        const name = this.s.detail ? this.#sessionName(hub, s.sessionId) : '';
        const extra = reason && !['end_turn', 'error'].includes(reason) ? reason : '';
        const body = [name, extra, took].filter(Boolean).join(' · ') || t.open;
        this.#whenAway(() => { this.push({ title, body, level: 'active', group: label }); }, () => !s.running);
      } catch (e) { this.log('bark: ' + e.message); }
    }, 0);
    timer.unref?.();
  }

  /** POST one notification. Resolves true/false, never rejects; never logs the key or server. */
  async push({ title, body, level = 'active', group = 'Leash' }) {
    const payload = { device_key: this.s.key, title, body, level, group };
    if (this.s.url) payload.url = this.s.url;
    if (this.s.sound) payload.sound = this.s.sound;
    try {
      const r = await this.fetch(this.s.server + '/push', {
        method: 'POST',
        headers: { 'content-type': 'application/json; charset=utf-8' },
        body: JSON.stringify(payload),
        signal: AbortSignal.timeout(this.timeoutMs),
      });
      if (!r.ok) { this.log(`bark push failed: HTTP ${r.status}`); return false; }
      return true;
    } catch (e) {
      this.log('bark push failed: ' + (e?.name === 'TimeoutError' ? 'timeout' : e?.message ?? String(e)));
      return false;
    }
  }

  test() { const t = this.s.text; return this.push({ title: t.test, body: t.testBody, group: 'Leash' }); }
}
