// Which agents can this machine run? Detection, the plan the bridge starts from, and the data behind "doctor".
// Explicit config ("agents" in config.json) is authoritative; without it the bridge starts whatever it detects
// (Antigravity "agy", a local Claude Code ACP adapter). Nothing here installs anything.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFile } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { findCommand, pathApi, spawnSpec, shellSpec } from './platform.mjs';
import { CONFIG_PATH, CLAUDE_ADAPTER_PKG, localClaudeEntry } from './config.mjs';

const BRIDGE_DIR = fileURLToPath(new URL('..', import.meta.url));

/** Everything environment-dependent, injectable for tests. */
export function systemDeps(over = {}) {
  const platform = over.platform ?? process.platform;
  const env = over.env ?? process.env;
  const home = over.home ?? os.homedir();
  const find = over.find ?? (c => findCommand(c, { platform, env, home, isFile: over.isFile, extraDirs: over.extraDirs }));
  return { platform, env, home, find, exists: over.exists ?? (p => fs.existsSync(p)), readJson: over.readJson ?? readJson,
    bridgeDir: over.bridgeDir ?? BRIDGE_DIR, configPath: over.configPath ?? CONFIG_PATH };
}

function readJson(p) { try { return JSON.parse(fs.readFileSync(p, 'utf8')); } catch { return null; } }

/** The desktop app's data dir under ~/.gemini; "--app-data-dir antigravity" points agy at it (agy's own default is antigravity-cli). */
export const agyDataDir = d => pathApi(d.platform).join(d.home, '.gemini', 'antigravity');

export function defaultAgyEntry(d) {
  // desktop conversations replay over ACP only when agy shares the desktop app's data dir; without the app there is none
  const args = d.exists(agyDataDir(d)) ? ['acp', '--app-data-dir', 'antigravity'] : ['acp'];
  return { id: 'agy', label: 'Antigravity', command: 'agy', args, quota: 'statusline' };
}

/**
 * Where a local claude-agent-acp lives: $LEASH_CLAUDE_ACP_DIR or ~/.leash/claude-acp (what the README installs), next to
 * the bridge (bridge/node_modules), or a global "claude-agent-acp" command. Never npx: nothing is downloaded behind your back.
 */
export function findClaudeAdapter(d) {
  const api = pathApi(d.platform);
  const rel = [...CLAUDE_ADAPTER_PKG.split('/'), 'dist', 'index.js'];
  const roots = [d.env.LEASH_CLAUDE_ACP_DIR, api.join(d.home, '.leash', 'claude-acp'), d.bridgeDir].filter(Boolean);
  for (const r of roots) {
    const js = api.join(r, 'node_modules', ...rel);
    if (d.exists(js)) return { kind: 'node', path: js, version: d.readJson(api.join(r, 'node_modules', ...CLAUDE_ADAPTER_PKG.split('/'), 'package.json'))?.version ?? null };
  }
  const bin = d.find('claude-agent-acp');
  return bin ? { kind: 'bin', path: bin, version: null } : null;
}

/** Fill in what a "claude-local" entry leaves out (adapter location, local helper, env); other entries pass through. */
export function materialize(entry, d) {
  if (entry.kind !== 'claude-local') return entry;
  const adapter = findClaudeAdapter(d);
  const claude = d.find('claude');
  const base = { home: d.home, helper: [process.execPath, pathApi(d.platform).join(d.bridgeDir, 'remote', 'helper.mjs')],
    env: claude ? { CLAUDE_CODE_EXECUTABLE: claude } : {}, envUnset: ['ANTHROPIC_API_KEY'] };   // the subscription login, not an API key from the environment
  const run = !adapter ? { command: null, args: [] } : adapter.kind === 'node' ? { command: process.execPath, args: [adapter.path] } : { command: adapter.path, args: [] };
  return { ...base, ...run, ...entry, adapter, claudeCli: claude };
}

const AGY_HINT = 'agy not found: install the Antigravity CLI and open a new terminal so PATH is refreshed, or set "command" to its full path';
export function installHint(entry, d) {
  const dir = pathApi(d.platform).join(d.home, '.leash', 'claude-acp');
  if (entry.kind === 'claude-local') return 'Claude Code adapter not found: npm install --prefix "' + dir + '" ' + CLAUDE_ADAPTER_PKG + ' (needs the claude CLI, logged in); the bridge finds it on its next start';
  if (entry.id === 'agy' || /^agy(\.|$)/i.test(path.basename(String(entry.command ?? '')))) return AGY_HINT;
  return 'command "' + entry.command + '" not found: fix "command" of agent "' + entry.id + '" in ' + d.configPath;
}

