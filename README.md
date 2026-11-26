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
node src/index.mjs doctor     # what this machine has: Node, agents, addresses, Bark; run it first on a new computer
node src/index.mjs            # foreground; prints a pairing QR code
# or keep it running in the background (login item, auto-restart):
bash scripts/install-launchagent.sh
node src/index.mjs pair       # show the QR again
node src/index.mjs rotate     # new token (re-pair the phone)
```

Config lives in `~/.leash/config.json` (`port`, `token`, `bind`, optionally `agents`; `%USERPROFILE%\.leash` on Windows).
**Agents are detected**: without `agents` in the config the bridge starts what it finds, Antigravity (`agy` on PATH or in the usual install
folders) and a local Claude Code adapter (see below). One it does not find is logged once at startup with an install hint and is not shown in the app. Once `agents` is in the file (`add-claude`, `add-agy`,
`remove-claude` write it) the list is authoritative: an agent whose command is missing is reported once in the log and shows in the app as
down with the reason and what to install (`Restart` in the app looks again); it is never restarted in a loop.
`bind: "auto"` listens on 127.0.0.1 plus the Mac's Tailscale (100.x) and 192.168.x LAN addresses (checked again every 30 s); with neither up it falls back to `0.0.0.0`. The pairing QR uses the Tailscale name when Tailscale is up, else the LAN IP. On the LAN the token travels in plain `ws://`. Set `bind` to `"127.0.0.1,100.x.y.z"` to pin it.
Log of the LaunchAgent: `~/Library/Logs/leash-bridge.log`.

## Set up on another computer

For a colleague whose machine has only Antigravity (`agy`) and no Claude Code, on macOS or Linux (Windows: next section):

1. Install Node.js 20 or newer (the current LTS).
2. Copy or clone the `bridge/` folder anywhere (the rest of the repo is not needed).
3. `cd bridge && npm install`
4. `node src/index.mjs doctor`: prints the Node version, which agents it found (with versions), the addresses it will listen on (Tailscale / LAN), Bark and the config path, and ends with what is missing. It changes nothing.
5. `node src/index.mjs pair` and scan the QR code in the app. Install Tailscale on both devices to reach the computer away from home.
6. Keep it running: `bash scripts/install-launchagent.sh` on macOS (a systemd user unit or `pm2` on Linux).

