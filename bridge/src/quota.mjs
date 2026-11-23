// Reads Antigravity quota through the official statusLine hook of the agy CLI:
// a hidden TUI session is started with a session-only --settings statusLine command
// that dumps agy's status JSON (which includes "quota") to a file. No prompt is sent.
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { CONFIG_DIR } from './config.mjs';

const GROUP_LABELS = { 'gemini': 'Gemini', '3p': 'Claude & GPT', 'gemini-argon-wtu': 'Argon', 'gemini-argon': 'Argon' };

/** statusLine "quota" map -> [{id, group, label, window, remainingFraction, resetTime}] grouped into pools. */
export function normalizeQuota(status) {
  const q = status?.quota;
  if (!q || typeof q !== 'object') return null;
  const pools = new Map();
  for (const [id, b] of Object.entries(q)) {
    if (!b || b.disabled) continue;
    const m = /^(.*?)-(5h|weekly|daily|monthly)$/i.exec(id);
    const group = m ? m[1] : id;
    const window = m ? m[2].toLowerCase() : 'other';
    const frac = typeof b.remaining_fraction === 'number' ? Math.max(0, Math.min(1, b.remaining_fraction)) : null;
    const pool = pools.get(group) ?? { id: group, label: GROUP_LABELS[group] ?? group.replace(/[-_]+/g, ' ').replace(/^./, c => c.toUpperCase()), buckets: [] };
    pool.buckets.push({ id, window, remainingFraction: frac, remainingAmount: b.remaining_amount ?? null, resetTime: b.reset_time ?? null });
    pools.set(group, pool);
  }
  const order = { '5h': 0, daily: 1, weekly: 2, monthly: 3, other: 4 };
  const list = [...pools.values()].map(p => ({ ...p, buckets: p.buckets.sort((a, b) => order[a.window] - order[b.window]) }));
  const rank = id => (id === 'gemini' ? 0 : id === '3p' ? 1 : 2);
  list.sort((a, b) => rank(a.id) - rank(b.id) || a.id.localeCompare(b.id));
  return { planTier: status.plan_tier ?? null, pools: list, fetchedAt: Date.now() };
}

/** Polls a function that returns normalized quota (e.g. the Claude host helper's "quota" command). */
export class FnQuotaPoller {
  constructor({ fetch, intervalMs = 10 * 60 * 1000, log = () => {}, onUpdate = () => {} }) {
    Object.assign(this, { fetch, intervalMs, log, onUpdate });
    this.latest = null; this.error = null; this.inflight = null; this.timer = null;
  }
  start(initialDelayMs = 8000) {
    setTimeout(() => this.refresh().catch(() => {}), initialDelayMs).unref?.();
    this.timer = setInterval(() => this.refresh().catch(() => {}), this.intervalMs); this.timer.unref?.();
  }
  stop() { clearInterval(this.timer); }
  refresh({ maxAgeMs = 0 } = {}) {
    if (this.latest && Date.now() - this.latest.fetchedAt < maxAgeMs) return Promise.resolve(this.latest);
    if (this.inflight) return this.inflight;
    this.inflight = (async () => {
      try { const q = await this.fetch(); if (!q?.pools) throw new Error('no quota'); q.fetchedAt ??= Date.now(); this.latest = q; this.error = null; this.onUpdate(q); return q; }
      catch (e) { this.error = e.message; this.log('quota: ' + e.message); throw e; }
      finally { this.inflight = null; }
    })();
    return this.inflight;
  }
}

export class QuotaPoller {
  constructor({ agentCommand, intervalMs = 10 * 60 * 1000, timeoutMs = 30000, workDir, log = () => {}, onUpdate = () => {}, usePty = true }) {
    Object.assign(this, { agentCommand, intervalMs, timeoutMs, log, onUpdate, usePty });
    this.workDir = workDir ?? path.join(CONFIG_DIR, 'quota');
    this.latest = null;
    this.error = null;
    this.inflight = null;
    this.timer = null;
  }

  start(initialDelayMs = 5000) {
    setTimeout(() => this.refresh().catch(() => {}), initialDelayMs).unref?.();
    this.timer = setInterval(() => this.refresh().catch(() => {}), this.intervalMs);
    this.timer.unref?.();
  }
  stop() { clearInterval(this.timer); }

  /** Refresh unless we have data younger than maxAgeMs. Concurrent callers share one run. */
  refresh({ maxAgeMs = 0 } = {}) {
    if (this.latest && Date.now() - this.latest.fetchedAt < maxAgeMs) return Promise.resolve(this.latest);
    if (this.inflight) return this.inflight;
    this.inflight = this.#run().finally(() => { this.inflight = null; });
    return this.inflight;
  }

