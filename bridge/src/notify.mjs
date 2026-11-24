// Optional Bark notifications (https://github.com/Finb/Bark). Leash has no APNs of its own, so when an agent
// needs you or finishes while the app is not on screen, the bridge asks the Bark app to alert the phone
// (iOS mirrors it to a paired Apple Watch). A Leash still running in the background posts its own notification
// and says so (bridge/noticed); Bark then stays quiet, so it only covers a suspended or closed app. Configure with `"bark": {"key": "…"}` in ~/.leash/config.json
// or `node src/index.mjs bark <key>`. Nothing here may throw into the hub: every path catches and logs.
import path from 'node:path';
import crypto from 'node:crypto';

// The app sends a WebSocket ping every 15 s while it runs; a client silent for this long is suspended or gone.
export const AWAY_MS = 25000;
// A Leash connected from the background says bridge/noticed within this long when it alerted about an event itself.
export const BACKGROUND_GRACE_MS = 4000;

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

/**
 * Normalised settings from the config's `bark` block, or null when notifications are off. Throws only for a bad
 * `encrypt` key: fail closed rather than fall back to plaintext.
 */
export function barkSettings(raw) {
  if (!raw || typeof raw !== 'object' || typeof raw.key !== 'string' || !raw.key.trim()) return null;
  let encrypt = null;
  if (raw.encrypt != null && raw.encrypt !== false) {
    const k = typeof raw.encrypt === 'string' ? raw.encrypt : raw.encrypt?.key;
    if (!validCipherKey(k)) throw new Error('bark.encrypt must be a 16, 24 or 32 character ASCII key (AES-128/192/256)');
    encrypt = { key: k };
  }
  let server = typeof raw.server === 'string' && raw.server.trim() ? raw.server.trim() : 'https://api.day.app';
  server = server.replace(/\/+$/, '');
  return {
    key: raw.key.trim(),
    server,
    always: raw.when === 'always',             // default "away": only while no Leash client is on screen
    encrypt,                                   // end-to-end: Bark's server and APNs only see ciphertext
    // include session / tool titles: on by default only when encrypted (otherwise they pass through the Bark server)
    detail: typeof raw.detail === 'boolean' ? raw.detail : !!encrypt,
    attention: raw.attention !== false,
    turnEnd: raw.turnEnd !== false,
    sound: typeof raw.sound === 'string' && raw.sound ? raw.sound : null,
    // tap target: a fixed `url` from the config wins; otherwise the conversation (leash://session?…) when titles are
    // allowed in pushes (detail), else just the app (leash://open). Older apps treat any of these as "open the app".
    url: typeof raw.url === 'string' ? raw.url : null,
    text: TEXT[raw.lang] ?? TEXT.zh,
  };
}

export function validCipherKey(k) { return typeof k === 'string' && [16, 24, 32].includes(k.length) && /^[\x21-\x7e]+$/.test(k); }

/** A random key for the Bark app's 推送加密 / Push Encryption setting (AES256, CBC, pkcs7). */
export function newCipherKey() { return crypto.randomBytes(24).toString('base64url'); }

/**
 * Bark's encrypted push format (Bark: CiphertextProcessor.swift / Model/Algorithm.swift): the JSON payload,
 * AES-CBC with PKCS#7 padding, key and IV used as their ASCII bytes, base64. The bits follow the key length.
 */
export function barkEncrypt(json, key, iv) {
  const c = crypto.createCipheriv(`aes-${key.length * 8}-cbc`, Buffer.from(key, 'latin1'), Buffer.from(iv, 'latin1'));
  return Buffer.concat([c.update(json, 'utf8'), c.final()]).toString('base64');
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
  constructor({ settings, clients = () => [], fetch = globalThis.fetch, log = () => {}, awayMs = AWAY_MS, graceMs = BACKGROUND_GRACE_MS, timeoutMs = 10000 }) {
    this.s = settings;
    this.clients = clients;
    this.fetch = fetch;
    this.log = log;
    this.awayMs = awayMs;
    this.graceMs = graceMs;
    this.timeoutMs = timeoutMs;
  }

  /** Listen to a hub's attention / turn_end events. */
  watch(hub) {
    if (this.s.attention) hub.on('attention', p => { try { this.#attention(hub, p); } catch (e) { this.log('bark: ' + e.message); } });
    if (this.s.turnEnd) hub.on('turn_end', s => { try { this.#turnEnd(hub, s); } catch (e) { this.log('bark: ' + e.message); } });
  }

  /** Latest time a connected client on screen was heard from (0 when none). */
  #lastSeen() {
    let t = 0;
    for (const c of this.clients() ?? []) if (!c.background) t = Math.max(t, c.lastSeen ?? 0);
    return t;
  }

  /** A Leash still connected from the background (kept running for its Live Activity). */
  #inBackground() {
    for (const c of this.clients() ?? []) if (c.background) return true;
    return false;
  }

  /**
   * Run send() now if nobody is watching; if a client looks live, wait one ping period and send only if it went
   * quiet (the app was backgrounded right before the event). A Leash running in the background gets a short grace
   * instead: it may alert about the event itself (bridge/noticed). still() can veto a late send.
   */
  #whenAway(send, still = () => true) {
    const t = Date.now();
    if (this.s.always) { send(); return; }
    const onScreen = this.#lastSeen() >= t - this.awayMs;
    if (!onScreen && !this.#inBackground()) { send(); return; }
    const timer = setTimeout(() => {
      try { if (this.#lastSeen() <= t && still()) send(); } catch (e) { this.log('bark: ' + e.message); }
    }, onScreen ? this.awayMs : this.graceMs);
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
    // still waiting, and the phone did not alert about it itself (bridge/noticed)
    const still = () => { const m = hub.pending; if (!m?.get) return true; const q = m.get(p.key); return !!q && !q.noticedAt; };
    this.#whenAway(() => { this.push({ title, body, level: 'timeSensitive', group: label, url: this.#link(hub, p.sessionId) }); }, still);
  }

  /** leash://session?id=…&agent=… for a push about one conversation (only when titles may be pushed). */
  #link(hub, sessionId) {
    if (!this.s.detail || !sessionId) return null;
    const u = new URL('leash://session');
    u.searchParams.set('id', String(sessionId));
    u.searchParams.set('agent', String(hub.id ?? ''));
    return u.toString();
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
        // not continued meanwhile, and the phone did not post this turn end itself
        const still = () => !s.running && !(s.endNoticedAt >= (s.turnEndedAt ?? 0));
        this.#whenAway(() => { this.push({ title, body, level: 'active', group: label, url: this.#link(hub, s.sessionId) }); }, still);
      } catch (e) { this.log('bark: ' + e.message); }
    }, 0);
    timer.unref?.();
  }

  /** POST one notification. Resolves true/false, never rejects; never logs the key or server. */
  async push({ title, body, level = 'active', group = 'Leash', url = null }) {
    const fields = { title, body, level, group, url: this.s.url ?? url ?? 'leash://open' };
    if (!fields.url) delete fields.url;
    if (this.s.sound) fields.sound = this.s.sound;
    let payload;
    if (this.s.encrypt) {
      // a fresh IV per push, sent alongside (Bark uses a passed iv over the one saved in the app)
      const iv = crypto.randomBytes(12).toString('base64url');
      payload = { device_key: this.s.key, ciphertext: barkEncrypt(JSON.stringify(fields), this.s.encrypt.key, iv), iv };
    } else payload = { device_key: this.s.key, ...fields };
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
