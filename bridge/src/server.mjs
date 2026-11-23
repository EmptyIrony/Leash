// HTTP + WebSocket front door for the phone app.
import http from 'node:http';
import crypto from 'node:crypto';
import { WebSocketServer } from 'ws';
import { RpcError } from './acp.mjs';

function tokenOk(given, expected) {
  if (!given || !expected) return false;
  const a = Buffer.from(String(given)); const b = Buffer.from(String(expected));
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

export function createServer({ hub, token, log = console.log }) {
  const wss = new WebSocketServer({ noServer: true, maxPayload: 48 * 1024 * 1024, perMessageDeflate: { threshold: 4096 } });
  const servers = new Map(); // host -> http.Server

  const onRequest = (req, res) => {
    const url = new URL(req.url, 'http://x');
    if (url.pathname === '/health') {
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ ok: true, name: 'leash-bridge', agent: hub.agentState, agents: hub.hubs ? [...hub.hubs.values()].map(h => ({ id: h.id, state: h.agentState })) : undefined }));
      return;
    }
    res.writeHead(404); res.end();
  };

  const onUpgrade = (req, socket, head) => {
    const url = new URL(req.url, 'http://x');
    const auth = req.headers['authorization'] || '';
    const given = auth.startsWith('Bearer ') ? auth.slice(7) : url.searchParams.get('token');
    if (url.pathname !== '/ws' || !tokenOk(given, token)) {
      log(`rejected upgrade from ${req.socket.remoteAddress} path=${url.pathname}`);
      socket.write('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n');
      socket.destroy();
      return;
    }
    wss.handleUpgrade(req, socket, head, ws => wss.emit('connection', ws, req));
  };

  wss.on('connection', (ws, req) => {
    const remote = req.socket.remoteAddress;
    const name = req.headers['x-leash-client'] || 'client';
    const send = msg => { if (ws.readyState === ws.OPEN) ws.send(JSON.stringify(msg)); };
    const client = hub.addClient(send, { remote, name });
    log(`client #${client.id} connected from ${remote} (${name})`);
    let alive = true;
    ws.on('pong', () => { alive = true; });
    const hb = setInterval(() => {
      if (!alive) { ws.terminate(); return; }
      alive = false; try { ws.ping(); } catch {}
    }, 25000);

    ws.on('message', async data => {
      let m;
      try { m = JSON.parse(String(data)); } catch { send({ op: 'error', message: 'invalid json' }); return; }
      if (m.op === 'ping') { send({ op: 'pong', t: m.t ?? null }); return; }
      if (m.op === 'reply') {
        try { hub.reply(client, m.key, m.result, m.error); send({ op: 'result', id: m.id ?? null, result: { ok: true } }); }
        catch (e) { send({ op: 'result', id: m.id ?? null, error: errJson(e) }); }
        return;
      }
      if (m.op === 'call') {
        try {
          const result = await hub.handleCall(client, String(m.method || ''), m.params ?? {});
          send({ op: 'result', id: m.id, result: result ?? null });
        } catch (e) {
          send({ op: 'result', id: m.id, error: errJson(e) });
        }
        return;
      }
      send({ op: 'error', message: 'unknown op ' + m.op });
    });
    ws.on('close', () => { clearInterval(hb); hub.removeClient(client); log(`client #${client.id} disconnected`); });
    ws.on('error', e => log(`client #${client.id} error: ${e.message}`));
  });

  function listen(host, port) {
    if (servers.has(host)) return Promise.resolve(servers.get(host));
    return new Promise((resolve, reject) => {
      const srv = http.createServer(onRequest);
      srv.on('upgrade', onUpgrade);
      srv.once('error', reject);
      srv.listen(port, host, () => {
        srv.removeListener('error', reject);
        srv.on('error', e => log(`server ${host} error: ${e.message}`));
        servers.set(host, srv);
        log(`listening on ${host}:${srv.address().port}`);
        resolve(srv);
      });
    });
  }

  function close() {
    for (const c of wss.clients) c.terminate();
    for (const s of servers.values()) s.close();
    servers.clear();
  }

  return { listen, close, servers, wss };
}

function errJson(e) {
  if (e instanceof RpcError) return e.toJSON();
  if (e && typeof e.code === 'number') return { code: e.code, message: e.message };
  return { code: -32603, message: e?.message || String(e) };
}
