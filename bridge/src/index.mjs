#!/usr/bin/env node
// Leash bridge: exposes the coding agents on this computer (Antigravity "agy", Claude Code) to the Leash phone app.
//   node src/index.mjs            start the bridge
//   node src/index.mjs doctor     check this machine (Node, agents, addresses, Bark); run this first on a new computer
//   node src/index.mjs pair       print pairing info / QR code
//   node src/index.mjs rotate     generate a new token
import fs from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { loadConfig, saveConfig, tailscaleIPs, tailscaleDNSName, lanIPs, resolveCommand, bindHosts, CONFIG_DIR, CONFIG_PATH, claudeAgentConfig } from './config.mjs';
import { systemDeps, planAgents, detectedEntries, defaultAgyEntry, materialize, quotaSupport, lockSupport, agyDataDir, diagnose, formatDiagnosis } from './agents.mjs';

const ts = () => new Date().toISOString().replace('T', ' ').slice(0, 19);
const log = (...a) => console.log(ts(), ...a);
const cmd = process.argv[2] || 'serve';
// A background run with no console (the Windows scheduled task) sets LEASH_LOG_FILE: everything printed also goes there.
if (process.env.LEASH_LOG_FILE) {
  const file = process.env.LEASH_LOG_FILE;
  try { if (fs.statSync(file).size > 5 << 20) fs.renameSync(file, file + '.1'); } catch {}
  for (const k of ['log', 'error', 'warn']) {
    const orig = console[k].bind(console);
    console[k] = (...a) => { orig(...a); try { fs.appendFileSync(file, a.map(x => (typeof x === 'string' ? x : JSON.stringify(x))).join(' ') + '\n'); } catch {} };
  }
}
const cfg = loadConfig(CONFIG_DIR, { persist: cmd !== 'doctor' });   // doctor never writes anything
const d = systemDeps();

async function pairingInfo() {
  const ips = tailscaleIPs();
  const dns = ips.length ? await tailscaleDNSName() : null;
  const lan = lanIPs();
  // Pair over Tailscale when it is up, otherwise over the LAN (dev phase).
  const host = cfg.publicHost || dns || ips[0] || lan[0] || '127.0.0.1';
  const u = new URL('leash://pair');
  u.searchParams.set('host', host);
  u.searchParams.set('port', String(cfg.port));
  u.searchParams.set('token', cfg.token);
  u.searchParams.set('name', os.hostname().replace(/\.local$/, ''));
  return { host, dns, ips, lan, url: u.toString() };
}

async function printPairing() {
  const p = await pairingInfo();
  console.log('\nLeash pairing');
  console.log('  host :', p.host, p.ips.length ? '(tailscale ' + p.ips.join(', ') + ')' : '(no tailscale; LAN ' + (p.lan.join(', ') || 'none') + ')');
  console.log('  port :', cfg.port);
  console.log('  token:', cfg.token);
  console.log('  url  :', p.url);
  const plan = planAgents(cfg, d);
  console.log('  agents:', plan.agents.map(a => a.id + (a.ok ? '' : ' (unavailable)')).join(', '));
  for (const a of plan.agents) if (!a.ok) console.log('    ' + a.id + ': ' + a.hint);
  if (!plan.agents.some(a => a.ok)) console.log('  No agent is installed yet: run  node src/index.mjs doctor');
  try {
    const qr = (await import('qrcode-terminal')).default;
    qr.generate(p.url, { small: true }, q => console.log('\n' + q));
  } catch { console.log('(npm install to get a QR code)'); }
  console.log('Scan this in the app: Settings > Pair with QR.\n');
}

/** The agents to write to config.json: its explicit list, or what is detected now (an explicit list is authoritative from then on). */
const agentList = () => (Array.isArray(cfg.agents) && cfg.agents.length ? cfg.agents : detectedEntries(planAgents(cfg, d)));

function portFree(port) {
  return new Promise(resolve => {
    const s = net.createServer();
    s.once('error', e => resolve(e.code === 'EADDRINUSE' ? false : null));
    s.listen(port, '127.0.0.1', () => s.close(() => resolve(true)));
  });
}

