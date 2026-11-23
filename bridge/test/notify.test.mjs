import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import path from 'node:path';
import os from 'node:os';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import WebSocket from 'ws';
import { BarkNotifier, barkSettings, parseBarkArg } from '../src/notify.mjs';
import { Hub } from '../src/hub.mjs';
import { createServer } from '../src/server.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const sleep = ms => new Promise(r => setTimeout(r, ms));
const AWAY = 60;

function fakeHub() {
  const h = new EventEmitter();
  h.id = 'claude'; h.label = 'Claude Code';
  h.sessions = new Map([['s1', { sessionId: 's1', cwd: '/x/proj', title: 'Fix the parser', running: false }]]);
  h.pending = new Map();
  return h;
}
function rig({ bark = {}, clients = [], fetchImpl } = {}) {
  const sent = [], logs = [];
  const fetch = fetchImpl ?? (async (url, init) => { sent.push({ endpoint: url, ...JSON.parse(init.body) }); return { ok: true, status: 200 }; });
  const n = new BarkNotifier({ settings: barkSettings({ key: 'SECRETKEY', ...bark }), clients: () => clients, fetch, log: m => logs.push(m), awayMs: AWAY });
  const hub = fakeHub();
  n.watch(hub);
  return { n, hub, sent, logs, clients };
}
const ask = (hub, key = 'claude:r1') => {
  const p = { key, method: 'session/request_permission', sessionId: 's1', params: { toolCall: { title: 'rm -rf build' } } };
  hub.pending.set(key, p); hub.emit('attention', p); return p;
};
const endTurn = (hub, reason = 'end_turn', ms = 125000) => {
  const s = hub.sessions.get('s1');
  Object.assign(s, { lastStopReason: reason, turnStart: 1000, turnEndedAt: 1000 + ms, running: false });
  hub.emit('turn_end', s); return s;
};

test('barkSettings: off unless a key is set; defaults', () => {
  for (const raw of [undefined, null, 'x', 42, {}, { key: '' }, { key: 5 }]) assert.equal(barkSettings(raw), null);
  const s = barkSettings({ key: ' k ', server: 'https://bark.example/' });
  assert.equal(s.key, 'k'); assert.equal(s.server, 'https://bark.example');
  assert.equal(s.always, false); assert.equal(s.detail, false); assert.equal(s.url, 'leash://open');
  assert.equal(barkSettings({ key: 'k' }).server, 'https://api.day.app');
});

test('parseBarkArg takes a key or the Bark app URL', () => {
  assert.deepEqual(parseBarkArg('abc'), { key: 'abc' });
  assert.deepEqual(parseBarkArg('https://api.day.app/abc/'), { key: 'abc', server: 'https://api.day.app' });
  assert.deepEqual(parseBarkArg('https://api.day.app/abc/title/body', 'https://mine'), { key: 'abc', server: 'https://mine' });
  assert.throws(() => parseBarkArg(''));
  assert.throws(() => parseBarkArg('https://api.day.app/'));
});

test('permission request with nobody connected pushes at once, without titles by default', async () => {
  const r = rig();
  ask(r.hub);
  await sleep(5);
  assert.equal(r.sent.length, 1);
  const m = r.sent[0];
  assert.equal(m.endpoint, 'https://api.day.app/push');
  assert.equal(m.url, 'leash://open');
  assert.equal(m.device_key, 'SECRETKEY');
  assert.equal(m.title, 'Claude Code 需要批准');
  assert.equal(m.level, 'timeSensitive');
  assert.equal(m.group, 'Claude Code');
  assert.ok(!JSON.stringify(m).includes('rm -rf'));
  assert.ok(!JSON.stringify(m).includes('Fix the parser'));
});

test('payload carries the tap URL and, with detail, the titles', async () => {
  const r = rig({ bark: { detail: true }, fetchImpl: async (_u, init) => { r2.push(JSON.parse(init.body)); return { ok: true }; } });
  const r2 = [];
  ask(r.hub);
  await sleep(5);
  assert.equal(r2[0].url, 'leash://open');
  assert.equal(r2[0].body, 'Fix the parser · rm -rf build');
});

test('phone on screen: no push while it keeps pinging; push once it goes quiet', async () => {
  const live = { lastSeen: Date.now() };
  const r = rig({ clients: [live] });
  ask(r.hub, 'claude:r1');
  const ping = setInterval(() => { live.lastSeen = Date.now(); }, 10);
  await sleep(AWAY + 30);
  clearInterval(ping);
  assert.equal(r.sent.length, 0, 'app was in the foreground');
  // backgrounded right before the next request: one ping period later it is pushed
  live.lastSeen = Date.now();
  ask(r.hub, 'claude:r2');
  await sleep(5);
  assert.equal(r.sent.length, 0);
  await sleep(AWAY + 30);
  assert.equal(r.sent.length, 1);
});

