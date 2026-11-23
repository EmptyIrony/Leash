import { spawn } from 'node:child_process';
import fs from 'node:fs'; import os from 'node:os'; import path from 'node:path';
const outDir = process.argv[2] || '.';
const lines = []; const say = (...a) => { const s = a.map(x => typeof x === 'string' ? x : JSON.stringify(x)).join(' '); lines.push(s); console.log(s); };
const sleep = ms => new Promise(r => setTimeout(r, ms));
async function run(label, args) {
  const child = spawn('agy', args, { cwd: os.homedir(), stdio: ['pipe', 'pipe', 'pipe'] });
  let stderr = ''; child.stderr.on('data', d => { stderr += d; });
  let id = 1, buf = ''; const pend = new Map(); const counts = new Map(); const samples = [];
  const send = m => child.stdin.write(JSON.stringify(m) + '\n');
  const call = (method, params, ms = 120000) => new Promise((res, rej) => { const i = id++; pend.set(i, { res, rej }); send({ jsonrpc: '2.0', id: i, method, params }); setTimeout(() => { if (pend.delete(i)) rej(new Error('timeout ' + method)); }, ms); });
  child.stdout.on('data', d => { buf += d; let k; while ((k = buf.indexOf('\n')) >= 0) { const line = buf.slice(0, k).trim(); buf = buf.slice(k + 1); if (!line) continue; let m; try { m = JSON.parse(line); } catch { continue; }
    if (m.id != null && !m.method) { const p = pend.get(m.id); if (p) { pend.delete(m.id); m.error ? p.rej(new Error(JSON.stringify(m.error))) : p.res(m.result); } continue; }
    if (m.method === 'session/update') { const s = m.params.sessionId, u = m.params.update; const c = counts.get(s) || {}; c[u.sessionUpdate] = (c[u.sessionUpdate] || 0) + 1; counts.set(s, c);
      if (samples.length < 12 && u.sessionUpdate !== 'available_commands_update') samples.push(JSON.stringify(u).slice(0, 220)); continue; }
    if (m.id != null && m.method) send({ jsonrpc: '2.0', id: m.id, error: { code: -32601, message: 'probe' } });
  } });
  try {
    await call('initialize', { protocolVersion: 1, clientCapabilities: { fs: { readTextFile: false, writeTextFile: false }, terminal: false }, clientInfo: { name: 'ag-remote-probe3', version: '0.0.1' } });
    const list = (await call('session/list', {})).sessions || [];
    say(label, 'list count', list.length, 'first', list.slice(0, 3).map(s => s.title));
    const desk = list.find(s => s.sessionId === '64ac5f86-2d42-47a9-aa8e-3dd0b57dc406') || list.find(s => s.cwd && s.cwd.includes('RE_Roguelike'));
    if (desk) {
      const t0 = Date.now();
      await call('session/load', { sessionId: desk.sessionId, cwd: desk.cwd, mcpServers: [] });
      await sleep(4000);
      say(label, 'desktop load', desk.title, 'ms', Date.now() - t0, 'updates', counts.get(desk.sessionId) || {});
      say(label, 'samples:\n  ' + samples.join('\n  '));
    } else say(label, 'desktop session not found');
  } catch (e) { say(label, 'error', e.message, stderr.slice(-500)); }
  child.kill(); await sleep(300);
}
await run('app-data-dir=antigravity', ['acp', '--app-data-dir', 'antigravity']);
fs.writeFileSync(path.join(outDir, 'summary3.txt'), lines.join('\n') + '\n');
process.exit(0);