if (cmd === 'doctor') {
  const ips = tailscaleIPs();
  const net_ = { port: cfg.port, bind: cfg.bind, hosts: bindHosts(cfg), tailscale: ips, dns: ips.length ? await tailscaleDNSName() : null, lan: lanIPs(), portFree: await portFree(cfg.port) };
  let bark = 'off';
  try {
    const { barkSettings } = await import('./notify.mjs');
    const bs = barkSettings(cfg.bark);
    bark = bs ? 'on (' + (bs.always ? 'always' : 'while away') + (bs.encrypt ? ', encrypted' : '') + ')' : cfg.bark ? 'config has no device key (off): node src/index.mjs bark <key>' : 'off (optional: node src/index.mjs bark <key>)';
  } catch (e) { bark = 'invalid config: ' + e.message; }
  let deps = { ok: true };
  try { await import('ws'); } catch { deps = { ok: false, hint: 'Packages are not installed: run  npm install  in ' + path.resolve(d.bridgeDir) }; }
  const r = await diagnose(cfg, d, { net: net_, bark, configExists: fs.existsSync(CONFIG_PATH), deps });
  console.log(formatDiagnosis(r));
  process.exit(r.ok ? 0 : 1);
}
if (cmd === 'pair') { await printPairing(); process.exit(0); }
if (cmd === 'rotate') { cfg.token = crypto.randomBytes(24).toString('base64url'); saveConfig(cfg); console.log('new token written to', CONFIG_PATH); await printPairing(); process.exit(0); }
if (cmd === 'add-claude') {
  // node src/index.mjs add-claude                                           Claude Code on THIS computer (claude-agent-acp, see README)
  // node src/index.mjs add-claude --fn <zsh function> --repo <Leash checkout on that host> --home <its home> [--code-mode]
  // node src/index.mjs add-claude --repo <path> --home <home> [--code-mode] ssh -T me@box     (a plain command prefix)
  const rest = process.argv.slice(3);
  const o = {};
  while (rest[0]?.startsWith('--')) {
    const f = rest.shift();
    if (f === '--code-mode') o.codeMode = true;
    else if (['--fn', '--repo', '--home'].includes(f) && rest.length) o[f.slice(2)] = rest.shift();
    else { console.error('unknown or incomplete option ' + f); process.exit(2); }
  }
  let entry;
  try { entry = claudeAgentConfig({ prefix: rest.length ? rest : null, shellFunction: o.fn ?? null, repo: o.repo, home: o.home, codeMode: !!o.codeMode }); }
  catch (e) { console.error(e.message); process.exit(2); }
  const before = agentList().filter(a => a.id !== 'claude');
  cfg.agents = [...before, entry];
  saveConfig(cfg);
  console.log('claude agent written to', CONFIG_PATH);
  console.log(JSON.stringify(entry, null, 2));
  const p = planAgents(cfg, d).agents.find(a => a.id === 'claude');
  if (!p.ok) console.log('\nNot usable yet: ' + p.hint);
  if (!before.some(a => a.id === 'agy')) console.log('\nNote: agy was not found on this computer, so it is not in the list. After installing it: node src/index.mjs add-agy');
  console.log('Restart the bridge to pick it up.');
  process.exit(0);
}
if (cmd === 'add-agy') {
  // node src/index.mjs add-agy [command]    (re)write the Antigravity entry; the command defaults to agy on PATH
  const entry = { ...defaultAgyEntry(d), ...(process.argv[3] ? { command: process.argv[3] } : {}) };
  cfg.agents = [entry, ...agentList().filter(a => a.id !== 'agy')];
  saveConfig(cfg);
  console.log('agy agent written to', CONFIG_PATH, '\n' + JSON.stringify(entry, null, 2));
  process.exit(0);
}
if (cmd === 'quota') {
  // one-shot diagnosis of the Antigravity statusLine quota probe (same code path the bridge uses)
  const { QuotaPoller } = await import('./quota.mjs');
  const plan = planAgents(cfg, d);
  const a = plan.agents.find(x => x.def.quota === 'statusline') ?? plan.agents[0];
  const qs = quotaSupport(d);
  if (!a.ok) { console.error(a.hint); process.exit(1); }
  if (!qs.ok) { console.error('quota unavailable here: ' + qs.reason); process.exit(1); }
  const p = new QuotaPoller({ agentCommand: a.command, timeoutMs: 40000, log });
  log('running', p.agentCommand, 'in', p.workDir);
  try { console.log(JSON.stringify(await p.refresh(), null, 2)); process.exit(0); }
  catch (e) { console.error('FAILED:', e.message, '\nfull TUI output:', p.workDir + '/tui.log'); process.exit(1); }
}
if (cmd === 'bark') {
  // node src/index.mjs bark                       send a test notification with the current config
  // node src/index.mjs bark <key|bark URL> [server] set the device key (the URL the Bark app shows works too), then test
  // node src/index.mjs bark off                   turn notifications off
  // node src/index.mjs bark encrypt [key|off]      end-to-end encryption (no key: generate one), then test
  const { BarkNotifier, barkSettings, parseBarkArg, validCipherKey, newCipherKey } = await import('./notify.mjs');
  const arg = process.argv[3];
  if (arg === 'off') { delete cfg.bark; saveConfig(cfg); console.log('bark notifications off'); process.exit(0); }
  if (arg === 'encrypt') {
    const given = process.argv[4];
    if (!cfg.bark?.key) { console.error('set the device key first: node src/index.mjs bark <key>'); process.exit(2); }
    if (given === 'off') { delete cfg.bark.encrypt; saveConfig(cfg); console.log('bark encryption off'); process.exit(0); }
    const key = given ?? newCipherKey();
    if (!validCipherKey(key)) { console.error('key must be 16, 24 or 32 ASCII characters (AES-128/192/256)'); process.exit(2); }
    cfg.bark.encrypt = key;
    saveConfig(cfg);
    console.log('bark encryption key written to', CONFIG_PATH);
    console.log(`In Bark: 推送加密 / Push Encryption → AES${key.length * 8}, CBC, pkcs7, IV: any 16 characters (each push sends its own)`);
    if (!given) console.log('Key:', key);
  } else if (arg) {
    try { cfg.bark = { ...(cfg.bark && typeof cfg.bark === 'object' ? cfg.bark : {}), ...parseBarkArg(arg, process.argv[4]) }; }
    catch (e) { console.error(e.message); process.exit(2); }
    saveConfig(cfg);
    console.log('bark key written to', CONFIG_PATH);
  }
  let bs;
  try { bs = barkSettings(cfg.bark); } catch (e) { console.error(e.message); process.exit(2); }
  if (!bs) { console.error('no bark key configured: node src/index.mjs bark <key>'); process.exit(2); }
  const ok = await new BarkNotifier({ settings: bs, log: m => console.error(m) }).test();
  console.log(ok ? `test notification sent${bs.encrypt ? ' (encrypted: "Decryption Failed" on the phone means the key in Bark does not match)' : ''}` : 'test notification FAILED');
  process.exit(ok ? 0 : 1);
}
if (cmd === 'remove-claude') { cfg.agents = agentList().filter(a => a.id !== 'claude'); saveConfig(cfg); console.log('removed'); process.exit(0); }
if (cmd !== 'serve') { console.error('usage: index.mjs [serve|doctor|pair|rotate|quota|bark [key|off|encrypt [key|off]]|add-claude [--fn f|prefix…] [--repo p --home h]|add-agy [command]|remove-claude]'); process.exit(2); }

