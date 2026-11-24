import { test } from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import os from 'node:os';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import WebSocket from 'ws';
import { Hub, compactEvents, normalizeUpdate, fitSnapshot } from '../src/hub.mjs';
import { QuotaPoller, normalizeQuota } from '../src/quota.mjs';
import { Router } from '../src/router.mjs';
import { createServer } from '../src/server.mjs';
import { agyLocks, parsePresenceLsof, parseSummaries } from '../src/agylock.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const TOKEN = 'test-token-123';

class Client {
  constructor(port, token = TOKEN) {
    this.msgs = []; this.waiters = []; this.id = 1;
    this.ws = new WebSocket(`ws://127.0.0.1:${port}/ws`, { headers: { authorization: 'Bearer ' + token, 'x-leash-client': 'test' } });
    this.ws.on('message', d => { const m = JSON.parse(String(d)); this.msgs.push(m); this.#check(); });
    this.opened = new Promise((res, rej) => { this.ws.once('open', res); this.ws.once('error', rej); this.ws.once('unexpected-response', (_q, r) => rej(new Error('HTTP ' + r.statusCode))); });
  }
  #check() { for (const w of [...this.waiters]) { const i = this.msgs.findIndex(w.pred); if (i >= 0) { this.waiters.splice(this.waiters.indexOf(w), 1); w.res(this.msgs[i]); } } }
  wait(pred, ms = 3000) { return new Promise((res, rej) => { const w = { pred, res }; this.waiters.push(w); this.#check(); setTimeout(() => rej(new Error('wait timeout')), ms); }); }
  async call(method, params) { const id = this.id++; this.ws.send(JSON.stringify({ op: 'call', id, method, params })); const m = await this.wait(x => x.op === 'result' && x.id === id, 5000); if (m.error) throw Object.assign(new Error(m.error.message), m.error); return m.result; }
  reply(key, result) { this.ws.send(JSON.stringify({ op: 'reply', key, result })); }
  close() { this.ws.close(); }
}

async function setup(extra = {}) {
  const home = fs.mkdtempSync(path.join(os.tmpdir(), 'agr-home-'));
  fs.mkdirSync(path.join(home, 'proj', 'src'), { recursive: true });
  fs.writeFileSync(path.join(home, 'proj', 'README.md'), '# hi\n');
  const hub = new Hub({ agentCommand: process.execPath, agentArgs: [path.join(here, 'mock-agent.mjs')], log: () => {}, home, ...extra });
  const server = createServer({ hub, token: TOKEN, log: () => {} });
  const srv = await server.listen('127.0.0.1', 0);
  await hub.startAgent();
  const port = srv.address().port;
  return { hub, server, port, home, done: () => { hub.stop(); server.close(); } };
}

test('rejects bad token', async () => {
  const t = await setup();
  try {
    const c = new Client(t.port, 'nope');
    await assert.rejects(c.opened, /401/);
  } finally { t.done(); }
});

test('ready, list, attach old session with compacted history', async () => {
  const t = await setup();
  const c = new Client(t.port);
  try {
    await c.opened;
    const ready = await c.wait(m => m.op === 'ready');
    assert.equal(ready.bridge.protocol, 1);
    const list = await c.call('session/list', {});
    assert.ok(list.sessions.some(s => s.sessionId === 'old-1'));
    const a = await c.call('bridge/attach', { sessionId: 'old-1' });
    assert.equal(a.reset, true);
    assert.equal(a.events.length, 2, 'two agent chunks merged');
    assert.equal(a.events[1].update.content.text, 'Hi! How can I help?');
    assert.equal(a.cwd, '/tmp/proj');
    // delta attach returns nothing new
    const a2 = await c.call('bridge/attach', { sessionId: 'old-1', epoch: a.epoch, sinceSeq: a.lastSeq });
    assert.equal(a2.reset, false); assert.equal(a2.events.length, 0);
  } finally { c.close(); t.done(); }
});

test('new session, prompt with permission answered by second device, multi-client fan-out', async () => {
  const t = await setup();
  const phone = new Client(t.port), ipad = new Client(t.port);
  try {
    await phone.opened; await ipad.opened;
    const ns = await phone.call('session/new', { cwd: path.join(t.home, 'proj') });
    assert.ok(ns.sessionId);
    assert.equal(ns.configOptions[0].id, 'model');
    await ipad.call('bridge/attach', { sessionId: ns.sessionId });
    const promptP = phone.call('session/prompt', { sessionId: ns.sessionId, prompt: [{ type: 'text', text: 'please edit' }] });
    const turnStart = await ipad.wait(m => m.op === 'turn' && m.running === true);
    assert.equal(turnStart.sessionId, ns.sessionId);
    const req = await ipad.wait(m => m.op === 'request' && m.method === 'session/request_permission');
    assert.equal(req.params.options.length, 3);
    ipad.reply(req.key, { outcome: { outcome: 'selected', optionId: 'allow' } });
    await phone.wait(m => m.op === 'resolved' && m.key === req.key);
    const r = await promptP;
    assert.equal(r.stopReason, 'end_turn');
    await ipad.wait(m => m.op === 'turn' && m.running === false && m.stopReason === 'end_turn');
    // ipad saw the synthetic user message and the tool call update
    const ups = ipad.msgs.filter(m => m.op === 'update').map(m => m.update);
    assert.ok(ups.some(u => u.sessionUpdate === 'user_message_chunk' && u.content.text === 'please edit'));
    assert.ok(ups.some(u => u.sessionUpdate === 'tool_call_update' && u.status === 'completed'));
    // fresh device gets a compacted snapshot: tool_call merged with its update
    const late = new Client(t.port); await late.opened;
    const snap = await late.call('bridge/attach', { sessionId: ns.sessionId });
    const tool = snap.events.find(e => e.update.sessionUpdate === 'tool_call');
    assert.equal(tool.update.status, 'completed');
    assert.ok(!snap.events.some(e => e.update.sessionUpdate === 'tool_call_update'));
    const texts = snap.events.filter(e => e.update.sessionUpdate === 'agent_message_chunk').map(e => e.update.content.text).join('|');
    assert.equal(texts, 'Sure, editing the file.| Done.', 'text on either side of a tool call stays separate');
    late.close();
  } finally { phone.close(); ipad.close(); t.done(); }
});

test('reconnect delta via epoch/sinceSeq', async () => {
  const t = await setup();
  const c = new Client(t.port);
  try {
    await c.opened;
    const ns = await c.call('session/new', { cwd: t.home });
    const a = await c.call('bridge/attach', { sessionId: ns.sessionId });
    c.close();
    const c2 = new Client(t.port); await c2.opened;
    // prompt from c2 while c (old) is gone
    await c2.call('bridge/attach', { sessionId: ns.sessionId });
    await c2.call('session/prompt', { sessionId: ns.sessionId, prompt: [{ type: 'text', text: 'hi' }] });
    const d = await c2.call('bridge/attach', { sessionId: ns.sessionId, epoch: a.epoch, sinceSeq: a.lastSeq });
    assert.equal(d.reset, false);
    assert.ok(d.events.length >= 5);
    assert.equal(d.events[0].seq, a.lastSeq + 1);
    c2.close();
  } finally { t.done(); }
});

test('cancel resolves pending permission and stops the turn', async () => {
  const t = await setup();
  const c = new Client(t.port);
  try {
    await c.opened;
    const ns = await c.call('session/new', { cwd: t.home });
    const p = c.call('session/prompt', { sessionId: ns.sessionId, prompt: [{ type: 'text', text: 'edit please' }] });
    const req = await c.wait(m => m.op === 'request');
    await c.call('session/cancel', { sessionId: ns.sessionId });
    const res = await c.wait(m => m.op === 'resolved' && m.key === req.key);
    assert.equal(res.reason, 'cancelled');
    assert.equal((await p).stopReason, 'cancelled');
    assert.equal(t.hub.pending.size, 0);
  } finally { c.close(); t.done(); }
});

test('config option change, browse and readFile sandboxed to home', async () => {
  const t = await setup();
  const c = new Client(t.port);
  try {
    await c.opened;
    const ns = await c.call('session/new', { cwd: t.home });
    const r = await c.call('session/set_config_option', { sessionId: ns.sessionId, configId: 'model', value: 'gemini-4-carbon' });
    assert.equal(r.configOptions[0].currentValue, 'gemini-4-carbon');
    await c.wait(m => m.op === 'update' && m.update.sessionUpdate === 'config_option_update');
    const b = await c.call('bridge/browse', {});
    assert.deepEqual(b.dirs, ['proj']);
    const b2 = await c.call('bridge/browse', { path: '~/proj' });
    assert.deepEqual(b2.files, ['README.md']);
    const f = await c.call('bridge/readFile', { path: path.join(t.home, 'proj', 'README.md') });
    assert.equal(f.text, '# hi\n');
    await assert.rejects(c.call('bridge/browse', { path: '/etc' }), /outside home/);
    await assert.rejects(c.call('bridge/readFile', { path: t.home + '/../../etc/passwd' }), /outside home/);
    await assert.rejects(c.call('initialize', {}), /not allowed/);
  } finally { c.close(); t.done(); }
});

test('attach returns the latest command list after the event log trimmed it', async () => {
  const t = await setup({ maxEvents: 3 });
  const c = new Client(t.port);
  try {
    await c.opened;
    await c.wait(m => m.op === 'ready');
    const ns = await c.call('session/new', { cwd: t.home });
    await c.call('bridge/attach', { sessionId: ns.sessionId });
    await c.call('session/prompt', { sessionId: ns.sessionId, prompt: [{ type: 'text', text: 'commands please' }] });
    const a = await c.call('bridge/attach', { sessionId: ns.sessionId });
    assert.equal(a.reset, true);
    assert.ok(!a.events.some(e => e.update.sessionUpdate === 'available_commands_update'), 'trimmed from the log');
    assert.deepEqual(a.availableCommands, [{ name: 'review', description: 'Review a PR', input: { hint: 'pr-number' } }]);
  } finally { c.close(); t.done(); }
});

test('agent crash: restarts and session can be re-attached', async () => {
  const t = await setup();
  const c = new Client(t.port);
  try {
    await c.opened;
    const a = await c.call('bridge/attach', { sessionId: 'old-1' });
    t.hub.agent.child.kill('SIGKILL');
    await c.wait(m => m.op === 'agent' && m.state === 'down');
    await c.wait(m => m.op === 'agent' && m.state === 'ready', 5000);
    const before = c.msgs.length;
    const a2 = await c.call('bridge/attach', { sessionId: 'old-1', epoch: a.epoch, sinceSeq: a.lastSeq });
    assert.equal(a2.reset, true, 'new epoch after reload');
    assert.notEqual(a2.epoch, a.epoch);
    assert.equal(a2.events.length, 2);
    const replayed = c.msgs.slice(before).filter(m => m.op === 'update' && m.sessionId === 'old-1');
    assert.equal(replayed.length, 0, 'replayed history must not be fanned out as live updates');
  } finally { c.close(); t.done(); }
});

test('turbo auto-approves permissions; turn stats recorded', async () => {
  const t = await setup();
  const c = new Client(t.port);
  try {
    await c.opened;
    const ns = await c.call('session/new', { cwd: t.home });
    const fl = await c.call('bridge/setAutoApprove', { sessionId: ns.sessionId, enabled: true });
    assert.equal(fl.autoApprove, true);
    const r = await c.call('session/prompt', { sessionId: ns.sessionId, prompt: [{ type: 'text', text: 'edit it' }] });
    assert.equal(r.stopReason, 'end_turn');
    assert.ok(!c.msgs.some(m => m.op === 'request'), 'no prompt reached the phone');
    const ups = c.msgs.filter(m => m.op === 'update').map(m => m.update);
    assert.ok(ups.some(u => u.sessionUpdate === 'leash_auto_approved'));
    assert.ok(ups.some(u => u.sessionUpdate === 'tool_call_update' && u.status === 'completed'));
    const st = ups.find(u => u.sessionUpdate === 'leash_turn_stats');
    assert.equal(st.usage.outputTokens, 40);
    assert.equal(st.model, 'Gemini 4 Hydrogen');
    assert.ok(st.durationMs >= 0);
    const a = await c.call('bridge/attach', { sessionId: ns.sessionId });
    assert.equal(a.autoApprove, true);
  } finally { c.close(); t.done(); }
});

test('normalizeUpdate maps agy tool_call name/args to title/rawInput', () => {
  const u = normalizeUpdate({ sessionUpdate: 'tool_call', toolCallId: 'c1', name: 'run_command', args: { CommandLine: 'git status', toolSummary: 'Check git status' } });
  assert.equal(u.title, 'Check git status'); assert.equal(u.kind, 'execute'); assert.equal(u.rawInput.CommandLine, 'git status');
  const v = normalizeUpdate({ sessionUpdate: 'tool_call', toolCallId: 'c2', name: 'view_file', args: { AbsolutePath: '/a' } });
  assert.equal(v.title, 'View file'); assert.equal(v.kind, 'read');
  const w = normalizeUpdate({ sessionUpdate: 'tool_call', toolCallId: 'c3', title: 'Std', kind: 'edit' });
  assert.equal(w.title, 'Std');
});

test('quota poller reads agy statusLine quota via a pty and kills the TUI', async () => {
  const work = fs.mkdtempSync(path.join(os.tmpdir(), 'agr-quota-'));
  const fake = path.join(here, 'fake-agy-statusline.mjs');
  fs.chmodSync(fake, 0o755);
  let pushed = null;
  const p = new QuotaPoller({ agentCommand: fake, workDir: work, timeoutMs: 8000, onUpdate: q => { pushed = q; } });
  const q = await p.refresh();
  assert.equal(q.planTier, 'Google AI Pro');
  assert.deepEqual(q.pools.map(x => x.label), ['Gemini', 'Claude & GPT', 'Argon']);
  assert.deepEqual(q.pools[0].buckets.map(b => [b.window, b.remainingFraction]), [['5h', 0.69], ['weekly', 0.88]]);
  assert.ok(!JSON.stringify(q).includes('example.com'), 'email never leaves the Mac');
  assert.equal(pushed, q);
  const again = await p.refresh({ maxAgeMs: 60000 });
  assert.equal(again, q, 'cached');
});

test('quota poller failure reports exit code and TUI text (email redacted)', async () => {
  const work = fs.mkdtempSync(path.join(os.tmpdir(), 'agr-quota-'));
  const fake = path.join(work, 'dying-agy.sh');
  fs.writeFileSync(fake, '#!/bin/sh\nprintf "\\033[31mError: not signed in as me@example.com\\033[0m\\r\\n"\nexit 3\n', { mode: 0o755 });
  const p = new QuotaPoller({ agentCommand: fake, workDir: work, timeoutMs: 8000 });
  await assert.rejects(p.refresh(), e => {
    assert.match(e.message, /agy exited without statusLine output/);
    assert.match(e.message, /Error: not signed in as <email>/);
    assert.ok(!e.message.includes('example.com'));
    return true;
  });
});

test('normalizeQuota ignores disabled buckets', () => {
  const q = normalizeQuota({ quota: { 'x-5h': { remaining_fraction: 0.5, disabled: true }, 'x-weekly': { remaining_fraction: 0.2 } } });
  assert.equal(q.pools.length, 1); assert.equal(q.pools[0].buckets.length, 1); assert.equal(q.pools[0].buckets[0].window, 'weekly');
});

async function setupMulti() {
  const home = fs.mkdtempSync(path.join(os.tmpdir(), 'agr-home-'));
  const mk = (id, label) => new Hub({ id, label, agentCommand: process.execPath, agentArgs: [path.join(here, 'mock-agent.mjs')], log: () => {}, home,
    sessionMeta: id === 'claude' ? { claudeCode: { options: { settings: { env: { X: '1' } } } } } : null });
  const agy = mk('agy', 'Antigravity'), claude = mk('claude', 'Claude Code');
  const router = new Router({ hubs: [agy, claude] });
  const server = createServer({ hub: router, token: TOKEN, log: () => {} });
  const srv = await server.listen('127.0.0.1', 0);
  await Promise.all([agy.startAgent(), claude.startAgent()]);
  return { agy, claude, router, port: srv.address().port, done: () => { router.stop(); server.close(); } };
}

test('router: ready lists agents; merged list tagged by agent; calls routed by agent/session', async () => {
  const t = await setupMulti();
  const c = new Client(t.port);
  try {
    await c.opened;
    const ready = await c.wait(m => m.op === 'ready');
    assert.deepEqual(ready.agents.map(a => a.id), ['agy', 'claude']);
    const list = await c.call('session/list', {});
    assert.equal(list.sessions.length, 2, 'old-1 from each mock agent');
    assert.deepEqual(list.sessions.map(s => s._leash.agent).sort(), ['agy', 'claude']);
    const ns = await c.call('session/new', { agent: 'claude', cwd: '/tmp' });
    assert.ok(t.claude.sessions.has(ns.sessionId));
    assert.ok(!t.agy.sessions.has(ns.sessionId));
    // session-scoped call without agent goes to the owner
    const p = c.call('session/prompt', { sessionId: ns.sessionId, prompt: [{ type: 'text', text: 'edit it' }] });
    const req = await c.wait(m => m.op === 'request');
    assert.equal(req.agentId, 'claude');
    assert.ok(req.key.startsWith('claude:'));
    c.reply(req.key, { outcome: { outcome: 'selected', optionId: 'allow' } });
    assert.equal((await p).stopReason, 'end_turn');
    const up = c.msgs.find(m => m.op === 'update' && m.sessionId === ns.sessionId);
    assert.equal(up.agentId, 'claude');
  } finally { c.close(); t.done(); }
});

test('idle sessions with no watchers get closed', async () => {
  const t = await setup();
  const c = new Client(t.port);
  try {
    await c.opened;
    const ns = await c.call('session/new', { cwd: t.home });
    assert.deepEqual(await t.hub.closeIdleSessions(Date.now() + 3600e3), [], 'still watched');
    await c.call('bridge/detach', { sessionId: ns.sessionId });
    const closed = await t.hub.closeIdleSessions(Date.now() + 3600e3);
    assert.deepEqual(closed, [ns.sessionId]);
    assert.equal(t.hub.sessions.get(ns.sessionId).loaded, false);
  } finally { c.close(); t.done(); }
});

test('helper args survive an ssh-like shell re-join (spaces, $(…), ;)', async () => {
  const home = fs.mkdtempSync(path.join(os.tmpdir(), 'agr-esc-'));
  const evil = path.join(home, '$(touch pwned); x');
  fs.mkdirSync(path.join(home, 'a b'));
  fs.mkdirSync(evil);
  fs.writeFileSync(path.join(evil, 'f.txt'), 'ok');
  const helperJs = path.join(here, '..', 'remote', 'helper.mjs');
  // like ssh: all args are joined with spaces and evaluated by a remote shell; HOME = tmp dir
  const h = new Hub({ id: 'claude', agentCommand: 'x', helper: ['bash', '-c', `cd "${home}" && HOME="${home}" eval "node ${helperJs} $*"`, 'agr'] });
  const b = await h.runHelper(['browse', home]);
  assert.deepEqual(b.dirs.sort(), ['$(touch pwned); x', 'a b'].sort());
  const f = await h.runHelper(['read', path.join(evil, 'f.txt')]);
  assert.equal(f.text, 'ok');
  assert.ok(!fs.existsSync(path.join(home, 'pwned')), 'nothing executed');
});

test('router prefers the hub that owns a session over params.agent', async () => {
  const t = await setupMulti();
  const c = new Client(t.port);
  try {
    await c.opened;
    const ns = await c.call('session/new', { agent: 'claude', cwd: '/tmp' });
    const r = await c.call('bridge/attach', { sessionId: ns.sessionId, agent: 'agy' });  // wrong guess
    assert.equal(r.sessionId, ns.sessionId);
    assert.ok(t.claude.sessions.get(ns.sessionId).subscribers.size > 0);
  } finally { c.close(); t.done(); }
});

test('hub merges sessionMeta into session/new and session/load', () => {
  const h = new Hub({ id: 'claude', agentCommand: 'x', sessionMeta: { claudeCode: { options: { settings: { env: { A: '1' } } } } } });
  const p = h.withSessionMeta({ cwd: '/x', _meta: { claudeCode: { options: { model: 'opus' } } } });
  assert.deepEqual(p._meta, { claudeCode: { options: { model: 'opus', settings: { env: { A: '1' } } } } });
});

test('compactEvents merges by messageId only', () => {
  const ev = [
    { seq: 1, update: { sessionUpdate: 'agent_message_chunk', messageId: 'a', content: { type: 'text', text: 'x' } } },
    { seq: 2, update: { sessionUpdate: 'agent_message_chunk', messageId: 'b', content: { type: 'text', text: 'y' } } },
    { seq: 3, update: { sessionUpdate: 'agent_message_chunk', messageId: 'b', content: { type: 'text', text: 'z' } } },
  ];
  const c = compactEvents(ev);
  assert.equal(c.length, 2); assert.equal(c[1].update.content.text, 'yz'); assert.equal(c[1].seq, 3);
  assert.equal(ev[1].update.content.text, 'y', 'input not mutated');
});


test('normalizeUpdate drops rawOutput a tool call also has as content', () => {
  const img = { type: 'content', content: { type: 'image', mimeType: 'image/png', data: 'AAAA' } };
  const u = normalizeUpdate({ sessionUpdate: 'tool_call_update', toolCallId: 't', title: 'Poll', content: [img], rawOutput: [img.content] });
  assert.equal(u.rawOutput, undefined); assert.deepEqual(u.content, [img]);
  const bare = normalizeUpdate({ sessionUpdate: 'tool_call_update', toolCallId: 't', title: 'Bash', rawOutput: 'out' });
  assert.equal(bare.rawOutput, 'out', 'kept when it is all there is');
});

test('fitSnapshot keeps an image-heavy history under the budget, oldest pictures first', () => {
  const pic = 'A'.repeat(400_000);
  const ev = [];
  for (let i = 1; i <= 100; i++) ev.push({ seq: i, update: { sessionUpdate: 'tool_call', toolCallId: 't' + i, title: 'Poll', status: 'completed',
    content: [{ type: 'content', content: { type: 'text', text: 'shot ' + i } }, { type: 'content', content: { type: 'image', mimeType: 'image/png', data: pic } }] } });
  ev.push({ seq: 101, update: { sessionUpdate: 'agent_message_chunk', content: { type: 'text', text: 'done' } } });
  const budget = 10_000_000;
  const out = fitSnapshot(ev, budget);
  assert.ok(Buffer.byteLength(JSON.stringify(out)) <= budget);
  assert.equal(out.length, ev.length, 'no history dropped');
  assert.equal(out[0].update.content[1].content.type, 'text', 'oldest picture replaced');
  assert.match(out[0].update.content[1].content.text, /not sent to the phone/);
  assert.equal(out[99].update.content[1].content.type, 'image', 'newest picture kept');
  assert.equal(ev[0].update.content[1].content.type, 'image', 'input not mutated');
  assert.equal(fitSnapshot(ev.slice(-3), budget).length, 3);
  assert.equal(fitSnapshot(ev.slice(-3), budget)[0], ev[98], 'small histories pass through untouched');
  // nothing left to shrink: the start goes
  const texts = Array.from({ length: 50 }, (_, i) => ({ seq: i + 1, update: { sessionUpdate: 'agent_message_chunk', messageId: 'm' + i, content: { type: 'text', text: 'x'.repeat(100_000) } } }));
  const cut = fitSnapshot(texts, 1_000_000);
  assert.ok(Buffer.byteLength(JSON.stringify(cut)) <= 1_000_000); assert.equal(cut.at(-1).seq, 50);
});


// ---------------------------------------------------------------- "open elsewhere" lock
function lockState() {
  const st = { now: 1000, held: [], touched: {}, fail: false };
  st.fn = async () => { if (st.fail) throw new Error('ssh down'); return { now: st.now, held: st.held, touched: st.touched }; };
  return st;
}
const desktop = sid => ({ sessionId: sid, pid: 4242, entrypoint: 'cli', kind: 'interactive', status: 'idle' });

test('lock: a session open in a desktop claude cannot be attached (not even loaded)', async () => {
  const st = lockState();
  const t = await setup({ locks: st.fn, locksMaxAgeMs: 0 });
  const c = new Client(t.port);
  try {
    await c.opened;
    st.held = [desktop('old-1')];
    await assert.rejects(c.call('bridge/attach', { sessionId: 'old-1' }), e => e.code === -32020 && /terminal/.test(e.message) && e.data?.holders?.[0]?.pid === 4242);
    assert.ok(!t.hub.sessions.get('old-1')?.loaded, 'never loaded');
    const list = await c.call('session/list', {});
    assert.equal(list.sessions.find(s => s.sessionId === 'old-1')._leash.lockedBy, 'cli');
    st.held = [];
    const r = await c.call('bridge/attach', { sessionId: 'old-1' });
    assert.equal(r.events.length, 2);
  } finally { c.close(); t.done(); }
});

test('lock: opening on the desktop later blocks prompts and drops our copy', async () => {
  const st = lockState();
  const t = await setup({ locks: st.fn, locksMaxAgeMs: 0 });
  const c = new Client(t.port);
  try {
    await c.opened;
    await c.call('bridge/attach', { sessionId: 'old-1' });
    st.held = [desktop('old-1')];
    await assert.rejects(c.call('session/prompt', { sessionId: 'old-1', prompt: [{ type: 'text', text: 'hi' }] }), e => e.code === -32020);
    assert.equal(t.hub.sessions.get('old-1').loaded, false, 'released');
    assert.equal(t.hub.sessions.get('old-1').running, false);
  } finally { c.close(); t.done(); }
});

test('lock: desktop opened and closed it since we loaded -> prompt refused once, re-attach reloads', async () => {
  const st = lockState();
  const t = await setup({ locks: st.fn, locksMaxAgeMs: 0 });
  const c = new Client(t.port);
  try {
    await c.opened;
    const a1 = await c.call('bridge/attach', { sessionId: 'old-1' });
    st.touched = { 'old-1': 2000 }; st.now = 3000;     // a desktop process (now dead) used it after our load at 1000
    await assert.rejects(c.call('session/prompt', { sessionId: 'old-1', prompt: [{ type: 'text', text: 'hi' }] }), e => e.code === -32021);
    const a2 = await c.call('bridge/attach', { sessionId: 'old-1', epoch: a1.epoch, sinceSeq: a1.lastSeq });
    assert.equal(a2.reset, true, 'fresh snapshot');
    assert.notEqual(a2.epoch, a1.epoch);
    const r = await c.call('session/prompt', { sessionId: 'old-1', prompt: [{ type: 'text', text: 'hi' }] });
    assert.equal(r.stopReason, 'end_turn');
  } finally { c.close(); t.done(); }
});

test('lock: a failing check fails open; hubs without a helper have no lock', async () => {
  const st = lockState(); st.fail = true;
  const t = await setup({ locks: st.fn, locksMaxAgeMs: 0 });
  const c = new Client(t.port);
  try {
    await c.opened;
    const r = await c.call('bridge/attach', { sessionId: 'old-1' });
    assert.equal(r.sessionId, 'old-1');
  } finally { c.close(); t.done(); }
  assert.equal(new Hub({ agentCommand: 'x' }).locksFn, null);
  assert.equal(new Hub({ agentCommand: 'x', helper: ['true'], locks: false }).locksFn, null);
  assert.equal(typeof new Hub({ agentCommand: 'x', helper: ['true'] }).locksFn, 'function');
});

test('helper locks: live busy foreign claude only (idle, dead, reused pid, sdk-* ignored)', { skip: process.platform !== 'linux' }, async () => {
  const home = fs.mkdtempSync(path.join(os.tmpdir(), 'agr-locks-'));
  const dir = path.join(home, '.claude', 'sessions'); fs.mkdirSync(dir, { recursive: true });
  const stat = fs.readFileSync('/proc/self/stat', 'utf8');
  const start = stat.slice(stat.lastIndexOf(')') + 2).split(' ')[19];
  const dom = 'linux:test:' + fs.readlinkSync('/proc/self/ns/pid');
  const w = (name, j) => fs.writeFileSync(path.join(dir, name + '.json'), JSON.stringify({ pidDomain: dom, kind: 'interactive', startedAt: 10, updatedAt: 20, ...j }));
  w(String(process.pid), { pid: process.pid, procStart: start, sessionId: 'live', entrypoint: 'cli', status: 'busy' });
  w('4', { pid: process.pid, procStart: start, sessionId: 'openidle', entrypoint: 'claude-desktop', status: 'idle' });
  w('999999', { pid: 999999, procStart: '1', sessionId: 'dead', entrypoint: 'claude-desktop', updatedAt: 50 });
  w('1', { pid: process.pid, procStart: '1', sessionId: 'reused', entrypoint: 'cli' });
  w('2', { pid: process.pid, procStart: start, sessionId: 'ours', entrypoint: 'sdk-ts' });
  w('3', { pid: process.pid, procStart: start, sessionId: 'otherns', entrypoint: 'cli', pidDomain: 'linux:x:pid:[1]' });
  fs.writeFileSync(path.join(dir, 'junk.json'), '{');
  const env = { ...process.env, HOME: home }; delete env.CLAUDE_CONFIG_DIR;
  const h = new Hub({ id: 'claude', agentCommand: 'x', agentEnv: env, helper: [process.execPath, path.join(here, '..', 'remote', 'helper.mjs')] });
  const r = await h.runHelper(['locks']);
  assert.deepEqual(r.held.map(x => x.sessionId), ['live']);
  assert.equal(r.held[0].basis, 'busy');
  assert.ok('openidle' in r.touched);
  assert.equal(r.touched.dead, 50);
  assert.ok(!('ours' in r.touched));
  assert.ok('otherns' in r.touched && 'reused' in r.touched);
});

test('lock sweep: desktop opens a loaded session -> released + broadcast; after it quits a prompt asks for reload', async () => {
  const st = lockState();
  const t = await setup({ locks: st.fn, locksMaxAgeMs: 0, lockSweepMs: 0 });
  const c = new Client(t.port);
  try {
    await c.opened;
    await c.call('bridge/attach', { sessionId: 'old-1' });
    assert.deepEqual(await t.hub.sweepLocks(), []);
    st.held = [desktop('old-1')];
    assert.deepEqual(await t.hub.sweepLocks(), ['old-1']);
    await c.wait(m => m.op === 'session_locked' && m.sessionId === 'old-1' && m.lockedBy === 'cli');
    await assert.rejects(c.call('session/prompt', { sessionId: 'old-1', prompt: [{ type: 'text', text: 'hi' }] }), e => e.code === -32020);
    st.held = [];                       // desktop quit cleanly: its session file is gone, nothing in touched
    await assert.rejects(c.call('session/prompt', { sessionId: 'old-1', prompt: [{ type: 'text', text: 'hi' }] }), e => e.code === -32021);
    const a = await c.call('bridge/attach', { sessionId: 'old-1' });
    assert.equal(a.reset, true);
    assert.equal((await c.call('session/prompt', { sessionId: 'old-1', prompt: [{ type: 'text', text: 'hi' }] })).stopReason, 'end_turn');
  } finally { c.close(); t.done(); }
});

test('agyLocks: foreign presence lock + busy status = held; own agy acp tree and idle ones are not', async () => {
  const A = 'aaaaaaaa-1111-2222-3333-444444444444', B = 'bbbbbbbb-1111-2222-3333-444444444444', C = 'cccccccc-1111-2222-3333-444444444444', D = 'dddddddd-1111-2222-3333-444444444444';
  const lsof = ['p100', 'f212', 'n/x/presence/' + A + '.lock', 'n/x/presence', 'p100', 'n/x/presence/' + B + '.lock', 'p501', 'n/x/presence/' + C + '.lock', 'p100', 'n/x/presence/' + D + '.lock'].join('\n');
  const ps = ' 100 1\n 500 1\n 501 500\n';
  const sums = [A + '|CASCADE_RUN_STATUS_RUNNING|1|2026-11-23 07:57:29.1+00:00', B + '|CASCADE_RUN_STATUS_IDLE|1|2026-11-23 07:57:44.5+00:00',
    C + '|CASCADE_RUN_STATUS_RUNNING|1|2026-11-23 07:00:00+00:00', D + '|CASCADE_RUN_STATUS_IDLE|0|2026-11-23 05:35:14.115698+00:00'].join('\n');
  const calls = [];
  const run = async (cmd, args) => { calls.push([cmd, args[0]]); if (cmd === 'lsof') return lsof; if (cmd === 'ps') return ps;
    if (args[0] === '-readonly') throw new Error('unable to open database file (14)'); return sums; };
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'agr-agy-')); fs.writeFileSync(path.join(dir, 'conversation_summaries.db'), 'x');
  const r = await agyLocks({ dataDir: dir, ownPids: () => [500], run })();
  assert.deepEqual(r.held.map(h => h.sessionId).sort(), [A, B].sort(), 'running, and idle-but-not-fully-idle; C is our own acp child; D idle');
  assert.equal(r.held[0].entrypoint, 'antigravity');
  assert.equal(r.touched[D], Date.parse('2026-11-23T05:35:14.115698+00:00'));
  assert.equal(r.ownWrites, true);
  assert.ok(calls.some(([c, a]) => c === 'sqlite3' && a === '-separator'), 'fell back to a read-write copy');
  assert.equal(parsePresenceLsof('p1\nn/p/not-a-lock.txt').size, 0);
  assert.equal(parseSummaries('garbage').size, 0);
});

test('lock (busy basis): our own running turn is never blocked; desktop turn -> lock, finish -> session_unlocked', async () => {
  const st = lockState();
  const t = await setup({ locks: async () => ({ ...(await st.fn()), ownWrites: true }), locksMaxAgeMs: 0, lockSweepMs: 0 });
  const c = new Client(t.port);
  try {
    await c.opened;
    await c.call('bridge/attach', { sessionId: 'old-1' });
    const busy = { sessionId: 'old-1', pid: 77, entrypoint: 'antigravity', basis: 'busy' };
    // our slow turn makes the shared DB say "busy" and the desktop holds a presence lock: steering still allowed
    const p1 = c.call('session/prompt', { sessionId: 'old-1', prompt: [{ type: 'text', text: 'slow' }] });
    await c.wait(m => m.op === 'turn' && m.running);
    st.held = [busy];
    assert.equal((await c.call('bridge/attach', { sessionId: 'old-1' })).running, true);
    await p1;
    st.touched = { 'old-1': Date.now() };               // our own turn's write: no stale reload
    st.held = [];
    assert.equal((await c.call('session/prompt', { sessionId: 'old-1', prompt: [{ type: 'text', text: 'hi' }] })).stopReason, 'end_turn');
    // a "busy" right after our own turn is the DB catching up; later, the desktop runs a turn
    st.held = [busy];
    assert.deepEqual(await t.hub.sweepLocks(), [], 'own-turn echo ignored');
    t.hub.sessions.get('old-1').turnEndedAt = Date.now() - 6000;
    assert.deepEqual(await t.hub.sweepLocks(), ['old-1']);
    await c.wait(m => m.op === 'session_locked' && m.lockedBy === 'antigravity');
    st.held = [];
    await t.hub.sweepLocks();
    await c.wait(m => m.op === 'session_unlocked' && m.sessionId === 'old-1');
    assert.equal(t.hub.lockedNotified.size, 0);
  } finally { c.close(); t.done(); }
});


test('"!" shell: runs in the session folder, logs Claude Code tags, reaches the model with the next prompt', async () => {
  const t = await setup();
  const c = new Client(t.port);
  try {
    await c.opened;
    const cwd = path.join(t.home, 'proj');
    const ns = await c.call('session/new', { cwd });
    const r = await c.call('bridge/shell', { sessionId: ns.sessionId, command: 'pwd; echo "a -> b" ; echo oops >&2; exit 3' });
    assert.equal(r.exitCode, 3);
    const ups = c.msgs.filter(m => m.op === 'update' && m.sessionId === ns.sessionId && m.update.sessionUpdate === 'user_message_chunk').map(m => m.update);
    assert.equal(ups.length, 2);
    assert.equal(ups[0].content.text, '<bash-input>pwd; echo "a -&gt; b" ; echo oops &gt;&amp;2; exit 3</bash-input>');
    assert.notEqual(ups[0].messageId, ups[1].messageId, 'input and output are separate messages');
    assert.equal(ups[1].content.text, `<bash-stdout>${fs.realpathSync(cwd)}\na -&gt; b\n</bash-stdout><bash-stderr>oops\n</bash-stderr><bash-exit-code>3</bash-exit-code>`);
    // the next prompt carries both blocks first; they are not echoed a second time
    const calls = [];
    const orig = t.hub.agent.call.bind(t.hub.agent);
    t.hub.agent.call = (m, p) => { calls.push({ m, p }); return orig(m, p); };
    await c.call('session/prompt', { sessionId: ns.sessionId, prompt: [{ type: 'text', text: 'what failed?' }] });
    const sent = calls.find(x => x.m === 'session/prompt').p.prompt.map(b => b.text);
    assert.deepEqual(sent, [ups[0].content.text, ups[1].content.text, 'what failed?']);
    const after = c.msgs.filter(m => m.op === 'update' && m.update.sessionUpdate === 'user_message_chunk').length;
    assert.equal(after, 3, 'only the typed prompt was echoed');
    assert.deepEqual(t.hub.sessions.get(ns.sessionId).shellContext, []);
    await assert.rejects(c.call('bridge/shell', { sessionId: 'nope', command: 'ls' }), /attach/);
  } finally { c.close(); t.done(); }
});

test('helper exec caps output and strips ANSI / carriage-return progress', async () => {
  const h = new Hub({ id: 'x', agentCommand: 'x' });
  const helperJs = path.join(here, '..', 'remote', 'helper.mjs');
  const r = await h.runHelper(['exec', os.tmpdir(), "printf '\\033[31mred\\033[0m\\n10%%\\r50%%\\r100%%\\n'; yes x | head -c 40000"], 20000, [process.execPath, helperJs]);
  assert.ok(r.stdout.startsWith('red\n100%\n'), JSON.stringify(r.stdout.slice(0, 40)));
  assert.equal(r.truncated, true);
  assert.ok(r.stdout.length > 29900 && r.stdout.length <= 30000);
  assert.equal(r.exitCode, 0);
});

test('helper exec returns promptly when the command backgrounds a process', async () => {
  const h = new Hub({ id: 'x', agentCommand: 'x' });
  const helperJs = path.join(here, '..', 'remote', 'helper.mjs');
  const t0 = Date.now();
  const r = await h.runHelper(['exec', os.tmpdir(), 'sleep 30 & echo started'], 10000, [process.execPath, helperJs]);
  assert.equal(r.stdout, 'started\n');
  assert.ok(Date.now() - t0 < 4000, 'took ' + (Date.now() - t0) + ' ms');
});


// ---------------------------------------------------------------- mid-turn messages: queue / interrupt / steering
const mockPrompts = (c, sid) => c.msgs.filter(m => m.op === 'update' && m.sessionId === sid && m.update.sessionUpdate === 'session_info_update').map(m => m.update._meta?.mockPrompt);
const userEchoes = (c, sid) => c.msgs.filter(m => m.op === 'update' && m.sessionId === sid && m.update.sessionUpdate === 'user_message_chunk').map(m => m.update.content.text);
const turnOps = (c, sid) => c.msgs.filter(m => m.op === 'turn' && m.sessionId === sid);

test('queue (no steering): mid-turn messages wait, then go out merged with no idle blip', async () => {
  const t = await setup();
  const c = new Client(t.port);
  try {
    await c.opened;
    assert.equal(t.hub.steers, false);
    const ns = await c.call('session/new', { cwd: t.home });
    const p1 = c.call('session/prompt', { sessionId: ns.sessionId, prompt: [{ type: 'text', text: 'slow one' }] });
    await c.wait(m => m.op === 'turn' && m.running);
    const q1 = await c.call('session/prompt', { sessionId: ns.sessionId, prompt: [{ type: 'text', text: 'q1' }] });
    assert.equal(q1.queued, true);
    await c.call('session/prompt', { sessionId: ns.sessionId, prompt: [{ type: 'text', text: 'q2' }, { type: 'image', mimeType: 'image/jpeg', data: 'AAAA' }] });
    const qm = await c.wait(m => m.op === 'queue' && m.items.length === 2);
    assert.deepEqual(qm.items.map(i => [i.text, i.imageCount]), [['q1', 0], ['q2', 1]]);
    assert.ok(!qm.items[0].blocks, 'summary only');
    assert.deepEqual(userEchoes(c, ns.sessionId), ['slow one'], 'queued messages are not in the transcript yet');
    assert.equal((await c.call('bridge/attach', { sessionId: ns.sessionId })).queue.length, 2);
    assert.equal((await p1).stopReason, 'end_turn');
    await c.wait(m => m.op === 'turn' && !m.running);
    // the mock refuses overlapping prompts: both prompts succeeded, so they were serialized
    assert.deepEqual(mockPrompts(c, ns.sessionId), ['slow one', 'q1 | q2']);
    const ops = turnOps(c, ns.sessionId);
    assert.deepEqual(ops.map(o => o.running), [true, true, false], 'running never drops between the turns');
    assert.ok(ops[1].startedAt >= ops[0].startedAt);
    assert.deepEqual(userEchoes(c, ns.sessionId).filter(x => x !== undefined), ['slow one', 'q1', 'q2']);
    assert.equal(t.hub.sessions.get(ns.sessionId).queue.length, 0);
    await c.wait(m => m.op === 'queue' && m.items.length === 0);
  } finally { c.close(); t.done(); }
});

test('Stop keeps the queue; unqueue takes one back; the next prompt sends the rest first', async () => {
  const t = await setup();
  const c = new Client(t.port);
  try {
    await c.opened;
    const ns = await c.call('session/new', { cwd: t.home });
    const sid = ns.sessionId;
    const p1 = c.call('session/prompt', { sessionId: sid, prompt: [{ type: 'text', text: 'slow' }] });
    await c.wait(m => m.op === 'turn' && m.running);
    const a = await c.call('session/prompt', { sessionId: sid, prompt: [{ type: 'text', text: 'first' }] });
    await c.call('session/prompt', { sessionId: sid, prompt: [{ type: 'text', text: 'second' }] });
    await c.call('session/cancel', { sessionId: sid });
    assert.equal((await p1).stopReason, 'cancelled');
    await c.wait(m => m.op === 'turn' && !m.running && m.stopReason === 'cancelled');
    await new Promise(r => setTimeout(r, 100));
    assert.equal(t.hub.sessions.get(sid).running, false, 'not auto-sent after a plain Stop');
    assert.equal((await c.call('bridge/attach', { sessionId: sid })).queue.length, 2);
    const back = await c.call('bridge/unqueue', { sessionId: sid, id: a.id });
    assert.deepEqual(back.prompt, [{ type: 'text', text: 'first' }]);
    await assert.rejects(c.call('bridge/unqueue', { sessionId: sid, id: a.id }), e => e.code === -32024);
    assert.equal((await c.call('session/prompt', { sessionId: sid, prompt: [{ type: 'text', text: 'third' }] })).stopReason, 'end_turn');
    assert.deepEqual(mockPrompts(c, sid), ['second | third']);
    assert.deepEqual(userEchoes(c, sid), ['slow', 'second', 'third']);
  } finally { c.close(); t.done(); }
});

test('Stop that the agent ignores (turn ends normally) still keeps the queue', async () => {
  const t = await setup();
  const c = new Client(t.port);
  try {
    await c.opened;
    const ns = await c.call('session/new', { cwd: t.home });
    const sid = ns.sessionId;
    const p1 = c.call('session/prompt', { sessionId: sid, prompt: [{ type: 'text', text: 'stubborn' }] });
    await c.wait(m => m.op === 'turn' && m.running);
    assert.equal((await c.call('session/prompt', { sessionId: sid, prompt: [{ type: 'text', text: 'held' }] })).queued, true);
    await c.call('session/cancel', { sessionId: sid });
    assert.equal((await p1).stopReason, 'end_turn');
    await c.wait(m => m.op === 'turn' && !m.running);
    await new Promise(r => setTimeout(r, 100));
    assert.equal(t.hub.sessions.get(sid).running, false);
    assert.equal(t.hub.sessions.get(sid).queue.length, 1);
    assert.equal(t.hub.sessions.get(sid).holdQueue, false, 'the hold covers one turn end only');
  } finally { c.close(); t.done(); }
});

test('interrupt and send: cancels the turn (and its permission prompt), sends right after', async () => {
  const t = await setup();
  const c = new Client(t.port);
  try {
    await c.opened;
    const ns = await c.call('session/new', { cwd: t.home });
    const sid = ns.sessionId;
    const p1 = c.call('session/prompt', { sessionId: sid, prompt: [{ type: 'text', text: 'please edit' }] });
    const req = await c.wait(m => m.op === 'request' && m.sessionId === sid);
    const r = await c.call('session/prompt', { sessionId: sid, prompt: [{ type: 'text', text: 'do this instead' }], interrupt: true });
    assert.equal(r.interrupting, true);
    const res = await c.wait(m => m.op === 'resolved' && m.key === req.key);
    assert.equal(res.reason, 'cancelled');
    assert.equal((await p1).stopReason, 'cancelled');
    await c.wait(m => m.op === 'turn' && !m.running);
    assert.deepEqual(turnOps(c, sid).map(o => o.running), [true, true, false]);
    assert.equal(turnOps(c, sid).at(-1).stopReason, 'end_turn');
    assert.deepEqual(mockPrompts(c, sid), ['do this instead']);
    // "Send Now" on a queue left by Stop: interrupt with no new content while idle just sends it
    const p2 = c.call('session/prompt', { sessionId: sid, prompt: [{ type: 'text', text: 'slow' }] });
    await c.wait(m => m.op === 'turn' && m.running && m.startedAt > turnOps(c, sid).at(-1).startedAt);
    await c.call('session/prompt', { sessionId: sid, prompt: [{ type: 'text', text: 'later' }] });
    await c.call('session/cancel', { sessionId: sid });
    await p2;
    assert.equal((await c.call('session/prompt', { sessionId: sid, prompt: [], interrupt: true })).stopReason, 'end_turn');
    assert.equal(mockPrompts(c, sid).at(-1), 'later');
    await assert.rejects(c.call('session/prompt', { sessionId: sid, prompt: [] }), e => e.code === -32602);
  } finally { c.close(); t.done(); }
});

test('steering: injected into the running turn; promptRequired falls back to a prompt', async () => {
  const t = await setup({ agentEnv: { ...process.env, MOCK_STEERING: '1' } });
  const c = new Client(t.port);
  try {
    await c.opened;
    assert.equal(t.hub.steers, true);
    assert.equal(t.hub.agentSummary().steering, true);
    const ns = await c.call('session/new', { cwd: t.home });
    const sid = ns.sessionId;
    const p1 = c.call('session/prompt', { sessionId: sid, prompt: [{ type: 'text', text: 'slow' }] });
    await c.wait(m => m.op === 'turn' && m.running);
    assert.deepEqual(await c.call('session/prompt', { sessionId: sid, prompt: [{ type: 'text', text: 'steer me' }] }), { steered: true });
    assert.deepEqual(userEchoes(c, sid), ['slow', 'steer me'], 'echoed once, in order');
    const late = c.call('session/prompt', { sessionId: sid, prompt: [{ type: 'text', text: 'late one' }] });
    assert.equal((await p1).stopReason, 'end_turn');
    assert.equal((await late).stopReason, 'end_turn', 'promptRequired: sent as a plain prompt');
    await c.wait(m => m.op === 'turn' && !m.running);
    const agentText = c.msgs.filter(m => m.op === 'update' && m.update.sessionUpdate === 'agent_message_chunk').map(m => m.update.content.text).join('');
    assert.ok(agentText.includes('steered: steer me'));
    assert.deepEqual(userEchoes(c, sid), ['slow', 'steer me', 'late one']);
    assert.deepEqual(turnOps(c, sid).map(o => o.running), [true, false], 'the fallback prompt joined the running turn');
    assert.equal(t.hub.sessions.get(sid).queue.length, 0);
  } finally { c.close(); t.done(); }
});


test('upload stores a phone file under ~/.leash/uploads, readFile base64 returns its bytes, relative paths use the cwd', async () => {
  const t = await setup();
  const c = new Client(t.port);
  try {
    await c.opened;
    const ns = await c.call('session/new', { cwd: path.join(t.home, 'proj') });
    const bytes = Buffer.from([0, 1, 2, 250, 255, 10, 13]);
    const up = await c.call('bridge/upload', { sessionId: ns.sessionId, name: '报告 (v2) [final].bin', data: bytes.toString('base64') });
    assert.equal(up.name, '报告_v2_final_.bin');
    assert.equal(up.path, path.join(t.home, '.leash', 'uploads', ns.sessionId, up.name));
    assert.equal(up.uri, 'file://' + up.path);
    assert.deepEqual(fs.readFileSync(up.path), bytes);
    const again = await c.call('bridge/upload', { sessionId: ns.sessionId, name: '报告 (v2) [final].bin', data: bytes.toString('base64') });
    assert.equal(again.name, '报告_v2_final_-2.bin', 'never overwrites');
    const r = await c.call('bridge/readFile', { path: up.path, mode: 'base64' });
    assert.deepEqual(Buffer.from(r.data, 'base64'), bytes);
    assert.equal(r.size, bytes.length);
    fs.writeFileSync(path.join(t.home, 'proj', 'src', 'a.png'), 'png!');
    const rel = await c.call('bridge/readFile', { path: 'src/a.png', mode: 'base64', sessionId: ns.sessionId });
    assert.equal(rel.mimeType, 'image/png');
    assert.equal(Buffer.from(rel.data, 'base64').toString(), 'png!');
    const relText = await c.call('bridge/readFile', { path: 'README.md', sessionId: ns.sessionId });
    assert.equal(relText.text, '# hi\n');
    await assert.rejects(c.call('bridge/readFile', { path: up.path, mode: 'base64', maxBytes: 3 }), e => e.code === -32007 && /too large/.test(e.message));
    await assert.rejects(c.call('bridge/readFile', { path: '../../../etc/passwd', mode: 'base64', sessionId: ns.sessionId }), /outside home/);
    await assert.rejects(c.call('bridge/upload', { sessionId: ns.sessionId, name: 'x' }), /data/);
  } finally { c.close(); t.done(); }
});

test('a prompt with a resource_link echoes the link to every device', async () => {
  const t = await setup();
  const c = new Client(t.port);
  try {
    await c.opened;
    const ns = await c.call('session/new', { cwd: path.join(t.home, 'proj') });
    const link = { type: 'resource_link', uri: 'file:///h/.leash/uploads/s/a.pdf', name: 'a.pdf', mimeType: 'application/pdf', size: 3 };
    await c.call('session/prompt', { sessionId: ns.sessionId, prompt: [{ type: 'text', text: 'read this' }, link] });
    const echoed = c.msgs.filter(m => m.op === 'update' && m.update.sessionUpdate === 'user_message_chunk').map(m => m.update.content);
    assert.deepEqual(echoed.map(b => b.type), ['text', 'resource_link']);
    assert.equal(echoed[1].uri, link.uri);
  } finally { c.close(); t.done(); }
});

test('helper upload over an ssh-like transport: base64 on stdin, byte count checked', async () => {
  const home = fs.mkdtempSync(path.join(os.tmpdir(), 'agr-up-'));
  const helperJs = path.join(here, '..', 'remote', 'helper.mjs');
  const h = new Hub({ id: 'claude', agentCommand: 'x', helper: ['bash', '-c', `cd "${home}" && HOME="${home}" eval "node ${helperJs} $*"`, 'agr'], locks: false });
  const data = Buffer.alloc(300 * 1024, 7);
  const r = await h.upload({ sessionId: 'sess-1', name: 'big.dat', data: data.toString('base64') });
  assert.equal(r.path, path.join(home, '.leash', 'uploads', 'sess-1', 'big.dat'));
  assert.deepEqual(fs.readFileSync(r.path), data);
  await assert.rejects(h.runHelper(['upload', 's', 'x.bin', '99'], 10000, h.helper, Buffer.from('abc').toString('base64')), /incomplete/);
  const back = await h.readFile(r.path, 0, { mode: 'base64' });
  assert.equal(Buffer.from(back.data, 'base64').length, data.length);
  h.stop();
});

test('listPerCwd adds sessions only a per-directory list finds (photo-first Claude sessions)', async () => {
  for (const listPerCwd of [false, true]) {
    const t = await setup({ listPerCwd });
    const c = new Client(t.port);
    try {
      await c.opened;
      const list = await c.call('session/list', {});
      assert.ok(list.sessions.some(s => s.sessionId === 'old-1'));
      assert.equal(list.sessions.some(s => s.sessionId === 'photo-1'), listPerCwd);
      if (listPerCwd) {
        assert.equal(list.sessions.filter(s => s.sessionId === 'old-1').length, 1, 'no duplicates');
        assert.equal(list.sessions.find(s => s.sessionId === 'photo-1')._leash.agent, 'agy');
        // a scoped request is passed through as is
        const scoped = await c.call('session/list', { cwd: '/tmp/proj' });
        assert.ok(scoped.sessions.some(s => s.sessionId === 'photo-1'));
      }
    } finally { c.close(); t.done(); }
  }
});

test('session/list tells how the bridge\'s last turn in a conversation ended', async () => {
  const t = await setup();
  const c = new Client(t.port);
  try {
    await c.opened;
    await c.wait(m => m.op === 'ready');
    const ns = await c.call('session/new', { cwd: path.join(t.home, 'proj') });
    let list = await c.call('session/list', {});
    let row = list.sessions.find(s => s.sessionId === ns.sessionId);
    assert.equal(row._leash.stopReason, null);
    await c.call('session/prompt', { sessionId: ns.sessionId, prompt: [{ type: 'text', text: 'hi' }] });
    list = await c.call('session/list', {});
    row = list.sessions.find(s => s.sessionId === ns.sessionId);
    assert.equal(row._leash.running, false);
    assert.equal(row._leash.stopReason, 'end_turn');
    assert.ok(row._leash.turnEndedAt > 0);
  } finally { c.close(); t.done(); }
});

test('btw: side question runs helper btw (throwaway fork, no tools) mid-turn and records nothing', async () => {
  const bin = fs.mkdtempSync(path.join(os.tmpdir(), 'agr-claude-'));
  const fake = path.join(bin, 'claude');
  // stands in for the claude CLI: answers with what it was started with
  fs.writeFileSync(fake, '#!' + process.execPath + '\n' +
    "process.stdout.write(JSON.stringify({ type: 'result', result: JSON.stringify({ argv: process.argv.slice(2), cwd: process.cwd(), key: process.env.ANTHROPIC_API_KEY ?? null }), modelUsage: { 'claude-test': {} } }) + '\\n');\n", { mode: 0o755 });
  const t = await setup({ id: 'claude', helper: [process.execPath, path.join(here, '..', 'remote', 'helper.mjs')], locks: false,
    agentEnv: { ...process.env, CLAUDE_CODE_EXECUTABLE: fake, ANTHROPIC_API_KEY: 'sk-test' },
    sessionMeta: { claudeCode: { options: { settings: { env: { CLAUDE_CODE_EXPERIMENTAL_CODE_MODE: '1' } } } } } });
  const c = new Client(t.port);
  try {
    await c.opened;
    const ready = await c.wait(m => m.op === 'ready');
    assert.equal(ready.agent?.btw ?? t.hub.agentSummary().btw, true);
    assert.equal(t.hub.agentSummary().turbo, false);
    const cwd = path.join(t.home, 'proj');
    const ns = await c.call('session/new', { cwd });
    const promptP = c.call('session/prompt', { sessionId: ns.sessionId, prompt: [{ type: 'text', text: 'please edit' }] });
    const req = await c.wait(m => m.op === 'request' && m.method === 'session/request_permission');
    const s = t.hub.sessions.get(ns.sessionId);
    assert.equal(s.running, true);
    const seq = s.seq, seen = c.msgs.length;
    const r = await c.call('bridge/btw', { sessionId: ns.sessionId, question: '  /why so slow?  ' });
    assert.equal(r.model, 'claude-test');
    const got = JSON.parse(r.text);
    assert.equal(got.cwd, fs.realpathSync(cwd));
    assert.equal(got.key, null, 'no API key: the subscription login answers');
    const a = got.argv;
    assert.equal(a[0], '-p');
    assert.ok(a[1].startsWith('<system-reminder>') && a[1].endsWith('/why so slow?'), 'a leading "/" is not read as a command');
    const after = flag => a[a.indexOf(flag) + 1];
    assert.equal(after('--resume'), ns.sessionId);
    for (const f of ['--fork-session', '--no-session-persistence', '--strict-mcp-config']) assert.ok(a.includes(f), f);
    assert.equal(after('--tools'), '');
    assert.equal(after('--max-turns'), '1');
    assert.deepEqual(JSON.parse(after('--settings')), { env: { CLAUDE_CODE_EXPERIMENTAL_CODE_MODE: '1' } });
    const model = s.configOptions.find(o => o.id === 'model').currentValue;
    if (model !== 'default') assert.equal(after('--model'), model);
    assert.equal(s.seq, seq, 'nothing recorded in the conversation');
    assert.ok(!c.msgs.slice(seen).some(m => m.op === 'update' || m.op === 'turn'), 'nothing broadcast');
    assert.equal(s.running, true, 'the turn is untouched');
    await assert.rejects(c.call('bridge/btw', { sessionId: ns.sessionId, question: ' ' }), /question/);
    await assert.rejects(c.call('bridge/setAutoApprove', { sessionId: ns.sessionId, enabled: true }), /Turbo/);
    c.reply(req.key, { outcome: { outcome: 'selected', optionId: 'allow' } });
    assert.equal((await promptP).stopReason, 'end_turn');
  } finally { c.close(); t.done(); fs.rmSync(bin, { recursive: true, force: true }); }
  // agy (no helper): no side questions, Turbo available
  const agy = new Hub({ agentCommand: 'x', log: () => {} });
  assert.equal(agy.agentSummary().btw, false);
  assert.equal(agy.agentSummary().turbo, true);
  await assert.rejects(agy.btw({ sessionId: 's', question: 'q' }), /side questions/);
});

test('cli list: a Leash (SDK) conversation is relabelled in place so Claude Code /resume lists it', async () => {
  const cfg = fs.mkdtempSync(path.join(os.tmpdir(), 'agr-cfg-'));
  const proj = path.join(cfg, 'projects', '-x-proj');
  fs.mkdirSync(proj, { recursive: true });
  const sid = '0e53cb7a-c7d6-4f0c-8159-d42ccdd00d9c', big = 'aaaaaaaa-0000-4000-8000-000000000000';
  const line = (o) => JSON.stringify(o) + '\n';
  const file = path.join(proj, sid + '.jsonl');
  fs.writeFileSync(file, line({ type: 'queue-operation', sessionId: sid }) +
    line({ type: 'user', message: { role: 'user', content: 'hi' }, entrypoint: 'sdk-ts', cwd: '/x/proj', sessionId: sid }) +
    line({ type: 'assistant', entrypoint: 'sdk-ts', sessionId: sid }));
  // a first message larger than the 64 KB Claude Code reads first: its label is out of reach
  fs.writeFileSync(path.join(proj, big + '.jsonl'), line({ type: 'user', message: { content: 'x'.repeat(70000) }, entrypoint: 'sdk-ts' }));
  const t = await setup({ id: 'claude', locks: false, helper: [process.execPath, path.join(here, '..', 'remote', 'helper.mjs')],
    agentEnv: { ...process.env, CLAUDE_CONFIG_DIR: cfg } });
  const c = new Client(t.port);
  try {
    await c.opened;
    await c.wait(m => m.op === 'ready');
    assert.equal(t.hub.agentSummary().cliList, true);
    assert.deepEqual(await c.call('bridge/cliVisibility', { sessionId: sid }), { visible: false, entrypoint: 'sdk-ts', fixable: true });
    const size = fs.statSync(file).size;
    assert.deepEqual(await c.call('bridge/showInCli', { sessionId: sid }), { visible: true, changed: true });
    assert.equal(fs.statSync(file).size, size, 'same length, in place');
    const lines = fs.readFileSync(file, 'utf8').trim().split('\n').map(l => JSON.parse(l));
    assert.deepEqual(lines.map(l => l.entrypoint), [undefined, 'cli', 'sdk-ts'], 'only the first label changes');
    assert.equal(lines[1].message.content, 'hi');
    assert.deepEqual(await c.call('bridge/cliVisibility', { sessionId: sid }), { visible: true, entrypoint: 'cli', fixable: true });
    assert.deepEqual(await c.call('bridge/showInCli', { sessionId: sid }), { visible: true, changed: false });

    assert.deepEqual(await c.call('bridge/cliVisibility', { sessionId: big }), { visible: false, entrypoint: 'sdk-ts', fixable: false });
    await assert.rejects(c.call('bridge/showInCli', { sessionId: big }), /large message/);
    await assert.rejects(c.call('bridge/showInCli', { sessionId: '../../etc' }), /bad session id/);
    await assert.rejects(c.call('bridge/cliVisibility', { sessionId: 'bbbbbbbb-0000-4000-8000-000000000000' }), /no transcript/);
  } finally { c.close(); t.done(); fs.rmSync(cfg, { recursive: true, force: true }); }
  const agy = new Hub({ agentCommand: 'x', log: () => {} });
  assert.equal(agy.agentSummary().cliList, false);
  await assert.rejects(agy.showInCli({ sessionId: sid }), /no Claude Code/);
});

test('subagents: child sessions file under the root, tagged; their requests show on the root; no root side effects', async () => {
  const t = await setup({ idleCloseMs: 1000 });
  const c = new Client(t.port);
  try {
    await c.opened;
    const ns = await c.call('session/new', { cwd: path.join(t.home, 'proj') });
    const sid = ns.sessionId, child = 'child-' + sid, grand = 'grand-' + sid;
    const promptP = c.call('session/prompt', { sessionId: sid, prompt: [{ type: 'text', text: 'delegate bg' }] });
    const req = await c.wait(m => m.op === 'request' && m.method === 'session/request_permission');
    assert.equal(req.sessionId, sid, 'filed under the root');
    assert.equal(req.params.sessionId, child, 'the agent still gets its own params back');
    assert.deepEqual(req.subagent, { sessionId: child, title: 'Explore the repo', description: 'Explore' });
    c.reply(req.key, { outcome: { outcome: 'selected', optionId: 'allow' } });
    await promptP;
    const ups = c.msgs.filter(m => m.op === 'update');
    assert.ok(ups.every(m => m.sessionId === sid), 'every update reaches the phone on the root session');
    const tagged = ups.filter(m => m.update._meta?.leash?.subagent);
    assert.ok(tagged.some(m => m.update._meta.leash.subagent === child && m.update.sessionUpdate === 'tool_call_update' && m.update.status === 'completed'));
    assert.ok(tagged.some(m => m.update._meta.leash.subagent === grand && m.update.content?.text === 'grandchild'));
    assert.ok(tagged.some(m => m.update._meta.leash.subagent === child && m.update.sessionUpdate === 'subagent_update' && m.update.sessionId === grand), 'grandchild announced inside the child');
    assert.ok(!ups.some(m => m.update.sessionUpdate === 'available_commands_update' && m.update._meta?.leash?.subagent), 'child command lists dropped');
    assert.ok(!ups.some(m => m.update.sessionUpdate === 'session_info_update' && m.update._meta?.leash?.subagent), 'child titles dropped');
    assert.ok(!t.hub.sessions.has(child) && !t.hub.sessions.has(grand), 'no session state for children');
    assert.equal(t.hub.children.get(grand).root, sid);
    const s = t.hub.sessions.get(sid);
    assert.equal(s.configOptions[0].currentValue, 'gemini-4-hydrogen', "the child's model list leaves the root's alone");
    assert.notEqual(s.title, 'not the root title');
    // snapshot: the child's and the parent's chunks with one messageId stay apart
    const late = new Client(t.port); await late.opened;
    const snap = await late.call('bridge/attach', { sessionId: sid });
    const shared = snap.events.filter(e => e.update.messageId === 'shared').map(e => [e.update._meta?.leash?.subagent ?? null, e.update.content.text]);
    assert.deepEqual(shared, [[child, 'child final'], [null, 'parent done']]);
    late.close();
    // the background child is still running after the turn: the idle sweep leaves the root loaded
    await c.call('bridge/detach', { sessionId: sid });
    assert.deepEqual(await t.hub.closeIdleSessions(Date.now() + 60000), []);
    t.hub.children.get(child).state = { state: 'idle', stopReason: 'end_turn' };
    assert.deepEqual(await t.hub.closeIdleSessions(Date.now() + 60000), [sid]);
  } finally { c.close(); t.done(); }
});
