// A fake `agy acp` that speaks enough ACP v1 to exercise the bridge.
import readline from 'node:readline';

const sessions = new Map(); // id -> {cwd, history: [update]}
let n = 0; let pid = 1000;
const pendingClient = new Map();
const out = m => process.stdout.write(JSON.stringify(m) + '\n');
const update = (sessionId, u) => { out({ jsonrpc: '2.0', method: 'session/update', params: { sessionId, update: u } }); sessions.get(sessionId)?.history.push(u); };
const ask = (method, params) => new Promise(res => { const id = pid++; pendingClient.set(id, res); out({ jsonrpc: '2.0', id, method, params }); });
const sleep = ms => new Promise(r => setTimeout(r, ms));
const configOptions = (model = 'gemini-4-hydrogen') => [
  { id: 'model', name: 'Model', category: 'model', type: 'select', currentValue: model, options: [{ value: 'gemini-4-hydrogen', name: 'Gemini 4 Hydrogen' }, { value: 'gemini-4-carbon', name: 'Gemini 4 Carbon' }] },
  { id: 'thought_level', name: 'Thinking', category: 'thought_level', type: 'select', currentValue: 'medium', options: [{ value: 'low', name: 'Low' }, { value: 'medium', name: 'Medium' }, { value: 'high', name: 'High' }] },
];
const cancelled = new Set();
// MOCK_STEERING=1: advertise ACP steering (like Claude Code's adapter). Without it, like agy, a second
// session/prompt while one runs is refused, so the bridge has to queue.
const STEERING = process.env.MOCK_STEERING === '1';
const busy = new Set();
const steered = new Map(); // sessionId -> [text]

// a pre-existing "desktop" conversation
sessions.set('photo-1', { cwd: '/tmp/proj', title: 'Copy selection', cwdPastHead: true, updatedAt: '2026-11-23T01:00:00Z', history: [] });
sessions.set('old-1', { cwd: '/tmp/proj', title: 'Old chat', history: [
  { sessionUpdate: 'user_message_chunk', content: { type: 'text', text: 'hello from the desktop' } },
  { sessionUpdate: 'agent_message_chunk', content: { type: 'text', text: 'Hi! ' } },
  { sessionUpdate: 'agent_message_chunk', content: { type: 'text', text: 'How can I help?' } },
] });

async function handle(m) {
  const r = (result) => out({ jsonrpc: '2.0', id: m.id, result });
  const e = (code, message) => out({ jsonrpc: '2.0', id: m.id, error: { code, message } });
  const p = m.params || {};
  switch (m.method) {
    case 'initialize': return r({ protocolVersion: 1, agentInfo: { name: 'mock-agy', version: '0.0.1' }, agentCapabilities: { loadSession: true, promptCapabilities: { image: true }, sessionCapabilities: { list: {}, close: {} } }, authMethods: [], ...(STEERING ? { _meta: { steering: { supported: true } } } : {}) });
    case '_session/steering': {
      if (!STEERING) return e(-32601, 'method not found ' + m.method);
      const text = p.prompt?.find(b => b.type === 'text')?.text ?? '';
      if (!busy.has(p.sessionId) || text.includes('late')) return r({ outcome: 'promptRequired', reason: 'noRunningTurn' });
      steered.get(p.sessionId)?.push(text);
      return r({ outcome: 'injected' });
    }
    case 'session/new': { const id = 's' + (++n); sessions.set(id, { cwd: p.cwd, history: [] }); return r({ sessionId: id, configOptions: configOptions() }); }
    // like Claude Code's SDK: a session whose cwd sits past the head of its transcript (first prompt with a photo)
    // is left out of an unscoped list, but a list scoped to its directory finds it
    case 'session/list': return r({ sessions: [...sessions].filter(([, s]) => p.cwd == null ? !s.cwdPastHead : s.cwd === p.cwd)
      .map(([id, s]) => ({ sessionId: id, cwd: s.cwd, title: s.title ?? null, updatedAt: s.updatedAt ?? '2026-11-23T00:00:00Z' })) });
    case 'session/load': {
      const s = sessions.get(p.sessionId); if (!s) return e(-32602, 'no such session');
      const hist = s.history.slice(); s.history = [];
      for (const u of hist) update(p.sessionId, u);
      return r({ configOptions: configOptions() });
    }
    case 'session/set_config_option': return r({ configOptions: configOptions(p.value) });
    case 'session/close': return r({});
    case 'session/prompt': {
      if (busy.has(p.sessionId) && !STEERING) return e(-32000, 'a prompt is already running in this session');
      busy.add(p.sessionId);
      try { return await prompt(m, p, r); } finally { busy.delete(p.sessionId); }
    }
    default: return e(-32601, 'method not found ' + m.method);
  }
}

