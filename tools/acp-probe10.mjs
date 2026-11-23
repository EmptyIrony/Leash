// Dump EVERY session/update of one desktop conversation, untruncated, to find how agy ships inline visualizations.
//   node acp-probe10.mjs <outDir> <titleSubstring|sessionId>
import { spawn } from 'node:child_process';
import fs from 'node:fs'; import os from 'node:os'; import path from 'node:path';
const [outDir, want] = process.argv.slice(2);
const child = spawn('agy', ['acp', '--app-data-dir', 'antigravity'], { cwd: os.homedir(), stdio: ['pipe', 'pipe', 'pipe'] });
let id = 1, buf = '', sid = null; const pend = new Map(); const kinds = {}; const raw = [];
const send = m => child.stdin.write(JSON.stringify(m) + '\n');
const call = (method, params, ms = 120000) => new Promise((res, rej) => { const i = id++; pend.set(i, { res, rej }); send({ jsonrpc: '2.0', id: i, method, params }); setTimeout(() => { if (pend.delete(i)) rej(new Error('timeout ' + method)); }, ms); });
child.stdout.on('data', d => { buf += d; let k; while ((k = buf.indexOf('\n')) >= 0) { const line = buf.slice(0, k).trim(); buf = buf.slice(k + 1); if (!line) continue; let m; try { m = JSON.parse(line); } catch { raw.push({ nonjson: line.slice(0, 2000) }); continue; }
  if (m.id != null && !m.method) { const p = pend.get(m.id); if (p) { pend.delete(m.id); m.error ? p.rej(new Error(JSON.stringify(m.error))) : p.res(m.result); } continue; }
  if (m.method === 'session/update' && (!sid || m.params.sessionId === sid)) { const u = m.params.update; const key = u.sessionUpdate + (u.name ? ':' + u.name : ''); kinds[key] = (kinds[key] || 0) + 1; raw.push(u); continue; }
  if (m.id != null && m.method) { raw.push({ agentRequest: m }); send({ jsonrpc: '2.0', id: m.id, error: { code: -32601, message: 'probe' } }); continue; }
  raw.push({ other: m });
} });
const info = {};
try {
  info.init = await call('initialize', { protocolVersion: 1, clientCapabilities: { fs: { readTextFile: false, writeTextFile: false }, terminal: false }, clientInfo: { name: 'ag-remote-probe10', version: '0.0.1' } });
  const list = (await call('session/list', {})).sessions || [];
  const s = list.find(x => x.sessionId === want) || list.find(x => (x.title || '').includes(want));
  if (!s) throw new Error('no session matching ' + want + '; titles: ' + list.map(x => x.title).join(' | '));
  sid = s.sessionId; info.session = s;
  info.load = await call('session/load', { sessionId: sid, cwd: s.cwd || os.homedir(), mcpServers: [] });
  await new Promise(r => setTimeout(r, 4000));
} catch (e) { info.error = e.message; }
fs.writeFileSync(path.join(outDir, 'kaleido.jsonl'), raw.map(x => JSON.stringify(x)).join('\n') + '\n');
fs.writeFileSync(path.join(outDir, 'kaleido-summary.json'), JSON.stringify({ info, kinds }, null, 2));
console.log('sessionId', sid, 'updates', raw.length, JSON.stringify(kinds));
child.kill(); setTimeout(() => process.exit(0), 300);
