#!/usr/bin/env node
// Helper the Mac bridge runs on the Claude Code host over SSH (one shot, JSON on stdout):
//   helper.mjs browse [path]      list a directory (under $HOME)
//   helper.mjs read <path> [max] [mode] [cwd]
//                                 read a file (under $HOME; a relative path is taken from cwd): text (default), or
//                                 mode "base64" for the whole file's bytes (download / inline images), refused past max
//   helper.mjs upload <sessionId> <name> <bytes>
//                                 store a file sent from the phone (base64 on stdin, exactly <bytes> once decoded) in
//                                 ~/.leash/uploads/<sessionId>/ and return its path; the Mac bridge runs this file
//                                 locally for agents on the Mac itself
//   helper.mjs quota              Claude subscription limits via the local "/usage" command (0 tokens)
//   helper.mjs locks              Claude Code sessions another live process (terminal / desktop app) has open
//   helper.mjs exec <cwd> <cmd>   run a "!" shell command for the phone in a conversation's folder (login shell,
//                                 stdin closed, 120 s timeout, output capped); the Mac bridge runs this file locally
//                                 for agents on the Mac itself
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { execFile, spawn } from 'node:child_process';
import { existsSync } from 'node:fs';

const home = os.homedir();
// The bridge sends argv as one base64url(JSON) token so ssh's re-joining into a remote shell string
// can never split or interpret it (paths with spaces, $(…), ; …).
let argv = process.argv.slice(2);
if (argv[0] === '--b64') argv = JSON.parse(Buffer.from(argv[1] ?? '', 'base64url').toString('utf8'));
const [cmd, arg, arg2] = argv;
const out = v => { process.stdout.write(JSON.stringify(v)); };
const fail = (code, message, data) => { out({ error: { code, message, ...(data ? { data } : {}) } }); process.exitCode = 1; };

function safe(p, cwd) {
  const expand = s => s.replace(/^~(?=$|\/)/, home);
  const abs = p && cwd && !path.isAbsolute(expand(p)) ? path.resolve(expand(cwd), p) : path.resolve(p ? expand(p) : home);
  const rel = path.relative(home, abs);
  if (rel.startsWith('..') || path.isAbsolute(rel)) throw Object.assign(new Error('path outside home directory'), { code: -32005 });
  return abs;
}

async function browse(p) {
  const abs = safe(p);
  const ents = await fs.readdir(abs, { withFileTypes: true });
  const dirs = [], files = [];
  for (const e of ents) { if (e.name.startsWith('.')) continue; if (e.isDirectory()) dirs.push(e.name); else if (e.isFile()) files.push(e.name); }
  dirs.sort((a, b) => a.localeCompare(b)); files.sort((a, b) => a.localeCompare(b));
  let isGit = false; try { await fs.access(path.join(abs, '.git')); isGit = true; } catch {}
  return { path: abs, parent: abs === home ? null : path.dirname(abs), isGit, dirs, files: files.slice(0, 500) };
}

const MIME = {
  png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', gif: 'image/gif', webp: 'image/webp', heic: 'image/heic', svg: 'image/svg+xml',
  bmp: 'image/bmp', tif: 'image/tiff', tiff: 'image/tiff', ico: 'image/x-icon', pdf: 'application/pdf', zip: 'application/zip',
  json: 'application/json', md: 'text/markdown', txt: 'text/plain', csv: 'text/csv', html: 'text/html', htm: 'text/html',
  mp4: 'video/mp4', mov: 'video/quicktime', mp3: 'audio/mpeg', m4a: 'audio/mp4', wav: 'audio/wav',
  docx: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  xlsx: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  pptx: 'application/vnd.openxmlformats-officedocument.presentationml.presentation',
};
function mimeFor(p) { return MIME[path.extname(String(p)).slice(1).toLowerCase()] ?? 'application/octet-stream'; }
const MAX_DOWNLOAD = 32 * 1024 * 1024;   // base64 grows it by 4/3; the phone accepts frames up to 64 MiB

