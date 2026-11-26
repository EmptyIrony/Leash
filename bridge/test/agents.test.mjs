import { test } from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import os from 'node:os';
import fs from 'node:fs';
import net from 'node:net';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import WebSocket from 'ws';
import { planAgents, systemDeps, materialize, findClaudeAdapter, quotaSupport, lockSupport, diagnose, formatDiagnosis, detectedEntries, defaultAgyEntry } from '../src/agents.mjs';
import { claudeAgentConfig, loadConfig, localClaudeEntry } from '../src/config.mjs';
import { Hub } from '../src/hub.mjs';
import { Router } from '../src/router.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const MOCK = path.join(here, 'mock-agent.mjs');

/** Deps for a pretend machine: `files` are the paths that exist, `cmds` the commands on PATH. */
function machine({ cmds = {}, files = [], env = {}, platform = 'linux', home = '/home/u' } = {}) {
  const set = new Set(files);
  return systemDeps({ platform, env, home, bridgeDir: '/opt/leash/bridge', configPath: '/home/u/.leash/config.json',
    find: c => cmds[c] ?? (c === process.execPath || (path.isAbsolute(c) && set.has(c)) ? c : null), exists: p => set.has(p), readJson: p => (set.has(p) ? { version: '9.9.9' } : null) });
}
const ADAPTER = '/home/u/.leash/claude-acp/node_modules/@agentclientprotocol/claude-agent-acp/dist/index.js';
const ids = plan => plan.agents.map(a => a.id + (a.ok ? '' : '!'));

test('no agents in config: only agy exists -> only agy, no claude entry and no broken agent', () => {
  const plan = planAgents({}, machine({ cmds: { agy: '/usr/bin/agy' } }));
  assert.equal(plan.explicit, false);
  assert.deepEqual(ids(plan), ['agy']);
  assert.equal(plan.agents[0].command, '/usr/bin/agy');
  assert.deepEqual(plan.absent.map(a => a.id), ['claude']);
});

test('agy args: --app-data-dir antigravity only when the desktop data dir exists', () => {
  assert.deepEqual(defaultAgyEntry(machine()).args, ['acp']);
  assert.deepEqual(defaultAgyEntry(machine({ files: ['/home/u/.gemini/antigravity'] })).args, ['acp', '--app-data-dir', 'antigravity']);
  assert.deepEqual(defaultAgyEntry(machine({ platform: 'win32', home: 'C:\\Users\\me', files: ['C:\\Users\\me\\.gemini\\antigravity'] })).args, ['acp', '--app-data-dir', 'antigravity']);
});

test('no agents in config: agy and a local Claude adapter -> both, agy first; adapter alone -> claude only', () => {
  const both = planAgents({}, machine({ cmds: { agy: '/usr/bin/agy', claude: '/usr/bin/claude' }, files: [ADAPTER] }));
  assert.deepEqual(ids(both), ['agy', 'claude']);
  const c = both.agents[1].def;
  assert.equal(c.command, process.execPath);
  assert.deepEqual(c.args, [ADAPTER]);
  assert.deepEqual(c.helper, [process.execPath, '/opt/leash/bridge/remote/helper.mjs']);
  assert.equal(c.env.CLAUDE_CODE_EXECUTABLE, '/usr/bin/claude');
  assert.deepEqual(c.envUnset, ['ANTHROPIC_API_KEY']);
  assert.deepEqual(ids(planAgents({}, machine({ files: [ADAPTER] }))), ['claude']);
});

test('no agents in config: nothing found -> one unavailable agy with an install hint (the bridge still runs)', () => {
  const plan = planAgents({}, machine());
  assert.deepEqual(ids(plan), ['agy!']);
  assert.match(plan.agents[0].hint, /agy not found: install the Antigravity CLI/);
  assert.match(plan.agents[0].hint, /claude-agent-acp/);
});

