// "Busy on the desktop" lock for Antigravity (runs on the same computer as the desktop app).
// Two read-only signals, shared across macOS, Linux and Windows:
//  * <dataDir>/presence/<conversationId>.lock is held open by the desktop language_server for every
//    conversation it has loaded (released only when the app quits; the files themselves stay).
//  * conversation_summaries.db: status CASCADE_RUN_STATUS_RUNNING while generating, and not_fully_idle = 1 for the
//    whole turn (incl. waiting on a terminal command) until it is really done.
// A conversation counts as held when a process other than our own agy acp holds its presence lock AND it is busy.
import { execFile } from 'node:child_process';
import fsSync from 'node:fs';
import fs from 'node:fs/promises';
import { createRequire } from 'node:module';
import os from 'node:os';
import path from 'node:path';
import { findCommand, pathApi } from './platform.mjs';

const IDLE = 'CASCADE_RUN_STATUS_IDLE';
const SQL = 'select conversation_id, status, not_fully_idle, last_modified_time from conversation_summaries';
const require = createRequire(import.meta.url);

let cachedNodeSqlite;
/** Synchronous availability check for Node's built-in node:sqlite (Node 22.5+). */
export function hasNodeSqlite() {
  if (cachedNodeSqlite !== undefined) return cachedNodeSqlite;
  try { cachedNodeSqlite = !!require('node:sqlite').DatabaseSync; }
  catch { cachedNodeSqlite = false; }
  return cachedNodeSqlite;
}

/** execFile resolving stdout; lsof exits 1 when nothing matches, which is not an error for us. */
export function runCmd(cmd, args, { timeout = 8000, okCodes = [], windowsHide = true } = {}) {
  return new Promise((resolve, reject) => {
    execFile(cmd, args, { timeout, maxBuffer: 8 << 20, windowsHide }, (err, stdout, stderr) => {
      if (err && !okCodes.includes(err.code)) return reject(Object.assign(new Error(cmd + ' failed: ' + (String(stderr || err.message).trim().slice(-200))), { code: err.code }));
      resolve(String(stdout ?? ''));
    });
  });
}

/** lsof -F pn output -> Map(conversationId -> Set(pid)) */
export function parsePresenceLsof(out) {
  const held = new Map(); let pid = null;
  for (const line of String(out).split('\n')) {
    if (line[0] === 'p') pid = Number(line.slice(1));
    else if (line[0] === 'n' && pid) {
      const m = /\/([0-9a-f]{8}-[0-9a-f-]{27})\.lock$/i.exec(line);
      if (!m) continue;
      if (!held.has(m[1])) held.set(m[1], new Set());
      held.get(m[1]).add(pid);
    }
  }
  return held;
}

function summarizeRow(id, status, nfi, mod) {
  if (!id || !status) return null;
  const t = mod ? Date.parse(String(mod).trim().replace(' ', 'T')) : NaN;
  return { status, busy: status !== IDLE || Number(nfi) === 1, modifiedAt: Number.isFinite(t) ? t : 0 };
}

/** sqlite3 "id|status|nfi|time" rows -> Map(id -> {status, busy, modifiedAt}) */
export function parseSummaries(out) {
  const rows = new Map();
  for (const line of String(out).split('\n')) {
    const [id, status, nfi, mod] = line.split('|');
    const r = summarizeRow(id, status, nfi, mod);
    if (r) rows.set(id, r);
  }
  return rows;
}

/** pid -> ppid for the whole machine (to skip our own agy acp and anything it spawned). */
export function parsePs(out) {
  const parent = new Map();
  for (const line of String(out).split('\n')) { const [p, pp] = line.trim().split(/\s+/).map(Number); if (p) parent.set(p, pp); }
  return parent;
}

