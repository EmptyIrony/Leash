# Leash (拴绳) — your coding agents on a leash, from your iPhone

A personal native iOS client for coding agents (Antigravity, Claude Code), in the spirit of Claude Code Remote / Codex remote.
The phone talks to a small **bridge** on your Mac; the bridge drives the official `agy acp`
(Agent Client Protocol over stdio). Everything still runs through `agy` on the Mac with your own login;
no OAuth tokens are copied anywhere.

```
iPhone (SwiftUI)  ──WebSocket + token, over Tailscale──▶  bridge (Node, on the Mac)  ──stdio JSON-RPC──▶  agy acp
```

## Layout

| Path | What |
|---|---|
| `bridge/` | Node bridge: spawns `agy acp`, caches per-session transcripts, fans out to devices, relays permission prompts |
| `ios/` | Xcode project (SwiftUI, iOS 17+) |
| `tools/probe-mac.sh` | One-off probe of the Mac's `agy` / ACP capabilities, writes `.probe/` |

## Mac setup

```bash
cd ~/workspace/Leash/bridge
npm install
node src/index.mjs            # foreground; prints a pairing QR code
# or keep it running in the background (login item, auto-restart):
bash scripts/install-launchagent.sh
node src/index.mjs pair       # show the QR again
node src/index.mjs rotate     # new token (re-pair the phone)
```

Config lives in `~/.leash/config.json` (`port`, `token`, `bind`, `agent.command`/`args`).
`bind: "auto"` listens on 127.0.0.1 plus the Mac's Tailscale address(es); if Tailscale is not up it falls back to `0.0.0.0` (LAN, dev phase) and the pairing QR uses the Mac's LAN IP. Set `bind` to `"127.0.0.1,100.x.y.z"` to pin it.
Log of the LaunchAgent: `~/Library/Logs/leash-bridge.log`.

### Notifications (Bark)