test('explicit config is authoritative: a missing command is unavailable, never dropped or replaced by detection', () => {
  const cfg = { agents: [{ id: 'agy', command: 'agy', args: ['acp'] }, { id: 'other', label: 'Other', command: '/nope/other', args: [] }] };
  const plan = planAgents(cfg, machine({ cmds: { agy: '/usr/bin/agy', claude: '/usr/bin/claude' }, files: [ADAPTER] }));
  assert.equal(plan.explicit, true);
  assert.deepEqual(ids(plan), ['agy', 'other!']);   // the detectable local claude is NOT added
  assert.match(plan.agents[1].hint, /command "\/nope\/other" not found.*config\.json/);
  assert.deepEqual(ids(planAgents({ agents: [{ id: 'agy', command: 'agy', args: [] }] }, machine())), ['agy!']);
});

test('explicit local claude without its adapter is unavailable with the npm hint', () => {
  const plan = planAgents({ agents: [localClaudeEntry()] }, machine());
  assert.equal(plan.agents[0].ok, false);
  assert.match(plan.agents[0].hint, /npm install --prefix ".*claude-acp" @agentclientprotocol\/claude-agent-acp/);
});

test('LEASH_AGENT_CMD replaces the first agent (tests, debugging)', () => {
  const plan = planAgents({}, machine({ env: { LEASH_AGENT_CMD: '/x/mock', LEASH_AGENT_ARGS: '["a"]' }, cmds: { '/x/mock': '/x/mock' }, files: ['/x/mock'] }));
  assert.equal(plan.agents[0].command, '/x/mock');
  assert.deepEqual(plan.agents[0].def.args, ['a']);
});

test('claude adapter search order: env dir, ~/.leash/claude-acp, next to the bridge, global command; never npx', () => {
  const rel = 'node_modules/@agentclientprotocol/claude-agent-acp/dist/index.js';
  assert.equal(findClaudeAdapter(machine({ env: { LEASH_CLAUDE_ACP_DIR: '/e' }, files: ['/e/' + rel, ADAPTER] })).path, '/e/' + rel);
  assert.equal(findClaudeAdapter(machine({ files: [ADAPTER, '/home/u/.leash/claude-acp/node_modules/@agentclientprotocol/claude-agent-acp/package.json', '/opt/leash/bridge/' + rel] })).version, '9.9.9');
  assert.equal(findClaudeAdapter(machine({ files: ['/opt/leash/bridge/' + rel] })).path, '/opt/leash/bridge/' + rel);
  assert.deepEqual(findClaudeAdapter(machine({ cmds: { 'claude-agent-acp': '/usr/local/bin/claude-agent-acp' } })), { kind: 'bin', path: '/usr/local/bin/claude-agent-acp', version: null });
  assert.equal(findClaudeAdapter(machine({ cmds: { npx: '/usr/bin/npx' } })), null);
  assert.equal(materialize(localClaudeEntry(), machine()).command, null);
});

test('claudeAgentConfig: the saved remote setup is unchanged; a fresh one carries no personal defaults', () => {
  // the entry an existing install already has in config.json (what claudeAgentConfig() produced before these args were required)
  const saved = { id: 'claude', label: 'Claude Code', command: 'zsh', args: ['-ic', 'ubuntu /config/workspace/Leash/bridge/remote/run-acp.sh'],
    helper: ['zsh', '-ic', 'ubuntu node /config/workspace/Leash/bridge/remote/helper.mjs "$@"', 'leash-helper'], home: '/config', quota: 'helper', maxListed: 200,
    sessionMeta: { claudeCode: { options: { settings: { env: { CLAUDE_CODE_EXPERIMENTAL_CODE_MODE: '1' } } } } } };
  assert.deepEqual(claudeAgentConfig({ shellFunction: 'ubuntu', repo: '/config/workspace/Leash', home: '/config', codeMode: true }), saved);
  const plan = planAgents({ agents: [saved] }, machine({ cmds: { zsh: '/bin/zsh' } }));
  assert.equal(plan.agents[0].ok, true);
  assert.deepEqual(plan.agents[0].def, saved);   // passes through untouched
  assert.deepEqual(claudeAgentConfig(), localClaudeEntry());
  assert.equal(JSON.stringify(claudeAgentConfig()).includes('/config'), false);
  assert.throws(() => claudeAgentConfig({ shellFunction: 'ubuntu' }), /--repo.*--home/);
  const viaSsh = claudeAgentConfig({ prefix: ['ssh', '-T', 'me@box'], repo: '/srv/Leash', home: '/home/me' });
  assert.deepEqual(viaSsh.args, ['-T', 'me@box', '/srv/Leash/bridge/remote/run-acp.sh']);
  assert.equal(viaSsh.sessionMeta, undefined);
});

