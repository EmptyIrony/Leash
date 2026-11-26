// Minimal ACP (Agent Client Protocol) JSON-RPC client over the stdio of a child process.
import { spawn } from 'node:child_process';
import { EventEmitter } from 'node:events';
import { spawnSpec, killTree } from './platform.mjs';

export class RpcError extends Error {
  constructor(code, message, data) { super(message); this.code = code; this.data = data; }
  toJSON() { return { code: this.code, message: this.message, ...(this.data !== undefined ? { data: this.data } : {}) }; }
}

/**
 * Events:
 *  'notification' (method, params)
 *  'request' (id, method, params)   -- agent -> client request; answer with respond()/respondError()
 *  'exit' (code, signal)
 *  'stderr' (text)
 */
export class AcpProcess extends EventEmitter {
  constructor({ command, args = [], cwd, env, log = () => {} }) {
    super();
    this.command = command; this.args = args; this.cwd = cwd; this.env = env; this.log = log;
    this.child = null; this.nextId = 1; this.pending = new Map(); this.buf = '';
    this.alive = false;
  }

  start() {
    this.alive = true;
    let spec;
    // Windows .cmd shims go through cmd.exe (platform.mjs); an argument it cannot carry safely fails the start, not the bridge
    try { spec = spawnSpec(this.command, this.args, { env: this.env ?? process.env }); } catch (e) { this.log('agent spawn error: ' + e.message); this.#onExit(null, null, e); return; }
    this.child = spawn(spec.command, spec.args, { cwd: this.cwd, env: this.env, stdio: ['pipe', 'pipe', 'pipe'], ...spec.options });
    this.child.stdout.setEncoding('utf8');
    this.child.stdout.on('data', d => this.#onData(d));
    this.child.stderr.setEncoding('utf8');
    this.child.stderr.on('data', d => this.emit('stderr', d));
    this.child.stdin.on('error', e => this.log('agent stdin error: ' + e.message));
    this.child.on('error', e => { this.log('agent spawn error: ' + e.message); this.#onExit(null, null, e); });
    this.child.on('exit', (code, signal) => this.#onExit(code, signal));
  }

  #onExit(code, signal, err) {
    if (!this.alive) return;
    this.alive = false;
    for (const [, p] of this.pending) p.reject(new RpcError(-32099, 'agent process exited' + (err ? ': ' + err.message : '')));
    this.pending.clear();
    this.emit('exit', code, signal, err);
  }

  stop() { if (this.child && this.alive) killTree(this.child); }

  #write(msg) {
    if (!this.alive) throw new RpcError(-32099, 'agent process not running');
    this.child.stdin.write(JSON.stringify(msg) + '\n');
  }

  call(method, params, { timeoutMs = 0 } = {}) {
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      let timer = null;
      if (timeoutMs) timer = setTimeout(() => { this.pending.delete(id); reject(new RpcError(-32098, 'timeout calling ' + method)); }, timeoutMs);
      this.pending.set(id, {
        resolve: v => { if (timer) clearTimeout(timer); resolve(v); },
        reject: e => { if (timer) clearTimeout(timer); reject(e); },
      });
      try { this.#write({ jsonrpc: '2.0', id, method, params }); }
      catch (e) { this.pending.delete(id); if (timer) clearTimeout(timer); reject(e); }
    });
  }

  notify(method, params) { this.#write({ jsonrpc: '2.0', method, params }); }
  respond(id, result) { this.#write({ jsonrpc: '2.0', id, result: result ?? null }); }
  respondError(id, error) { this.#write({ jsonrpc: '2.0', id, error }); }

  #onData(d) {
    this.buf += d;
    let i;
    while ((i = this.buf.indexOf('\n')) >= 0) {
      const line = this.buf.slice(0, i).trim();
      this.buf = this.buf.slice(i + 1);
      if (!line) continue;
      let m;
      try { m = JSON.parse(line); } catch { this.emit('stderr', '[non-json stdout] ' + line + '\n'); continue; }
      this.#dispatch(m);
    }
  }

  #dispatch(m) {
    if (m.method && m.id !== undefined && m.id !== null) { this.emit('request', m.id, m.method, m.params); return; }
    if (m.method) { this.emit('notification', m.method, m.params); return; }
    const p = this.pending.get(m.id);
    if (!p) return;
    this.pending.delete(m.id);
    if (m.error) p.reject(new RpcError(m.error.code, m.error.message, m.error.data));
    else p.resolve(m.result);
  }
}
