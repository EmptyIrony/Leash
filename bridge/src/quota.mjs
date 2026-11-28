// Reads Antigravity quota through the official statusLine hook of the agy CLI:
// a hidden TUI session is started (script(1) on Unix, Windows ConPTY on win32) with a session-only
// --settings statusLine command that dumps agy's status JSON (which includes "quota") to a file. No prompt is sent.
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { CONFIG_DIR } from './config.mjs';
import { findCommand, killTree } from './platform.mjs';

const GROUP_LABELS = { 'gemini': 'Gemini', '3p': 'Claude & GPT', 'gemini-argon-wtu': 'Argon', 'gemini-argon': 'Argon' };

/** statusLine "quota" map -> [{id, group, label, window, remainingFraction, resetTime}] grouped into pools. */
export function normalizeQuota(status) {
  const q = status?.quota;
  if (!q || typeof q !== 'object') return null;
  const pools = new Map();
  for (const [id, b] of Object.entries(q)) {
    if (!b || b.disabled) continue;
    const m = /^(.*?)-(5h|weekly|daily|monthly)$/i.exec(id);
    const group = m ? m[1] : id;
    const window = m ? m[2].toLowerCase() : 'other';
    const frac = typeof b.remaining_fraction === 'number' ? Math.max(0, Math.min(1, b.remaining_fraction)) : null;
    const pool = pools.get(group) ?? { id: group, label: GROUP_LABELS[group] ?? group.replace(/[-_]+/g, ' ').replace(/^./, c => c.toUpperCase()), buckets: [] };
    pool.buckets.push({ id, window, remainingFraction: frac, remainingAmount: b.remaining_amount ?? null, resetTime: b.reset_time ?? null });
    pools.set(group, pool);
  }
  const order = { '5h': 0, daily: 1, weekly: 2, monthly: 3, other: 4 };
  const list = [...pools.values()].map(p => ({ ...p, buckets: p.buckets.sort((a, b) => order[a.window] - order[b.window]) }));
  const rank = id => (id === 'gemini' ? 0 : id === '3p' ? 1 : 2);
  list.sort((a, b) => rank(a.id) - rank(b.id) || a.id.localeCompare(b.id));
  return { planTier: status.plan_tier ?? null, pools: list, fetchedAt: Date.now() };
}

/**
 * Quota from a function that returns it normalized (e.g. the Claude host helper's "quota" command). Fetched only
 * when the phone asks (bridge/quota); nothing runs on a timer.
 */
export class FnQuotaPoller {
  constructor({ fetch, log = () => {}, onUpdate = () => {} }) {
    Object.assign(this, { fetch, log, onUpdate });
    this.latest = null; this.error = null; this.inflight = null;
  }
  refresh({ maxAgeMs = 0 } = {}) {
    if (this.latest && Date.now() - this.latest.fetchedAt < maxAgeMs) return Promise.resolve(this.latest);
    if (this.inflight) return this.inflight;
    this.inflight = (async () => {
      try { const q = await this.fetch(); if (!q?.pools) throw new Error('no quota'); q.fetchedAt ??= Date.now(); this.latest = q; this.error = null; this.onUpdate(q); return q; }
      catch (e) { this.error = e.message; this.log('quota: ' + e.message); throw e; }
      finally { this.inflight = null; }
    })();
    return this.inflight;
  }
}