test('loadConfig: a fresh install saves token/port/bind only (no agent entries); persist:false writes nothing', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'leash-cfg-'));
  const dry = loadConfig(dir, { persist: false });
  assert.equal(fs.existsSync(path.join(dir, 'config.json')), false);
  assert.ok(dry.token);
  const cfg = loadConfig(dir);
  const saved = JSON.parse(fs.readFileSync(path.join(dir, 'config.json'), 'utf8'));
  assert.deepEqual(Object.keys(saved).sort(), ['bind', 'port', 'token']);
  assert.equal(cfg.agents, undefined);
  // an existing config with agents is read as is and not rewritten
  const entry = { id: 'agy', command: 'agy', args: ['acp'] };
  fs.writeFileSync(path.join(dir, 'config.json'), JSON.stringify({ ...saved, agents: [entry], agent: { command: 'old' } }));
  const before = fs.readFileSync(path.join(dir, 'config.json'), 'utf8');
  assert.deepEqual(loadConfig(dir).agents, [entry]);
  assert.equal(fs.readFileSync(path.join(dir, 'config.json'), 'utf8'), before);
});

test('detectedEntries: what add-claude / remove-claude write when the config had no list', () => {
  const plan = planAgents({}, machine({ cmds: { agy: '/usr/bin/agy' }, files: [ADAPTER] }));
  assert.deepEqual(detectedEntries(plan), [{ id: 'agy', label: 'Antigravity', command: 'agy', args: ['acp'], quota: 'statusline' }, localClaudeEntry()]);
  assert.deepEqual(detectedEntries(planAgents({}, machine())), []);   // nothing found: nothing pinned
});

test('quota and busy lock: skipped with a reason where script / lsof / sqlite3 / the data dir are missing', () => {
  assert.equal(quotaSupport(machine({ cmds: { script: '/usr/bin/script' } })).ok, true);
  assert.match(quotaSupport(machine()).reason, /script/);
  assert.match(quotaSupport(machine({ platform: 'win32', cmds: { script: 'x' } })).reason, /Windows/);
  const all = { lsof: '/usr/sbin/lsof', ps: '/bin/ps', sqlite3: '/usr/bin/sqlite3' };
  assert.equal(lockSupport(machine({ cmds: all, files: ['/home/u/.gemini/antigravity'] })).ok, true);
  assert.match(lockSupport(machine({ cmds: { ps: 'x' }, files: ['/home/u/.gemini/antigravity'] })).reason, /lsof, sqlite3 not found/);
  assert.match(lockSupport(machine({ cmds: all })).reason, /does not exist/);
  assert.match(lockSupport(machine({ platform: 'win32', cmds: all })).reason, /Windows/);
});

