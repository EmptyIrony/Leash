// The hub owns the `agy acp` process, caches per-session event logs and fans
// everything out to connected phone clients.
import { EventEmitter } from 'node:events';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { AcpProcess, RpcError } from './acp.mjs';
import { safePath, fileUri, isSamePath } from './platform.mjs';
import { phoneUpdate, MEDIA_CHUNK } from './media.mjs';

export const BRIDGE_VERSION = '0.1.0';
export const BRIDGE_PROTOCOL = 1;

const CHUNK_KINDS = new Set(['agent_message_chunk', 'agent_thought_chunk', 'user_message_chunk']);
const BLOCKED_METHODS = new Set(['initialize', 'authenticate', 'logout']);
/** The same helper the Claude Code host runs over SSH; agents on the Mac run it locally (for "!" shell commands). */
const LOCAL_HELPER = [process.execPath, fileURLToPath(new URL('../remote/helper.mjs', import.meta.url))];

/** Phone attachments (base64 grows them by 4/3; the bridge accepts frames up to 48 MiB). */
export const MAX_UPLOAD = 25 * 1024 * 1024;

/** How claude-agent-acp writes a resource_link into the transcript: "[@name](file:///…)" (its formatUriAsLink). */
const linkText = b => b.uri?.startsWith('file://') ? `[@${b.uri.slice(7).split('/').pop()}](${b.uri})` : String(b.uri ?? '');