function psSingleQuote(s) {
  return "'" + String(s).replace(/'/g, "''") + "'";
}

/** PowerShell script that allocates a headless Windows ConPTY (CreatePseudoConsole) and runs `exe` with `args`. */
export function winConPtyScript({ exe, args, cwd, tuiLog, maxWaitMs }) {
  const argArray = args.map(psSingleQuote).join(', ');
  return `
$ErrorActionPreference = 'Stop'
if (-not ('LeashConPty' -as [type])) {
Add-Type -TypeDefinition @'
using System;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using Microsoft.Win32.SafeHandles;

public static class LeashConPty {
  const uint EXTENDED_STARTUPINFO_PRESENT = 0x00080000;
  static readonly IntPtr PROC_THREAD_ATTRIBUTE_PSEUDOCONSOLE = (IntPtr)0x00020016;
  [StructLayout(LayoutKind.Sequential)] struct COORD { public short X; public short Y; }
  [StructLayout(LayoutKind.Sequential)] struct SECURITY_ATTRIBUTES { public int nLength; public IntPtr lpSecurityDescriptor; [MarshalAs(UnmanagedType.Bool)] public bool bInheritHandle; }
  [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
  struct STARTUPINFO {
    public int cb; public string lpReserved; public string lpDesktop; public string lpTitle;
    public int dwX, dwY, dwXSize, dwYSize, dwXCountChars, dwYCountChars, dwFillAttribute, dwFlags;
    public short wShowWindow; public short cbReserved2; public IntPtr lpReserved2;
    public IntPtr hStdInput; public IntPtr hStdOutput; public IntPtr hStdError;
  }
  [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)] struct STARTUPINFOEX { public STARTUPINFO StartupInfo; public IntPtr lpAttributeList; }
  [StructLayout(LayoutKind.Sequential)] struct PROCESS_INFORMATION { public IntPtr hProcess; public IntPtr hThread; public int dwProcessId; public int dwThreadId; }
  [DllImport("kernel32.dll", SetLastError = true)] [return: MarshalAs(UnmanagedType.Bool)] static extern bool CreatePipe(out IntPtr hReadPipe, out IntPtr hWritePipe, ref SECURITY_ATTRIBUTES lpPipeAttributes, int nSize);
  [DllImport("kernel32.dll", SetLastError = true)] static extern int CreatePseudoConsole(COORD size, IntPtr hInput, IntPtr hOutput, uint dwFlags, out IntPtr phPC);
  [DllImport("kernel32.dll", SetLastError = true)] static extern void ClosePseudoConsole(IntPtr hPC);
  [DllImport("kernel32.dll", SetLastError = true)] [return: MarshalAs(UnmanagedType.Bool)] static extern bool InitializeProcThreadAttributeList(IntPtr lpAttributeList, int dwAttributeCount, int dwFlags, ref IntPtr lpSize);
  [DllImport("kernel32.dll", SetLastError = true)] [return: MarshalAs(UnmanagedType.Bool)] static extern bool UpdateProcThreadAttribute(IntPtr lpAttributeList, uint dwFlags, IntPtr Attribute, IntPtr lpValue, IntPtr cbSize, IntPtr lpPreviousValue, IntPtr lpReturnSize);
  [DllImport("kernel32.dll", SetLastError = true)] static extern void DeleteProcThreadAttributeList(IntPtr lpAttributeList);
  [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)] [return: MarshalAs(UnmanagedType.Bool)]
  static extern bool CreateProcessW(string lpApplicationName, StringBuilder lpCommandLine, IntPtr lpProcessAttributes, IntPtr lpThreadAttributes,
    [MarshalAs(UnmanagedType.Bool)] bool bInheritHandles, uint dwCreationFlags, IntPtr lpEnvironment, string lpCurrentDirectory, ref STARTUPINFOEX lpStartupInfo, out PROCESS_INFORMATION lpProcessInformation);
  [DllImport("kernel32.dll", SetLastError = true)] static extern uint WaitForSingleObject(IntPtr hHandle, uint dwMilliseconds);
  [DllImport("kernel32.dll", SetLastError = true)] [return: MarshalAs(UnmanagedType.Bool)] static extern bool GetExitCodeProcess(IntPtr hProcess, out uint lpExitCode);
  [DllImport("kernel32.dll", SetLastError = true)] [return: MarshalAs(UnmanagedType.Bool)] static extern bool CloseHandle(IntPtr hObject);

  static string QuoteArg(string arg) {
    if (arg == null) return "\\"\\"";
    if (arg.Length > 0 && arg.IndexOfAny(new[] { ' ', '\\t', '\\n', '\\v', '"' }) < 0) return arg;
    var sb = new StringBuilder().Append('"'); int bs = 0;
    foreach (char c in arg) {
      if (c == '\\\\') bs++;
      else if (c == '"') { sb.Append('\\\\', bs * 2 + 1).Append('"'); bs = 0; }
      else { if (bs > 0) { sb.Append('\\\\', bs); bs = 0; } sb.Append(c); }
    }
    if (bs > 0) sb.Append('\\\\', bs * 2);
    return sb.Append('"').ToString();
  }

  static void KillTree(int pid) {
    try {
      var psi = new ProcessStartInfo("taskkill", "/pid " + pid + " /T /F") { UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true };
      using (var p = Process.Start(psi)) { if (p != null) p.WaitForExit(3000); }
    } catch {}
  }

  public static int Run(string exePath, string[] args, string cwd, string logPath, int maxWaitMs) {
    IntPtr inRead = IntPtr.Zero, inWrite = IntPtr.Zero, outRead = IntPtr.Zero, outWrite = IntPtr.Zero;
    IntPtr hPC = IntPtr.Zero, attrList = IntPtr.Zero;
    var pi = new PROCESS_INFORMATION();
    Thread reader = null;
    try {
      var sa = new SECURITY_ATTRIBUTES { nLength = Marshal.SizeOf(typeof(SECURITY_ATTRIBUTES)), bInheritHandle = false };
      if (!CreatePipe(out inRead, out inWrite, ref sa, 0) || !CreatePipe(out outRead, out outWrite, ref sa, 0)) return 1;
      if (CreatePseudoConsole(new COORD { X = 120, Y = 40 }, inRead, outWrite, 0, out hPC) != 0) return 1;
      CloseHandle(inRead); inRead = IntPtr.Zero;
      CloseHandle(outWrite); outWrite = IntPtr.Zero;

      var outHandle = new SafeFileHandle(outRead, true); outRead = IntPtr.Zero;
      reader = new Thread(() => {
        try {
          using (var fs = new FileStream(outHandle, FileAccess.Read, 4096, false))
          using (var log = new FileStream(logPath, FileMode.Create, FileAccess.Write, FileShare.ReadWrite)) {
            var buf = new byte[4096]; int n;
            while ((n = fs.Read(buf, 0, buf.Length)) > 0) { log.Write(buf, 0, n); log.Flush(); }
          }
        } catch {}
      }) { IsBackground = true };
      reader.Start();

      IntPtr lpSize = IntPtr.Zero;
      InitializeProcThreadAttributeList(IntPtr.Zero, 1, 0, ref lpSize);
      attrList = Marshal.AllocHGlobal(lpSize);
      if (!InitializeProcThreadAttributeList(attrList, 1, 0, ref lpSize)) return 1;
      if (!UpdateProcThreadAttribute(attrList, 0, PROC_THREAD_ATTRIBUTE_PSEUDOCONSOLE, hPC, (IntPtr)IntPtr.Size, IntPtr.Zero, IntPtr.Zero)) return 1;

      var si = new STARTUPINFOEX(); si.StartupInfo.cb = Marshal.SizeOf(typeof(STARTUPINFOEX)); si.lpAttributeList = attrList;
      var cl = new StringBuilder(QuoteArg(exePath));
      foreach (var a in args) cl.Append(' ').Append(QuoteArg(a));
      if (!CreateProcessW(null, cl, IntPtr.Zero, IntPtr.Zero, false, EXTENDED_STARTUPINFO_PRESENT, IntPtr.Zero, cwd, ref si, out pi)) return 1;

      uint w = WaitForSingleObject(pi.hProcess, (uint)Math.Max(1000, maxWaitMs));
      uint code = 0;
      if (w == 0) GetExitCodeProcess(pi.hProcess, out code);
      else KillTree(pi.dwProcessId);
      return (int)code;
    } finally {
      if (hPC != IntPtr.Zero) ClosePseudoConsole(hPC);
      if (inWrite != IntPtr.Zero) CloseHandle(inWrite);
      if (inRead != IntPtr.Zero) CloseHandle(inRead);
      if (outWrite != IntPtr.Zero) CloseHandle(outWrite);
      if (outRead != IntPtr.Zero) CloseHandle(outRead);
      if (reader != null) reader.Join(1000);
      if (attrList != IntPtr.Zero) { DeleteProcThreadAttributeList(attrList); Marshal.FreeHGlobal(attrList); }
      if (pi.hThread != IntPtr.Zero) CloseHandle(pi.hThread);
      if (pi.hProcess != IntPtr.Zero) CloseHandle(pi.hProcess);
    }
  }
}
'@
}
$code = [LeashConPty]::Run(${psSingleQuote(exe)}, @(${argArray}), ${psSingleQuote(cwd)}, ${psSingleQuote(tuiLog)}, ${Math.ceil(maxWaitMs)})
exit $code
`;
}

/** Antigravity quota via agy's statusLine. Run only when the phone asks (bridge/quota); nothing runs on a timer. */
export class QuotaPoller {
  constructor({ agentCommand, timeoutMs = 30000, workDir, log = () => {}, onUpdate = () => {}, usePty = true, platform = process.platform, find = null, spawnFn = spawn }) {
    Object.assign(this, { agentCommand, timeoutMs, log, onUpdate, usePty, platform, find, spawnFn });
    this.workDir = workDir ?? path.join(CONFIG_DIR, 'quota');
    this.latest = null;
    this.error = null;
    this.inflight = null;
  }

  /** Refresh unless we have data younger than maxAgeMs. Concurrent callers share one run. */
  refresh({ maxAgeMs = 0 } = {}) {
    if (this.latest && Date.now() - this.latest.fetchedAt < maxAgeMs) return Promise.resolve(this.latest);
    if (this.inflight) return this.inflight;
    this.inflight = this.#run().finally(() => { this.inflight = null; });
    return this.inflight;
  }

  #run() {
    const win = this.platform === 'win32';
    fs.mkdirSync(this.workDir, { recursive: true, mode: 0o700 });
    const out = path.join(this.workDir, 'status.json');
    const tuiLog = path.join(this.workDir, 'tui.log');
    try { fs.unlinkSync(out); } catch {}
    let sink;
    if (win) {
      const sinkJs = path.join(this.workDir, 'statusline.mjs');
      sink = path.join(this.workDir, 'statusline.cmd');
      fs.writeFileSync(sinkJs,
        `import fs from 'node:fs';\n` +
        `const chunks = []; for await (const c of process.stdin) chunks.push(c);\n` +
        `const raw = Buffer.concat(chunks).toString('utf8');\n` +
        `const out = ${JSON.stringify(out)}; const tmp = out + '.tmp';\n` +
        `fs.writeFileSync(tmp, raw);\n` +
        `try { fs.renameSync(tmp, out); } catch { fs.writeFileSync(out, raw); try { fs.unlinkSync(tmp); } catch {} }\n` +
        `process.stdout.write('agr\\n');\n`, 'utf8');
      fs.writeFileSync(sink, `@echo off\r\n"${process.execPath}" "${sinkJs}"\r\n`, 'utf8');
    } else {
      sink = path.join(this.workDir, 'statusline.sh');
      fs.writeFileSync(sink, `#!/bin/sh\ncat > "${out}.tmp" && mv "${out}.tmp" "${out}"\necho agr\n`, { mode: 0o700 });
    }
    const settings = JSON.stringify({ statusLine: { type: 'command', command: sink } });
    const agyArgs = ['--settings', settings];

    let cmd, args, spawnOpts, closeLogFd = null;
    if (win && this.usePty) {
      const lookup = this.find ?? (c => findCommand(c, { platform: 'win32' }));
      const ps = lookup('pwsh') || lookup('powershell') || 'powershell.exe';
      const isCmd = /\.(cmd|bat)$/i.test(this.agentCommand);
      const exe = isCmd ? (process.env.ComSpec || process.env.COMSPEC || 'cmd.exe') : this.agentCommand;
      const exeArgs = isCmd ? ['/d', '/c', `"${this.agentCommand}" --settings "${settings.replace(/"/g, '\\"')}"`] : agyArgs;
      const script = winConPtyScript({ exe, args: exeArgs, cwd: this.workDir, tuiLog, maxWaitMs: this.timeoutMs + 2000 });
      cmd = ps;
      args = ['-NoLogo', '-NoProfile', '-NonInteractive', '-EncodedCommand', Buffer.from(script, 'utf16le').toString('base64')];
      try { fs.writeFileSync(tuiLog, ''); } catch {}
      spawnOpts = { cwd: this.workDir, stdio: 'ignore', windowsHide: true, env: { ...process.env, TERM: process.env.TERM || 'xterm-256color', COLUMNS: '120', LINES: '40' } };
    } else {
      // keep what the hidden TUI printed, so a failure can say why (it used to go to /dev/null)
      const logFd = fs.openSync(tuiLog, 'w', 0o600);
      if (!this.usePty) { cmd = this.agentCommand; args = agyArgs; }
      else if (this.platform === 'darwin') {
        // macOS script(1) calls tcgetattr on stdin and dies with ENOTSUP when stdin is a socket, which is
        // what Node's stdio 'pipe' is on macOS. Feed it a real pipe from sleep and kill sleep as soon as script exits.
        const secs = String(Math.ceil(this.timeoutMs / 1000) + 5);
        const pf = path.join(this.workDir, 'sleep.pid');
        cmd = '/bin/sh';
        args = ['-c', 's=$1; pf=$2; shift 2; sh -c \'echo $$ > "$1"; exec sleep "$2"\' _ "$pf" "$s" | { script -q /dev/null "$@"; r=$?; [ -f "$pf" ] || sleep 0.05; read sp < "$pf" 2>/dev/null && kill "$sp" 2>/dev/null; rm -f "$pf"; exit $r; }', 'agr-quota', secs, pf, this.agentCommand, ...agyArgs];
      }
      else { cmd = 'script'; args = ['-qfc', [this.agentCommand, ...agyArgs].map(a => `'${String(a).replace(/'/g, `'\\''`)}'`).join(' '), '/dev/null']; }
      spawnOpts = { cwd: this.workDir, stdio: [this.platform === 'darwin' ? 'ignore' : 'pipe', logFd, logFd], detached: !win, windowsHide: true, env: { ...process.env, TERM: process.env.TERM || 'xterm-256color', COLUMNS: '120', LINES: '40' } };
      closeLogFd = () => { try { fs.closeSync(logFd); } catch {} };
    }

    return new Promise((resolve, reject) => {
      const child = this.spawnFn(cmd, args, spawnOpts);
      closeLogFd?.();
      let done = false, exitInfo = null, firstQuotaAt = null;
      const finish = (err, val) => {
        if (done) return; done = true;
        if (err) err.message += tuiTail(tuiLog, exitInfo);
        clearInterval(poll); clearTimeout(timer);
        if (win) killTree(child, { platform: 'win32' });
        else {
          try { process.kill(-child.pid, 'SIGTERM'); } catch {}
          setTimeout(() => { try { process.kill(-child.pid, 'SIGKILL'); } catch {} }, 1500).unref?.();
        }
        if (err) { this.error = err.message; this.log('quota: ' + err.message); reject(err); }
        else { this.latest = val; this.error = null; this.onUpdate(val); resolve(val); }
      };
      child.on('error', e => finish(e));
      child.on('exit', (code, signal) => { exitInfo = signal ? 'signal ' + signal : 'exit ' + code; setTimeout(() => { if (!done) check(true); }, 200); });
      const check = (final = false) => {
        let raw;
        try { raw = fs.readFileSync(out, 'utf8'); } catch { if (final) finish(new Error('agy exited without statusLine output')); return; }
        let j; try { j = JSON.parse(raw); } catch { return; }
        const q = normalizeQuota(j);
        // agy's first statusLine call can come before plan_tier is loaded; give it a few seconds for a fuller one
        if (q && (q.planTier || final || (firstQuotaAt && Date.now() - firstQuotaAt > 5000))) finish(null, q);
        else if (q) firstQuotaAt ??= Date.now();
        else if (final) finish(new Error('statusLine had no quota'));
      };
      const poll = setInterval(check, 400);
      const timer = setTimeout(() => finish(new Error('timed out waiting for agy statusLine')), this.timeoutMs);
    });
  }
}

/** " (exit 1): <last printable TUI text>" for error messages; strips ANSI and never includes an email address. */
export function tuiTail(file, exitInfo, max = 240) {
  let raw = '';
  try { raw = fs.readFileSync(file, 'utf8'); } catch {}
  const text = raw.replace(/\r\n?/g, '\n')
    .replace(/\x1b\][^\x07\x1b]*(\x07|\x1b\\)/g, '')       // OSC
    .replace(/\x1b\[[0-9;?<>=]*[ -\/]*[@-~]/g, ' ')           // CSI
    .replace(/\x1b[@-_]/g, '')
    .replace(/[^\S\n]+/g, ' ').replace(/[\x00-\x09\x0b-\x1f\x7f]/g, '')
    .replace(/[\w.+-]+@[\w-]+(\.[\w-]+)+/g, '<email>')
    .split('\n').map(s => s.trim()).filter(Boolean).join(' | ');
  const tail = text.length > max ? '…' + text.slice(-max) : text;
  return (exitInfo ? ` (${exitInfo})` : '') + (tail ? ': ' + tail : '');
}