test('diagnose / formatDiagnosis: versions, what is missing, next step', async () => {
  const d = machine({ cmds: { agy: '/usr/bin/agy' } });
  const r = await diagnose({ port: 8787, bind: 'auto' }, d, { probeVersion: async c => ({ out: c + ' 1.2.3' }), configExists: false,
    net: { port: 8787, bind: 'auto', hosts: ['127.0.0.1', '100.64.0.5'], tailscale: ['100.64.0.5'], dns: 'pc.tail.ts.net', lan: ['192.168.1.5'], portFree: true }, bark: 'off', deps: { ok: true } });
  assert.equal(r.ok, true);
  assert.equal(r.agents[0].version, '/usr/bin/agy 1.2.3');
  const text = formatDiagnosis(r);
  assert.match(text, /\[ok\]\s+agy/);
  assert.match(text, /Not detected \(optional\): claude/);
  assert.match(text, /100\.64\.0\.5 \(pc\.tail\.ts\.net\)/);
  assert.match(text, /node src\/index\.mjs pair/);
  const bad = await diagnose({ port: 1, bind: 'auto' }, machine(), { deps: { ok: false, hint: 'Packages are not installed: run npm install' } });
  assert.equal(bad.ok, false);
  assert.match(formatDiagnosis(bad), /MISSING[\s\S]*Packages are not installed[\s\S]*Not ready yet/);
  const win = formatDiagnosis(await diagnose({ port: 1, bind: 'auto' }, machine({ platform: 'win32', home: 'C:\\Users\\me', cmds: { agy: 'C:\\x\\agy.exe', pwsh: 'C:\\Program Files\\PowerShell\\7\\pwsh.exe' } }),
    { probeVersion: async () => ({ out: '1.0' }), net: { port: 1, bind: 'auto', hosts: [], tailscale: [], dns: null, lan: [], portFree: true } }));
  assert.match(win, /Windows Firewall/);
  assert.match(win, /Busy lock\s+off: .*Windows/);
  assert.match(win, /"!" shell\s+C:\\Program Files\\PowerShell\\7\\pwsh\.exe/);
});

// ---------------------------------------------------------------- hub / router with an unavailable agent

test('unavailable hub: down with the hint as its error, nothing spawned, no restart loop; Restart re-checks', async () => {
  const logs = [];
  let found = null;
  const hub = new Hub({ id: 'agy', agentCommand: 'agy-not-installed', agentArgs: ['acp'], log: m => logs.push(m), unavailable: 'agy not found: install it', recheck: () => found });
  const states = [];
  hub.attachClient({ id: 1, subs: new Set(), send: m => { if (m.op === 'agent') states.push([m.state, m.error]); } });
  await hub.startAgent();
  assert.equal(hub.agentState, 'down');
  assert.equal(hub.agent, null);
  assert.equal(hub.restartTimer, null);
  assert.deepEqual(states.at(-1), ['down', 'agy not found: install it']);
  assert.equal(hub.agentSummary().error, 'agy not found: install it');
  await hub.restartAgent();                                   // still missing: stays down, nothing spawned, no timer
  assert.equal(hub.agentState, 'down'); assert.equal(hub.restartTimer, null); assert.equal(hub.agent, null);
  found = { command: process.execPath, args: [MOCK] };       // installed meanwhile
  await hub.restartAgent();
  for (let i = 0; i < 100 && hub.agentState !== 'ready'; i++) await new Promise(r => setTimeout(r, 50));
  assert.equal(hub.agentState, 'ready');
  assert.equal(hub.unavailable, null);
  hub.stop();
  const h2 = new Hub({ id: 'x', agentCommand: 'x', log() {}, unavailable: 'gone' });
  await h2.startAgent();
  await assert.rejects(h2.handleCall({ subs: new Set() }, 'session/new', { cwd: '/tmp' }), /agent not ready \(down\): gone/);
});