/** Claude Code's "!" transcript format escapes the tag bodies; the app unescapes them. */
const escapeTag = s => String(s ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

/** "<bash-input>…</bash-input>" + "<bash-stdout>…</bash-stdout><bash-stderr>…</bash-stderr>", as Claude Code writes them. */
export function shellTranscript(command, r) {
  let err = r.stderr ?? '';
  if (r.timedOut) err += (err && !err.endsWith('\n') ? '\n' : '') + 'Command timed out after 120 s';
  if (r.truncated) err += (err && !err.endsWith('\n') ? '\n' : '') + '[output truncated]';
  const code = r.exitCode == null ? (r.signal ? 128 : null) : r.exitCode;
  return {
    input: `<bash-input>${escapeTag(command)}</bash-input>`,
    output: `<bash-stdout>${escapeTag(r.stdout)}</bash-stdout><bash-stderr>${escapeTag(err)}</bash-stderr>` + (code ? `<bash-exit-code>${code}</bash-exit-code>` : ''),
  };
}

class SessionState {
  constructor(sessionId, cwd) {
    this.sessionId = sessionId;
    this.cwd = cwd ?? null;
    this.epoch = crypto.randomBytes(6).toString('hex');
    this.events = [];          // [{seq, update, phone, refs}]: update as the agent sent it, phone as phones get it
    this.media = new Map();    // ref -> {mimeType, data, uses}: the pictures the logged events hold
    this.seq = 0;
    this.trimmed = false;      // maxEvents has cut the start off the log
    this.loaded = false;       // known to the current agent process
    this.loading = null;       // Promise while session/load is in flight
    this.running = false;      // a session/prompt is in flight
    this.lastStopReason = null;
    this.configOptions = null;
    this.modes = null;
    this.title = null;
    this.updatedAt = null;
    this.synthUserText = '';   // text we echoed for the current turn (dedupe agent echoes)
    this.autoApprove = false;  // "Turbo": bridge answers permission prompts with allow_once
    this.queue = [];           // messages sent mid-turn, waiting for it to end: [{id, blocks, text, imageCount, clientId, at}]
    this.flushAfterCancel = false; // "interrupt and send": the cancelled turn's end sends the queue
    this.holdQueue = false;    // a plain Stop: the turn's end keeps the queue (however the agent reports that end)
    this.turnStart = 0;        // ms, when the current turn began
    this.turnFirstOutput = 0;  // ms, first agent output of the turn
    this.subscribers = new Set();
  }
  resetForReload() {
    this.epoch = crypto.randomBytes(6).toString('hex');
    this.events = [];
    this.media = new Map();
    this.seq = 0;
    this.trimmed = false;
  }
}

/** Merge chunk runs and fold tool_call_update into their tool_call so a fresh client gets a small snapshot. */
export function compactEvents(events) {
  const out = [];
  const toolIndex = new Map();
  for (const { seq, update } of events) {
    const k = update?.sessionUpdate;
    if (CHUNK_KINDS.has(k) && update.content?.type === 'text') {
      const prev = out[out.length - 1];
      const pu = prev?.update;
      // a subagent's chunks never merge into the parent's (Claude Code's replay can reuse one messageId for both)
      if (pu && pu.sessionUpdate === k && pu.content?.type === 'text' && (pu.messageId ?? null) === (update.messageId ?? null)
          && subagentOf(pu) === subagentOf(update)) {
        prev.update = { ...pu, content: { ...pu.content, text: pu.content.text + update.content.text } };
        prev.seq = seq;
        continue;
      }
      out.push({ seq, update: { ...update, content: { ...update.content } } });
      continue;
    }
    if (k === 'tool_call_update' && toolIndex.has(update.toolCallId)) {
      const target = out[toolIndex.get(update.toolCallId)];
      const merged = { ...target.update };
      for (const [key, v] of Object.entries(update)) {
        if (key === 'sessionUpdate' || v === null || v === undefined) continue;
        merged[key] = v;
      }
      target.update = merged;
      target.seq = Math.max(target.seq, seq);
      continue;
    }
    if (k === 'tool_call') toolIndex.set(update.toolCallId, out.length);
    out.push({ seq, update });
  }
  return out;
}

/**
 * The phone's pings wait behind a frame while it downloads, and the connection is dropped when they time out, so an
 * attach result stays small: pictures travel apart (media.mjs) and the text in it is held under this.
 */
export const SNAPSHOT_BUDGET = 2 * 1024 * 1024;
const LONG_TEXT = 16 * 1024;
const kb = n => Math.max(1, Math.round(n / 1024)) + ' KB';
const isTool = u => u?.sessionUpdate === 'tool_call' || u?.sessionUpdate === 'tool_call_update';

/** The first event of a snapshot that lacks the start of the conversation (phones show their own wording). */
const truncationMarker = seq => ({ seq, update: { sessionUpdate: 'notice', severity: 'info', title: 'Earlier history not shown',
  description: 'Open the conversation on the computer to see all of it.', _meta: { leash: { synthetic: true, historyTruncated: true } } } });
export const isTruncationMarker = e => !!e?.update?._meta?.leash?.historyTruncated && !e.update._meta.leash.subagent;

/** What is left of a long string once the snapshot is short of room. */
const SHORT_TEXT = 300;
const cutText = (s, keep) => s.length > keep + 100 ? s.slice(0, keep) + `\n… [${kb(Buffer.byteLength(s.slice(keep)))} more not sent to the phone]` : s;

/** `v` with every long string in it cut to SHORT_TEXT, keys and shape kept (the phones read fields of rawInput); `v` itself when none is long. */
function shortStrings(v) {
  if (typeof v === 'string') return cutText(v, SHORT_TEXT);
  if (!v || typeof v !== 'object') return v;
  let out = null;
  for (const key of Object.keys(v)) {
    const next = shortStrings(v[key]);
    if (next === v[key]) continue;
    out ??= Array.isArray(v) ? v.slice() : { ...v };
    out[key] = next;
  }
  return out ?? v;
}

/** A tool call's output cut to 16 KB per block. */
function cutLongOutput(u) {
  if (!isTool(u) || !Array.isArray(u.content)) return u;
  let changed = false;
  const content = u.content.map(b => {
    if (b?.type !== 'content' || b.content?.type !== 'text' || !(b.content.text?.length > LONG_TEXT)) return b;
    changed = true;
    return { type: 'content', content: { type: 'text', text: cutText(b.content.text, LONG_TEXT) } };
  });
  return changed ? { ...u, content } : u;
}

/**
 * A tool call down to what its row needs: long input and output cut short, a large diff replaced by a line naming its
 * file (cut text would show wrong changes; the file stays in locations). Picture references are small and stay.
 */
function shortTool(u) {
  if (!isTool(u)) return u;
  let out = null;
  const set = (key, v) => { if (v !== u[key]) (out ??= { ...u })[key] = v; };
  for (const key of ['title', 'rawInput', 'rawOutput', 'args']) if (u[key] !== undefined) set(key, shortStrings(u[key]));
  if (Array.isArray(u.content)) {
    let changed = false;
    const dropped = [];
    const content = u.content.map(b => {
      if (b?.type === 'diff') {
        const length = String(b.oldText ?? '').length + String(b.newText ?? '').length;
        if (length <= 2 * SHORT_TEXT) return b;
        changed = true;
        if (b.path) dropped.push(b.path);
        return { type: 'content', content: { type: 'text', text: `[changes to ${b.path ?? 'a file'} not sent to the phone (${kb(length)})]` } };
      }
      if (b?.type !== 'content' || b.content?.type !== 'text' || typeof b.content.text !== 'string') return b;
      const text = cutText(b.content.text, SHORT_TEXT);
      if (text === b.content.text) return b;
      changed = true;
      return { type: 'content', content: { type: 'text', text } };
    });
    if (changed) set('content', content);
    const known = new Set((u.locations ?? []).map(l => l?.path));
    const missing = [...new Set(dropped)].filter(p => !known.has(p));
    if (missing.length) set('locations', [...(u.locations ?? []), ...missing.map(path => ({ path }))]);
  }
  return out ?? u;
}

/**
 * Events (as phones get them) that fit in `budget` bytes of JSON. Past it, weight goes in the order a reader misses it
 * least, oldest first within each step, until the rest fits: long tool output is cut to 16 KB; subagents' tool calls are
 * shortened; subagents' transcripts are left out (their rows stay, each with a note inside); the conversation's own tool
 * calls are shortened; and only then is history dropped from the start (the newest event always stays), so what was
 * said outlasts what tools printed. With `shed: false` (a catch-up, which must lose nothing) only the first and last
 * steps apply. When events were dropped from the start, or `trimmed` says the log had already lost it, a marker saying
 * so comes first, within the budget. Returns copies; the log itself keeps everything.
 */
export function fitSnapshot(events, budget = SNAPSHOT_BUDGET, { trimmed = false, shed = true } = {}) {
  const size = e => e ? Buffer.byteLength(JSON.stringify(e)) + 1 : 0;   // with its comma
  const sizes = events.map(size);
  let total = sizes.reduce((a, b) => a + b, 1);                           // and the array's brackets
  // room for the marker, whatever its seq
  const marker = size(truncationMarker(Number.MAX_SAFE_INTEGER));
  const limit = trimmed ? budget - marker : budget;
  const marked = list => [truncationMarker(Math.max(0, (list[0]?.seq ?? 1) - 1)), ...list];
  if (total <= limit) return trimmed ? marked(events) : events;
  const out = events.slice();                                             // null: left out
  /** One step: `change(update, seq)` gives the update to send, or null to leave the event out. */
  const step = change => {
    for (let i = 0; i < out.length && total > limit; i++) {
      if (!out[i]) continue;
      const update = change(out[i].update, out[i].seq);
      if (update === out[i].update) continue;
      out[i] = update && { ...out[i], update };
      const n = size(out[i]);
      total -= sizes[i] - n; sizes[i] = n;
    }
  };
  step(cutLongOutput);
  if (shed) {
    step(u => subagentOf(u) ? shortTool(u) : u);
    const noted = new Set();
    step((u, seq) => {
      const child = subagentOf(u);
      // announcements make the subagent's row (and those of the subagents it launched)
      if (!child || u.sessionUpdate === 'subagent_update') return u;
      if (noted.has(child)) return null;
      noted.add(child);
      const note = truncationMarker(seq).update;
      return { ...note, _meta: { leash: { ...note._meta.leash, subagent: child } } };
    });
    step(u => subagentOf(u) ? u : shortTool(u));
  }
  const left = [], leftSizes = [];
  for (let i = 0; i < out.length; i++) if (out[i]) { left.push(out[i]); leftSizes.push(sizes[i]); }
  if (total <= limit) return trimmed ? marked(left) : left;
  let drop = 0;
  while (total > budget - marker && drop < left.length - 1) total -= leftSizes[drop++];
  const kept = drop ? left.slice(drop) : left;
  return drop || trimmed ? marked(kept) : kept;
}

/** The child session a recorded update came from (the bridge tags subagent updates it files under their root). */
const subagentOf = u => u?._meta?.leash?.subagent ?? null;

/** Child-session updates about state the phone keeps only for the root session. */
const CHILD_DROPPED = new Set(['available_commands_update', 'usage_update', 'current_mode_update', 'session_info_update']);
/** A subagent still has work in flight (its turn can outlive the parent's prompt). */
const childBusy = c => ['running', 'requires_action'].includes(c?.state?.state);

/** agy sends tool calls as {name, args:{CommandLine, toolSummary, ...}}; map them onto ACP's title/rawInput. */
export function normalizeUpdate(u) {
  const k = u?.sessionUpdate;
  if (k !== 'tool_call' && k !== 'tool_call_update') return u;
  // Claude Code's result again (screenshots included): the phone reads rawOutput only when there is no content
  if (Array.isArray(u.content) && u.content.length && u.rawOutput != null) { const { rawOutput: _, ...rest } = u; u = rest; }
  const toolName = u.name ?? u._meta?.claudeCode?.toolName;
  if (toolName === 'REPL' && u.rawInput && typeof u.rawInput === 'object') {
    const out = { ...u };
    if (!out.title || out.title === 'REPL') out.title = u.rawInput.description || 'Run script';
    if (!out.kind || out.kind === 'other') out.kind = 'execute';
    return out;
  }
  const args = u.args && typeof u.args === 'object' ? u.args : null;
  if (!args && u.title) return u;
  const out = { ...u };
  if (args && out.rawInput === undefined) out.rawInput = args;
  if (!out.title) {
    const t = args?.toolSummary || args?.Summary || args?.summary;
    if (t || out.name) out.title = t || humanize(out.name);
  }
  if (!out.kind && out.name) out.kind = guessKind(out.name);
  return out;
}
function describeHolder(h) {
  const where = h.entrypoint === 'cli' ? 'Claude Code in a terminal' : h.entrypoint === 'claude-desktop' ? 'the Claude desktop app' : (h.entrypoint || 'another client');
  return where + (h.pid ? ' (pid ' + h.pid + ')' : '');
}
function humanize(name) { return String(name).replace(/[_-]+/g, ' ').replace(/^./, c => c.toUpperCase()); }
function guessKind(name) {
  const n = String(name).toLowerCase();
  if (/command|terminal|exec|shell|bash/.test(n)) return 'execute';
  if (/write|edit|replace|patch|create_file/.test(n)) return 'edit';
  if (/delete|remove/.test(n)) return 'delete';
  if (/grep|search|find|glob/.test(n)) return 'search';
  if (/read|view|open|list_dir|ls/.test(n)) return 'read';
  if (/fetch|browse|url|web/.test(n)) return 'fetch';
  return 'other';
}

export class Hub extends EventEmitter {
  constructor({ id = 'agy', label = 'Antigravity', agentCommand, agentArgs = ['acp'], agentCwd = os.homedir(), agentEnv = process.env, log = console.log, maxEvents = 10000, home = os.homedir(), sessionMeta = null, helper = null, maxListed = 0, listPerCwd = false, idleCloseMs = 20 * 60 * 1000, locks = undefined, locksMaxAgeMs = 3000, lockSweepMs = 15000, turbo = undefined, btw = undefined, unavailable = null, recheck = null }) {
    super();
    Object.assign(this, { id, label, agentCommand, agentArgs, agentCwd, agentEnv, maxEvents, home, sessionMeta, helper, maxListed, listPerCwd, idleCloseMs, locksMaxAgeMs });
    // The agent's command is not on this machine: the hub stays 'down' with this text as its error (no spawn, no restart
    // loop) until "Restart" in the app finds the command (recheck() -> {command, args?, env?} or null).
    this.unavailable = unavailable; this.recheck = recheck;
    // "Turbo" (bridge-side approve-all) is Antigravity's name; Claude Code has its own modes for that (bypassPermissions).
    this.turbo = turbo ?? !helper;
    // /btw side questions need `claude -p --fork-session` on the host (helper.mjs btw); agy has no throwaway fork.
    this.sideQuestions = btw ?? !!helper;
    /** conversations can be listed in Claude Code's own /resume on the agent's host (helper cli-visibility) */
    this.cliList = !!helper;
    // "Open elsewhere" lock: a function resolving {now, held, touched} (see remote/helper.mjs locks).
    // Default: on for hubs with a remote helper (Claude Code); false turns it off.
    this.locksFn = locks === false ? null : typeof locks === 'function' ? locks : (helper ? () => this.runHelper(['locks'], 10000) : null);
    this.locksCache = null; this.locksInflight = null;
    this.lockOwnWrites = false;      // lock source's "touched" also counts our own agent's writes (agy: shared DB)
    this.lockedNotified = new Map(); // sessionId -> lockedBy, told to phones; cleared with session_unlocked
    // Claude deletes its session file on a clean exit, so a desktop visit can leave no trace: watch loaded sessions.
    if (this.locksFn && lockSweepMs > 0) { this.lockTimer = setInterval(() => this.sweepLocks().catch(() => {}), lockSweepMs); this.lockTimer.unref?.(); }
    // Close sessions nobody is watching (each loaded Claude session is a live `claude` process).
    if (idleCloseMs > 0) { this.idleTimer = setInterval(() => this.closeIdleSessions(), Math.min(60000, idleCloseMs)); this.idleTimer.unref?.(); }
    this.log = (...a) => log(`[${id}]`, ...a);
    this.clients = new Set();
    this.sessions = new Map();
    this.pending = new Map();      // key -> {key, rpcId, method, params, sessionId, createdAt}
    this.nextKey = 1;
    this.nextClientId = 1;
    this.agent = null;
    this.agentState = 'down';      // starting | ready | down
    this.agentInfo = null;         // initialize result
    this.agentError = null;
    this.restartDelay = 1000;
    this.restartTimer = null;
    this.stopping = false;
    this.knownCwds = new Map();    // sessionId -> cwd (from session/list)
    // Subagent sessions the agent announced with subagent_update (ACP "subagents" RFD): child sessionId ->
    // {root, parent, title, description, state}. Their updates and requests are filed under the root session.
    this.children = new Map();
    this.stderrTail = [];
    this.quota = null;           // QuotaPoller (optional)
  }

  attachQuota(poller) {
    this.quota = poller;
    poller.onUpdate = q => this.broadcast({ op: 'quota', quota: q });
  }

  // ---------------------------------------------------------------- agent lifecycle
  async startAgent() {
    if (this.stopping) return;
    clearTimeout(this.restartTimer);
    if (this.unavailable) { this.agentError = this.unavailable; this.setAgentState('down'); return; }
    this.setAgentState('starting');
    const agent = new AcpProcess({ command: this.agentCommand, args: this.agentArgs, cwd: this.agentCwd, env: this.agentEnv, log: this.log });
    this.agent = agent;
    agent.on('stderr', t => {
      for (const line of String(t).split('\n')) if (line.trim()) { this.stderrTail.push(line); if (this.stderrTail.length > 50) this.stderrTail.shift(); }
    });
    agent.on('notification', (m, p) => this.#onAgentNotification(m, p));
    agent.on('request', (id, m, p) => this.#onAgentRequest(id, m, p));
    agent.on('exit', (code, signal, err) => this.#onAgentExit(agent, code, signal, err));
    agent.start();
    try {
      const info = await agent.call('initialize', {
        protocolVersion: 1,
        clientCapabilities: {
          fs: { readTextFile: false, writeTextFile: false },
          terminal: false,
          elicitation: { form: {} },
          // subagents as child sessions (claude-agent-acp's RFD form): their history replays on load, and their
          // updates come on their own session ids instead of mixed into the parent's
          subagents: {},
        },
        clientInfo: { name: 'leash-bridge', title: 'Leash', version: BRIDGE_VERSION },
      }, { timeoutMs: 60000 });
      if (this.agent !== agent) return;
      this.agentInfo = info;
      this.steeringBroken = false;
      this.agentError = null;
      this.restartDelay = 1000;
      this.setAgentState('ready');
      this.log('agent ready: ' + JSON.stringify(info?.agentInfo ?? {}));
    } catch (e) {
      if (this.agent !== agent) return;
      this.agentError = e.message + (this.stderrTail.length ? ' | ' + this.stderrTail.slice(-3).join(' | ') : '');
      this.log('agent initialize failed: ' + this.agentError);
      agent.stop();
    }
  }

  setAgentState(state) {
    this.agentState = state;
    this.broadcast({ op: 'agent', state, error: this.agentError, info: this.agentInfo, label: this.label, home: this.home, steering: this.steers, turbo: this.turbo, btw: this.sideQuestions, cliList: this.cliList });
  }

  /** Merge this agent's _meta (e.g. Claude Code settings) into session/new|load|resume params. */
  withSessionMeta(params) {
    if (!this.sessionMeta) return params;
    const merge = (a, b) => {
      if (!a || typeof a !== 'object' || Array.isArray(a)) return b;
      const out = { ...a };
      for (const [k, v] of Object.entries(b ?? {})) out[k] = (v && typeof v === 'object' && !Array.isArray(v)) ? merge(a[k], v) : v;
      return out;
    };
    return { ...params, _meta: merge(params._meta ?? {}, this.sessionMeta) };
  }

  /** Run the remote fs/quota helper (array command, e.g. ['ubuntu','node','/…/helper.mjs']); `input` goes to its stdin. */
  runHelper(args, timeoutMs = 60000, helper = this.helper, input = null) {
    return new Promise((resolve, reject) => {
      const [cmd, ...pre] = helper;
      const token = Buffer.from(JSON.stringify(args.map(String)), 'utf8').toString('base64url');
      // run locally, the helper's $HOME is this hub's home (its sandbox)
      const env = helper === LOCAL_HELPER ? { ...this.agentEnv, HOME: this.home, ...(process.platform === 'win32' ? { USERPROFILE: this.home } : {}) } : this.agentEnv;
      const child = spawn(cmd, [...pre, '--b64', token], { stdio: [input == null ? 'ignore' : 'pipe', 'pipe', 'pipe'], env });
      if (input != null) {
        child.stdin.on('error', () => {});   // the helper may exit before reading it all (EPIPE): its JSON says why
        child.stdin.end(input);
      }
      let out = '', err = '';
      const timer = setTimeout(() => { child.kill('SIGKILL'); reject(new RpcError(-32098, 'helper timed out')); }, timeoutMs);
      child.stdout.on('data', d => { out += d; });
      child.stderr.on('data', d => { err += d; });
      child.on('error', e => { clearTimeout(timer); reject(new RpcError(-32603, 'helper failed: ' + e.message)); });
      child.on('close', () => {
        clearTimeout(timer);
        const start = out.indexOf('{');
        let j; try { j = JSON.parse(out.slice(start)); } catch { return reject(new RpcError(-32603, 'helper returned no JSON' + (err ? ': ' + err.trim().slice(-200) : ''))); }
        if (j.error) reject(new RpcError(j.error.code ?? -32603, j.error.message)); else resolve(j);
      });
    });
  }

  #onAgentExit(agent, code, signal, err) {
    if (this.agent !== agent) return;
    this.log(`agent exited code=${code} signal=${signal}${err ? ' err=' + err.message : ''}`);
    if (!this.agentError) this.agentError = `agent exited (code ${code}${signal ? ', ' + signal : ''})` + (this.stderrTail.length ? ': ' + this.stderrTail.slice(-3).join(' | ') : '');
    this.agent = null;
    this.children.clear();
    for (const p of [...this.pending.values()]) this.#resolvePending(p.key, { reason: 'agent_exited' });
    for (const s of this.sessions.values()) {
      s.loaded = false; s.loading = null;
      if (s.running) { s.running = false; s.lastStopReason = 'agent_exited'; this.#broadcastTurn(s, { error: 'agent exited' }); }
      else if (s.endPending) {
        s.endPending = false;
        s.lastStopReason = 'agent_exited';
        s.turnEndedAt = Date.now();
        this.emit('turn_end', s);
        this.#broadcastTurn(s, { error: 'agent exited' });
      }
    }
    this.setAgentState('down');
    if (this.stopping) return;
    const delay = this.restartDelay;
    this.restartDelay = Math.min(this.restartDelay * 2, 30000);
    this.restartTimer = setTimeout(() => this.startAgent(), delay);
  }

  async restartAgent() {
    this.restartDelay = 1000;
    if (this.unavailable) {
      const found = this.recheck?.();
      if (!found) { this.setAgentState('down'); return { ok: true }; }
      Object.assign(this, { agentCommand: found.command, agentArgs: found.args ?? this.agentArgs, agentEnv: found.env ?? this.agentEnv });
      this.unavailable = null; this.agentError = null;
      this.log('agent found: ' + found.command);
    }
    if (this.agent) { const a = this.agent; a.stop(); }
    else this.startAgent();
    return { ok: true };
  }

  stop() { this.stopping = true; clearTimeout(this.restartTimer); clearInterval(this.idleTimer); clearInterval(this.lockTimer); this.agent?.stop(); }

  async closeIdleSessions(now = Date.now()) {
    if (!this.agent || this.agentState !== 'ready') return [];
    if (!this.agentInfo?.agentCapabilities?.sessionCapabilities?.close) return [];
    const closed = [];
    for (const s of this.sessions.values()) {
      if (!s.loaded || s.loading || this.#busy(s) || s.subscribers.size > 0) continue;
      if ([...this.pending.values()].some(p => p.sessionId === s.sessionId)) continue;
      if (now - (s.lastActive ?? 0) < this.idleCloseMs) continue;
      try { await this.agent.call('session/close', { sessionId: s.sessionId }, { timeoutMs: 15000 }); } catch {}
      s.loaded = false;
      closed.push(s.sessionId);
      this.log('closed idle session ' + s.sessionId);
    }
    return closed;
  }

  /** Sessions another live client has open (cached briefly; null when off or the check failed = fail open). */
  async foreignLocks({ maxAgeMs = this.locksMaxAgeMs } = {}) {
    if (!this.locksFn) return null;
    const c = this.locksCache;
    if (c && Date.now() - c.at < maxAgeMs) return c.value;
    if (!this.locksInflight) {
      this.locksInflight = (async () => {
        try { const v = await this.locksFn(); if (v?.ownWrites) this.lockOwnWrites = true; this.locksCache = { at: Date.now(), value: v }; return v; }
        catch (e) { this.log('lock check failed, allowing: ' + e.message); return null; }
        finally { this.locksInflight = null; }
      })();
    }
    return this.locksInflight;
  }

  /**
   * Refuse a session another client (Claude Code in a terminal / desktop app, the Antigravity app) is running a turn
   * in, so two writers never race on one conversation. Our own copy is closed so the next attach reloads from disk.
   * A copy that another client opened (and closed) since we loaded it is stale: dropped, and a prompt is refused
   * with -32021 so the phone re-attaches first.
   */
  async checkLock(sessionId, { forPrompt = false } = {}) {
    const lk = await this.foreignLocks();
    if (!lk) return null;
    const s = this.sessions.get(sessionId);
    const holder = (lk.held ?? []).find(h => h.sessionId === sessionId && !this.#ownBusyEcho(h));
    if (holder) {
      // our own turn: keep watching it; a "busy" holder is then most likely that very turn (shared status DB)
      if (s?.running && (!forPrompt || (holder.basis === 'busy' && lk.ownWrites))) return lk;
      if (s?.loaded && !this.#busy(s)) await this.releaseSession(s);
      this.lockedNotified.set(sessionId, holder.entrypoint || 'other');
      throw new RpcError(-32020, 'conversation is busy in ' + describeHolder(holder) + '; wait until it finishes', { locked: true, holders: [holder] });
    }
    const touchedAt = lk.touched?.[sessionId] ?? 0;
    if (s?.loaded && !this.#busy(s) && s.extBaseline != null && touchedAt > s.extBaseline) {
      await this.releaseSession(s);
      if (forPrompt) throw new RpcError(-32021, 'conversation changed on another device; reload it before sending', { stale: true });
    }
    return lk;
  }

  /** Release loaded sessions another client has open (or used since we loaded them) and tell the phones. */
  async sweepLocks() {
    const loaded = [...this.sessions.values()].filter(s => s.loaded && !this.#busy(s) && !s.loading);
    if (!loaded.length && !this.lockedNotified.size) return [];
    const lk = await this.foreignLocks();
    if (!lk) return [];
    const heldBy = new Map((lk.held ?? []).filter(h => !this.#ownBusyEcho(h)).map(h => [h.sessionId, h]));
    for (const sid of [...this.lockedNotified.keys()]) {
      if (heldBy.has(sid)) continue;
      this.lockedNotified.delete(sid);
      this.broadcast({ op: 'session_unlocked', sessionId: sid });
    }
    const released = [];
    for (const s of loaded) {
      const h = heldBy.get(s.sessionId);
      const changed = s.extBaseline != null && (lk.touched?.[s.sessionId] ?? 0) > s.extBaseline;
      if (!h && !changed) continue;
      await this.releaseSession(s);
      if (h) { this.lockedNotified.set(s.sessionId, h.entrypoint || 'other'); this.broadcast({ op: 'session_locked', sessionId: s.sessionId, lockedBy: h.entrypoint || 'other' }); }
      released.push(s.sessionId);
    }
    return released;
  }

  /** A "busy" holder for a session whose own turn just ended is the status DB catching up with us, not the desktop. */
  #ownBusyEcho(h) {
    const s = h.basis === 'busy' && this.lockOwnWrites ? this.sessions.get(h.sessionId) : null;
    return !!s?.turnEndedAt && Date.now() - s.turnEndedAt < 5000;
  }

  async releaseSession(s) {
    if (this.agent && this.agentInfo?.agentCapabilities?.sessionCapabilities?.close) {
      try { await this.agent.call('session/close', { sessionId: s.sessionId }, { timeoutMs: 15000 }); } catch {}
    }
    s.loaded = false;
    s.extBaseline = null;
    s.releasedElsewhere = true;   // next prompt: -32021 (reload first) rather than "attach first"
    this.log('released ' + s.sessionId + ' (open or changed elsewhere)');
  }

  requireAgent() {
    if (!this.agent || this.agentState !== 'ready') throw new RpcError(-32001, 'agent not ready (' + this.agentState + ')' + (this.agentError ? ': ' + this.agentError : ''));
    return this.agent;
  }

  // ---------------------------------------------------------------- clients
  /** Standalone use (tests / single agent): own client registry + 'ready'. With a Router, use attachClient. */
  addClient(send, meta = {}) {
    const client = { id: this.nextClientId++, send, subs: new Set(), ...meta };
    this.clients.add(client);
    send({ op: 'ready', ...this.info() });
    return client;
  }

  attachClient(client) { this.clients.add(client); }

  removeClient(client) {
    this.clients.delete(client);
    for (const sid of client.subs) { const s = this.sessions.get(sid); if (s) { s.subscribers.delete(client); s.lastActive = Date.now(); } }
  }

  broadcast(msg) { const m = { ...msg, agentId: this.id }; for (const c of this.clients) c.send(m); }

  agentSummary() {
    return { id: this.id, label: this.label, state: this.agentState, error: this.agentError, info: this.agentInfo, home: this.home, steering: this.steers, turbo: this.turbo, btw: this.sideQuestions, cliList: this.cliList, quota: this.quota?.latest ?? null };
  }

  info() {
    return {
      bridge: { version: BRIDGE_VERSION, protocol: BRIDGE_PROTOCOL, hostname: os.hostname(), home: this.home, platform: process.platform },
      agent: { state: this.agentState, error: this.agentError, info: this.agentInfo },
      pending: [...this.pending.values()].map(p => this.pendingMsg(p)),
      // a turn waiting on its background subagents counts as running
      running: [...this.sessions.values()].filter(s => this.#busy(s)).map(s => s.sessionId),
      quota: this.quota?.latest ?? null,
    };
  }

  // ---------------------------------------------------------------- agent -> bridge
  #session(sessionId, cwd) {
    let s = this.sessions.get(sessionId);
    if (!s) { s = new SessionState(sessionId, cwd ?? this.knownCwds.get(sessionId)); this.sessions.set(sessionId, s); }
    else if (cwd && !s.cwd) s.cwd = cwd;
    return s;
  }

  #record(s, update) {
    s.lastActive = Date.now();
    // the latest command list outlives the event log's trim (attach returns it)
    if (update?.sessionUpdate === 'available_commands_update' && !subagentOf(update)) s.commands = update.availableCommands ?? [];
    const seq = ++s.seq;
    const entry = this.#logEntry(s, seq, update);
    s.events.push(entry);
    if (s.events.length > this.maxEvents) this.#trim(s);
    // During session/load the agent replays history; clients get it in the attach result instead.
    if (s.loading) return;
    const msg = { op: 'update', agentId: this.id, sessionId: s.sessionId, epoch: s.epoch, seq, update: entry.phone };
    for (const c of s.subscribers) c.send(msg);
  }

  /** The update is logged whole; what phones get is made once, here, and its pictures filed for bridge/media. */
  #logEntry(s, seq, update) {
    const { update: phone, images } = phoneUpdate(update);
    for (const { ref, mimeType, data } of images) {
      const m = s.media.get(ref);
      if (m) m.uses++;
      else s.media.set(ref, { mimeType, data, uses: 1 });
    }
    return { seq, update, phone, refs: images.map(i => i.ref) };
  }

  /** Cut the log to maxEvents; a picture goes with the last event that held it. */
  #trim(s) {
    for (const { refs } of s.events.splice(0, s.events.length - this.maxEvents)) {
      for (const ref of refs) {
        const m = s.media.get(ref);
        if (m && --m.uses === 0) s.media.delete(ref);
      }
    }
    s.trimmed = true;
  }

  /**
   * One slice of a picture's base64, from character `offset`; the phone asks on until it has `total` characters.
   * Only what the log holds is served: a session this bridge has not attached since it started has nothing.
   */
  async media({ sessionId, ref, offset = 0 }) {
    if (typeof sessionId !== 'string' || !sessionId) throw new RpcError(-32602, 'sessionId required');
    if (typeof ref !== 'string' || !ref) throw new RpcError(-32602, 'ref required');
    if (!Number.isInteger(offset) || offset < 0) throw new RpcError(-32602, 'offset must be a non-negative integer');
    const s = this.sessions.get(sessionId);
    // a reload empties the log and the replay fills it again
    if (s?.loading && !s.media.has(ref)) await s.loading.catch(() => {});
    const m = s?.media.get(ref);
    if (!m) throw new RpcError(-32004, 'image not available');
    const total = m.data.length;
    if (offset >= total) throw new RpcError(-32602, 'offset out of range');
    return { ref, mimeType: m.mimeType, offset, total, data: m.data.slice(offset, offset + MEDIA_CHUNK) };
  }

  /** Subagents of `s` still working. Only ones heard live count: a load's replay can end on a stale "running". */
  #subagentsRunning(s) {
    let n = 0;
    for (const c of this.children.values()) if (c.root === s.sessionId && c.live && childBusy(c)) n++;
    return n;
  }

  /** A prompt is in flight, or a subagent it started is still working (background subagents outlive the turn). */
  #busy(s) { return s.running || this.#subagentsRunning(s) > 0; }

  /** subagent_update on `parentId` (a root or itself a child) announces or updates a child session. */
  #trackSubagent(parentId, u) {
    if (u.sessionUpdate !== 'subagent_update' || typeof u.sessionId !== 'string') return;
    const root = this.children.get(parentId)?.root ?? parentId;
    const s = this.sessions.get(root);
    const before = s ? this.#subagentsRunning(s) : 0;
    const c = this.children.get(u.sessionId) ?? { root, parent: parentId, title: null, description: null, state: null, live: false };
    for (const k of ['title', 'description', 'state']) if (u[k] !== undefined) c[k] = u[k];
    if (!s?.loading) c.live = true;
    this.children.set(u.sessionId, c);
    if (s && !s.loading && this.#subagentsRunning(s) !== before) this.#subagentsChanged(s);
  }

  /**
   * Phones see a turn as over once its prompt has returned and its last subagent has finished: the parent often
   * ends its prompt only to wait for background subagents.
   */
  #subagentsChanged(s) {
    if (s.endPending && !s.running && !this.#subagentsRunning(s)) {
      s.endPending = false;
      s.turnEndedAt = Date.now();
      if (this.lockOwnWrites) s.extBaseline = Date.now() + 3000;
      this.emit('turn_end', s);
    }
    this.#broadcastTurn(s);
  }

  /** A child session's update goes into its root's log, tagged with the child id; it never touches the root's state. */
  #onChildUpdate(childId, child, update) {
    const s = this.sessions.get(child.root);
    if (!s) return;
    const u = normalizeUpdate(update ?? {});
    if (CHILD_DROPPED.has(u.sessionUpdate)) return;
    this.#trackSubagent(childId, u);
    const meta = u._meta && typeof u._meta === 'object' ? u._meta : {};
    this.#record(s, { ...u, _meta: { ...meta, leash: { ...(meta.leash ?? {}), subagent: childId } } });
  }

  #onAgentNotification(method, params) {
    if (method !== 'session/update' || !params?.sessionId) {
      this.broadcast({ op: 'notification', method, params });
      return;
    }
    const child = this.children.get(params.sessionId);
    if (child) return this.#onChildUpdate(params.sessionId, child, params.update);
    const s = this.#session(params.sessionId);
    const u = normalizeUpdate(params.update ?? {});
    switch (u.sessionUpdate) {
      case 'subagent_update': this.#trackSubagent(s.sessionId, u); break;
      case 'config_option_update': if (u.configOptions) s.configOptions = u.configOptions; break;
      case 'current_mode_update': if (s.modes && u.currentModeId) s.modes = { ...s.modes, currentModeId: u.currentModeId }; break;
      case 'session_info_update':
        if (u.title !== undefined) s.title = u.title;
        if (u.updatedAt) s.updatedAt = u.updatedAt;
        this.broadcast({ op: 'session_info', sessionId: s.sessionId, title: s.title, updatedAt: s.updatedAt });
        break;
      case 'user_message_chunk':
        // We already echoed the user's prompt ourselves; drop the agent's live echo of it.
        if (s.running && u.content?.type === 'text' && s.synthUserText && s.synthUserText.includes(u.content.text)) return;
        break;
    }
    if (s.running && !s.turnFirstOutput && ['agent_message_chunk', 'agent_thought_chunk', 'tool_call'].includes(u.sessionUpdate)) s.turnFirstOutput = Date.now();
    this.#record(s, u);
  }

  #onAgentRequest(rpcId, method, params) {
    if (method === 'session/request_permission' || method === 'elicitation/create') {
      let sessionId = params?.sessionId ?? null;
      if (!sessionId) {
        // Request-scoped elicitation: attribute it to the running session if unambiguous, else decline.
        const runningIds = [...this.sessions.values()].filter(s => s.running).map(s => s.sessionId);
        if (runningIds.length === 1) sessionId = runningIds[0];
        else {
          try { this.agent?.respond(rpcId, method === 'elicitation/create' ? { action: 'decline' } : { outcome: { outcome: 'cancelled' } }); } catch {}
          this.log(`declined ${method} with no session scope`);
          return;
        }
      }
      // a subagent's request names its child session: file it under the root, which shows and cancels it
      const child = this.children.get(sessionId);
      const subagent = child ? { sessionId, title: child.title ?? null, description: child.description ?? null } : null;
      if (child) sessionId = child.root;
      const sess = this.sessions.get(sessionId);
      if (method === 'session/request_permission' && sess?.autoApprove) {
        const opts = params?.options ?? [];
        const pick = opts.find(o => o.kind === 'allow_once') || opts.find(o => String(o.kind).startsWith('allow'));
        if (pick) {
          try { this.agent?.respond(rpcId, { outcome: { outcome: 'selected', optionId: pick.optionId } }); } catch {}
          this.#record(sess, { sessionUpdate: 'leash_auto_approved', toolCallId: params?.toolCall?.toolCallId ?? null, title: params?.toolCall?.title ?? null });
          return;
        }
      }
      const key = this.id + ':r' + (this.nextKey++);
      const p = { key, rpcId, method, params, sessionId, subagent, createdAt: Date.now() };
      this.pending.set(key, p);
      this.broadcast(this.pendingMsg(p));
      this.emit('attention', p);
      return;
    }
    // fs/* and terminal/* are not advertised; anything else is unknown.
    try { this.agent?.respondError(rpcId, { code: -32601, message: 'Method not supported by Leash bridge: ' + method }); } catch {}
  }

  pendingMsg(p) { return { op: 'request', agentId: this.id, key: p.key, method: p.method, sessionId: p.sessionId, subagent: p.subagent ?? null, params: p.params, createdAt: p.createdAt }; }

  #resolvePending(key, info) {
    const p = this.pending.get(key);
    if (!p) return null;
    this.pending.delete(key);
    this.broadcast({ op: 'resolved', key, sessionId: p.sessionId, ...info });
    return p;
  }

  /** A phone answered a permission / elicitation request. */
  reply(client, key, result, error) {
    const p = this.pending.get(key);
    if (!p) throw new RpcError(-32002, 'request already resolved');
    if (error) this.agent?.respondError(p.rpcId, error);
    else this.agent?.respond(p.rpcId, result);
    this.#resolvePending(key, { by: client?.id ?? null, result: result ?? null });
    return { ok: true };
  }

  #cancelPendingFor(sessionId) {
    for (const p of [...this.pending.values()]) {
      if (p.sessionId !== sessionId) continue;
      const result = p.method === 'session/request_permission' ? { outcome: { outcome: 'cancelled' } } : { action: 'cancel' };
      try { this.agent?.respond(p.rpcId, result); } catch {}
      this.#resolvePending(p.key, { result, reason: 'cancelled' });
    }
  }

  /** `running`: a prompt is in flight; `subagents`: how many of its subagents still work (the turn isn't over). */
  #broadcastTurn(s, extra = {}) {
    const subagents = this.#subagentsRunning(s);
    const busy = s.running || subagents > 0;
    this.broadcast({ op: 'turn', sessionId: s.sessionId, running: s.running, subagents, stopReason: busy ? null : s.lastStopReason, startedAt: busy ? s.turnStart || null : null, ...extra });
  }

  // ---------------------------------------------------------------- phone -> bridge calls
  async handleCall(client, method, params = {}) {
    switch (method) {
      case 'bridge/info': return this.info();
      case 'bridge/attach': return this.attach(client, params);
      case 'bridge/detach': {
        const s = this.sessions.get(params.sessionId);
        s?.subscribers.delete(client); client.subs.delete(params.sessionId);
        if (s) s.lastActive = Date.now();
        return { ok: true };
      }
      case 'bridge/media': return this.media(params);
      case 'bridge/browse': return this.browse(params.path);
      case 'bridge/readFile': return this.readFile(params.path, params.maxBytes, params);
      case 'bridge/upload': return this.upload(params);
      case 'bridge/restartAgent': return this.restartAgent();
      case 'bridge/shell': return this.shell(client, params);
      case 'bridge/btw': return this.btw(params);
      case 'bridge/cliVisibility': return this.cliVisibility(params);
      case 'bridge/showInCli': return this.showInCli(params);
      case 'bridge/agentLog': return { lines: this.stderrTail.slice() };
      case 'bridge/noticed': {
        // a phone alerted about this itself (its own notification or a Live Activity alert): Bark need not push it too
        if (params.turnEnd) {
          const s = this.sessions.get(params.sessionId);
          if (s) s.endNoticedAt = Date.now();
          return { ok: !!s };
        }
        const p = this.pending.get(params.key);
        if (p) p.noticedAt = Date.now();
        return { ok: !!p };
      }
      case 'bridge/quota': {
        if (!this.quota) return { quota: null, error: 'quota disabled' };
        try { return { quota: await this.quota.refresh({ maxAgeMs: params.force ? 0 : 120000 }) }; }
        catch (e) { return { quota: this.quota.latest, error: e.message }; }
      }
      case 'bridge/setAutoApprove': {
        const s = this.sessions.get(params.sessionId);
        if (!s) throw new RpcError(-32004, 'unknown session');
        if (!this.turbo && params.enabled) throw new RpcError(-32601, this.label + ' has no Turbo; use its own permission modes');
        s.autoApprove = !!params.enabled;
        this.broadcast({ op: 'session_flags', sessionId: s.sessionId, autoApprove: s.autoApprove });
        // approve anything already waiting
        if (s.autoApprove) for (const p of [...this.pending.values()]) {
          if (p.sessionId !== s.sessionId || p.method !== 'session/request_permission') continue;
          const pick = (p.params?.options ?? []).find(o => o.kind === 'allow_once') || (p.params?.options ?? []).find(o => String(o.kind).startsWith('allow'));
          if (pick) this.reply(null, p.key, { outcome: { outcome: 'selected', optionId: pick.optionId } });
        }
        return { ok: true, autoApprove: s.autoApprove };
      }
      case 'session/load': return this.attach(client, { ...params, sinceSeq: 0 });
      case 'session/new': return this.newSession(client, params);
      case 'session/list': return this.listSessions(params);
      case 'session/prompt': return this.prompt(client, params);
      case 'session/cancel': {
        this.requireAgent();
        const s = this.sessions.get(params.sessionId);
        if (s?.running) { s.flushAfterCancel = false; s.holdQueue = true; }   // a plain Stop keeps the queue for the user to send, edit or drop
        this.#cancelTurn(params.sessionId);
        return { ok: true };
      }
      case 'bridge/unqueue': return this.unqueue(params);
      case 'bridge/requeue': return this.requeue(client, params);
      case 'session/set_config_option': {
        const r = await this.requireAgent().call(method, params);
        const s = this.sessions.get(params.sessionId);
        if (s && r?.configOptions) { s.configOptions = r.configOptions; this.#record(s, { sessionUpdate: 'config_option_update', configOptions: r.configOptions, _meta: { leash: { synthetic: true } } }); }
        return r;
      }
      case 'session/set_mode': {
        const r = await this.requireAgent().call(method, params);
        const s = this.sessions.get(params.sessionId);
        if (s) { if (s.modes) s.modes = { ...s.modes, currentModeId: params.modeId }; this.#record(s, { sessionUpdate: 'current_mode_update', currentModeId: params.modeId, _meta: { leash: { synthetic: true } } }); }
        return r;
      }
      default:
        if (BLOCKED_METHODS.has(method) || method.startsWith('bridge/')) throw new RpcError(-32601, 'method not allowed: ' + method);
        return this.requireAgent().call(method, params);
    }
  }

  async newSession(client, params) {
    const agent = this.requireAgent();
    const cwd = params.cwd || this.home;
    const r = await agent.call('session/new', this.withSessionMeta({ mcpServers: [], ...params, cwd }));
    const s = this.#session(r.sessionId, cwd);
    s.loaded = true;
    s.configOptions = r.configOptions ?? null;
    s.modes = r.modes ?? null;
    this.#subscribe(client, s);
    // any lock snapshot taken before now is a valid baseline for "changed elsewhere since we had it"
    if (this.locksFn) this.foreignLocks({ maxAgeMs: 60000 }).then(lk => { if (lk && s.extBaseline == null) s.extBaseline = lk.now; });
    this.broadcast({ op: 'session_created', sessionId: s.sessionId, cwd });
    return { ...r, epoch: s.epoch, lastSeq: s.seq };
  }

  async listSessions(params) {
    const agent = this.requireAgent();
    const [r, lk] = await Promise.all([agent.call('session/list', params), this.foreignLocks({ maxAgeMs: 10000 })]);
    const heldBy = new Map((lk?.held ?? []).map(h => [h.sessionId, h]));
    let list = r?.sessions ?? [];
    if (this.listPerCwd && params?.cwd == null && params?.cursor == null) list = await this.#withScopedLists(agent, list);
    for (const it of list) if (it.sessionId && it.cwd) this.knownCwds.set(it.sessionId, it.cwd);
    if (this.maxListed && list.length > this.maxListed) {
      list = [...list].sort((a, b) => String(b.updatedAt ?? '').localeCompare(String(a.updatedAt ?? ''))).slice(0, this.maxListed);
    }
    return {
      ...r,
      sessions: list.map(it => {
        const s = this.sessions.get(it.sessionId);
        const h = heldBy.get(it.sessionId);
        const subagents = s ? this.#subagentsRunning(s) : 0;
        const busy = !!s?.running || subagents > 0;
        return { ...it, _leash: { agent: this.id, running: !!s?.running, subagents, loaded: !!s?.loaded, lockedBy: h ? h.entrypoint || 'other' : null, pending: [...this.pending.values()].filter(p => p.sessionId === it.sessionId).length,
          // how this bridge's last turn in it ended (the phone's Live Activity fills in a turn it missed)
          stopReason: busy ? null : s?.lastStopReason ?? null, turnEndedAt: busy ? null : s?.turnEndedAt ?? null } };
      }),
    };
  }

  /**
   * Claude Code's SDK looks for a session's cwd only in the first 64 KB of its transcript, so a session whose first
   * prompt carries a photo (inline base64, ~600 KB) has none there and an unscoped list leaves it out; a list scoped
   * to a directory falls back to that directory. Ask each directory we know of once and add what the full list missed.
   */
  async #withScopedLists(agent, list) {
    try {
      const seen = new Set(list.map(s => s.sessionId));
      const cwds = new Set(list.map(s => s.cwd));
      for (const c of this.knownCwds.values()) cwds.add(c);
      for (const s of this.sessions.values()) cwds.add(s.cwd);
      cwds.delete(null); cwds.delete(undefined); cwds.delete('');
      const pages = await Promise.all([...cwds].map(cwd => agent.call('session/list', { cwd }).catch(() => null)));
      const extra = [];
      for (const p of pages) for (const it of p?.sessions ?? []) if (it.sessionId && !seen.has(it.sessionId)) { seen.add(it.sessionId); extra.push(it); }
      if (extra.length) this.log(`session/list: ${extra.length} session(s) found only in a per-directory list`);
      return extra.length ? [...list, ...extra] : list;
    } catch (e) {
      this.log('per-directory session/list failed: ' + e.message);
      return list;
    }
  }

  #subscribe(client, s) { s.subscribers.add(client); client.subs.add(s.sessionId); }

  async ensureLoaded(sessionId, cwd) {
    const s = this.#session(sessionId, cwd);
    if (s.loaded) return s;
    if (s.loading) { await s.loading; return s; }
    const agent = this.requireAgent();
    const caps = this.agentInfo?.agentCapabilities ?? {};
    const useCwd = cwd || s.cwd || this.knownCwds.get(sessionId) || this.home;
    s.cwd = useCwd;
    s.resetForReload();
    // the load's replay announces them again
    for (const [id, c] of this.children) if (c.root === sessionId) this.children.delete(id);
    s.loading = (async () => {
      let r;
      if (caps.loadSession) r = await agent.call('session/load', this.withSessionMeta({ sessionId, cwd: useCwd, mcpServers: [] }));
      else if (caps.sessionCapabilities?.resume) r = await agent.call('session/resume', this.withSessionMeta({ sessionId, cwd: useCwd, mcpServers: [] }));
      else throw new RpcError(-32003, 'agent cannot load or resume sessions');
      if (this.agent !== agent) throw new RpcError(-32099, 'agent restarted during load');
      s.loaded = true;
      s.releasedElsewhere = false;
      if (r?.configOptions) s.configOptions = r.configOptions;
      if (r?.modes) s.modes = r.modes;
      return r;
    })();
    try { await s.loading; } finally { s.loading = null; }
    return s;
  }

  async attach(client, { sessionId, cwd, epoch, sinceSeq = 0 }) {
    if (!sessionId) throw new RpcError(-32602, 'sessionId required');
    const lk = await this.checkLock(sessionId);
    const wasLoaded = !!this.sessions.get(sessionId)?.loaded;
    const s = await this.ensureLoaded(sessionId, cwd);
    // with ownWrites the load itself may bump "touched": take the baseline after it (local clock, small slack)
    if (!wasLoaded) s.extBaseline = lk ? (lk.ownWrites ? Date.now() + 3000 : lk.now) : null;
    const firstSeq = s.events.length ? s.events[0].seq : s.seq + 1;
    // only the phone's forms leave the bridge
    const forPhone = list => list.map(e => ({ seq: e.seq, update: e.phone }));
    let reset = true, events;
    if (epoch && epoch === s.epoch && sinceSeq >= firstSeq - 1 && sinceSeq <= s.seq) {
      const missed = fitSnapshot(forPhone(s.events.filter(e => e.seq > sinceSeq)), SNAPSHOT_BUDGET, { shed: false });
      // a catch-up never leaves a hole: if some of it would not fit, the phone starts over from a snapshot
      if (!isTruncationMarker(missed[0])) { reset = false; events = missed; }
    }
    if (reset) events = fitSnapshot(compactEvents(forPhone(s.events)), SNAPSHOT_BUDGET, { trimmed: s.trimmed });
    this.#subscribe(client, s);
    s.lastActive = Date.now();
    return {
      sessionId, cwd: s.cwd, epoch: s.epoch, lastSeq: s.seq, reset, events,
      running: s.running, subagents: this.#subagentsRunning(s), lastStopReason: s.lastStopReason, turnStartedAt: this.#busy(s) ? s.turnStart || null : null,
      configOptions: s.configOptions, modes: s.modes, title: s.title, autoApprove: s.autoApprove, queue: this.queueSummary(s),
      availableCommands: s.commands ?? null,
      pending: [...this.pending.values()].filter(p => p.sessionId === sessionId).map(p => this.pendingMsg(p)),
    };
  }

  /**
   * A message from a phone. Idle: it starts a turn (after anything still queued). Mid-turn it is delivered by
   * `steer` (Claude Code's `_session/steering`: injected into the running turn) or else queued on the bridge and
   * sent, merged, when the turn ends. `interrupt: true` queues it and stops the turn, so it goes out right after.
   */
  async prompt(client, params) {
    this.requireAgent();
    await this.checkLock(params.sessionId, { forPrompt: true });
    const s = this.sessions.get(params.sessionId);
    if (s && !s.loaded && s.releasedElsewhere) throw new RpcError(-32021, 'conversation changed on another device; reload it before sending', { stale: true });
    if (!s || !s.loaded) throw new RpcError(-32004, 'attach to the session before prompting');
    const { interrupt = false, ...rest } = params;
    const blocks = Array.isArray(rest.prompt) ? rest.prompt : [];
    const item = blocks.length ? this.#queueItem(blocks, client) : null;
    if (!s.running) {
      const items = [...this.#takeQueue(s), ...(item ? [item] : [])];
      if (!items.length) throw new RpcError(-32602, 'prompt required');
      return this.#runTurn(s, items, rest);
    }
    if (interrupt) {
      if (item) s.queue.push(item);
      if (!s.queue.length) throw new RpcError(-32602, 'prompt required');
      s.flushAfterCancel = true;
      s.holdQueue = false;
      this.#broadcastQueue(s);
      this.#cancelTurn(s.sessionId);
      return { queued: true, interrupting: true, id: item?.id ?? null };
    }
    if (!item) throw new RpcError(-32602, 'prompt required');
    if (this.steers) {
      this.#echo(s, [item]);
      let r = null;
      try {
        r = await this.agent.call('_session/steering', { sessionId: s.sessionId, prompt: blocks, _meta: { steering: { idleBehavior: 'promptRequired' } } });
      } catch (e) {
        if (e.code === -32601) this.steeringBroken = true;
        this.log('steering failed, sending as a prompt: ' + e.message);
      }
      if (r?.outcome === 'injected') return { steered: true };
      // the turn ended meanwhile (or steering failed): a plain prompt; already on screen, so not echoed again
      item.echoed = true;
      return this.#runTurn(s, [item], rest);
    }
    s.queue.push(item);
    this.#broadcastQueue(s);
    return { queued: true, id: item.id };
  }

  /** Mid-turn messages are injected into the running turn when the agent supports ACP steering; otherwise queued. */
  get steers() { return !!this.agentInfo?._meta?.steering?.supported && !this.steeringBroken; }

  #queueItem(blocks, client) {
    const text = blocks.filter(b => b.type === 'text').map(b => b.text).join('\n');
    const links = blocks.filter(b => b.type === 'resource_link');
    return { id: this.id + ':q' + (this.nextKey++), blocks, text, imageCount: blocks.filter(b => b.type === 'image').length, fileCount: links.length,
      echoText: [text, ...links.map(linkText)].filter(Boolean).join('\n'), clientId: client?.id ?? null, at: Date.now() };
  }

  #takeQueue(s) {
    if (!s.queue.length) return [];
    const items = s.queue; s.queue = [];
    this.#broadcastQueue(s);
    return items;
  }

  queueSummary(s) { return s.queue.map(({ id, text, imageCount, fileCount, clientId, at }) => ({ id, text, imageCount, fileCount, clientId, at })); }

  #broadcastQueue(s) { this.broadcast({ op: 'queue', sessionId: s.sessionId, items: this.queueSummary(s) }); }

  #cancelTurn(sessionId) {
    this.#cancelPendingFor(sessionId);
    this.agent?.notify('session/cancel', { sessionId });
  }

  /** Echo user messages into the log so every device (and reconnects) see them. */
  #echo(s, items) {
    for (const it of items) {
      // the text also dedupes the agent's own echo of it (a steering fallback re-sends an echoed item)
      if (it.echoText) s.synthUserText += (s.synthUserText ? '\n' : '') + it.echoText;
      if (it.echoed) continue;
      it.echoed = true;
      for (const block of it.blocks) this.#record(s, { sessionUpdate: 'user_message_chunk', content: block, _meta: { leash: { synthetic: true, clientId: it.clientId } } });
    }
  }

  /** Send `items` (merged into one prompt) as a turn; joins the running one when a prompt is already in flight. */
  async #runTurn(s, items, params, { continued = false } = {}) {
    const agent = this.requireAgent();
    const wasRunning = s.running && !continued;
    if (!wasRunning) s.synthUserText = '';
    this.#echo(s, items);
    let prompt = items.flatMap(it => it.blocks);
    // "!" commands run since the last prompt reach the model with this one (already on screen, so not echoed again).
    if (s.shellContext?.length) {
      const ctx = s.shellContext;
      s.shellContext = [];
      s.synthUserText = ctx.join('\n') + (s.synthUserText ? '\n' + s.synthUserText : '');
      prompt = [...ctx.map(text => ({ type: 'text', text })), ...prompt];
    }
    s.inflight = (s.inflight ?? 0) + 1;
    s.running = true;
    if (!wasRunning) { s.turnStart = Date.now(); s.turnFirstOutput = 0; s.lastStopReason = null; s.endPending = false; this.#broadcastTurn(s); }
    try {
      const r = await agent.call('session/prompt', { ...params, sessionId: s.sessionId, prompt });
      s.lastStopReason = r?.stopReason ?? 'end_turn';
      if (s.inflight <= 1) this.#recordTurnStats(s, r?.usage);
      return r;
    } catch (e) {
      s.lastStopReason = 'error';
      this.#record(s, { sessionUpdate: 'notice', severity: 'error', title: 'Prompt failed', description: e.message, _meta: { leash: { synthetic: true } } });
      throw e;
    } finally {
      // Another prompt sent while running (a steering fallback) shares the same turn; only the last one finishing clears it.
      s.inflight = (s.inflight ?? 1) - 1;
      if (s.inflight <= 0) {
        s.inflight = 0;
        s.turnEndedAt = Date.now();
        if (this.lockOwnWrites) s.extBaseline = Date.now() + 3000;   // our own turn just wrote the shared status DB
        // Queued messages go out right away (running stays true, no idle blip); a plain Stop or a failure keeps them.
        const flush = s.queue.length && this.agent === agent && this.agentState === 'ready' && s.loaded
          && s.lastStopReason !== 'error' && !s.holdQueue && (s.lastStopReason !== 'cancelled' || s.flushAfterCancel);
        // background subagents still working: the turn ends with the last of them (#subagentsChanged)
        s.endPending = !flush && this.#subagentsRunning(s) > 0;
        if (!s.endPending) this.emit('turn_end', s);
        s.flushAfterCancel = false;
        s.holdQueue = false;
        if (flush) {
          this.#runTurn(s, this.#takeQueue(s), { sessionId: s.sessionId }, { continued: true })
            .catch(e => this.log('queued prompt failed: ' + e.message));
        } else {
          s.running = false;
          s.synthUserText = '';
          this.#broadcastTurn(s);
        }
      }
    }
  }

  /** Take a queued message back (to edit it, or drop it): it is returned with its content. */
  unqueue({ sessionId, id }) {
    const s = this.sessions.get(sessionId);
    const i = s ? s.queue.findIndex(q => q.id === id) : -1;
    if (i < 0) throw new RpcError(-32024, 'message already sent');
    const [it] = s.queue.splice(i, 1);
    this.#broadcastQueue(s);
    return { id: it.id, prompt: it.blocks };
  }

  /**
   * Undo of a dropped queued message: the `prompt` that `unqueue` returned goes back in at `index` (clamped),
   * under its old `id` while no other message has it. It waits like any queued message (a held queue stays held).
   */
  requeue(client, { sessionId, id, prompt, index }) {
    const s = this.sessions.get(sessionId);
    if (!s) throw new RpcError(-32004, 'attach to the session before queueing');
    const blocks = Array.isArray(prompt) ? prompt : [];
    if (!blocks.length) throw new RpcError(-32602, 'prompt required');
    const item = this.#queueItem(blocks, client);
    if (typeof id === 'string' && id && !s.queue.some(q => q.id === id)) item.id = id;
    const at = Number.isInteger(index) ? Math.min(Math.max(index, 0), s.queue.length) : s.queue.length;
    s.queue.splice(at, 0, item);
    this.#broadcastQueue(s);
    return { id: item.id, index: at };
  }

  /**
   * "!" shell mode from the phone: run one command in the conversation's folder on the agent's host and log it in
   * Claude Code's own transcript format. Like the CLI, the model sees it with the next prompt.
   */
  async shell(client, { sessionId, command }) {
    const cmd = String(command ?? '').trim();
    if (!cmd) throw new RpcError(-32602, 'command required');
    const s = this.sessions.get(sessionId);
    if (!s || !s.loaded) throw new RpcError(-32004, 'attach to the session before running commands');
    if (s.running) throw new RpcError(-32022, 'the agent is working; run shell commands between turns');
    if (s.shellRunning) throw new RpcError(-32023, 'a shell command is already running in this conversation');
    const n = this.nextKey++;
    const meta = { leash: { synthetic: true, shell: true, clientId: client?.id ?? null } };
    const input = `<bash-input>${escapeTag(cmd)}</bash-input>`;
    this.#record(s, { sessionUpdate: 'user_message_chunk', messageId: `leash-sh${n}-in`, content: { type: 'text', text: input }, _meta: meta });
    s.shellRunning = true;
    this.broadcast({ op: 'shell', sessionId: s.sessionId, running: true });
    let r;
    try { r = await this.runHelper(['exec', s.cwd || this.home, cmd], 140000, this.helper ?? LOCAL_HELPER); }
    catch (e) { r = { stdout: '', stderr: e.message, exitCode: 1 }; }
    finally { s.shellRunning = false; }
    const t = shellTranscript(cmd, r);
    this.#record(s, { sessionUpdate: 'user_message_chunk', messageId: `leash-sh${n}-out`, content: { type: 'text', text: t.output }, _meta: meta });
    s.shellContext = [...(s.shellContext ?? []), t.input, t.output];
    this.broadcast({ op: 'shell', sessionId: s.sessionId, running: false });
    return { exitCode: r.exitCode ?? null, timedOut: !!r.timedOut, truncated: !!r.truncated };
  }

  /**
   * /btw: a side question about a conversation, answered from its context by a throwaway fork on the agent's host
   * (helper.mjs btw). Allowed mid-turn; nothing is recorded in the session or sent to other devices, and the
   * conversation itself is never touched, so its turn, queue and lock state stay as they are.
   */
  async btw({ sessionId, question }) {
    if (!this.sideQuestions || !this.helper) throw new RpcError(-32601, this.label + ' has no side questions');
    const q = String(question ?? '').trim();
    if (!q) throw new RpcError(-32602, 'question required');
    const s = this.sessions.get(sessionId);
    const cwd = s?.cwd ?? this.knownCwds.get(sessionId) ?? null;
    if (!s && !cwd) throw new RpcError(-32004, 'unknown session');
    const model = s?.configOptions?.find(o => o.category === 'model' || o.id === 'model')?.currentValue ?? null;
    const env = this.sessionMeta?.claudeCode?.options?.settings?.env ?? null;
    const r = await this.runHelper(['btw'], 170000, this.helper, JSON.stringify({ sessionId, cwd, question: q, model, env }));
    return { text: String(r.text ?? ''), durationMs: r.durationMs ?? null, model: r.model ?? null };
  }

  /**
   * Claude Code's /resume list on the computer leaves out conversations started through the Agent SDK (all of
   * Leash's). The helper reports whether one is listed and relabels it so it is; nothing else about it changes.
   */
  async cliVisibility({ sessionId }) {
    if (!this.cliList) throw new RpcError(-32601, this.label + ' has no Claude Code /resume list');
    return this.runHelper(['cli-visibility', String(sessionId ?? '')], 20000);
  }

  async showInCli({ sessionId }) {
    if (!this.cliList) throw new RpcError(-32601, this.label + ' has no Claude Code /resume list');
    return this.runHelper(['show-in-cli', String(sessionId ?? '')], 20000);
  }

  #recordTurnStats(s, usage) {
    const end = Date.now();
    const out = (usage?.outputTokens ?? 0) + (usage?.thoughtTokens ?? 0);
    const genMs = s.turnFirstOutput ? end - s.turnFirstOutput : 0;
    const model = s.configOptions?.find(o => o.category === 'model' || o.id === 'model');
    const effort = s.configOptions?.find(o => o.category === 'thought_level' || o.id === 'thought_level');
    const name = (opt) => { if (!opt) return null; const v = opt.currentValue; const flat = (opt.options ?? []).flatMap(o => o.options ?? [o]); return flat.find(o => o.value === v)?.name ?? v; };
    this.#record(s, {
      sessionUpdate: 'leash_turn_stats',
      usage: usage ?? null,
      durationMs: s.turnStart ? end - s.turnStart : null,
      ttftMs: s.turnFirstOutput && s.turnStart ? s.turnFirstOutput - s.turnStart : null,
      tokensPerSecond: out && genMs > 250 ? Math.round((out / (genMs / 1000)) * 10) / 10 : null,
      model: name(model), effort: name(effort),
      stopReason: s.lastStopReason,
    });
  }

  // ---------------------------------------------------------------- filesystem helpers (read-only, under $HOME)
  #safePath(p, cwd = '') {
    const abs = safePath(p, { cwd, home: this.home });
    if (!abs) throw new RpcError(-32005, 'path outside home directory');
    return abs;
  }

  /**
   * A file attached on the phone: stored on the agent's host in ~/.leash/uploads/<sessionId>/ (helper.mjs upload), so
   * the prompt can carry it as a `resource_link` the agent opens with its own tools — any type, any agent.
   */
  async upload({ sessionId, name, data }) {
    if (typeof data !== 'string' || !data) throw new RpcError(-32602, 'data (base64) required');
    const bytes = Buffer.byteLength(data, 'base64');
    if (bytes > MAX_UPLOAD) throw new RpcError(-32007, `file too large (${(bytes / 1048576).toFixed(1)} MB; limit ${MAX_UPLOAD / 1048576} MB)`);
    const r = await this.runHelper(['upload', sessionId || 'misc', String(name || 'file'), String(Buffer.from(data, 'base64').length)], 180000, this.helper ?? LOCAL_HELPER, data);
    return { ...r, uri: fileUri(r.path) };
  }

  ownsSession(id) { return this.sessions.has(id) || this.knownCwds.has(id); }

  async browse(p) {
    if (this.helper) return this.runHelper(['browse', ...(p ? [p] : [])]);
    const abs = this.#safePath(p);
    const ents = await fs.readdir(abs, { withFileTypes: true });
    const dirs = [], files = [];
    for (const e of ents) {
      if (e.name.startsWith('.')) continue;
      if (e.isDirectory()) dirs.push(e.name); else if (e.isFile()) files.push(e.name);
    }
    dirs.sort((a, b) => a.localeCompare(b)); files.sort((a, b) => a.localeCompare(b));
    let isGit = false;
    try { await fs.access(path.join(abs, '.git')); isGit = true; } catch {}
    return { path: abs, parent: isSamePath(abs, this.home) ? null : path.dirname(abs), isGit, dirs, files: files.slice(0, 500) };
  }

  /** The folder a relative path in this conversation is taken from (markdown images and links like `out/a.png`). */
  #cwdFor(sessionId) { return (sessionId && (this.sessions.get(sessionId)?.cwd || this.knownCwds.get(sessionId))) || ''; }

  /**
   * Read a file for the phone. Text mode (default) returns at most maxBytes of text; `mode: "base64"` returns the
   * whole file as `data` (downloads, inline images, QuickLook) or fails with -32007 past the 32 MB cap.
   */
  async readFile(p, maxBytes = 512 * 1024, { mode = 'text', sessionId = null } = {}) {
    const cwd = this.#cwdFor(sessionId);
    if (mode === 'base64') return this.runHelper(['read', p, String(maxBytes ?? 0), 'base64', cwd], 120000, this.helper ?? LOCAL_HELPER);
    if (this.helper) return this.runHelper(['read', p, String(maxBytes), 'text', cwd]);
    const abs = this.#safePath(p, cwd);
    const st = await fs.stat(abs);
    if (!st.isFile()) throw new RpcError(-32006, 'not a file');
    const fh = await fs.open(abs, 'r');
    try {
      const n = Math.min(st.size, Math.max(1, Math.min(maxBytes, 4 * 1024 * 1024)));
      const buf = Buffer.alloc(n);
      await fh.read(buf, 0, n, 0);
      const binary = buf.subarray(0, 8000).includes(0);
      return { path: abs, size: st.size, truncated: st.size > n, binary, text: binary ? null : buf.toString('utf8') };
    } finally { await fh.close(); }
  }
}
