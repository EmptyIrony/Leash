// Platform helpers shared by the bridge and remote/helper.mjs. Everything takes the platform as a parameter
// (default: this process) so the win32 behaviour is unit-tested from Linux with path.win32. Node builtins only:
// helper.mjs runs on other hosts straight from a checkout.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';

export const pathApi = (platform = process.platform) => (platform === 'win32' ? path.win32 : path.posix);

const defaultIsFile = p => { try { return fs.statSync(p).isFile(); } catch { return false; } };

// ---------------------------------------------------------------- command lookup

/** Windows executable extensions in the order cmd.exe tries them (PATHEXT), upper case, with the dot. */
export function pathExts(env = process.env) {
  const raw = env.PATHEXT || env.PathExt || '.COM;.EXE;.BAT;.CMD';
  return raw.split(';').map(s => s.trim().toUpperCase()).filter(s => s.startsWith('.'));
}

/** Directories searched after PATH (a LaunchAgent / scheduled task gets a tiny PATH; installers do not always add theirs). */
export function extraBinDirs({ platform = process.platform, env = process.env, home = os.homedir() } = {}) {
  const api = pathApi(platform);
  if (platform === 'win32') {
    const local = env.LOCALAPPDATA || api.join(home, 'AppData', 'Local');
    const roaming = env.APPDATA || api.join(home, 'AppData', 'Roaming');
    return [
      api.join(local, 'agy', 'bin'),                    // install.ps1 default (per third-party guides)
      api.join(local, 'Programs', 'agy', 'bin'),
      api.join(local, 'Programs', 'Antigravity', 'bin'),
      api.join(local, 'Antigravity'),
      api.join(local, 'Microsoft', 'WinGet', 'Links'),  // winget portable shims
      api.join(roaming, 'npm'),                         // npm -g shims (.cmd)
      api.join(home, '.local', 'bin'), api.join(home, '.antigravity', 'bin'), api.join(home, '.gemini', 'bin'),
    ];
  }
  return [api.join(home, '.local/bin'), api.join(home, 'bin'), '/opt/homebrew/bin', '/usr/local/bin', '/usr/bin', '/snap/bin',
    api.join(home, '.antigravity/bin'), api.join(home, '.gemini/bin')];
}

/**
 * Absolute path of an executable, or null. A bare name is looked up in PATH, then extraBinDirs. A name with a
 * directory part is checked as given. On Windows only PATHEXT extensions count: npm leaves an extensionless sh
 * shim next to agy.cmd, which CreateProcess cannot run.
 */
export function findCommand(cmd, { platform = process.platform, env = process.env, home = os.homedir(), isFile = defaultIsFile, extraDirs } = {}) {
  if (!cmd) return null;
  const api = pathApi(platform);
  const win = platform === 'win32';
  const exts = win ? pathExts(env) : [''];
  const variants = base => {
    if (!win) return [base];
    const own = exts.includes(api.extname(base).toUpperCase());
    return own ? [base] : exts.map(e => base + e.toLowerCase());
  };
  if (/[\\/]/.test(cmd) || api.isAbsolute(cmd)) return variants(cmd).find(isFile) ?? null;
  const pathVar = (win ? (env.PATH ?? env.Path) : env.PATH) || '';
  const dirs = [...pathVar.split(api.delimiter).map(d => d.replace(/^"(.*)"$/, '$1')), ...(extraDirs ?? extraBinDirs({ platform, env, home }))];
  for (const d of dirs) {
    if (!d) continue;
    for (const p of variants(api.join(d, cmd))) if (isFile(p)) return p;
  }
  return null;
}

// ---------------------------------------------------------------- spawning

/**
 * How to spawn `command` with `args`. A Windows .cmd/.bat (an npm shim) cannot be spawned directly (Node refuses since
 * CVE-2024-27980): it goes through cmd.exe /d /s /c with a hand-quoted line. Arguments that cmd.exe would reinterpret
 * inside quotes (a double quote, %, a line break) are refused instead of guessed at.
 */