/** PowerShell Restart Manager + Win32_Process output ("P pid ppid" / "L cid pid1,pid2") -> {locks, parent}. */
export function parseWinLocks(out) {
  const locks = new Map();
  const parent = new Map();
  for (const raw of String(out).split(/\r?\n/)) {
    const line = raw.trim();
    if (line.startsWith('P ')) {
      const [, p, pp] = line.split(/\s+/);
      const pid = Number(p), ppid = Number(pp);
      if (pid) parent.set(pid, ppid);
    } else if (line.startsWith('L ')) {
      const [, cid, pids] = line.split(/\s+/);
      if (!cid || !pids) continue;
      const set = new Set(pids.split(',').map(Number).filter(Boolean));
      if (set.size) locks.set(cid, set);
    }
  }
  return { locks, parent };
}

function descendsFrom(pid, roots, parent) {
  for (let p = pid, n = 0; p && n < 64; p = parent.get(p), n++) if (roots.has(p)) return true;
  return false;
}

async function copyDbTemp(db, fn) {
  // A read-only open fails (SQLITE_CANTOPEN) once the app has checkpointed and dropped -wal/-shm: read a copy
  // instead, opened read-write so SQLite can rebuild its WAL index (the copy is ours; the original is untouched).
  const tmp = await fs.mkdtemp(path.join(os.tmpdir(), 'agr-agy-'));
  try {
    const copy = path.join(tmp, 's.db');
    await fs.copyFile(db, copy);
    for (const ext of ['-wal', '-shm']) { try { await fs.copyFile(db + ext, copy + ext); } catch {} }
    return await fn(copy);
  } finally { await fs.rm(tmp, { recursive: true, force: true }); }
}

function readSummariesNodeSqlite(db) {
  const { DatabaseSync } = require('node:sqlite');
  const query = (p, opts) => {
    const conn = new DatabaseSync(p, opts);
    try {
      const rows = new Map();
      for (const row of conn.prepare(SQL).all()) {
        const r = summarizeRow(row.conversation_id, row.status, row.not_fully_idle, row.last_modified_time);
        if (r) rows.set(row.conversation_id, r);
      }
      return rows;
    } finally { conn.close(); }
  };
  try { return Promise.resolve(query(db, { readOnly: true })); }
  catch { return copyDbTemp(db, copy => query(copy)); }
}

async function readSummariesCli(db, run) {
  try { return parseSummaries(await run('sqlite3', ['-readonly', '-separator', '|', db, SQL])); }
  catch { return copyDbTemp(db, async copy => parseSummaries(await run('sqlite3', ['-separator', '|', copy, SQL]))); }
}