/**
 * What the bridge starts. Returns {explicit, agents: [{id, label, def, command, ok, hint, source}], absent}.
 *  - agents in config: all of them, in order; a missing command is reported as unavailable, never dropped
 *  - no agents in config: only what is detected (agy, local Claude Code); none found -> one unavailable agy so the bridge
 *    still runs and the phone shows why
 * LEASH_AGENT_CMD / LEASH_AGENT_ARGS replace the first agent's command (tests, single-agent debugging).
 */
export function planAgents(cfg, d = systemDeps()) {
  const probe = (entry, source) => {
    const def = materialize(entry, d);
    const command = def.command ? d.find(def.command) : null;
    return { id: def.id, label: def.label ?? def.id, entry, def, command, ok: !!command, hint: command ? null : installHint(entry, d), source };
  };
  const override = d.env.LEASH_AGENT_CMD;
  let explicit = Array.isArray(cfg.agents) && cfg.agents.length > 0;
  let defs = explicit ? cfg.agents : null;
  if (override) {
    defs = (defs ?? [defaultAgyEntry(d)]).map((a, i) => i === 0
      ? { ...a, command: override, args: d.env.LEASH_AGENT_ARGS ? JSON.parse(d.env.LEASH_AGENT_ARGS) : a.args } : a);
    explicit = true;
  }
  if (explicit) return { explicit, agents: defs.map(a => probe(a, 'config')), absent: [] };
  const found = [probe(defaultAgyEntry(d), 'detected'), probe(localClaudeEntry(), 'detected')];
  const ok = found.filter(a => a.ok);
  if (ok.length) return { explicit, agents: ok, absent: found.filter(a => !a.ok) };
  const [agy, claude] = found;
  return { explicit, agents: [{ ...agy, source: 'default', hint: agy.hint + ' (or set up Claude Code: ' + claude.hint + ')' }], absent: [] };
}

/** Entries to write to config.json when the config had no explicit list yet (add-claude, remove-claude): what is detected now. */
export const detectedEntries = plan => plan.agents.filter(a => a.ok).map(a => a.entry);

// ---------------------------------------------------------------- what the platform lets us do

/** Antigravity quota needs a pty for agy's TUI: script(1). */
export function quotaSupport(d) {
  if (d.platform === 'win32') return { ok: false, reason: 'agy quota needs a pty (script), which Windows does not have' };
  if (!d.find('script')) return { ok: false, reason: '`script` (util-linux / bsdutils) not found' };
  return { ok: true };
}

/** The Antigravity "busy on the desktop" lock reads presence locks (lsof), ps and the summaries DB (sqlite3). */
export function lockSupport(d, dataDir = agyDataDir(d)) {
  if (d.platform === 'win32') return { ok: false, reason: 'needs lsof / ps / sqlite3, which Windows does not have (conversations are never locked)' };
  const missing = ['lsof', 'ps', 'sqlite3'].filter(c => !d.find(c));
  if (missing.length) return { ok: false, reason: missing.join(', ') + ' not found' };
  if (!d.exists(dataDir)) return { ok: false, reason: dataDir + ' does not exist (no desktop app data)' };
  return { ok: true };
}

// ---------------------------------------------------------------- doctor

function run(command, args, d, timeout = 5000) {
  return new Promise(resolve => {
    let spec; try { spec = spawnSpec(command, args, { platform: d.platform, env: d.env }); } catch (e) { return resolve({ error: e.message }); }
    execFile(spec.command, spec.args, { timeout, windowsHide: true, ...spec.options }, (err, stdout, stderr) =>
      resolve(err ? { error: err.message.split('\n')[0] } : { out: String(stdout || stderr).split('\n').map(s => s.trim()).find(Boolean)?.slice(0, 80) ?? '' }));
  });
}