// ---------------------------------------------------------------- serve
// Heavy modules load only now, so doctor / pair still run on a machine where npm install has not.
const { Hub, BRIDGE_VERSION } = await import('./hub.mjs');
const { Router } = await import('./router.mjs');
const { agyLocks } = await import('./agylock.mjs');
const { QuotaPoller, FnQuotaPoller } = await import('./quota.mjs');
const { createServer } = await import('./server.mjs');

const plan = planAgents(cfg, d);
const logged = new Set();
const logOnce = msg => { if (!logged.has(msg)) { logged.add(msg); log(msg); } };
log(plan.explicit ? 'agents from config: ' + plan.agents.map(a => a.id).join(', ') : 'no "agents" in config; detected: ' + plan.agents.map(a => a.id + (a.ok ? '' : ' (not found)')).join(', '));

const hubs = plan.agents.map(p => {
  const a = p.def;
  const command = p.command ?? a.command;
  const helper = a.helper ? [resolveCommand(a.helper[0]), ...a.helper.slice(1)] : null;
  const agentEnv = runEnv(a);
  if (p.ok) log('leash-bridge ' + BRIDGE_VERSION + '; agent ' + a.id + ': ' + command + ' ' + (a.args ?? []).join(' '));
  else log('agent ' + a.id + ' unavailable. ' + p.hint);   // once; the hub then stays down (no restart loop) and the phone shows this text
  // agy runs on this computer: lock conversations the Antigravity desktop app is busy with (see agylock.mjs)
  const isAgy = !helper && (a.lock === 'agy' || /^agy(\.(exe|cmd|bat))?$/i.test(path.basename(command)));
  let locks;
  if (a.lock === false) locks = false;
  else if (isAgy && p.ok) {
    const dataDir = a.dataDir || agyDataDir(d);
    const ls = lockSupport(d, dataDir);
    if (ls.ok) locks = agyLocks({ dataDir, ownPids: () => [hub.agent?.child?.pid] });
    else { locks = false; logOnce('[' + a.id + '] busy lock off: ' + ls.reason); }
  }
  const hub = new Hub({ id: a.id, label: a.label ?? a.id, agentCommand: command, agentArgs: a.args ?? [], agentCwd: a.cwd || os.homedir(), agentEnv,
    home: a.home || os.homedir(), sessionMeta: a.sessionMeta ?? null, helper, maxListed: a.maxListed ?? 0,
    // Claude Code (reached through a helper) drops photo-first sessions from an unscoped list; see Hub.listSessions
    listPerCwd: a.listPerCwd ?? !!helper,
    idleCloseMs: (a.idleCloseMin ?? 20) * 60000, locks, turbo: a.turbo, btw: a.btw, log,
    unavailable: p.ok ? null : p.hint,
    // "Restart" in the app looks again (the CLI may have been installed meanwhile)
    recheck: () => {
      const m = materialize(p.entry, d);
      const found = m.command ? d.find(m.command) : null;
      if (found && poller) poller.agentCommand = found;
      return found ? { command: found, args: m.args ?? [], env: runEnv(m) } : null;
    } });
  hub.on('attention', x => log('[' + a.id + '] needs input: ' + x.method + ' session=' + x.sessionId));
  // quota is read only when the phone asks for it (bridge/quota), never on a timer or after turns
  let poller = null;
  if (a.quota === 'statusline' && cfg.quota?.enabled !== false) {
    const qs = quotaSupport(d);
    if (qs.ok) { poller = new QuotaPoller({ agentCommand: command, log }); hub.attachQuota(poller); }
    else if (p.ok) logOnce('[' + a.id + '] quota off: ' + qs.reason);
  } else if (a.quota === 'helper' && helper && cfg.quota?.enabled !== false) {
    hub.attachQuota(new FnQuotaPoller({ fetch: () => hub.runHelper(['quota'], 60000), log }));
  }
  return hub;
});