  #run() {
    fs.mkdirSync(this.workDir, { recursive: true, mode: 0o700 });
    const out = path.join(this.workDir, 'status.json');
    const sink = path.join(this.workDir, 'statusline.sh');
    const tuiLog = path.join(this.workDir, 'tui.log');
    try { fs.unlinkSync(out); } catch {}
    // keep what the hidden TUI printed, so a failure can say why (it used to go to /dev/null)
    const logFd = fs.openSync(tuiLog, 'w', 0o600);
    fs.writeFileSync(sink, `#!/bin/sh\ncat > "${out}.tmp" && mv "${out}.tmp" "${out}"\necho agr\n`, { mode: 0o700 });
    const settings = JSON.stringify({ statusLine: { type: 'command', command: sink } });
    const agyArgs = ['--settings', settings];
    let cmd, args;
    if (!this.usePty) { cmd = this.agentCommand; args = agyArgs; }
    else if (process.platform === 'darwin') {
      // macOS script(1) calls tcgetattr on stdin and dies with ENOTSUP when stdin is a socket, which is
      // what Node's stdio 'pipe' is on macOS. Feed it a real pipe from a sleeping process instead (as probe8 did).
      const secs = String(Math.ceil(this.timeoutMs / 1000) + 5);
      cmd = '/bin/sh'; args = ['-c', 's=$1; shift; sleep "$s" | script -q /dev/null "$@"', 'agr-quota', secs, this.agentCommand, ...agyArgs];
    }
    else { cmd = 'script'; args = ['-qfc', [this.agentCommand, ...agyArgs].map(a => `'${String(a).replace(/'/g, `'\\''`)}'`).join(' '), '/dev/null']; }

    return new Promise((resolve, reject) => {
      const child = spawn(cmd, args, { cwd: this.workDir, stdio: [process.platform === 'darwin' ? 'ignore' : 'pipe', logFd, logFd], detached: true, env: { ...process.env, TERM: process.env.TERM || 'xterm-256color', COLUMNS: '120', LINES: '40' } });
      try { fs.closeSync(logFd); } catch {}
      let done = false, exitInfo = null, firstQuotaAt = null;
      const finish = (err, val) => {
        if (done) return; done = true;
        if (err) err.message += tuiTail(tuiLog, exitInfo);
        clearInterval(poll); clearTimeout(timer);
        try { process.kill(-child.pid, 'SIGTERM'); } catch {}
        setTimeout(() => { try { process.kill(-child.pid, 'SIGKILL'); } catch {} }, 1500).unref?.();
        if (err) { this.error = err.message; this.log('quota: ' + err.message); reject(err); }
        else { this.latest = val; this.error = null; this.onUpdate(val); resolve(val); }
      };
      child.on('error', e => finish(e));
      child.on('exit', (code, signal) => { exitInfo = signal ? 'signal ' + signal : 'exit ' + code; setTimeout(() => { if (!done) check(true); }, 200); });
      const check = (final = false) => {
        let raw;
        try { raw = fs.readFileSync(out, 'utf8'); } catch { if (final) finish(new Error('agy exited without statusLine output')); return; }
        let j; try { j = JSON.parse(raw); } catch { return; }
        const q = normalizeQuota(j);
        // agy's first statusLine call can come before plan_tier is loaded; give it a few seconds for a fuller one
        if (q && (q.planTier || final || (firstQuotaAt && Date.now() - firstQuotaAt > 5000))) finish(null, q);
        else if (q) firstQuotaAt ??= Date.now();
        else if (final) finish(new Error('statusLine had no quota'));
      };
      const poll = setInterval(check, 400);
      const timer = setTimeout(() => finish(new Error('timed out waiting for agy statusLine')), this.timeoutMs);
    });
  }
}

/** " (exit 1): <last printable TUI text>" for error messages; strips ANSI and never includes an email address. */
export function tuiTail(file, exitInfo, max = 240) {
  let raw = '';
  try { raw = fs.readFileSync(file, 'utf8'); } catch {}
  const text = raw.replace(/\r\n?/g, '\n')
    .replace(/\x1b\][^\x07\x1b]*(\x07|\x1b\\)/g, '')       // OSC
    .replace(/\x1b\[[0-9;?<>=]*[ -\/]*[@-~]/g, ' ')           // CSI
    .replace(/\x1b[@-_]/g, '')
    .replace(/[^\S\n]+/g, ' ').replace(/[\x00-\x09\x0b-\x1f\x7f]/g, '')
    .replace(/[\w.+-]+@[\w-]+(\.[\w-]+)+/g, '<email>')
    .split('\n').map(s => s.trim()).filter(Boolean).join(' | ');
  const tail = text.length > max ? '…' + text.slice(-max) : text;
  return (exitInfo ? ` (${exitInfo})` : '') + (tail ? ': ' + tail : '');
}