/** Everything "doctor" prints, as data. */
export async function diagnose(cfg, d = systemDeps(), { net, bark, configExists, deps = null, probeVersion = run } = {}) {
  const plan = planAgents(cfg, d);
  const major = Number(process.versions.node.split('.')[0]);
  const agents = [];
  for (const a of plan.agents) {
    let version = null;
    if (a.ok && a.def.kind === 'claude-local') version = a.def.adapter?.version ?? null;
    else if (a.ok && !a.def.helper) version = (await probeVersion(a.command, ['--version'], d)).out ?? null;
    agents.push({ id: a.id, label: a.label, ok: a.ok, command: a.command, args: a.def.args ?? [], version, source: a.source, hint: a.hint, fallback: a.source === 'default',
      remote: !!a.def.helper && a.def.kind !== 'claude-local', note: a.def.kind === 'claude-local' && a.ok && !a.def.claudeCli ? 'claude CLI not found: the adapter needs it (install Claude Code and log in)' : null });
  }
  const dataDir = agyDataDir(d);
  const sh = shellSpec('x', { platform: d.platform, env: d.env, find: d.find });
  const usable = agents.filter(a => a.ok);
  const problems = [];
  if (major < 20) problems.push('Node ' + process.versions.node + ' is too old: install Node 20 or newer (the current LTS)');
  if (deps && !deps.ok) problems.push(deps.hint);
  if (!usable.length) problems.push('No agent can run on this machine. ' + (agents[0]?.hint ?? ''));
  for (const a of agents) if (!a.ok && !a.fallback) problems.push(a.id + ': ' + a.hint);
  for (const a of plan.absent) problems.push('Not detected (optional): ' + a.id + ' - ' + a.hint);
  for (const a of agents) if (a.note) problems.push(a.id + ': ' + a.note);
  return {
    node: { version: process.versions.node, ok: major >= 20 }, platform: d.platform + ' ' + os.arch(),
    configPath: d.configPath, configExists: !!configExists, explicit: plan.explicit, agents,
    agyDataDir: { path: dataDir, exists: d.exists(dataDir) },
    quota: quotaSupport(d), lock: lockSupport(d, dataDir), shell: { command: sh.command },
    net: net ?? null, bark: bark ?? null, deps, problems, ok: major >= 20 && usable.length > 0 && (!deps || deps.ok),
  };
}

export function formatDiagnosis(r) {
  const L = [];
  const row = (k, v) => L.push('  ' + k.padEnd(12) + v);
  L.push('Leash bridge doctor');
  row('Node', 'v' + r.node.version + (r.node.ok ? ' (ok)' : ' (too old; needs 20 or newer)'));
  row('Platform', r.platform);
  if (r.deps) row('Packages', r.deps.ok ? 'installed' : 'MISSING (run: npm install, in the bridge folder)');
  row('Config', r.configPath + (r.configExists ? '' : ' (not created yet)'));
  row('Agents', r.explicit ? 'from config' : 'detected automatically (no "agents" in config)');
  for (const a of r.agents) {
    L.push('    ' + (a.ok ? '[ok]     ' : '[missing]') + ' ' + a.id + ' (' + a.label + ')' + (a.ok ? '  ' + (a.command ?? '') + (a.version ? '  ' + a.version : '') + (a.remote ? '  (remote host, not probed)' : '') : '  ' + a.hint));
    if (a.ok && a.args.length) L.push('               args: ' + a.args.join(' '));
  }
  row('agy data', r.agyDataDir.path + (r.agyDataDir.exists ? ' (found: desktop conversations replay)' : ' (not found: plain "agy acp")'));
  row('Quota', r.quota.ok ? 'on, read from the agy status line when the app asks' : 'off: ' + r.quota.reason);
  row('Busy lock', r.lock.ok ? 'on (Antigravity desktop)' : 'off: ' + r.lock.reason);
  row('"!" shell', r.shell.command);
  if (r.net) {
    row('Port', r.net.port + (r.net.portFree === false ? ' (in use: is the bridge already running?)' : r.net.portFree ? ' (free)' : ''));
    row('Bind', r.net.bind + ' -> ' + r.net.hosts.join(', '));
    row('Tailscale', r.net.tailscale.length ? r.net.tailscale.join(', ') + (r.net.dns ? ' (' + r.net.dns + ')' : '') : 'not detected (the phone can only reach this machine over the LAN)');
    row('LAN', r.net.lan.length ? r.net.lan.join(', ') : 'none');
    if (r.platform.startsWith('win32')) L.push('               Windows Firewall asks about Node the first time it listens: allow it on Private networks.');
  }
  if (r.bark !== null) row('Bark', r.bark);
  if (r.problems.length) { L.push('', 'To fix:'); for (const p of r.problems) L.push('  - ' + p); }
  L.push('', r.ok ? 'Ready: run  node src/index.mjs pair  to show the QR code.' : 'Not ready yet: fix the items above, then run doctor again.');
  return L.join('\n');
}
