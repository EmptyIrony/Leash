import { spawn } from 'node:child_process';
import fs from 'node:fs'; import os from 'node:os'; import path from 'node:path';
const [outDir, sid] = process.argv.slice(2);
const child = spawn('agy', ['acp', '--app-data-dir', 'antigravity'], { cwd: os.homedir(), stdio: ['pipe', 'pipe', 'pipe'] });
let id = 1, buf = ''; const pend = new Map(); const byTool = {}; const kinds = {}; const order = [];
const trunc = (v, n = 700) => { const s = JSON.stringify(v); return s && s.length > n ? s.slice(0, n) + '…' : v; };
const send = m => child.stdin.write(JSON.stringify(m) + '\n');
const call = (method, params, ms = 120000) => new Promise((res, rej) => { const i = id++; pend.set(i, { res, rej }); send({ jsonrpc: '2.0', id: i, method, params }); setTimeout(() => { if (pend.delete(i)) rej(new Error('timeout ' + method)); }, ms); });
child.stdout.on('data', d => { buf += d; let k; while ((k = buf.indexOf('\n')) >= 0) { const line = buf.slice(0, k).trim(); buf = buf.slice(k + 1); if (!line) continue; let m; try { m = JSON.parse(line); } catch { continue; }
  if (m.id != null && !m.method) { const p = pend.get(m.id); if (p) { pend.delete(m.id); m.error ? p.rej(new Error(JSON.stringify(m.error))) : p.res(m.result); } continue; }
  if (m.method === 'session/update' && m.params.sessionId === sid) { const u = m.params.update; kinds[u.sessionUpdate] = (kinds[u.sessionUpdate] || 0) + 1;
    order.push(u.sessionUpdate === 'tool_call' || u.sessionUpdate === 'tool_call_update' ? (u.sessionUpdate + ':' + (u.name || '?')) : u.sessionUpdate);
    if (u.sessionUpdate === 'tool_call' || u.sessionUpdate === 'tool_call_update') {
      const key = u.sessionUpdate + ':' + (u.name || u.title || '?');
      byTool[key] = byTool[key] || { count: 0, keys: Object.keys(u), sample: Object.fromEntries(Object.entries(u).map(([a, b]) => [a, trunc(b)])) };
      byTool[key].count++;
    }
  }
  if (m.id != null && m.method) send({ jsonrpc: '2.0', id: m.id, error: { code: -32601, message: 'probe' } });
} });
try {
  await call('initialize', { protocolVersion: 1, clientCapabilities: { fs: { readTextFile: false, writeTextFile: false }, terminal: false }, clientInfo: { name: 'ag-remote-probe5', version: '0.0.1' } });
  const list = (await call('session/list', {})).sessions || [];
  const s = list.find(x => x.sessionId === sid);
  await call('session/load', { sessionId: sid, cwd: s?.cwd || os.homedir(), mcpServers: [] });
  await new Promise(r => setTimeout(r, 3000));
} catch (e) { byTool.error = e.message; }
// compress the event order into runs, e.g. "agent_thought_chunk x3, tool_call:run_command x2"
const runs = []; for (const k of order) { const l = runs[runs.length - 1]; if (l && l[0] === k) l[1]++; else runs.push([k, 1]); }
fs.writeFileSync(path.join(outDir, 'tools.json'), JSON.stringify({ kinds, tools: byTool, sequence: runs.slice(0, 400).map(([k, n]) => n > 1 ? k + ' x' + n : k) }, null, 2));
console.log('tools.json written:', Object.keys(byTool).length, 'tool shapes');
child.kill(); setTimeout(() => process.exit(0), 300);
