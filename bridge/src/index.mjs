#!/usr/bin/env node
// Leash bridge: exposes `agy acp` (and other ACP agents) on this Mac to the Leash iOS app over Tailscale.
//   node src/index.mjs          start the bridge
//   node src/index.mjs pair     print pairing info / QR code
//   node src/index.mjs rotate   generate a new token
import os from 'node:os';
import { Hub, BRIDGE_VERSION } from './hub.mjs';
import { Router } from './router.mjs';
import { agyLocks } from './agylock.mjs';
import path from 'node:path';
import { QuotaPoller, FnQuotaPoller } from './quota.mjs';
import { createServer } from './server.mjs';
import { loadConfig, saveConfig, tailscaleIPs, tailscaleDNSName, lanIPs, resolveCommand, CONFIG_PATH, claudeAgentConfig } from './config.mjs';
import crypto from 'node:crypto';

const ts = () => new Date().toISOString().replace('T', ' ').slice(0, 19);
const log = (...a) => console.log(ts(), ...a);
const cmd = process.argv[2] || 'serve';
const cfg = loadConfig();

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
  try {
    const qr = (await import('qrcode-terminal')).default;
    qr.generate(p.url, { small: true }, q => console.log('\n' + q));
  } catch { console.log('(npm install to get a QR code)'); }
  console.log('Scan this in the app: Settings > Pair with QR.\n');
}

if (cmd === 'pair') { await printPairing(); process.exit(0); }
if (cmd === 'rotate') { cfg.token = crypto.randomBytes(24).toString('base64url'); saveConfig(cfg); console.log('new token written to', CONFIG_PATH); await printPairing(); process.exit(0); }
if (cmd === 'add-claude') {
  // node src/index.mjs add-claude                      (uses the 'ubuntu' zsh function)
  // node src/index.mjs add-claude --fn myhost          (another zsh function)
  // node src/index.mjs add-claude ssh -T me@box        (a plain command prefix)
  const rest = process.argv.slice(3);
  const entry = rest[0] === '--fn' ? claudeAgentConfig({ shellFunction: rest[1] }) : claudeAgentConfig(rest.length ? { prefix: rest } : {});
  cfg.agents = [...cfg.agents.filter(a => a.id !== 'claude'), entry];
  saveConfig(cfg);
  console.log('claude agent written to', CONFIG_PATH);
  console.log(JSON.stringify(entry, null, 2));
  process.exit(0);
}
if (cmd === 'quota') {
  // one-shot diagnosis of the Antigravity statusLine quota probe (same code path the bridge uses)
  const a = cfg.agents.find(x => x.quota === 'statusline') ?? cfg.agents[0];
  const p = new QuotaPoller({ agentCommand: resolveCommand(a.command), timeoutMs: 40000, log });
  log('running', p.agentCommand, 'in', p.workDir);
  try { console.log(JSON.stringify(await p.refresh(), null, 2)); process.exit(0); }
  catch (e) { console.error('FAILED:', e.message, '\nfull TUI output:', p.workDir + '/tui.log'); process.exit(1); }
}
if (cmd === 'remove-claude') { cfg.agents = cfg.agents.filter(a => a.id !== 'claude'); saveConfig(cfg); console.log('removed'); process.exit(0); }
if (cmd !== 'serve') { console.error('usage: index.mjs [serve|pair|rotate|quota|add-claude [prefix…]|remove-claude]'); process.exit(2); }

// env overrides (tests / single-agent debugging) replace the first agent's command
const agentDefs = cfg.agents.map((a, i) => i === 0 && process.env.LEASH_AGENT_CMD
  ? { ...a, command: process.env.LEASH_AGENT_CMD, args: process.env.LEASH_AGENT_ARGS ? JSON.parse(process.env.LEASH_AGENT_ARGS) : a.args }
  : a);

const hubs = agentDefs.map(a => {
  const command = resolveCommand(a.command);
  log(`leash-bridge ${BRIDGE_VERSION}; agent ${a.id}: ${command} ${(a.args ?? []).join(' ')}`);
  const helper = a.helper ? [resolveCommand(a.helper[0]), ...a.helper.slice(1)] : null;
  // agy runs here on the Mac: lock conversations the Antigravity desktop app is busy with (see agylock.mjs)
  const isAgy = !helper && (a.lock === 'agy' || path.basename(command) === 'agy');
  const locks = a.lock === false ? false : isAgy ? agyLocks({ ownPids: () => [hub.agent?.child?.pid] }) : undefined;
  const hub = new Hub({ id: a.id, label: a.label ?? a.id, agentCommand: command, agentArgs: a.args ?? [], agentCwd: a.cwd || os.homedir(),
    home: a.home || os.homedir(), sessionMeta: a.sessionMeta ?? null, helper, maxListed: a.maxListed ?? 0,
    idleCloseMs: (a.idleCloseMin ?? 20) * 60000, locks, log });
  hub.on('attention', p => log(`[${a.id}] needs input: ${p.method} session=${p.sessionId}`));
  // quota moves with every turn: refresh after a turn ends (at most every 2 min)
  hub.on('turn_end', () => { hub.quota?.refresh({ maxAgeMs: 120000 }).catch(() => {}); });
  if (a.quota === 'statusline' && cfg.quota?.enabled !== false) {
    hub.attachQuota(new QuotaPoller({ agentCommand: command, intervalMs: (cfg.quota?.intervalSec ?? 600) * 1000, log }));
  } else if (a.quota === 'helper' && helper && cfg.quota?.enabled !== false) {
    hub.attachQuota(new FnQuotaPoller({ fetch: () => hub.runHelper(['quota'], 60000), intervalMs: (cfg.quota?.intervalSec ?? 600) * 1000, log }));
  }
  return hub;
});
const router = new Router({ hubs, log });
const server = createServer({ hub: router, token: cfg.token, log });

function bindHosts() {
  if (cfg.bind !== 'auto') return String(cfg.bind).split(',').map(s => s.trim()).filter(Boolean);
  const ts = tailscaleIPs();
  // No Tailscale yet: fall back to all interfaces so the phone can reach us on the LAN.
  return ts.length ? ['127.0.0.1', ...ts] : ['0.0.0.0'];
}

async function bindAll() {
  if (server.servers.has('0.0.0.0')) return;   // already listening everywhere
  const hosts = bindHosts();
  for (const h of hosts) {
    if (server.servers.has(h)) continue;
    try { await server.listen(h, cfg.port); } catch (e) { log(`cannot listen on ${h}:${cfg.port}: ${e.message}`); }
  }
}
await bindAll();
if (server.servers.size === 0) { log('no listener could be started; exiting'); process.exit(1); }
if (cfg.bind === 'auto') setInterval(bindAll, 30000).unref(); // pick up Tailscale coming up later

for (const h of hubs) { h.startAgent(); h.quota?.start(); }
if (process.stdout.isTTY) await printPairing();

const shutdown = () => { log('shutting down'); router.stop(); for (const h of hubs) h.quota?.stop(); server.close(); setTimeout(() => process.exit(0), 500); };
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
