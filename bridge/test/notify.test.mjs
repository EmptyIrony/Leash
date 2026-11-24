import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import path from 'node:path';
import os from 'node:os';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import WebSocket from 'ws';
import crypto from 'node:crypto';
import { BarkNotifier, barkSettings, parseBarkArg, barkEncrypt, newCipherKey, validCipherKey } from '../src/notify.mjs';
import { Hub } from '../src/hub.mjs';
import { createServer } from '../src/server.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const sleep = ms => new Promise(r => setTimeout(r, ms));
const AWAY = 60;
const GRACE = 20;

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
  const n = new BarkNotifier({ settings: barkSettings({ key: 'SECRETKEY', ...bark }), clients: () => clients, fetch, log: m => logs.push(m), awayMs: AWAY, graceMs: GRACE });
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
  assert.equal(s.always, false); assert.equal(s.detail, false); assert.equal(s.url, null);
  assert.equal(barkSettings({ key: 'k', url: 'leash://custom' }).url, 'leash://custom');
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
  assert.equal(r2[0].url, 'leash://session?id=s1&agent=claude');
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

test('a Leash running in the background: pushed after a short grace, unless it alerted itself', async () => {
  const bg = { lastSeen: 0, background: true };
  const r = rig({ clients: [bg] });
  ask(r.hub, 'claude:r1');
  await sleep(5);
  assert.equal(r.sent.length, 0, 'waits for bridge/noticed');
  await sleep(GRACE + 20);
  assert.equal(r.sent.length, 1);
  const p2 = ask(r.hub, 'claude:r2');
  p2.noticedAt = Date.now();
  await sleep(GRACE + 20);
  assert.equal(r.sent.length, 1, 'the phone alerted itself');
  endTurn(r.hub);
  await sleep(GRACE + 20);
  assert.equal(r.sent.length, 2, 'turn end still reaches Bark');
  // a stale lastSeen on a background client never counts as on screen
  bg.lastSeen = Date.now();
  endTurn(r.hub);
  await sleep(GRACE + 20);
  assert.equal(r.sent.length, 3);
  // the phone posted this turn end itself
  const s = endTurn(r.hub);
  s.turnEndedAt = Date.now();
  s.endNoticedAt = Date.now() + 1;
  await sleep(GRACE + 20);
  assert.equal(r.sent.length, 3, 'noticed turn end is not pushed again');
  // an older notice does not cover the next turn
  s.turnEndedAt = s.endNoticedAt + 5;
  r.hub.emit('turn_end', s);
  await sleep(GRACE + 20);
  assert.equal(r.sent.length, 4);
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

const decrypt = (b64, key, iv) => { const d = crypto.createDecipheriv(`aes-${key.length * 8}-cbc`, Buffer.from(key, 'latin1'), Buffer.from(iv, 'latin1')); return Buffer.concat([d.update(Buffer.from(b64, 'base64')), d.final()]).toString('utf8'); };

test('barkEncrypt matches the ciphertexts in Bark\'s own encryption docs', () => {
  const j = '{"body": "test", "sound": "birdsong"}';
  assert.equal(barkEncrypt(j, '1234567890123456', '1234567890123456'), '+aPt5cwN9GbTLLSFri60l3h1X00u/9j1FENfWiTxhNHVLGU+XoJ15JJG5W/d/yf0');
  assert.equal(barkEncrypt(j, '1234567890123456', '1111111111111111'), 'd3QhjQjP5majvNt5CjsvFWwqqj2gKl96RFj5OO+u6ynTt7lkyigDYNA3abnnCLpr');
});

test('encrypt: bad keys fail closed; a generated key is AES-256 sized; titles default on', () => {
  for (const k of ['short', 'x'.repeat(20), 'é'.repeat(16), 'a b'.padEnd(16, 'c'), 42, {}]) assert.throws(() => barkSettings({ key: 'k', encrypt: k }), /16, 24 or 32/);
  const g = newCipherKey();
  assert.equal(g.length, 32); assert.ok(validCipherKey(g));
  assert.equal(barkSettings({ key: 'k', encrypt: g }).detail, true);
  assert.equal(barkSettings({ key: 'k', encrypt: { key: g }, detail: false }).detail, false);
  assert.equal(barkSettings({ key: 'k', encrypt: false }).encrypt, null);
});

test('encrypted push: the request carries only device key, ciphertext and a fresh IV', async () => {
  const key = newCipherKey();
  const r = rig({ bark: { encrypt: key } });
  ask(r.hub);
  ask(r.hub, 'claude:r2');
  await sleep(5);
  assert.equal(r.sent.length, 2);
  const m = r.sent[0];
  assert.deepEqual(Object.keys(m).sort(), ['ciphertext', 'device_key', 'endpoint', 'iv']);
  assert.equal(m.iv.length, 16);
  assert.notEqual(m.iv, r.sent[1].iv);
  assert.ok(!JSON.stringify(m).includes('rm -rf'));
  const inner = JSON.parse(decrypt(m.ciphertext, key, m.iv));
  assert.deepEqual(inner, { title: 'Claude Code 需要批准', body: 'Fix the parser · rm -rf build', level: 'timeSensitive', group: 'Claude Code', url: 'leash://session?id=s1&agent=claude' });
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

test('presence: a backgrounded app stops counting as on screen, coming back counts again', async () => {
  const home = fs.mkdtempSync(path.join(os.tmpdir(), 'leash-presence-'));
  const hub = new Hub({ agentCommand: process.execPath, agentArgs: [path.join(here, 'mock-agent.mjs')], log: () => {}, home });
  const server = createServer({ hub, token: 'tok', log: () => {} });
  const srv = await server.listen('127.0.0.1', 0);
  const ws = new WebSocket(`ws://127.0.0.1:${srv.address().port}/ws`, { headers: { authorization: 'Bearer tok' } });
  try {
    await new Promise((res, rej) => { ws.once('open', res); ws.once('error', rej); });
    await sleep(20);
    const [c] = [...hub.clients];
    ws.send(JSON.stringify({ op: 'presence', visible: false }));
    await sleep(30);
    assert.equal(c.background, true);
    assert.equal(c.lastSeen, 0);
    ws.ping();
    ws.send(JSON.stringify({ op: 'ping', t: 1 }));
    await sleep(30);
    assert.equal(c.lastSeen, 0, 'pings and messages from the background do not count');
    ws.send(JSON.stringify({ op: 'presence', visible: true }));
    await sleep(30);
    assert.equal(c.background, false);
    assert.ok(c.lastSeen > 0);
  } finally { ws.close(); hub.stop(); server.close(); }
});

test('tap URL: the conversation with detail, the app without; a configured url always wins', async () => {
  const plain = rig();
  ask(plain.hub); endTurn(plain.hub);
  await sleep(10);
  assert.deepEqual(plain.sent.map(m => m.url), ['leash://open', 'leash://open']);
  const detail = rig({ bark: { detail: true } });
  ask(detail.hub); endTurn(detail.hub);
  await sleep(10);
  assert.deepEqual(detail.sent.map(m => m.url), ['leash://session?id=s1&agent=claude', 'leash://session?id=s1&agent=claude']);
  const fixed = rig({ bark: { detail: true, url: 'leash://open' } });
  ask(fixed.hub);
  await sleep(5);
  assert.equal(fixed.sent[0].url, 'leash://open');
  assert.equal(await detail.n.test(), true);
  assert.equal(detail.sent.at(-1).url, 'leash://open');
});

test('a request the phone alerted about itself (bridge/noticed) is not pushed again', async () => {
  const r = rig({ clients: [{ lastSeen: Date.now() }] });
  const p = ask(r.hub);
  p.noticedAt = Date.now();
  await sleep(AWAY + 30);
  assert.equal(r.sent.length, 0);
});

test('bridge/noticed marks the pending request on the hub that owns its key', async () => {
  const home = fs.mkdtempSync(path.join(os.tmpdir(), 'leash-noticed-'));
  const mk = (id) => new Hub({ id, label: id, agentCommand: process.execPath, agentArgs: [path.join(here, 'mock-agent.mjs')], log: () => {}, home, idleCloseMs: 0 });
  const agy = mk('agy'), claude = mk('claude');
  const { Router } = await import('../src/router.mjs');
  const router = new Router({ hubs: [agy, claude] });
  try {
    claude.pending.set('claude:r1', { key: 'claude:r1', method: 'session/request_permission', sessionId: 's1', params: {} });
    assert.deepEqual(await router.handleCall(null, 'bridge/noticed', { key: 'claude:r1' }), { ok: true });
    assert.ok(claude.pending.get('claude:r1').noticedAt > 0);
    assert.deepEqual(await router.handleCall(null, 'bridge/noticed', { key: 'claude:r9' }), { ok: false });
    assert.deepEqual(await router.handleCall(null, 'bridge/noticed', { key: 'nope' }), { ok: false });
    // a turn end names its hub as agent
    claude.sessions.set('s7', { sessionId: 's7' });
    assert.deepEqual(await router.handleCall(null, 'bridge/noticed', { agent: 'claude', sessionId: 's7', turnEnd: true }), { ok: true });
    assert.ok(claude.sessions.get('s7').endNoticedAt > 0);
    assert.deepEqual(await router.handleCall(null, 'bridge/noticed', { agent: 'claude', sessionId: 'nope', turnEnd: true }), { ok: false });
  } finally { agy.stop(); claude.stop(); }
});

test('a reply with an id is answered with that id (-32002 once already resolved)', async () => {
  const home = fs.mkdtempSync(path.join(os.tmpdir(), 'leash-reply-'));
  const hub = new Hub({ agentCommand: process.execPath, agentArgs: [path.join(here, 'mock-agent.mjs')], log: () => {}, home, idleCloseMs: 0 });
  const server = createServer({ hub, token: 'tok', log: () => {} });
  const srv = await server.listen('127.0.0.1', 0);
  const ws = new WebSocket(`ws://127.0.0.1:${srv.address().port}/ws`, { headers: { authorization: 'Bearer tok' } });
  try {
    await new Promise((res, rej) => { ws.once('open', res); ws.once('error', rej); });
    const got = new Promise(res => ws.on('message', d => { const m = JSON.parse(String(d)); if (m.op === 'result' && m.id === 7) res(m); }));
    ws.send(JSON.stringify({ op: 'reply', id: 7, key: 'agy:r404', result: { outcome: { outcome: 'cancelled' } } }));
    const m = await got;
    assert.equal(m.error.code, -32002);
  } finally { ws.close(); hub.stop(); server.close(); }
});
