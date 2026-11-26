import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { execFile } from 'node:child_process';
import { findCommand } from './platform.mjs';

export const CONFIG_DIR = process.env.LEASH_HOME || path.join(os.homedir(), '.leash');
export const CONFIG_PATH = path.join(CONFIG_DIR, 'config.json');
export const CLAUDE_ADAPTER_PKG = '@agentclientprotocol/claude-agent-acp';

/**
 * Reads config.json (missing is fine). Fills in token / port / bind and, unless persist is false (doctor), writes them back.
 * `agents` is only ever written by add-claude / remove-claude: without it the bridge starts whatever agents it detects
 * (see agents.mjs), so a fresh install never pins another machine's setup.
 */
export function loadConfig(dir = CONFIG_DIR, { persist = true } = {}) {
  let cfg = {};
  try { cfg = JSON.parse(fs.readFileSync(path.join(dir, 'config.json'), 'utf8')); } catch {}
  let dirty = false;
  if (!cfg.token) { cfg.token = crypto.randomBytes(24).toString('base64url'); dirty = true; }
  if (!cfg.port) { cfg.port = 8787; dirty = true; }
  if (!cfg.bind) { cfg.bind = 'auto'; dirty = true; }
  if (dirty && persist) saveConfig(cfg, dir);
  if (process.env.LEASH_PORT) cfg.port = Number(process.env.LEASH_PORT);
  if (process.env.LEASH_TOKEN) cfg.token = process.env.LEASH_TOKEN;
  if (process.env.LEASH_BIND) cfg.bind = process.env.LEASH_BIND;
  return cfg;
}

/** Claude Code on this machine: claude-agent-acp is found when the bridge starts (agents.mjs), so nothing machine-specific is saved. */
export function localClaudeEntry() {
  return { id: 'claude', label: 'Claude Code', kind: 'claude-local', quota: 'helper', maxListed: 200 };
}

/**
 * Agent entry for Claude Code on another host, reached through a command prefix (e.g. ssh -T me@box) or a zsh function
 * from ~/.zshrc (`shellFunction`, e.g. one that ssh-es into the box). `repo` is the Leash checkout and `home` the home
 * directory ON THAT HOST; both are required, nothing is assumed. `zsh -ic` (interactive, so ~/.zshrc defines the
 * function) is the form verified end to end. With neither prefix nor shellFunction: the local entry.
 */
export function claudeAgentConfig({ prefix = null, shellFunction = null, repo, home, codeMode = false } = {}) {
  if (!prefix && !shellFunction) return localClaudeEntry();
  if (!repo || !home) throw new Error('a remote Claude Code needs --repo <Leash checkout on that host> and --home <its home directory>');
  const run = repo + '/bridge/remote/run-acp.sh';
  const helperScript = repo + '/bridge/remote/helper.mjs';
  const transport = prefix
    ? { command: prefix[0], args: [...prefix.slice(1), run], helper: [...prefix, 'node', helperScript] }
    : { command: 'zsh', args: ['-ic', shellFunction + ' ' + run], helper: ['zsh', '-ic', shellFunction + ' node ' + helperScript + ' "$@"', 'leash-helper'] };
  return {
    id: 'claude',
    label: 'Claude Code',
    ...transport,
    home,
    quota: 'helper',
    maxListed: 200,
    // resume sessions that were started in Claude Code's (experimental) Code Mode: opt-in, it is a personal setting
    ...(codeMode ? { sessionMeta: { claudeCode: { options: { settings: { env: { CLAUDE_CODE_EXPERIMENTAL_CODE_MODE: '1' } } } } } } : {}),
  };
}

export function saveConfig(cfg, dir = CONFIG_DIR) {
  fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
  fs.writeFileSync(path.join(dir, 'config.json'), JSON.stringify(cfg, null, 2) + '\n', { mode: 0o600 });
}

/** IPv4 addresses in the Tailscale CGNAT range 100.64.0.0/10. */
export function tailscaleIPs() {
  const out = [];
  for (const addrs of Object.values(os.networkInterfaces())) {
    for (const a of addrs ?? []) {
      if (a.family !== 'IPv4' && a.family !== 4) continue;
      const [x, y] = a.address.split('.').map(Number);
      if (x === 100 && y >= 64 && y <= 127) out.push(a.address);
    }
  }
  return [...new Set(out)];
}

/** Private LAN IPv4 addresses (192.168/16, 10/8, 172.16/12), excluding Tailscale. */
export function lanIPs() {
  const out = [];
  for (const [name, addrs] of Object.entries(os.networkInterfaces())) {
    for (const a of addrs ?? []) {
      if ((a.family !== 'IPv4' && a.family !== 4) || a.internal) continue;
      const [x, y] = a.address.split('.').map(Number);
      const priv = x === 10 || (x === 192 && y === 168) || (x === 172 && y >= 16 && y <= 31);
      if (priv) out.push({ name, address: a.address });
    }
  }
  // prefer en0 / en1 (Wi-Fi / Ethernet on macOS)
  out.sort((a, b) => (a.name.startsWith('en') ? 0 : 1) - (b.name.startsWith('en') ? 0 : 1) || a.name.localeCompare(b.name));
  return [...new Set(out.map(o => o.address))];
}

export function tailscaleDNSName() {
  const candidates = ['tailscale', '/Applications/Tailscale.app/Contents/MacOS/Tailscale', '/usr/local/bin/tailscale', '/opt/homebrew/bin/tailscale', 'C:\\Program Files\\Tailscale\\tailscale.exe'];
  return new Promise(resolve => {
    const tryNext = i => {
      if (i >= candidates.length) return resolve(null);
      execFile(candidates[i], ['status', '--json'], { timeout: 4000 }, (err, stdout) => {
        if (err) return tryNext(i + 1);
        try { const j = JSON.parse(stdout); resolve(j?.Self?.DNSName?.replace(/\.$/, '') || null); } catch { tryNext(i + 1); }
      });
    };
    tryNext(0);
  });
}

/** Resolve a bare command name against PATH plus common install locations (LaunchAgents get a tiny PATH); unresolved names come back as given. */
export function resolveCommand(cmd) { return findCommand(cmd) ?? cmd; }

/** Addresses the bridge listens on: `bind` as configured, or (auto) loopback + Tailscale + the 192.168 LAN. */
export function bindHosts(cfg) {
  if (cfg.bind !== 'auto') return String(cfg.bind).split(',').map(s => s.trim()).filter(Boolean);
  // Tailscale (100.64/10) and the home LAN (192.168/16) only, so other interfaces (VPNs, Docker, …) stay closed.
  const hosts = [...tailscaleIPs(), ...lanIPs().filter(ip => ip.startsWith('192.168.'))];
  // Neither is up yet: fall back to all interfaces so the phone can reach us at all.
  return hosts.length ? ['127.0.0.1', ...hosts] : ['0.0.0.0'];
}