What differs on a machine without the author's setup:
* Only `agy` found: only `agy` runs; nothing about Claude Code is shown in the app (the log says once that it was not detected). If nothing at all is found, the app shows one agent, `agy`, as down with the install hint. `agy acp` gets `--app-data-dir antigravity` only when `~/.gemini/antigravity` (the desktop app's data) exists, otherwise plain `agy acp`. If `agy` answers *Authentication required* with that flag, set the args to `["acp"]` (agent `args` in the config; see google-antigravity/antigravity-cli#1672).
* Quota (`agy` status line) needs `script`; the busy lock needs `lsof`, `ps` and `sqlite3` and the desktop data dir. A missing one turns that feature off with one log line, nothing else changes.
* Claude Code on the same computer: `npm install --prefix ~/.leash/claude-acp @agentclientprotocol/claude-agent-acp` (the claude CLI must be installed and logged in), then restart the bridge; it is found automatically (also `$LEASH_CLAUDE_ACP_DIR`, `bridge/node_modules`, or a global `claude-agent-acp`). `add-claude` with no arguments pins it in the config. Nothing is ever fetched by `npx`.
* Claude Code on another host needs explicit arguments, see "Claude Code" below.

## Set up on Windows

Windows is supported for `agy` (and a local Claude Code adapter); the bridge runs the same code with these differences: `agy.cmd` / `agy.exe`
are found through PATH and PATHEXT plus the usual install folders (`%LOCALAPPDATA%\agy\bin`, `%APPDATA%\npm`, winget links), `.cmd` shims are started
through `cmd.exe`, the `!` shell is PowerShell (`pwsh`, else `powershell.exe`), file paths accept `C:\`, `/C:/` and `file:///C:/` forms, and
the Antigravity quota and busy lock are off (they need `script` / `lsof` / `sqlite3`).

1. Install Node.js LTS (nodejs.org), open a new PowerShell. Install the Antigravity CLI (`agy`) and sign in once (`agy`).
2. Copy the `bridge` folder, e.g. to `C:\leash\bridge`, then `cd C:\leash\bridge; npm install`.
3. `node src\index.mjs doctor`; fix what it lists.
4. `node src\index.mjs pair`, scan the QR code. Tailscale for Windows gives the phone a route from anywhere.
5. Autostart: `powershell -ExecutionPolicy Bypass -File scripts\install-windows-task.ps1` (scheduled task *LeashBridge*: at logon, hidden, restarted after a crash, log `%USERPROFILE%\.leash\bridge.log`; `-Uninstall` removes it). No administrator rights needed.
6. **Windows Firewall** asks about Node.js the first time the bridge listens: allow it on *Private* networks, or the phone cannot connect.

First-run checklist on the Windows PC (untested on real Windows so far):
1. `node src\index.mjs doctor` shows `agy` as `[ok]` with a path and version (`agy.exe` or `agy.cmd`), and `Quota` / `Busy lock` as off.
2. `node src\index.mjs` logs `agent ready: {...}` for `agy`; the phone lists it as ready. If it says *Authentication required*, set the `agy` args to `["acp"]` in `%USERPROFILE%\.leash\config.json`.
3. In the app: start a conversation in a folder under your user profile (browse works, a folder on another drive is refused); send a prompt; approve a tool call.
4. Stop in the app, then check Task Manager: no `agy` / `cmd.exe` left behind (`taskkill /T` path).
5. `!dir` or `!Get-Location` in a conversation runs in PowerShell in that folder; non-ASCII output (Chinese) is intact.
6. Attach a photo from the phone: it lands in `%USERPROFILE%\.leash\uploads\<id>\` and the agent can open it (`file:///C:/...`).
7. `!echo "a b"` (a command with double quotes) prints a b.
8. If the agent writes an HTML widget (`<agent-embed src="file:///C:/Users/...`) the app shows it.
9. `install-windows-task.ps1`: after logging off and on, the phone reconnects without a console window; `%USERPROFILE%\.leash\bridge.log` has entries; kill `node.exe` and it comes back within a minute or two.
10. Bark: `node src\index.mjs bark <key>` sends a test push.

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
pushed (`detail`, on with encryption), otherwise just Leash; a `url` in the `bark` block overrides both. While Leash runs in the
background it posts its own notifications (requests with Allow / Deny, turn ends) and says `bridge/noticed`; the
bridge then skips Bark, which waits 4 s for that whenever a Leash is connected from the background. So Bark covers a
suspended or closed Leash, or one with notifications off. Options in the `bark` block of `~/.leash/config.json`:
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
* **Turbo** (Antigravity's name, so Antigravity only): bridge-side per-conversation auto-approval of permission prompts
  (+ agy's `auto` mode). Claude Code shows its own modes under their own names instead (Manual, Accept edits, Plan, Auto,
  Bypass permissions, as claude-agent-acp reports them); the bridge refuses `bridge/setAutoApprove` there.
* agy's tool calls use `name`/`args` instead of ACP's `title`/`rawInput`; the bridge normalizes them.
* **Inline widgets (generative UI)**: agy writes an HTML file into `~/.gemini/antigravity/brain/<id>/` and puts
  `<agent-embed src="file:///…/x.html"></agent-embed>` in its reply. The app reads it with `bridge/readFile` and runs it in a
  sandboxed WKWebView (`Views/AgentEmbedView.swift`) that copies agy's host contract: theme tokens, `html.light/.dark` +
  `themechange`, CSP allowing only the gstatic Tailwind script, height from content (max 800pt inline; tap ⤢ for full screen).
  Preview the wrapper in Chromium: `node tools/embed-preview.mjs <widget.html> out.html dark`.

## Claude Code (second agent, on another host)

The bridge can drive several ACP agents at once (`agents` in `~/.leash/config.json`). In the author's setup Claude Code runs on an
Ubuntu box and is reached from the Mac through a zsh function (`ubuntu`, an ssh wrapper); it can also run on the bridge's own computer (see above):

```
Mac bridge ──zsh -c 'source ~/.zshrc; ubuntu …/bridge/remote/run-acp.sh'──▶ claude-agent-acp ──▶ official claude CLI
```

* Host setup (once, on the Ubuntu box): `mkdir -p ~/.leash/claude-acp && cd ~/.leash/claude-acp && npm i @agentclientprotocol/claude-agent-acp`
* Mac: `node src/index.mjs add-claude --fn <zsh-function> --repo <Leash checkout on that host> --home <its home directory>` (or
  `add-claude --repo <path> --home <home> ssh -T user@host`), then restart the bridge. Nothing is assumed: `--repo` and `--home` are required;
  add `--code-mode` to resume sessions started in Claude Code's experimental Code Mode. `add-claude` with no arguments sets up Claude Code on the
  bridge's own computer instead (adapter in `~/.leash/claude-acp`).
* `bridge/remote/helper.mjs` runs on the host for folder browsing, file reads/uploads and quota (`claude -p /usage`: a local command, 0 tokens).
* Sessions started in Claude Code's Code Mode are resumed by passing `settings.env.CLAUDE_CODE_EXPERIMENTAL_CODE_MODE=1` through `_meta.claudeCode.options`.
* The adapter uses your own Claude login on that machine (`ANTHROPIC_API_KEY` is unset in `run-acp.sh`).
* **Subagents**: the bridge declares `clientCapabilities.subagents`, so claude-agent-acp runs each Agent/Task subagent as a
  child session announced with `subagent_update` (and replays the child's history on load). The bridge files child
  updates under the root conversation with `_meta.leash.subagent = <child id>`, files a child's permission / question
  requests under the root too (`request.subagent = {sessionId, title, description}`), and drops child updates that would
  touch root state (commands, usage, mode, title). A background subagent can keep working after the prompt returns; the
  root then counts as busy, so the idle and lock sweeps don't close it, and the turn isn't over: `turn` broadcasts carry
  `subagents` (how many still work, next to `running` for the prompt), `turn_end` (Bark) waits for the last one, and the
  phone keeps the conversation's spinner, Live Activity and turn-end notification going until then. The chat docks the
  running subagents above the composer.
* **Busy-elsewhere lock**: a conversation that Claude Code is *running a turn in* (terminal or Claude desktop app;
  session-file `status` other than `idle`) cannot be opened or prompted from the phone (error `-32020`, lock icon in
  the list); merely having it open there does not lock it. `helper.mjs locks` reads Claude Code's
  `~/.claude/sessions/<pid>.json` (undocumented; a holder counts only if its pid is alive with the same start time in
  the helper's pid namespace; `sdk-*` entries are the bridge's own). If another client used a conversation since the
  bridge loaded it, the bridge drops its copy and a prompt gets `-32021`; the app reloads and resends. The check fails
  open on errors. Disable per agent with `"lock": false`.

**Antigravity busy lock** (`bridge/src/agylock.mjs`, local to the computer; off where `lsof` / `sqlite3` are missing and on Windows): a conversation is refused on the phone while the
desktop app is *running a turn* in it. Signals (found with `tools/probe-agy*-mac.sh`): the desktop `language_server`
holds `~/.gemini/antigravity/presence/<id>.lock` open for every conversation it has loaded (until the app quits), and
`conversation_summaries.db` has `status = CASCADE_RUN_STATUS_RUNNING` / `not_fully_idle = 1` for the whole turn.
Held = presence lock by a process outside our own `agy acp` tree **and** busy. Read-only: `lsof +d`, `ps`, and `sqlite3`
(falls back to a temp copy when the live DB can't be opened read-only). When the turn ends the bridge pushes
`session_unlocked` and the app reloads the conversation.

## iPhone

Open `ios/Leash.xcodeproj`, pick your signing team for each target (Leash, LeashLiveActivity, LeashWatch; a free
personal team works, profiles last 7 days), run on the device, then Settings → *Scan pairing QR code*. The bundle IDs
derive from one build setting, `LEASH_BUNDLE_ID`. See `HANDOFF-personal-cert.md` for the Live Activity, Dynamic Island
and Apple Watch app (the watch reaches the Mac through the iPhone app over WatchConnectivity).

## Bridge protocol (v1)

JSON text frames on `ws://<mac>:8787/ws` with `Authorization: Bearer <token>`.

* phone → bridge: `{op:"call", id, method, params}` (ACP methods like `session/new|list|prompt|cancel|set_config_option`,
  plus `bridge/attach|detach|browse|readFile|restartAgent|shell|btw|unqueue|requeue`), `{op:"reply", key, result}` to answer a permission/elicitation.
* bridge → phone: `ready`, `result`, `update` (`session/update` with `epoch`+`seq`), `request`/`resolved`
  (permission & questions, broadcast to every device), `turn` (running/idle), `shell` (a "!" command running/done), `agent` (agy process state),
  `queue` (messages waiting for the running turn to end).
* `session/prompt` mid-turn: with an agent that advertises ACP steering (`initialize` `_meta.steering.supported`, Claude Code) the
  bridge injects the message into the running turn via `_session/steering` (`{steered:true}`); otherwise (agy) it is queued on the
  bridge (`{queued:true, id}`) and sent, merged into one prompt, the moment the turn ends — `running` stays true in between.
  `interrupt:true` queues it and cancels the turn, so it goes out right after (an empty `prompt` sends just the queue). A plain
  `session/cancel` keeps the queue; it goes out ahead of the next prompt, or `bridge/unqueue {sessionId, id}` takes one back
  (returns its `prompt` blocks); `bridge/requeue {sessionId, id, prompt, index}` puts it back (the phone's Undo). `bridge/attach` returns the `queue`; agent summaries carry `steering`, `turbo` and `btw`.
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

## /btw side questions (Claude Code)

Typing `/btw <question>` (or + → *Ask a side question*) asks about the conversation without adding to it, also while a
turn is running, like Claude Code's own `/btw`. `bridge/btw {sessionId, question}` runs `helper.mjs btw` on the host:
`claude -p --resume <id> --fork-session --no-session-persistence --tools "" --strict-mcp-config --max-turns 1` in the
conversation's folder, with the conversation's model and Code Mode settings. The fork reads the transcript as it is on disk,
answers once with no tools and no MCP servers, and is never saved, so the conversation, its turn and its lock state are
untouched; nothing is recorded or sent to other devices (only the asking phone gets `{text, durationMs, model}`). The
answer shows in a card above the composer and is gone when dismissed. It is a separate request, so the conversation's
context is read again without the main turn's prompt cache. agy has no throwaway fork, so Antigravity has no /btw.

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