async function read(p, maxBytes = 512 * 1024, mode = 'text', cwd = '') {
  const abs = safe(p, cwd);
  const st = await fs.stat(abs);
  if (!st.isFile()) throw Object.assign(new Error('not a file'), { code: -32006 });
  if (mode === 'base64') {
    const cap = Math.min(Number(maxBytes) || MAX_DOWNLOAD, MAX_DOWNLOAD);
    if (st.size > cap) throw Object.assign(new Error(`file too large (${(st.size / 1048576).toFixed(1)} MB; limit ${Math.round(cap / 1048576)} MB)`), { code: -32007, data: { size: st.size, limit: cap } });
    const buf = await fs.readFile(abs);
    return { path: abs, size: buf.length, mimeType: mimeFor(abs), binary: buf.subarray(0, 8000).includes(0), data: buf.toString('base64') };
  }
  const n = Math.min(st.size, Math.max(1, Math.min(Number(maxBytes) || 524288, 4 * 1024 * 1024)));
  const fh = await fs.open(abs, 'r');
  try {
    const buf = Buffer.alloc(n); await fh.read(buf, 0, n, 0);
    const binary = buf.subarray(0, 8000).includes(0);
    return { path: abs, size: st.size, truncated: st.size > n, binary, text: binary ? null : buf.toString('utf8') };
  } finally { await fh.close(); }
}

function claudeBin() {
  if (process.env.CLAUDE_CODE_EXECUTABLE) return process.env.CLAUDE_CODE_EXECUTABLE;
  for (const d of (process.env.PATH || '').split(':')) { const p = path.join(d, 'claude'); if (d && existsSync(p)) return p; }
  const local = path.join(home, '.local/bin/claude');
  return existsSync(local) ? local : 'claude';
}

/** "/usage" is a local Claude Code command: no model call, no persisted session. */
function quota() {
  const claude = claudeBin();
  const env = { ...process.env }; delete env.ANTHROPIC_API_KEY;
  return new Promise((resolve, reject) => {
    execFile(claude, ['-p', '/usage', '--output-format', 'stream-json', '--verbose', '--no-session-persistence',
      '--strict-mcp-config', '--mcp-config', '{"mcpServers":{}}'],   // don't spin up the user's MCP servers for a quota read
      { cwd: os.tmpdir(), env, timeout: 45000, maxBuffer: 8 * 1024 * 1024 }, (err, stdout) => {
        let report = null;
        for (const line of String(stdout || '').split('\n')) {
          if (!line.trim()) continue;
          try { const j = JSON.parse(line); if (j.usage_report) report = j.usage_report; } catch {}
        }
        if (!report) return reject(err || new Error('no usage_report from claude /usage'));
        resolve(normalize(report));
      });
  });
}

function normalize(report) {
  const limits = report?.rate_limits?.limits ?? [];
  const main = { id: 'claude', label: 'Claude', buckets: [] };
  const scoped = [];
  for (const l of limits) {
    const frac = typeof l.percent === 'number' ? Math.max(0, Math.min(1, 1 - l.percent / 100)) : null;
    const b = { id: l.kind + (l.scope?.model?.display_name ? ':' + l.scope.model.display_name : ''), window: l.group === 'session' ? '5h' : (l.group || 'other'), remainingFraction: frac, resetTime: l.resets_at ? new Date(l.resets_at).toISOString().replace(/\.\d+Z$/, 'Z') : null, active: !!l.is_active };
    if (l.scope?.model?.display_name) scoped.push({ id: 'claude-' + l.scope.model.display_name.toLowerCase().replace(/\W+/g, '-'), label: 'Claude · ' + l.scope.model.display_name, model: l.scope.model.display_name, buckets: [b] });
    else main.buckets.push(b);
  }
  const order = { '5h': 0, weekly: 2, other: 4 };
  main.buckets.sort((a, b) => (order[a.window] ?? 3) - (order[b.window] ?? 3));
  return { planTier: null, pools: [main, ...scoped].filter(p => p.buckets.length), fetchedAt: Date.now() };
}

