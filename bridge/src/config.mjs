import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { execFile } from 'node:child_process';

export const CONFIG_DIR = process.env.LEASH_HOME || path.join(os.homedir(), '.leash');
export const CONFIG_PATH = path.join(CONFIG_DIR, 'config.json');
// Use the desktop app's data dir so desktop conversations replay their history over ACP.
export const DEFAULT_AGENT_ARGS = ['acp', '--app-data-dir', 'antigravity'];

export function loadConfig(dir = CONFIG_DIR) {
  let cfg = {};
  try { cfg = JSON.parse(fs.readFileSync(path.join(dir, 'config.json'), 'utf8')); } catch {}
  let dirty = false;
  if (!cfg.token) { cfg.token = crypto.randomBytes(24).toString('base64url'); dirty = true; }
  if (!cfg.port) { cfg.port = 8787; dirty = true; }
  if (!cfg.bind) { cfg.bind = 'auto'; dirty = true; }
  if (!cfg.agent) { cfg.agent = { command: 'agy', args: DEFAULT_AGENT_ARGS }; dirty = true; }
  // migrate configs written by v0.1.0 (plain `agy acp`, which cannot replay desktop history)
  if (JSON.stringify(cfg.agent.args) === JSON.stringify(['acp'])) { cfg.agent.args = DEFAULT_AGENT_ARGS; dirty = true; }
  if (!Array.isArray(cfg.agents)) {
    cfg.agents = [{ id: 'agy', label: 'Antigravity', command: cfg.agent?.command ?? 'agy', args: cfg.agent?.args ?? DEFAULT_AGENT_ARGS, quota: 'statusline' }];
    dirty = true;
  }
  if (dirty) saveConfig(cfg, dir);
  if (process.env.LEASH_PORT) cfg.port = Number(process.env.LEASH_PORT);
  if (process.env.LEASH_TOKEN) cfg.token = process.env.LEASH_TOKEN;
  if (process.env.LEASH_BIND) cfg.bind = process.env.LEASH_BIND;
  return cfg;
}

/** Default agent entry for Claude Code reached through a command prefix (e.g. ssh) on another host. */
export function claudeAgentConfig({ prefix = null, shellFunction = 'ubuntu', repo = '/config/workspace/Leash', home = '/config' } = {}) {
  const run = repo + '/bridge/remote/run-acp.sh';
  const helperScript = repo + '/bridge/remote/helper.mjs';
  // Default: the host is reached through a zsh function from ~/.zshrc (e.g. `ubuntu` = ssh into the box),
  // so start it via zsh after sourcing the rc file. A plain command prefix (e.g. ssh -T user@host) also works.
  // `zsh -ic` (interactive, so ~/.zshrc defines the function) is the form probe9 verified end to end.
  const transport = prefix
    ? { command: prefix[0], args: [...prefix.slice(1), run], helper: [...prefix, 'node', helperScript] }
    : { command: 'zsh', args: ['-ic', shellFunction + ' ' + run], helper: ['zsh', '-ic', shellFunction + ' node ' + helperScript + ' "$@"', 'leash-helper'],
        // "Open on Computer": a new Ghostty window on this Mac (the ubuntu script's --ghostty-window), ssh with a tty (-t);
        // the bridge appends the remote command as one argument
        open: ['zsh', '-ic', shellFunction + ' --ghostty-window -t "$@"', 'leash-open'] };
  return {
    id: 'claude',
    label: 'Claude Code',
    ...transport,
    home,
    quota: 'helper',
    maxListed: 200,
    // resume sessions that were started in Claude Code's (experimental) Code Mode
    sessionMeta: { claudeCode: { options: { settings: { env: { CLAUDE_CODE_EXPERIMENTAL_CODE_MODE: '1' } } } } },
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
  const candidates = ['tailscale', '/Applications/Tailscale.app/Contents/MacOS/Tailscale', '/usr/local/bin/tailscale', '/opt/homebrew/bin/tailscale'];
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

/** Resolve a bare command name against PATH plus common install locations (LaunchAgents get a tiny PATH). */
export function resolveCommand(cmd) {
  if (!cmd || cmd.includes('/')) return cmd;
  const extra = [path.join(os.homedir(), '.local/bin'), '/opt/homebrew/bin', '/usr/local/bin', path.join(os.homedir(), '.antigravity/bin'), path.join(os.homedir(), '.gemini/bin')];
  const dirs = [...(process.env.PATH || '').split(':'), ...extra];
  for (const d of dirs) {
    if (!d) continue;
    const p = path.join(d, cmd);
    try { fs.accessSync(p, fs.constants.X_OK); return p; } catch {}
  }
  return cmd;
}