async function prompt(m, p, r) {
      const sid = p.sessionId; const text = p.prompt?.filter(b => b.type === 'text').map(b => b.text).join(' | ') ?? '';
      if (!steered.has(sid)) steered.set(sid, []);
      sessions.get(sid).history.push({ sessionUpdate: 'user_message_chunk', content: { type: 'text', text } });
      cancelled.delete(sid);
      if (text.includes('commands')) update(sid, { sessionUpdate: 'available_commands_update', availableCommands: [{ name: 'review', description: 'Review a PR', input: { hint: 'pr-number' } }] });
      update(sid, { sessionUpdate: 'agent_thought_chunk', content: { type: 'text', text: 'Thinking about it' } });
      for (const w of ['Sure', ', ', 'editing ', 'the file.']) { update(sid, { sessionUpdate: 'agent_message_chunk', content: { type: 'text', text: w } }); await sleep(5); }
      if (text.includes('edit')) {
        update(sid, { sessionUpdate: 'tool_call', toolCallId: 't1', title: 'Edit README.md', kind: 'edit', status: 'pending', locations: [{ path: '/tmp/proj/README.md' }], content: [{ type: 'diff', path: '/tmp/proj/README.md', oldText: 'a\nb\n', newText: 'a\nc\n' }] });
        const res = await ask('session/request_permission', { sessionId: sid, toolCall: { toolCallId: 't1', title: 'Edit README.md' }, options: [
          { optionId: 'allow', name: 'Allow', kind: 'allow_once' }, { optionId: 'always', name: 'Always allow', kind: 'allow_always' }, { optionId: 'deny', name: 'Deny', kind: 'reject_once' }] });
        if (res?.outcome?.outcome === 'cancelled' || cancelled.has(sid)) return r({ stopReason: 'cancelled' });
        const ok = res?.outcome?.optionId !== 'deny';
        update(sid, { sessionUpdate: 'tool_call_update', toolCallId: 't1', status: ok ? 'completed' : 'failed' });
        update(sid, { sessionUpdate: 'agent_message_chunk', content: { type: 'text', text: ok ? ' Done.' : ' Skipped.' } });
      }
      if (text.includes('delegate')) {
        // like claude-agent-acp's subagents RFD form: a child session announced on the parent, its own updates and
        // permission requests on the child's id, a grandchild announced on the child
        const c = 'child-' + sid, g = 'grand-' + sid;
        update(sid, { sessionUpdate: 'subagent_update', sessionId: c, title: 'Explore the repo', description: 'Explore', state: { state: 'running' } });
        update(c, { sessionUpdate: 'session_message', messageId: 'm-in', senderSessionId: sid, recipientSessionId: c, content: [{ type: 'text', text: 'look around' }] });
        update(c, { sessionUpdate: 'config_option_update', configOptions: [{ id: 'model', name: 'Model', category: 'model', type: 'select', currentValue: 'fable', options: [{ value: 'fable', name: 'Fable 5.2' }] }] });
        update(c, { sessionUpdate: 'available_commands_update', availableCommands: [] });
        update(c, { sessionUpdate: 'agent_message_chunk', content: { type: 'text', text: 'child looks' } });
        update(c, { sessionUpdate: 'tool_call', toolCallId: 'ct1', title: 'Edit a.txt', kind: 'edit', status: 'pending', _meta: { claudeCode: { toolName: 'Edit', parentToolUseId: 'launch-1' } } });
        const res = await ask('session/request_permission', { sessionId: c, toolCall: { toolCallId: 'ct1', title: 'Edit a.txt' }, options: [{ optionId: 'allow', name: 'Allow', kind: 'allow_once' }, { optionId: 'deny', name: 'Deny', kind: 'reject_once' }] });
        update(c, { sessionUpdate: 'tool_call_update', toolCallId: 'ct1', status: res?.outcome?.optionId === 'allow' ? 'completed' : 'failed' });
        update(c, { sessionUpdate: 'subagent_update', sessionId: g, title: 'Nested', state: { state: 'running' } });
        update(g, { sessionUpdate: 'agent_message_chunk', content: { type: 'text', text: 'grandchild' } });
        update(g, { sessionUpdate: 'session_info_update', title: 'not the root title' });
        update(c, { sessionUpdate: 'subagent_update', sessionId: g, state: { state: 'idle', stopReason: 'end_turn' } });
        update(c, { sessionUpdate: 'agent_message_chunk', messageId: 'shared', content: { type: 'text', text: 'child final' } });
        update(sid, { sessionUpdate: 'agent_message_chunk', messageId: 'shared', content: { type: 'text', text: 'parent done' } });
        // "bg": a background subagent that is still working when the parent's prompt returns
        if (!text.includes('bg')) update(sid, { sessionUpdate: 'subagent_update', sessionId: c, state: { state: 'idle', stopReason: 'end_turn' } });
        // "bgend": that background subagent finishes a moment after the prompt returned
        if (text.includes('bgend')) setTimeout(() => update(sid, { sessionUpdate: 'subagent_update', sessionId: c, state: { state: 'idle', stopReason: 'end_turn' } }), 300);
      }
      if (text.includes('stubborn')) await sleep(300);   // ignores cancel and ends normally
      if (text.includes('slow')) { for (let i = 0; i < 40 && !cancelled.has(sid); i++) await sleep(25); if (cancelled.has(sid)) return r({ stopReason: 'cancelled' }); }
      for (const s of steered.get(sid).splice(0)) update(sid, { sessionUpdate: 'agent_message_chunk', content: { type: 'text', text: ' steered: ' + s } });
      update(sid, { sessionUpdate: 'session_info_update', title: 'Chat: ' + text.slice(0, 20), _meta: { mockPrompt: text } });
      return r({ stopReason: 'end_turn', usage: { inputTokens: 1000, outputTokens: 40, thoughtTokens: 10, totalTokens: 1050 } });
}

readline.createInterface({ input: process.stdin }).on('line', line => {
  if (!line.trim()) return;
  const m = JSON.parse(line);
  if (m.method === 'session/cancel') { cancelled.add(m.params.sessionId); return; }
  if (m.method && m.id != null) { handle(m); return; }
  if (!m.method && pendingClient.has(m.id)) { const f = pendingClient.get(m.id); pendingClient.delete(m.id); f(m.result); }
});
