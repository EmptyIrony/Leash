// Does session/load replay history? Test a CLI-made session and a desktop one, in a fresh agy acp.
import { spawn } from 'node:child_process';
import fs from 'node:fs'; import os from 'node:os'; import path from 'node:path';
const outDir = process.argv[2] || '.';
const log = fs.createWriteStream(path.join(outDir, 'acp2.jsonl'));
const lines = []; const say = (...a) => { const s = a.map(x => typeof x === 'string' ? x : JSON.stringify(x)).join(' '); lines.push(s); console.log(s); };
const child = spawn('agy', ['acp'], { cwd: os.homedir(), stdio: ['pipe', 'pipe', 'pipe'] });
child.stderr.on('data', d => fs.appendFileSync(path.join(outDir, 'acp2-stderr.txt'), d));
let id = 1, buf = ''; const pend = new Map(); const counts = new Map(); const firstTexts = new Map();
const send = m => { log.write(JSON.stringify({ dir: 'out', t: Date.now(), m }) + '\n'); child.stdin.write(JSON.stringify(m) + '\n'); };
const call = (method, params, ms = 120000) => new Promise((res, rej) => { const i = id++; pend.set(i, { res, rej }); send({ jsonrpc: '2.0', id: i, method, params }); setTimeout(() => { if (pend.delete(i)) rej(new Error('timeout ' + method)); }, ms); });
child.stdout.on('data', d => { buf += d; let k; while ((k = buf.indexOf('\n')) >= 0) { const line = buf.slice(0, k).trim(); buf = buf.slice(k + 1); if (!line) continue; let m; try { m = JSON.parse(line); } catch { continue; }
  log.write(JSON.stringify({ dir: 'in', t: Date.now(), m }) + '\n');
  if (m.id != null && !m.method) { const p = pend.get(m.id); if (p) { pend.delete(m.id); m.error ? p.rej(new Error(JSON.stringify(m.error))) : p.res(m.result); } continue; }
  if (m.method === 'session/update') { const s = m.params.sessionId, u = m.params.update; const c = counts.get(s) || {}; c[u.sessionUpdate] = (c[u.sessionUpdate] || 0) + 1; counts.set(s, c);
    if (u.content?.text && !firstTexts.has(s + u.sessionUpdate)) firstTexts.set(s + u.sessionUpdate, u.content.text.slice(0, 60)); continue; }
  if (m.id != null && m.method) send({ jsonrpc: '2.0', id: m.id, error: { code: -32601, message: 'probe' } });
} });
const sleep = ms => new Promise(r => setTimeout(r, ms));
try {
  await call('initialize', { protocolVersion: 1, clientCapabilities: { fs: { readTextFile: false, writeTextFile: false }, terminal: false }, clientInfo: { name: 'ag-remote-probe2', version: '0.0.1' } });
  const list = (await call('session/list', {})).sessions || [];
  const cli = list.find(s => s.title && /prompt test|pong/i.test(s.title)) || list.find(s => s.cwd?.endsWith('.agbridge-probe-cwd'));
  const desk = list.find(s => s.sessionId === '64ac5f86-2d42-47a9-aa8e-3dd0b57dc406') || list.find(s => s.cwd && !s.cwd.endsWith('.agbridge-probe-cwd'));
  for (const [label, s] of [['cli-made', cli], ['desktop', desk]]) {
    if (!s) { say(label, 'not found'); continue; }
    const t0 = Date.now();
    try { const r = await call('session/load', { sessionId: s.sessionId, cwd: s.cwd, mcpServers: [] }); say(label, 'load ok', s.sessionId, s.title, 'ms', Date.now() - t0, 'meta', r?._meta ? Object.keys(r._meta) : null); }
    catch (e) { say(label, 'load failed', e.message); }
    await sleep(5000);
    say(label, 'updates after load+5s:', counts.get(s.sessionId) || {});
  }
  // does resume differ?
  if (desk) { try { await call('session/close', { sessionId: desk.sessionId }); } catch (e) { say('close failed', e.message); }
    try { await call('session/resume', { sessionId: desk.sessionId, cwd: desk.cwd, mcpServers: [] }); await sleep(3000); say('desktop after resume:', counts.get(desk.sessionId) || {}); } catch (e) { say('resume failed', e.message); } }
  say('first texts:', Object.fromEntries(firstTexts));
} catch (e) { say('error', e.message); }
fs.writeFileSync(path.join(outDir, 'summary2.txt'), lines.join('\n') + '\n');
child.kill(); setTimeout(() => process.exit(0), 300);