test('a request answered during the grace period is not pushed', async () => {
  const r = rig({ clients: [{ lastSeen: Date.now() }] });
  const p = ask(r.hub);
  r.hub.pending.delete(p.key);
  await sleep(AWAY + 30);
  assert.equal(r.sent.length, 0);
});

test('when: always pushes even with the app open', async () => {
  const r = rig({ bark: { when: 'always' }, clients: [{ lastSeen: Date.now() }] });
  ask(r.hub);
  await sleep(5);
  assert.equal(r.sent.length, 1);
});

test('turn end: finished / error pushed with duration; Stop and queued continuations are not', async () => {
  const r = rig();
  endTurn(r.hub, 'end_turn', 125000);
  await sleep(10);
  assert.equal(r.sent.length, 1);
  assert.equal(r.sent[0].title, 'Claude Code 完成了');
  assert.equal(r.sent[0].body, '用时 2 分 5 秒');
  assert.equal(r.sent[0].level, 'active');
  endTurn(r.hub, 'error', 4000);
  await sleep(10);
  assert.equal(r.sent[1].title, 'Claude Code 出错了');
  endTurn(r.hub, 'cancelled');
  await sleep(10);
  assert.equal(r.sent.length, 2);
  const s = endTurn(r.hub, 'end_turn');
  s.running = true;                    // the hub flushed a queued message right after turn_end
  await sleep(10);
  assert.equal(r.sent.length, 2);
  endTurn(r.hub, 'max_tokens', 1000);
  await sleep(10);
  assert.equal(r.sent[2].title, 'Claude Code 停下了');
  assert.match(r.sent[2].body, /max_tokens/);
});

test('english strings', async () => {
  const r = rig({ bark: { lang: 'en' } });
  ask(r.hub);
  await sleep(5);
  assert.equal(r.sent[0].title, 'Claude Code needs approval');
});

test('push failures resolve false, are logged without the key, and never throw into the hub', async () => {
  const r = rig({ fetchImpl: async () => { throw new Error('getaddrinfo ENOTFOUND'); } });
  ask(r.hub);
  assert.equal(await r.n.push({ title: 't', body: 'b' }), false);
  const r500 = rig({ fetchImpl: async () => ({ ok: false, status: 500 }) });
  assert.equal(await r500.n.push({ title: 't', body: 'b' }), false);
  assert.ok(r500.logs.some(l => l.includes('HTTP 500')));
  await sleep(5);
  assert.ok(r.logs.some(l => l.includes('ENOTFOUND')));
  assert.ok(![...r.logs, ...r500.logs].some(l => l.includes('SECRETKEY')));
  // a broken clients() source must not escape the listener
  const n = new BarkNotifier({ settings: barkSettings({ key: 'k' }), clients: () => { throw new Error('boom'); }, fetch: async () => ({ ok: true }), log: () => {} });
  const hub = fakeHub(); n.watch(hub);
  assert.doesNotThrow(() => ask(hub));
  assert.doesNotThrow(() => endTurn(hub));
  await sleep(10);
});

test('timeout: a hanging Bark server resolves false', async () => {
  const keep = setInterval(() => {}, 1000);   // AbortSignal.timeout is unref'd; the bridge's servers keep the loop alive
  after(() => clearInterval(keep));
  const n = new BarkNotifier({ settings: barkSettings({ key: 'k' }), log: () => {}, timeoutMs: 50,
    fetch: (_u, init) => new Promise((_res, rej) => init.signal.addEventListener('abort', () => rej(init.signal.reason))) });
  assert.equal(await n.push({ title: 't', body: 'b' }), false);
});

test('server stamps client.lastSeen on WebSocket pings', async () => {
  const home = fs.mkdtempSync(path.join(os.tmpdir(), 'leash-notify-'));
  const hub = new Hub({ agentCommand: process.execPath, agentArgs: [path.join(here, 'mock-agent.mjs')], log: () => {}, home });
  const server = createServer({ hub, token: 'tok', log: () => {} });
  const srv = await server.listen('127.0.0.1', 0);
  const ws = new WebSocket(`ws://127.0.0.1:${srv.address().port}/ws`, { headers: { authorization: 'Bearer tok' } });
  try {
    await new Promise((res, rej) => { ws.once('open', res); ws.once('error', rej); });
    await sleep(20);
    const [c] = [...hub.clients];
    assert.ok(c.lastSeen > 0);
    const before = c.lastSeen;
    await sleep(15);
    ws.ping();
    await sleep(30);
    assert.ok(c.lastSeen > before);
  } finally { ws.close(); hub.stop(); server.close(); }
});