test('router: the default agent is the first one that can run; the phone sees the missing one as down with its error', async () => {
  const missing = new Hub({ id: 'agy', agentCommand: 'x', log() {}, unavailable: 'agy not found' });
  const ok = new Hub({ id: 'claude', agentCommand: process.execPath, agentArgs: [MOCK], log() {} });
  const router = new Router({ hubs: [missing, ok], log() {} });
  assert.equal(router.defaultId, 'claude');
  await Promise.all([missing.startAgent(), ok.startAgent()]);
  const info = router.info();
  assert.deepEqual(info.agents.map(a => [a.id, a.state]), [['agy', 'down'], ['claude', 'ready']]);
  assert.equal(info.agents[0].error, 'agy not found');
  assert.equal(info.agent.state, 'ready');
  const ns = await router.handleCall({ subs: new Set() }, 'session/new', { cwd: os.tmpdir() });
  assert.equal(router.owner.get(ns.sessionId), 'claude');
  ok.stop();
  assert.equal(new Router({ hubs: [missing], log() {} }).defaultId, 'agy');   // nothing available: still answers
});

// ---------------------------------------------------------------- the real entry point

const freePort = () => new Promise(res => { const s = net.createServer().listen(0, '127.0.0.1', () => { const p = s.address().port; s.close(() => res(p)); }); });
function runIndex(args, env, { wait = false } = {}) {
  const child = spawn(process.execPath, [path.join(here, '..', 'src', 'index.mjs'), ...args], { env: { ...process.env, ...env }, stdio: ['ignore', 'pipe', 'pipe'] });
  let out = '';
  child.stdout.on('data', x => { out += x; }); child.stderr.on('data', x => { out += x; });
  const done = new Promise(res => child.on('exit', code => res({ code, out })));
  return { child, done, output: () => out };
}

test('index.mjs serve: explicit config with a missing agent -> reported once, shown as down, other agent works, no restart spam', async () => {
  const home = fs.mkdtempSync(path.join(os.tmpdir(), 'leash-e2e-'));
  const port = await freePort();
  const missing = path.join(home, 'no-such-agent');
  fs.writeFileSync(path.join(home, 'config.json'), JSON.stringify({ token: 'tok', port, bind: '127.0.0.1', agents: [
    { id: 'gone', label: 'Gone', command: missing, args: [] }, { id: 'agy', label: 'Mock', command: process.execPath, args: [MOCK] }] }));
  const before = fs.readFileSync(path.join(home, 'config.json'), 'utf8');
  const p = runIndex([], { LEASH_HOME: home, HOME: home, USERPROFILE: home });
  try {
    let ws, ready;
    for (let i = 0; i < 50 && !ready; i++) {   // the bridge needs a moment to listen
      ws = new WebSocket('ws://127.0.0.1:' + port + '/ws', { headers: { authorization: 'Bearer tok', 'x-leash-client': 'test' } });
      ready = await new Promise(res => { ws.on('message', d => { const m = JSON.parse(String(d)); if (m.op === 'ready') res(m); }); ws.on('error', () => res(null)); });
      if (!ready) await new Promise(r => setTimeout(r, 100));
    }
    assert.ok(ready, p.output());
    assert.deepEqual(ready.agents.map(a => a.id), ['gone', 'agy']);
    const gone = ready.agents.find(a => a.id === 'gone');
    assert.equal(gone.state, 'down');
    assert.match(gone.error, /command ".*no-such-agent" not found/);
    await new Promise(r => setTimeout(r, 2500));   // a crash loop would have restarted by now
    const list = await new Promise(res => { ws.on('message', d => { const m = JSON.parse(String(d)); if (m.op === 'result' && m.id === 1) res(m.result); }); ws.send(JSON.stringify({ op: 'call', id: 1, method: 'bridge/agents', params: {} })); });
    assert.equal(list.agents.find(a => a.id === 'agy').state, 'ready');
    assert.equal(list.agents.find(a => a.id === 'gone').state, 'down');
    const out = p.output();
    assert.equal(out.split('agent gone unavailable').length - 1, 1, out);
    assert.equal((out.match(/agent spawn error/g) ?? []).length, 0, out);
    assert.equal(fs.readFileSync(path.join(home, 'config.json'), 'utf8'), before, 'config untouched');
    ws.close();
  } finally { p.child.kill('SIGTERM'); await p.done; }
});

