<#
.SYNOPSIS
  Windows Antigravity busy-lock probe (read-only).

.DESCRIPTION
  Checks what signals exist on Windows when the Antigravity desktop app is running a turn
  in a conversation vs idle vs quit:
    - Node / agy / sqlite3 / handle.exe availability and node:sqlite support
    - Antigravity / agy / language_server process tree (PID, PPID, redacted CommandLine)
    - Data directory layout (%USERPROFILE%\.gemini\antigravity, %APPDATA%\Antigravity, %LOCALAPPDATA%\Antigravity)
    - Windows Restart Manager (rstrtmgr.dll) open-handle PIDs for presence/*.lock and *.db
    - File-sharing lock check on presence/*.lock
    - conversation_summaries.db status / not_fully_idle / last_modified_time rows (via node:sqlite or sqlite3 if available)

  Keep the target conversation closed on the phone while probing.
  Usage:
    1) On Windows Antigravity desktop, start a ~60s task in a conversation, then while it is busy:
         powershell -ExecutionPolicy Bypass -File tools\probe-agylock-win.ps1 busy [conversationId]
    2) After the turn finishes (leave the conversation open in the desktop app):
         powershell -ExecutionPolicy Bypass -File tools\probe-agylock-win.ps1 idle
    3) (Optional) Quit Antigravity desktop completely, then:
         powershell -ExecutionPolicy Bypass -File tools\probe-agylock-win.ps1 quit

  Output: .probe\agylock-win-<label>.txt (tokens/secrets redacted; no conversation message text).
#>
param(
  [string]$Label = "busy",
  [string]$Cid = ""
)

$ErrorActionPreference = "Continue"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$Here = Split-Path -Parent $MyInvocation.MyCommand.Path
$OutDir = Join-Path (Split-Path -Parent $Here) ".probe"
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$OutFile = Join-Path $OutDir ("agylock-win-" + $Label + ".txt")
$CidCache = Join-Path $OutDir "agylock-win-cid"

$AgDir = Join-Path $env:USERPROFILE ".gemini\antigravity"
$PresenceDir = Join-Path $AgDir "presence"
$ConvDir = Join-Path $AgDir "conversations"
$BrainDir = Join-Path $AgDir "brain"

if ([string]::IsNullOrWhiteSpace($Cid) -and $Label -ne "busy" -and (Test-Path $CidCache)) {
  $Cid = (Get-Content $CidCache -Raw).Trim()
}
if ([string]::IsNullOrWhiteSpace($Cid) -and (Test-Path $ConvDir)) {
  $newestDb = Get-ChildItem -Path $ConvDir -Filter "*.db" -ErrorAction SilentlyContinue |
    Where-Object { $_.BaseName -match '^[0-9a-f]{8}-[0-9a-f-]{27}$' } |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
  if ($newestDb) { $Cid = $newestDb.BaseName }
}
if ([string]::IsNullOrWhiteSpace($Cid) -and (Test-Path $BrainDir)) {
  $newestBrain = Get-ChildItem -Path $BrainDir -Directory -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -match '^[0-9a-f]{8}-[0-9a-f-]{27}$' } |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
  if ($newestBrain) { $Cid = $newestBrain.Name }
}
if (-not [string]::IsNullOrWhiteSpace($Cid)) {
  Set-Content -Path $CidCache -Value $Cid -Encoding UTF8
}

function Redact-Text([string]$s) {
  if ([string]::IsNullOrEmpty($s)) { return "" }
  $s = [regex]::Replace($s, '((?:--?|\b)[\w.-]*(?:token|key|secret|csrf|auth|cookie|password)[\w.-]*[= :]+)\S+', '$1<redacted>', 'IgnoreCase')
  $s = [regex]::Replace($s, '\b[A-Za-z0-9_\-]{40,}\b', '<long>')
  return $s
}

# Windows Restart Manager (rstrtmgr.dll) wrapper: returns PIDs holding a given file path without external tools.
if (-not ("WinLockProbe" -as [type])) {
Add-Type -TypeDefinition @"
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Text;

public static class WinLockProbe {
    [StructLayout(LayoutKind.Sequential)]
    struct RM_UNIQUE_PROCESS {
        public int dwProcessId;
        public System.Runtime.InteropServices.ComTypes.FILETIME ProcessStartTime;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    struct RM_PROCESS_INFO {
        public RM_UNIQUE_PROCESS Process;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 256)]
        public string strAppName;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 64)]
        public string strServiceShortName;
        public int ApplicationType;
        public uint AppStatus;
        public uint TSSessionId;
        [MarshalAs(UnmanagedType.Bool)]
        public bool bRestartable;
    }

    [DllImport("rstrtmgr.dll", CharSet = CharSet.Unicode)]
    static extern int RmStartSession(out uint pSessionHandle, int dwSessionFlags, StringBuilder strSessionKey);

    [DllImport("rstrtmgr.dll")]
    static extern int RmEndSession(uint pSessionHandle);

    [DllImport("rstrtmgr.dll", CharSet = CharSet.Unicode)]
    static extern int RmRegisterResources(uint pSessionHandle, uint nFiles, string[] rgsFilenames,
        uint nApplications, IntPtr rgApplications, uint nServices, IntPtr rgServices);

    [DllImport("rstrtmgr.dll")]
    static extern int RmGetList(uint dwSessionHandle, out uint pnProcInfoNeeded, ref uint pnProcInfo,
        [In, Out] RM_PROCESS_INFO[] rgAffectedApps, ref uint lpdwRebootReasons);

    public static string GetLockHolders(string path) {
        uint handle = 0;
        var key = new StringBuilder(64);
        int res = RmStartSession(out handle, 0, key);
        if (res != 0) return "RmStartSession_err=" + res;
        try {
            res = RmRegisterResources(handle, 1, new[] { path }, 0, IntPtr.Zero, 0, IntPtr.Zero);
            if (res != 0) return "RmRegister_err=" + res;
            uint needed = 0, count = 0, reboot = 0;
            res = RmGetList(handle, out needed, ref count, null, ref reboot);
            if (res == 0) return "none";
            if (res != 234) return "RmGetList_err=" + res;
            var infos = new RM_PROCESS_INFO[needed];
            count = needed;
            res = RmGetList(handle, out needed, ref count, infos, ref reboot);
            if (res != 0) return "RmGetList2_err=" + res;
            var list = new List<string>();
            for (int i = 0; i < count; i++) {
                list.Add(infos[i].Process.dwProcessId + ":" + infos[i].strAppName);
            }
            return list.Count == 0 ? "none" : string.Join(", ", list);
        } finally {
            RmEndSession(handle);
        }
    }

    public static string TestExclusiveOpen(string path) {
        try {
            using (var fs = new System.IO.FileStream(path, System.IO.FileMode.Open, System.IO.FileAccess.Read, System.IO.FileShare.None)) {
                return "unlocked (FileShare.None succeeded)";
            }
        } catch (System.IO.IOException ex) {
            return "locked_io (0x" + ex.HResult.ToString("X8") + ": " + ex.Message.Split('\r', '\n')[0] + ")";
        } catch (Exception ex) {
            return "err (" + ex.GetType().Name + ": " + ex.Message.Split('\r', '\n')[0] + ")";
        }
    }
}
"@
}

$lines = New-Object System.Collections.Generic.List[string]
function Log([string]$s = "") { $lines.Add($s) }

Log ("== agylock-win label={0} cid={1} at {2}" -f $Label, $Cid, (Get-Date -Format "yyyy-MM-dd HH:mm:ss"))
Log ("== PowerShell: {0}" -f $PSVersionTable.PSVersion)
Log ("== Node: {0}" -f (& { try { (node -v 2>&1) -join " " } catch { "(not found)" } }))
Log ("== agy: {0}" -f (& { try { (agy --version 2>&1 | Select-Object -First 2) -join " " } catch { "(not found)" } }))

$hasNodeSqlite = $false
try {
  $nsOut = node -e "import('node:sqlite').then(() => console.log('ok')).catch(e => console.log('no:' + e.code))" 2>&1
  if ($nsOut -match 'ok') { $hasNodeSqlite = $true }
  Log ("== node:sqlite: {0}" -f ($nsOut -join " "))
} catch {
  Log "== node:sqlite: probe failed"
}

foreach ($tool in @("sqlite3", "handle", "handle64")) {
  $cmd = Get-Command $tool -ErrorAction SilentlyContinue
  Log ("== tool {0}: {1}" -f $tool, $(if ($cmd) { $cmd.Source } else { "(not found)" }))
}

Log ""
Log "== processes (PID PPID Name CommandLine)"
$procs = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object {
  $_.Name -match 'antigravity|agy|language_server|jetski' -or
  ($_.Name -match '^node(\.exe)?$' -and $_.CommandLine -match 'leash|bridge|agy')
}
foreach ($p in ($procs | Sort-Object ProcessId)) {
  $cl = Redact-Text ([string]$p.CommandLine)
  if ($cl.Length -gt 350) { $cl = $cl.Substring(0, 350) + "..." }
  Log ("  {0,6} {1,6} {2,-28} {3}" -f $p.ProcessId, $p.ParentProcessId, $p.Name, $cl)
}

Log ""
Log "== directories"
$candidates = @(
  $AgDir,
  $PresenceDir,
  $ConvDir,
  (Join-Path $env:APPDATA "Antigravity"),
  (Join-Path $env:LOCALAPPDATA "Antigravity"),
  (Join-Path $env:LOCALAPPDATA "Programs\Antigravity")
)
foreach ($d in $candidates) {
  if (Test-Path $d) {
    $cnt = (Get-ChildItem -Path $d -ErrorAction SilentlyContinue | Measure-Object).Count
    Log ("  [exists] {0} (items={1})" -f $d, $cnt)
  } else {
    Log ("  [missing] {0}" -f $d)
  }
}

Log ""
Log "== presence/*.lock (newest 20 + target cid)"
if (Test-Path $PresenceDir) {
  $lockFiles = Get-ChildItem -Path $PresenceDir -Filter "*.lock" -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending
  Log ("  total .lock files: {0}" -f $lockFiles.Count)
  $sampleLocks = @($lockFiles | Select-Object -First 20)
  if ($Cid) {
    $cidLock = $lockFiles | Where-Object { $_.BaseName -eq $Cid } | Select-Object -First 1
    if ($cidLock -and ($sampleLocks.FullName -notcontains $cidLock.FullName)) {
      $sampleLocks = @($cidLock) + $sampleLocks
    }
  }
  foreach ($f in $sampleLocks) {
    $rm = [WinLockProbe]::GetLockHolders($f.FullName)
    $io = [WinLockProbe]::TestExclusiveOpen($f.FullName)
    $tag = if ($Cid -and $f.BaseName -eq $Cid) { " *TARGET*" } else { "" }
    Log ("  {0}  size={1}  {2}{3}" -f $f.LastWriteTime.ToString("HH:mm:ss"), $f.Length, $f.Name, $tag)
    Log ("      RmGetList : {0}" -f $rm)
    Log ("      FileShare : {0}" -f $io)
  }
} else {
  Log "  (presence dir missing)"
}

Log ""
Log "== db files & Restart Manager holders"
$dbTargets = @(
  (Join-Path $AgDir "conversation_summaries.db"),
  (Join-Path $AgDir "conversation_summaries.db-wal"),
  (Join-Path $AgDir "conversation_summaries.db-shm")
)
if ($Cid) {
  $dbTargets += (Join-Path $ConvDir ($Cid + ".db"))
  $dbTargets += (Join-Path $ConvDir ($Cid + ".db-wal"))
}
foreach ($p in $dbTargets) {
  if (Test-Path $p) {
    $it = Get-Item $p
    $rm = [WinLockProbe]::GetLockHolders($p)
    Log ("  {0}  size={1,9}  {2}  -> RmGetList: {3}" -f $it.LastWriteTime.ToString("HH:mm:ss"), $it.Length, $it.Name, $rm)
  } else {
    Log ("  [missing] {0}" -f (Split-Path -Leaf $p))
  }
}

Log ""
Log "== conversation_summaries.db (top 10 + target cid, status columns only)"
$sumDb = Join-Path $AgDir "conversation_summaries.db"
if (Test-Path $sumDb) {
  if ($hasNodeSqlite) {
    $js = @'
import { DatabaseSync } from 'node:sqlite';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const dbPath = process.env.LEASH_SUM_DB;
const cid = process.env.LEASH_CID || '';
function query(p) {
  const db = new DatabaseSync(p, { readOnly: true });
  try {
    const rows = db.prepare('SELECT conversation_id, status, not_fully_idle, last_modified_time FROM conversation_summaries ORDER BY last_modified_time DESC LIMIT 10').all();
    let target = null;
    if (cid) target = db.prepare('SELECT conversation_id, status, not_fully_idle, last_modified_time FROM conversation_summaries WHERE conversation_id = ?').get(cid);
    return { rows, target };
  } finally { db.close(); }
}
try {
  console.log(JSON.stringify({ mode: 'direct', ...query(dbPath) }, null, 2));
} catch (e) {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'leash-probe-'));
  try {
    const copy = path.join(tmp, 's.db');
    fs.copyFileSync(dbPath, copy);
    for (const ext of ['-wal', '-shm']) { try { fs.copyFileSync(dbPath + ext, copy + ext); } catch {} }
    const db = new DatabaseSync(copy);
    const rows = db.prepare('SELECT conversation_id, status, not_fully_idle, last_modified_time FROM conversation_summaries ORDER BY last_modified_time DESC LIMIT 10').all();
    let target = null;
    if (cid) target = db.prepare('SELECT conversation_id, status, not_fully_idle, last_modified_time FROM conversation_summaries WHERE conversation_id = ?').get(cid);
    db.close();
    console.log(JSON.stringify({ mode: 'copy', directError: e.message, rows, target }, null, 2));
  } finally { fs.rmSync(tmp, { recursive: true, force: true }); }
}
'@
    $tmpJs = [System.IO.Path]::Combine([System.IO.Path]::GetTempPath(), "leash-sum-probe-" + [Guid]::NewGuid().ToString("N") + ".mjs")
    Set-Content -Path $tmpJs -Value $js -Encoding UTF8
    $env:LEASH_SUM_DB = $sumDb
    $env:LEASH_CID = $Cid
    try {
      $res = node $tmpJs 2>&1
    } finally {
      Remove-Item $tmpJs -ErrorAction SilentlyContinue
      Remove-Item Env:LEASH_SUM_DB -ErrorAction SilentlyContinue
      Remove-Item Env:LEASH_CID -ErrorAction SilentlyContinue
    }
    foreach ($l in $res) { Log ("  " + $l) }
  } elseif (Get-Command sqlite3 -ErrorAction SilentlyContinue) {
    $sql = "SELECT conversation_id, status, not_fully_idle, last_modified_time FROM conversation_summaries ORDER BY last_modified_time DESC LIMIT 10;"
    $res = sqlite3 -readonly -separator "|" $sumDb $sql 2>&1
    foreach ($l in $res) { Log ("  " + $l) }
  } else {
    Log "  (neither node:sqlite nor sqlite3 available on PATH to inspect DB rows)"
  }
} else {
  Log "  (conversation_summaries.db missing)"
}

Log ""
Log "== recently modified files under .gemini\antigravity (last 15 min, excluding Cache/logs)"
if (Test-Path $AgDir) {
  $cutoff = (Get-Date).AddMinutes(-15)
  Get-ChildItem -Path $AgDir -Recurse -File -ErrorAction SilentlyContinue |
    Where-Object { $_.LastWriteTime -gt $cutoff -and $_.FullName -notmatch '\\(Cache|Code Cache|GPUCache|logs)\\' } |
    Sort-Object LastWriteTime -Descending | Select-Object -First 40 |
    ForEach-Object {
      $rel = $_.FullName.Substring($AgDir.Length).TrimStart('\')
      Log ("  {0}  size={1,9}  {2}" -f $_.LastWriteTime.ToString("HH:mm:ss"), $_.Length, $rel)
    }
}

$lines | Set-Content -Path $OutFile -Encoding UTF8
Write-Host ("wrote {0} ({1} lines, cid={2})" -f $OutFile, $lines.Count, $Cid)
switch ($Label) {
  "busy" { Write-Host ("next: wait for the task to finish (keep the conversation open), then run:`n  powershell -ExecutionPolicy Bypass -File tools\probe-agylock-win.ps1 idle") }
  "idle" { Write-Host ("done (optional: quit Antigravity desktop and run with 'quit'). Send .probe\agylock-win-*.txt back.") }
  default { Write-Host "done. Send .probe\agylock-win-*.txt back." }
}