/** starttime (field 22 of /proc/<pid>/stat); comm (field 2) may contain spaces and ')' so split after the last ')'. */
async function procStart(pid) {
  try {
    const s = await fs.readFile('/proc/' + Number(pid) + '/stat', 'utf8');
    return s.slice(s.lastIndexOf(')') + 2).split(' ')[19] ?? null;   // rest starts at field 3
  } catch { return null; }
}

/**
 * Claude Code writes ~/.claude/sessions/<pid>.json for every running claude process (undocumented, may change).
 * Files outlive their process, so a holder only counts when the pid is alive in our pid namespace with the same
 * start time. sdk-* entrypoints are our own claude-agent-acp children (and `claude -p`), not "another device".
 *   held:    [{sessionId, pid, entrypoint, kind, name, status, cwd, basis}]   live foreign holders that are busy
 *            (status other than "idle": busy, shell, …); a conversation merely open in a terminal is not held
 *   touched: {sessionId: ms}   last start/update of any foreign process on that session (alive or not, any namespace)
 */
async function locks() {
  const dir = path.join(process.env.CLAUDE_CONFIG_DIR || path.join(home, '.claude'), 'sessions');
  const now = Date.now();
  let ownNs = null; try { ownNs = await fs.readlink('/proc/self/ns/pid'); } catch {}
  let names = [];
  try { names = await fs.readdir(dir); } catch { return { now, held: [], touched: {}, supported: false }; }
  const held = [], touched = {};
  for (const n of names) {
    if (!/^\d+\.json$/.test(n)) continue;
    let j; try { j = JSON.parse(await fs.readFile(path.join(dir, n), 'utf8')); } catch { continue; }
    if (!j?.sessionId || String(j.entrypoint ?? '').startsWith('sdk')) continue;
    const at = Math.max(Number(j.updatedAt) || 0, Number(j.startedAt) || 0);
    if (at > (touched[j.sessionId] ?? 0)) touched[j.sessionId] = at;
    if (!ownNs || (j.pidDomain && !String(j.pidDomain).endsWith(':' + ownNs))) continue;  // other pid namespace: cannot verify
    const st = await procStart(j.pid);
    if (st == null || (j.procStart != null && String(j.procStart) !== st)) continue;      // dead, or pid reused
    if (!j.status || j.status === 'idle') continue;                                          // open but idle: not locked
    held.push({ sessionId: j.sessionId, pid: j.pid, entrypoint: j.entrypoint ?? null, kind: j.kind ?? null, name: j.name ?? null, status: j.status, cwd: j.cwd ?? null, basis: 'busy' });
  }
  return { now, held, touched, supported: !!ownNs };
}

const SHELL_TIMEOUT_MS = 120000;
const SHELL_MAX_CHARS = 30000;   // per stream, like Claude Code's own bash output cap

/** Plain text for the phone: no ANSI colours, carriage-return progress lines collapsed to their last state. */
function clean(s) {
  return s.replace(/\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)/g, '').replace(/\x1b\[[0-9;?]*[ -\/]*[@-~]/g, '').replace(/\x1b[@-_]/g, '')
    .replace(/\r\n/g, '\n').split('\n').map(l => { l = l.replace(/\r+$/, ''); return l.slice(l.lastIndexOf('\r') + 1); }).join('\n');
}