/** process.env plus the entry's env overrides, minus envUnset (the local Claude Code uses its login, not an API key). */
function runEnv(a) {
  const env = { ...process.env, ...(a.env ?? {}) };
  for (const k of a.envUnset ?? []) delete env[k];
  return env;
}

const router = new Router({ hubs, log });
const server = createServer({ hub: router, token: cfg.token, log });

// Optional Bark notifications; a bad config or module error must never stop the bridge.
try {
  const { BarkNotifier, barkSettings } = await import('./notify.mjs');
  const bs = barkSettings(cfg.bark);
  if (bs) {
    const notifier = new BarkNotifier({ settings: bs, clients: () => router.clients, log });
    for (const h of hubs) notifier.watch(h);
    log('bark notifications on (' + (bs.always ? 'always' : 'while away') + (bs.encrypt ? ', encrypted' : '') + (bs.detail ? ', with titles' : '') + ')');
  } else if (cfg.bark) log('bark: config has no device key; notifications off');
} catch (e) { log('bark disabled: ' + e.message); }

async function bindAll() {
  if (server.servers.has('0.0.0.0')) return;   // already listening everywhere
  const hosts = bindHosts(cfg);
  for (const h of hosts) {
    if (server.servers.has(h)) continue;
    try { await server.listen(h, cfg.port); } catch (e) { log('cannot listen on ' + h + ':' + cfg.port + ': ' + e.message); }
  }
}
await bindAll();
if (server.servers.size === 0) { log('no listener could be started; exiting'); process.exit(1); }
if (cfg.bind === 'auto') setInterval(bindAll, 30000).unref(); // pick up Tailscale coming up later

for (const h of hubs) h.startAgent();
if (process.stdout.isTTY) await printPairing();

const shutdown = () => { log('shutting down'); router.stop(); server.close(); setTimeout(() => process.exit(0), 500); };
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
