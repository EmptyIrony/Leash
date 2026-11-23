// ACP probe: talks to `agy acp` over stdio and records every frame.
// Usage: node acp-probe.mjs <outDir> [--no-prompt] [--no-load]
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const outDir = process.argv[2] || '.';
const flags = new Set(process.argv.slice(3));
const log = fs.createWriteStream(path.join(outDir, 'acp.jsonl'));
const summary = [];
const say = (...a) => { const s = a.map(x => typeof x === 'string' ? x : JSON.stringify(x, null, 2)).join(' '); summary.push(s); console.log(s); };

const scratch = path.join(os.homedir(), '.agbridge-probe-cwd');
fs.mkdirSync(scratch, { recursive: true });

const child = spawn('agy', ['acp'], { cwd: scratch, stdio: ['pipe', 'pipe', 'pipe'] });
child.stderr.on('data', d => fs.appendFileSync(path.join(outDir, 'acp-stderr.txt'), d));
child.on('exit', (c, s) => say('agy acp exited', c, s));

let nextId = 1; const pending = new Map(); let buf = '';
const updatesBySession = new Map();
const send = m => { const line = JSON.stringify(m); log.write(JSON.stringify({ dir: 'out', t: Date.now(), m }) + '\n'); child.stdin.write(line + '\n'); };
const call = (method, params, ms = 60000) => new Promise((res, rej) => {
  const id = nextId++; send({ jsonrpc: '2.0', id, method, params });
  const to = setTimeout(() => { pending.delete(id); rej(new Error('timeout ' + method)); }, ms);
  pending.set(id, { res: v => { clearTimeout(to); res(v); }, rej: e => { clearTimeout(to); rej(e); } });
});
child.stdout.on('data', d => {
  buf += d; let i;
  while ((i = buf.indexOf('\n')) >= 0) {
    const line = buf.slice(0, i).trim(); buf = buf.slice(i + 1); if (!line) continue;
    let m; try { m = JSON.parse(line); } catch { fs.appendFileSync(path.join(outDir, 'acp-stderr.txt'), 'NONJSON: ' + line + '\n'); continue; }
    log.write(JSON.stringify({ dir: 'in', t: Date.now(), m }) + '\n');
    if (m.id != null && !m.method) { const p = pending.get(m.id); if (p) { pending.delete(m.id); m.error ? p.rej(Object.assign(new Error(m.error.message), { rpc: m.error })) : p.res(m.result); } continue; }
    if (m.method === 'session/update') {
      const sid = m.params?.sessionId; const k = m.params?.update?.sessionUpdate;
      const c = updatesBySession.get(sid) || {}; c[k] = (c[k] || 0) + 1; updatesBySession.set(sid, c);
      continue;
    }
    if (m.method === 'session/request_permission') {
      const opts = m.params?.options || [];
      const rej = opts.find(o => String(o.kind).startsWith('reject')) || opts[opts.length - 1];
      say('permission request:', { toolCall: m.params?.toolCall?.title, options: opts.map(o => [o.optionId, o.kind, o.name]) });
      send({ jsonrpc: '2.0', id: m.id, result: { outcome: rej ? { outcome: 'selected', optionId: rej.optionId } : { outcome: 'cancelled' } } });
      continue;
    }
    if (m.id != null && m.method) {
      say('agent request (declined):', m.method);
      send({ jsonrpc: '2.0', id: m.id, error: { code: -32601, message: 'not supported by probe' } });
    }
  }
});

const kill = () => { try { child.kill(); } catch {} };
setTimeout(() => { say('global timeout'); finish(); }, 240000);
function finish() {
  say('updates by session:', Object.fromEntries(updatesBySession));
  fs.writeFileSync(path.join(outDir, 'summary.txt'), summary.join('\n') + '\n');
  kill(); setTimeout(() => process.exit(0), 300);
}

try {
  const init = await call('initialize', {
    protocolVersion: 1,
    clientCapabilities: { fs: { readTextFile: false, writeTextFile: false }, terminal: false },
    clientInfo: { name: 'ag-remote-probe', version: '0.0.1' },
  });
  say('initialize result:', init);

  let sessions = [];
  try {
    const l = await call('session/list', {});
    sessions = l.sessions || [];
    say('session/list: count', sessions.length, 'nextCursor', l.nextCursor ?? null);
    say('first sessions:', sessions.slice(0, 15).map(s => ({ id: s.sessionId, title: s.title, cwd: s.cwd, updatedAt: s.updatedAt, meta: s._meta })));
  } catch (e) { say('session/list failed:', e.message, e.rpc || ''); }

  if (!flags.has('--no-load') && sessions.length) {
    const s = sessions[0];
    try {
      const r = await call('session/load', { sessionId: s.sessionId, cwd: s.cwd || scratch, mcpServers: [] }, 120000);
      say('session/load ok for', s.sessionId, 'result keys:', Object.keys(r || {}), 'configOptions:', r?.configOptions?.map(o => ({ id: o.id, name: o.name, category: o.category, current: o.currentValue, n: o.options?.length })), 'modes:', r?.modes);
    } catch (e) { say('session/load failed:', e.message, e.rpc || ''); }
  }

  if (!flags.has('--no-prompt')) {
    const ns = await call('session/new', { cwd: scratch, mcpServers: [] });
    say('session/new:', { sessionId: ns.sessionId, modes: ns.modes, configOptions: ns.configOptions, keys: Object.keys(ns) });
    const pr = await call('session/prompt', { sessionId: ns.sessionId, prompt: [{ type: 'text', text: 'Reply with exactly the word: pong. Do not use any tools.' }] }, 180000);
    say('session/prompt result:', pr);
    try {
      const l2 = await call('session/list', {});
      say('after prompt, list count', (l2.sessions || []).length, 'new present:', (l2.sessions || []).some(x => x.sessionId === ns.sessionId));
    } catch {}
  }
} catch (e) {
  say('probe error:', e.message, e.rpc || '');
}
finish();