Leash has no APNs, so the bridge can alert the phone (and a paired Apple Watch) through the free
[Bark](https://github.com/Finb/Bark) app when an agent asks for approval / a question, or a turn finishes / fails:

```
node src/index.mjs bark https://api.day.app/<key>/   # the URL Bark shows (or just <key>); sends a test push
node src/index.mjs bark                              # test again;  `bark off` removes it
```

Restart the bridge afterwards. Pushes go out only while no Leash client is on screen (the app pings every 15 s;
~25 s of silence counts as away; a request answered within that grace period is not pushed). A Stop you pressed
yourself is not pushed. Tapping a notification opens that conversation (`leash://session?id=…&agent=…`) when titles may be
pushed (`detail`, on with encryption), otherwise just Leash; a `url` in the `bark` block overrides both. A request the phone
already alerted about itself (a Live Activity alert, `bridge/noticed`) is not pushed again. Options in the `bark` block of `~/.leash/config.json`:
`server` (self-hosted bark-server, default `https://api.day.app`), `when: "always"`, `attention` / `turnEnd: false`,
`sound`, `lang: "en"`, and `detail` (conversation and tool titles in the text).

**Encryption** (recommended): `node src/index.mjs bark encrypt` generates a 32-character key (or pass your own
16/24/32-character one) and stores it as `bark.encrypt`. Enter it in Bark → 推送加密 / Push Encryption with AES256, CBC,
pkcs7 and any 16-character IV. Every push is then AES-CBC encrypted with a fresh IV, so Bark's server and APNs see only
the device key and ciphertext, and `detail` defaults to on. Without encryption `detail` defaults to off and a push only
says which agent needs you and how long the turn took. An invalid key turns notifications off rather than sending
plaintext; "Decryption Failed" on the phone means the key in Bark does not match.

## How it talks to Antigravity (all official agy surfaces)

* **Conversations**: `agy acp --app-data-dir antigravity` — ACP over stdio, pointed at the desktop app's data dir so
  desktop conversations replay their full history (plain `agy acp` only replays CLI-made ones).
* **Quota (5-hour / weekly)**: only when the app asks (`bridge/quota`: on connect, back in the foreground, opening a chat, the quota sheet's refresh;
  answers younger than 2 min are reused) the bridge starts a hidden `agy` TUI under `script` with a session-only
  `--settings '{"statusLine":…}'`; agy pipes its status JSON (incl. `quota`) to a sink script, the bridge keeps the
  quota part (never the email) and kills the TUI. No prompt is sent, and nothing is fetched on a timer or after turns.
  Disable with `"quota": {"enabled": false}` in `~/.leash/config.json`.
* **Turbo**: bridge-side per-conversation auto-approval of permission prompts (+ agy's `auto` mode).
* agy's tool calls use `name`/`args` instead of ACP's `title`/`rawInput`; the bridge normalizes them.
* **Inline widgets (generative UI)**: agy writes an HTML file into `~/.gemini/antigravity/brain/<id>/` and puts
  `<agent-embed src="file:///…/x.html"></agent-embed>` in its reply. The app reads it with `bridge/readFile` and runs it in a
  sandboxed WKWebView (`Views/AgentEmbedView.swift`) that copies agy's host contract: theme tokens, `html.light/.dark` +
  `themechange`, CSP allowing only the gstatic Tailwind script, height from content (max 800pt inline; tap ⤢ for full screen).
  Preview the wrapper in Chromium: `node tools/embed-preview.mjs <widget.html> out.html dark`.

## Claude Code (second agent, on another host)

The bridge can drive several ACP agents at once (`agents` in `~/.leash/config.json`). Claude Code runs on the
Ubuntu box and is reached from the Mac through the `ubuntu` zsh function (ssh):

```
Mac bridge ──zsh -c 'source ~/.zshrc; ubuntu …/bridge/remote/run-acp.sh'──▶ claude-agent-acp ──▶ official claude CLI
```

* Host setup (once, on the Ubuntu box): `mkdir -p ~/.leash/claude-acp && cd ~/.leash/claude-acp && npm i @agentclientprotocol/claude-agent-acp`
* Mac: `node src/index.mjs add-claude` (or `add-claude --fn <zsh-function>` / `add-claude ssh -T user@host`), then restart the bridge.
* `bridge/remote/helper.mjs` runs on the host for folder browsing, file reads/uploads and quota (`claude -p /usage`: a local command, 0 tokens).
* Sessions started in Claude Code's Code Mode are resumed by passing `settings.env.CLAUDE_CODE_EXPERIMENTAL_CODE_MODE=1` through `_meta.claudeCode.options`.
* The adapter uses your own Claude login on that machine (`ANTHROPIC_API_KEY` is unset in `run-acp.sh`).
* **Busy-elsewhere lock**: a conversation that Claude Code is *running a turn in* (terminal or Claude desktop app;
  session-file `status` other than `idle`) cannot be opened or prompted from the phone (error `-32020`, lock icon in
  the list); merely having it open there does not lock it. `helper.mjs locks` reads Claude Code's
  `~/.claude/sessions/<pid>.json` (undocumented; a holder counts only if its pid is alive with the same start time in
  the helper's pid namespace; `sdk-*` entries are the bridge's own). If another client used a conversation since the
  bridge loaded it, the bridge drops its copy and a prompt gets `-32021`; the app reloads and resends. The check fails
  open on errors. Disable per agent with `"lock": false`.

**Antigravity busy lock** (`bridge/src/agylock.mjs`, local to the Mac): a conversation is refused on the phone while the
desktop app is *running a turn* in it. Signals (found with `tools/probe-agy*-mac.sh`): the desktop `language_server`
holds `~/.gemini/antigravity/presence/<id>.lock` open for every conversation it has loaded (until the app quits), and
`conversation_summaries.db` has `status = CASCADE_RUN_STATUS_RUNNING` / `not_fully_idle = 1` for the whole turn.
Held = presence lock by a process outside our own `agy acp` tree **and** busy. Read-only: `lsof +d`, `ps`, and `sqlite3`
(falls back to a temp copy when the live DB can't be opened read-only). When the turn ends the bridge pushes
`session_unlocked` and the app reloads the conversation.

## iPhone

Open `ios/Leash.xcodeproj`, pick your signing team, run on the device, then Settings → *Scan pairing QR code*.

## Bridge protocol (v1)

JSON text frames on `ws://<mac>:8787/ws` with `Authorization: Bearer <token>`.

* phone → bridge: `{op:"call", id, method, params}` (ACP methods like `session/new|list|prompt|cancel|set_config_option`,
  plus `bridge/attach|detach|browse|readFile|restartAgent|shell|unqueue`), `{op:"reply", key, result}` to answer a permission/elicitation.
* bridge → phone: `ready`, `result`, `update` (`session/update` with `epoch`+`seq`), `request`/`resolved`
  (permission & questions, broadcast to every device), `turn` (running/idle), `shell` (a "!" command running/done), `agent` (agy process state),
  `queue` (messages waiting for the running turn to end).
* `session/prompt` mid-turn: with an agent that advertises ACP steering (`initialize` `_meta.steering.supported`, Claude Code) the
  bridge injects the message into the running turn via `_session/steering` (`{steered:true}`); otherwise (agy) it is queued on the
  bridge (`{queued:true, id}`) and sent, merged into one prompt, the moment the turn ends — `running` stays true in between.
  `interrupt:true` queues it and cancels the turn, so it goes out right after (an empty `prompt` sends just the queue). A plain
  `session/cancel` keeps the queue; it goes out ahead of the next prompt, or `bridge/unqueue {sessionId, id}` takes one back
  (returns its `prompt` blocks). `bridge/attach` returns the `queue`; agent summaries carry `steering`.
* `bridge/attach {sessionId, epoch, sinceSeq}` returns only missed events after a reconnect, or a compacted snapshot.
* `bridge/readFile {path, sessionId?, mode?, maxBytes?}`: text (default, first 512 KB) or `mode: "base64"` (whole file as
  `data`, ≤ 32 MB, else `-32007`); a relative path is taken from the conversation's folder. Reads stay under `$HOME`.
* `bridge/noticed {key}`: the phone alerted about this pending request itself (Live Activity), so Bark skips it.
* `{op:"reply", id, key, result}`: with an `id` the bridge answers `{op:"result", id, result:{ok:true}}` (or an error, -32002 when already resolved).
* `bridge/upload {sessionId, name, data}`: stores a phone file (≤ 25 MB, base64) on the agent's host, returns `{path, uri, name, size, mimeType}`.

## Files

* **Attach** (composer + → *Choose Files*): any file goes to the agent's host first — `~/.leash/uploads/<sessionId>/<name>`
  (`helper.mjs upload`, base64 over stdin, byte count checked; over SSH for Claude Code, locally for Antigravity) — and the
  prompt carries a `resource_link` (`file://…`) that the agent opens with its own tools. Names keep Unicode but lose spaces and
  brackets (Claude Code writes the link as `[@name](file://…)`, which is also how the app recognises it after a reload).
  Uploads are never deleted automatically.
* **Download**: tapping a file (tool-call location, attachment, a markdown link to a local path) opens the viewer: text with
  line numbers, everything else in QuickLook; Share → *Save to Files* / AirDrop gets the whole file.
* **Inline images**: `![alt](src)`, `<img src>` and image paths an answer lists (code spans, links, bare `/…` / `~/…`) render
  in the transcript — remote URLs directly, host paths through `bridge/readFile`; a listed path shows only if it loads.

## "!" shell mode

Typing `!cmd` in the composer (or + → *Run shell command*) runs `cmd` with the user's login shell in the conversation's
folder on the agent's host — over SSH via `helper.mjs exec` for Claude Code, locally on the Mac for Antigravity —
with stdin closed, a 120 s timeout and 30 000 characters per stream. `bridge/shell {sessionId, command}` logs it in
Claude Code's own transcript format (`<bash-input>`, `<bash-stdout>`/`<bash-stderr>`, HTML-escaped bodies), so
commands typed in a terminal and on the phone render the same, and prepends both blocks to the next `session/prompt`
so the model sees them, as the CLI does. Refused while the agent is running a turn.

## Tests

```bash
cd bridge && npm test       # runs against test/mock-agent.mjs, no agy needed
```