test('index.mjs: doctor writes nothing and exits 1 when no agent can run; add-claude is local by default, remote needs explicit args', async () => {
  // what the machine has installed is not controlled here (PATH is only part of the search), so the assertions hold either way
  const home = fs.mkdtempSync(path.join(os.tmpdir(), 'leash-fresh-'));
  const env = { LEASH_HOME: path.join(home, '.leash'), HOME: home, USERPROFILE: home, LEASH_AGENT_CMD: '', LEASH_PORT: String(await freePort()) };
  const doctor = await runIndex(['doctor'], env).done;
  assert.match(doctor.out, /Leash bridge doctor/);
  assert.equal(fs.existsSync(path.join(home, '.leash', 'config.json')), false, 'doctor must not create the config');
  const bad = await runIndex(['add-claude', '--fn', 'ubuntu'], env).done;
  assert.equal(bad.code, 2); assert.match(bad.out, /--repo/);
  assert.equal(JSON.parse(fs.readFileSync(path.join(home, '.leash', 'config.json'), 'utf8')).agents, undefined, 'a refused add-claude writes no agents');
  const add = await runIndex(['add-claude'], env).done;
  assert.equal(add.code, 0, add.out);
  const raw = fs.readFileSync(path.join(home, '.leash', 'config.json'), 'utf8');
  assert.deepEqual(JSON.parse(raw).agents.at(-1), localClaudeEntry());
  assert.equal(raw.includes('/config/workspace') || raw.includes('ubuntu'), false);
});

test('index.mjs doctor: exit 1 and the reason when the configured agent is not installed', async () => {
  const home = fs.mkdtempSync(path.join(os.tmpdir(), 'leash-doc-'));
  fs.writeFileSync(path.join(home, 'config.json'), JSON.stringify({ token: 't', port: await freePort(), bind: '127.0.0.1', agents: [{ id: 'agy', command: path.join(home, 'no-agy'), args: ['acp'] }] }));
  const r = await runIndex(['doctor'], { LEASH_HOME: home, HOME: home, USERPROFILE: home }).done;
  assert.equal(r.code, 1, r.out);
  assert.match(r.out, /\[missing\]\s+agy/);
  assert.match(r.out, /Not ready yet/);
});

test('index.mjs: LEASH_LOG_FILE gets the console output too (the Windows scheduled task has no console)', async () => {
  const home = fs.mkdtempSync(path.join(os.tmpdir(), 'leash-log-'));
  const logFile = path.join(home, 'bridge.log');
  const port = await freePort();
  fs.writeFileSync(path.join(home, 'config.json'), JSON.stringify({ token: 'tok', port, bind: '127.0.0.1', agents: [{ id: 'agy', command: process.execPath, args: [MOCK] }] }));
  const p = runIndex([], { LEASH_HOME: home, HOME: home, LEASH_LOG_FILE: logFile });
  try {
    for (let i = 0; i < 50 && !(fs.existsSync(logFile) && /listening on/.test(fs.readFileSync(logFile, 'utf8'))); i++) await new Promise(r => setTimeout(r, 100));
    assert.match(fs.readFileSync(logFile, 'utf8'), /^\d{4}-\d\d-\d\d \d\d:\d\d:\d\d agents from config: agy\n/);
  } finally { p.child.kill('SIGTERM'); await p.done; }
});

test('AcpProcess reads CRLF-terminated JSON lines (Windows agents may print them)', async () => {
  const { AcpProcess } = await import('../src/acp.mjs');
  const script = "process.stdin.once('data',()=>{process.stdout.write(JSON.stringify({jsonrpc:'2.0',id:1,result:{ok:true}})+'\\r\\n')})";
  const a = new AcpProcess({ command: process.execPath, args: ['-e', script], env: process.env });
  a.start();
  assert.deepEqual(await a.call('ping', {}, { timeoutMs: 5000 }), { ok: true });
  a.stop();
});