function psSingleQuote(s) {
  return "'" + String(s).replace(/'/g, "''") + "'";
}

function winLockProbeScript(targets) {
  const entries = targets.map(t => `@(${psSingleQuote(t.id)}, ${psSingleQuote(t.lockPath)})`).join(', ');
  return `
$ErrorActionPreference = 'SilentlyContinue'
if (-not ('WinLockProbe' -as [type])) {
Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Text;
public static class WinLockProbe {
  [StructLayout(LayoutKind.Sequential)] struct RM_UNIQUE_PROCESS { public int dwProcessId; public System.Runtime.InteropServices.ComTypes.FILETIME ProcessStartTime; }
  [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
  struct RM_PROCESS_INFO {
    public RM_UNIQUE_PROCESS Process;
    [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 256)] public string strAppName;
    [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 64)] public string strServiceShortName;
    public int ApplicationType; public uint AppStatus; public uint TSSessionId;
    [MarshalAs(UnmanagedType.Bool)] public bool bRestartable;
  }
  [DllImport("rstrtmgr.dll", CharSet = CharSet.Unicode)] static extern int RmStartSession(out uint pSessionHandle, int dwSessionFlags, StringBuilder strSessionKey);
  [DllImport("rstrtmgr.dll")] static extern int RmEndSession(uint pSessionHandle);
  [DllImport("rstrtmgr.dll", CharSet = CharSet.Unicode)] static extern int RmRegisterResources(uint pSessionHandle, uint nFiles, string[] rgsFilenames, uint nApplications, IntPtr rgApplications, uint nServices, IntPtr rgServices);
  [DllImport("rstrtmgr.dll")] static extern int RmGetList(uint dwSessionHandle, out uint pnProcInfoNeeded, ref uint pnProcInfo, [In, Out] RM_PROCESS_INFO[] rgAffectedApps, ref uint lpdwRebootReasons);
  public static string GetLockPids(string path) {
    uint handle = 0; var key = new StringBuilder(64);
    if (RmStartSession(out handle, 0, key) != 0) return "";
    try {
      if (RmRegisterResources(handle, 1, new[] { path }, 0, IntPtr.Zero, 0, IntPtr.Zero) != 0) return "";
      uint needed = 0, count = 0, reboot = 0;
      int res = RmGetList(handle, out needed, ref count, null, ref reboot);
      if (res != 234 || needed == 0) return "";
      var infos = new RM_PROCESS_INFO[needed]; count = needed;
      if (RmGetList(handle, out needed, ref count, infos, ref reboot) != 0) return "";
      var pids = new List<string>();
      for (int i = 0; i < count; i++) pids.Add(infos[i].Process.dwProcessId.ToString());
      return string.Join(",", pids);
    } finally { RmEndSession(handle); }
  }
}
'@
}
Get-CimInstance Win32_Process | ForEach-Object { "P $($_.ProcessId) $($_.ParentProcessId)" }
foreach ($t in @(${entries})) {
  $pids = [WinLockProbe]::GetLockPids($t[1])
  if ($pids) { "L $($t[0]) $pids" }
}
`;
}

/**
 * Lock function for Hub({locks}): resolves {now, held, touched, ownWrites}.
 * touched includes our own agy acp's writes (shared DB), hence ownWrites: the hub moves its baseline after own turns.
 */
export function agyLocks({
  dataDir = path.join(os.homedir(), '.gemini', 'antigravity'),
  ownPids = () => [],
  run = runCmd,
  platform = process.platform,
  exists = p => fsSync.existsSync(p),
  readDb = null,
  powershell = null,
} = {}) {
  const api = pathApi(platform);
  const dbPath = api.join(dataDir, 'conversation_summaries.db');
  const presenceDir = api.join(dataDir, 'presence');
  const loadRows = readDb ?? (() => (platform === 'win32' && hasNodeSqlite() ? readSummariesNodeSqlite(dbPath) : readSummariesCli(dbPath, run)));

  return async () => {
    const now = Date.now();
    let locks, parent, rows;
    if (platform === 'win32') {
      rows = await loadRows();
      const targets = [];
      for (const [id, row] of rows) {
        if (!row.busy) continue;
        const lockPath = api.join(presenceDir, id + '.lock');
        if (exists(lockPath)) targets.push({ id, lockPath });
      }
      if (!targets.length) {
        const touched = {};
        for (const [id, r] of rows) if (r.modifiedAt) touched[id] = r.modifiedAt;
        return { now, held: [], touched, ownWrites: true };
      }
      const ps = powershell ?? findCommand('pwsh', { platform }) ?? findCommand('powershell', { platform }) ?? 'powershell.exe';
      const encoded = Buffer.from(winLockProbeScript(targets), 'utf16le').toString('base64');
      const out = await run(ps, ['-NoLogo', '-NoProfile', '-NonInteractive', '-EncodedCommand', encoded], { timeout: 10000 });
      ({ locks, parent } = parseWinLocks(out));
    } else {
      const [lsofOut, psOut, r] = await Promise.all([
        run('lsof', ['-nP', '-F', 'pn', '+d', presenceDir], { okCodes: [1] }),
        run('ps', ['-axo', 'pid=,ppid=']),
        loadRows(),
      ]);
      locks = parsePresenceLsof(lsofOut);
      parent = parsePs(psOut);
      rows = r;
    }

    const own = new Set(ownPids().filter(Boolean));
    const held = [];
    for (const [sessionId, pids] of locks) {
      const foreign = [...pids].filter(p => !descendsFrom(p, own, parent));
      const row = rows.get(sessionId);
      if (foreign.length && row?.busy) held.push({ sessionId, pid: foreign[0], entrypoint: 'antigravity', status: row.status, basis: 'busy' });
    }
    const touched = {};
    for (const [id, r] of rows) if (r.modifiedAt) touched[id] = r.modifiedAt;
    return { now, held, touched, ownWrites: true };
  };
}