async function exec(cwd, command) {
  // not limited to $HOME like browse/read: the command itself can go anywhere, the folder is only where it starts
  let dir = path.resolve(cwd ? cwd.replace(/^~(?=$|\/)/, home) : home);
  try { if (!(await fs.stat(dir)).isDirectory()) dir = home; } catch { dir = home; }
  const shell = process.env.SHELL || '/bin/bash';
  return new Promise(resolve => {
    const env = { ...process.env, TERM: 'dumb', NO_COLOR: '1', GIT_TERMINAL_PROMPT: '0', PAGER: 'cat', GIT_PAGER: 'cat' };
    const child = spawn(shell, ['-lc', command], { cwd: dir, env, stdio: ['ignore', 'pipe', 'pipe'], detached: true });
    const bufs = { stdout: '', stderr: '' };
    let truncated = false, timedOut = false, done = false;
    const take = k => d => { if (bufs[k].length < SHELL_MAX_CHARS) { bufs[k] += d; if (bufs[k].length > SHELL_MAX_CHARS) { bufs[k] = bufs[k].slice(0, SHELL_MAX_CHARS); truncated = true; } } else truncated = true; };
    child.stdout.setEncoding('utf8'); child.stderr.setEncoding('utf8');
    child.stdout.on('data', take('stdout')); child.stderr.on('data', take('stderr'));
    const killGroup = () => { try { process.kill(-child.pid, 'SIGKILL'); } catch {} };
    const timer = setTimeout(() => { timedOut = true; killGroup(); }, SHELL_TIMEOUT_MS);
    const finish = (exitCode, signal) => {
      if (done) return; done = true; clearTimeout(timer);
      // let go of pipes a backgrounded grandchild may still hold, or this helper (and the bridge's call) can't end
      child.stdout.destroy(); child.stderr.destroy(); child.unref();
      resolve({ stdout: clean(bufs.stdout), stderr: clean(bufs.stderr), exitCode, signal: signal ?? null, timedOut, truncated, cwd: dir });
    };
    child.on('error', e => { bufs.stderr += e.message; finish(127, null); });
    // a backgrounded grandchild ("cmd &") can hold the pipes open: don't wait for it past the shell's own exit
    child.on('exit', (code, signal) => setTimeout(() => finish(code, signal), 300));
    child.on('close', (code, signal) => finish(code, signal));
  });
}

/** Stored names stay readable (Unicode kept) but safe inside a markdown link and a path: no separators, spaces or brackets. */
function uploadName(name) {
  let n = path.basename(String(name ?? '')).normalize('NFC').replace(/[\x00-\x1f\x7f/\\:*?"<>|\[\]()\s]+/g, '_').replace(/^[._]+/, '');
  if (!n) n = 'file';
  if (n.length > 120) { const ext = path.extname(n).slice(0, 16); n = n.slice(0, 120 - ext.length) + ext; }
  return n;
}

async function upload(sessionId, name, bytes) {
  const want = Number(bytes);
  let b64 = '';
  process.stdin.setEncoding('utf8');
  for await (const chunk of process.stdin) b64 += chunk;
  const buf = Buffer.from(b64.replace(/\s+/g, ''), 'base64');
  if (!Number.isFinite(want) || buf.length !== want) throw Object.assign(new Error(`upload arrived incomplete (${buf.length} of ${want} bytes); the host transport may not forward stdin`), { code: -32008 });
  const sid = String(sessionId ?? '').replace(/[^A-Za-z0-9._-]+/g, '_').replace(/^\.+/, '') || 'misc';
  const dir = path.join(home, '.leash', 'uploads', sid);
  await fs.mkdir(dir, { recursive: true });
  const base = uploadName(name);
  const ext = path.extname(base), stem = base.slice(0, base.length - ext.length);
  for (let i = 1; i < 1000; i++) {
    const p = path.join(dir, i === 1 ? base : `${stem}-${i}${ext}`);
    try { await fs.writeFile(p, buf, { flag: 'wx' }); return { path: p, name: path.basename(p), size: buf.length, mimeType: mimeFor(p) }; }
    catch (e) { if (e.code !== 'EEXIST') throw e; }
  }
  throw Object.assign(new Error('too many files with this name'), { code: -32008 });
}

try {
  if (cmd === 'browse') out(await browse(arg));
  else if (cmd === 'read') out(await read(arg, arg2, argv[3], argv[4]));
  else if (cmd === 'upload') out(await upload(arg, arg2, argv[3]));
  else if (cmd === 'quota') out(await quota());
  else if (cmd === 'locks') out(await locks());
  else if (cmd === 'exec') out(await exec(arg, arg2));
  else fail(-32601, 'unknown helper command ' + cmd);
} catch (e) { fail(typeof e.code === 'number' ? e.code : -32603, e.message, e.data); }
