// "Busy on the desktop" lock for Antigravity (runs on the Mac, next to the desktop app).
// Two read-only signals, both found by probing (tools/probe-agy*-mac.sh):
//  * ~/.gemini/antigravity/presence/<conversationId>.lock is held open by the desktop language_server for every
//    conversation it has loaded (released only when the app quits; the files themselves stay).
//  * conversation_summaries.db: status CASCADE_RUN_STATUS_RUNNING while generating, and not_fully_idle = 1 for the
//    whole turn (incl. waiting on a terminal command) until it is really done.
// A conversation counts as held when a process other than our own agy acp holds its presence lock AND it is busy.
import { execFile } from 'node:child_process';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';

const IDLE = 'CASCADE_RUN_STATUS_IDLE';
const SQL = 'select conversation_id, status, not_fully_idle, last_modified_time from conversation_summaries';

/** execFile resolving stdout; lsof exits 1 when nothing matches, which is not an error for us. */
export function runCmd(cmd, args, { timeout = 8000, okCodes = [] } = {}) {
  return new Promise((resolve, reject) => {
    execFile(cmd, args, { timeout, maxBuffer: 8 << 20 }, (err, stdout, stderr) => {
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

/** sqlite3 "id|status|nfi|time" rows -> Map(id -> {status, busy, modifiedAt}) */
export function parseSummaries(out) {
  const rows = new Map();
  for (const line of String(out).split('\n')) {
    const [id, status, nfi, mod] = line.split('|');
    if (!id || !status) continue;
    const t = mod ? Date.parse(mod.trim().replace(' ', 'T')) : NaN;
    rows.set(id, { status, busy: status !== IDLE || nfi === '1', modifiedAt: Number.isFinite(t) ? t : 0 });
  }
  return rows;
}

/** pid -> ppid for the whole machine (to skip our own agy acp and anything it spawned). */
export function parsePs(out) {
  const parent = new Map();
  for (const line of String(out).split('\n')) { const [p, pp] = line.trim().split(/\s+/).map(Number); if (p) parent.set(p, pp); }
  return parent;
}
function descendsFrom(pid, roots, parent) {
  for (let p = pid, n = 0; p && n < 64; p = parent.get(p), n++) if (roots.has(p)) return true;
  return false;
}

async function readSummaries(db, run) {
  try { return await run('sqlite3', ['-readonly', '-separator', '|', db, SQL]); }
  catch {
    // A read-only open fails (SQLITE_CANTOPEN) once the app has checkpointed and dropped -wal/-shm: read a copy
    // instead, opened read-write so SQLite can rebuild its WAL index (the copy is ours; the original is untouched).
    const tmp = await fs.mkdtemp(path.join(os.tmpdir(), 'agr-agy-'));
    try {
      await fs.copyFile(db, path.join(tmp, 's.db'));
      try { await fs.copyFile(db + '-wal', path.join(tmp, 's.db-wal')); } catch {}
      return await run('sqlite3', ['-separator', '|', path.join(tmp, 's.db'), SQL]);
    } finally { await fs.rm(tmp, { recursive: true, force: true }); }
  }
}

/**
 * Lock function for Hub({locks}): resolves {now, held, touched, ownWrites}.
 * touched includes our own agy acp's writes (shared DB), hence ownWrites: the hub moves its baseline after own turns.
 */
export function agyLocks({ dataDir = path.join(os.homedir(), '.gemini', 'antigravity'), ownPids = () => [], run = runCmd } = {}) {
  return async () => {
    const now = Date.now();
    const [lsofOut, psOut, sumOut] = await Promise.all([
      run('lsof', ['-nP', '-F', 'pn', '+d', path.join(dataDir, 'presence')], { okCodes: [1] }),
      run('ps', ['-axo', 'pid=,ppid=']),
      readSummaries(path.join(dataDir, 'conversation_summaries.db'), run),
    ]);
    const locks = parsePresenceLsof(lsofOut);
    const rows = parseSummaries(sumOut);
    const parent = parsePs(psOut);
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