export function spawnSpec(command, args = [], { platform = process.platform, env = process.env } = {}) {
  if (platform !== 'win32') return { command, args, options: {} };
  const options = { windowsHide: true };
  if (!/\.(cmd|bat)$/i.test(command)) return { command, args, options };
  for (const a of [command, ...args]) {
    if (/["%\r\n]/.test(String(a))) throw new Error('argument cannot be passed through cmd.exe safely: ' + JSON.stringify(String(a)));
  }
  const line = [command, ...args].map(a => '"' + String(a) + '"').join(' ');
  return { command: env.ComSpec || env.COMSPEC || 'cmd.exe', args: ['/d', '/s', '/c', '"' + line + '"'], options: { ...options, windowsVerbatimArguments: true } };
}

/** Stop a child and what it started. Windows has no process groups here: a cmd.exe wrapper would leave the agent behind. */
export function killTree(child, { platform = process.platform, spawnFn = spawn, signal = 'SIGTERM' } = {}) {
  if (!child?.pid) return;
  if (platform === 'win32') {
    try { spawnFn('taskkill', ['/pid', String(child.pid), '/T', '/F'], { stdio: 'ignore', windowsHide: true }).on?.('error', () => {}); return; } catch {}
  }
  try { child.kill(signal); } catch {}
}

/** The user's shell for "!" commands: login shell on Unix, PowerShell (pwsh, else Windows PowerShell) on Windows. */
export function shellSpec(command, { platform = process.platform, env = process.env, find = c => findCommand(c, { platform, env }) } = {}) {
  if (platform !== 'win32') return { command: env.SHELL || '/bin/bash', args: ['-lc', command], options: { detached: true } };
  const ps = find('pwsh') || find('powershell') || 'powershell.exe';
  // UTF-8 both ways (a Chinese Windows defaults to CP936); a native command's exit code becomes the shell's
  const script = '[Console]::OutputEncoding=[Text.Encoding]::UTF8; $OutputEncoding=[Text.Encoding]::UTF8\n' + command +
    '\nif ($null -ne $LASTEXITCODE -and $LASTEXITCODE -ne 0) { exit $LASTEXITCODE }';
  return { command: ps, args: ['-NoLogo', '-NoProfile', '-NonInteractive', '-Command', script], options: { windowsHide: true } };
}

// ---------------------------------------------------------------- paths

/** "~/x" or "~\x" -> home. */
export function expandHome(p, home, platform = process.platform) {
  return platform === 'win32' ? p.replace(/^~(?=$|[\\/])/, () => home) : p.replace(/^~(?=$|\/)/, () => home);
}

/** file:// URI -> path ("file:///C:/a%20b" -> "C:\a b" on Windows); anything else is returned as is. */
export function pathFromFileUri(uri, platform = process.platform) {
  const s = String(uri ?? '');
  if (!/^file:\/\//i.test(s)) return s;
  let p = s.slice(7);
  try { p = decodeURIComponent(p); } catch {}
  if (platform === 'win32') return pathApi(platform).normalize(p.replace(/^\/(?=[A-Za-z]:)/, ''));
  return p;
}

/** Path -> file:// URI ("C:\a b" -> "file:///C:/a%20b"). Posix paths without special characters come out as 'file://' + path. */
export function fileUri(p, platform = process.platform) {
  let s = String(p);
  if (platform === 'win32') { s = s.replace(/\\/g, '/'); if (!s.startsWith('/')) s = '/' + s; }
  return 'file://' + encodeURI(s).replace(/[#?]/g, c => (c === '#' ? '%23' : '%3F'));
}

/**
 * Resolve a phone-supplied path under `home` (relative paths from `cwd`, "~", file:// URIs, "/C:/x" from a URL
 * path, mixed slashes). Returns the absolute path, or null when it escapes home (other directory, other drive).
 */
export function safePath(p, { cwd = '', home, platform = process.platform } = {}) {
  const api = pathApi(platform);
  const norm = s => {
    s = pathFromFileUri(String(s), platform);
    if (platform === 'win32') s = s.replace(/^[\\/]+(?=[A-Za-z]:)/, '');
    return expandHome(s, home, platform);
  };
  const abs = p ? api.resolve(cwd ? norm(cwd) : home, norm(p)) : home;
  const rel = api.relative(home, abs);
  if (rel === '..' || rel.startsWith('..' + api.sep) || api.isAbsolute(rel)) return null;
  return abs;
}

/** Is `abs` the home directory itself (case-insensitive on Windows)? */
export const isSamePath = (a, b, platform = process.platform) => pathApi(platform).relative(a, b) === '';

const RESERVED = /^(con|prn|aux|nul|com[1-9]|lpt[1-9])$/i;

/** A stored upload name: readable (Unicode kept), no separators, spaces or brackets; Windows reserved device names avoided. */
export function uploadName(name, platform = process.platform) {
  const api = pathApi(platform);
  let n = api.basename(String(name ?? '')).normalize('NFC').replace(/[\x00-\x1f\x7f/\\:*?"<>|\[\]()\s]+/g, '_').replace(/^[._]+/, '');
  if (platform === 'win32') n = n.replace(/[._ ]+$/, '');
  if (!n) n = 'file';
  if (n.length > 120) { const ext = api.extname(n).slice(0, 16); n = n.slice(0, 120 - ext.length) + ext; }
  if (platform === 'win32' && RESERVED.test(n.replace(/\..*$/, ''))) n = '_' + n;
  return n;
}
