# Leash (拴绳) — your coding agents on a leash, from your phone

A personal native client for coding agents (Antigravity, Claude Code), on iPhone and Android, in the spirit of Claude Code Remote / Codex remote.
The phone talks to a small **bridge** (Node) on the computer where the agents live: macOS, Linux or Windows; the bridge drives the official
`agy acp` (Agent Client Protocol over stdio) and, optionally, Claude Code. Everything still runs through the agent's own CLI on that computer with your own login;
no OAuth tokens are copied anywhere.

```
iPhone (SwiftUI)    ─┐
                     ├─ WebSocket + token, over Tailscale / LAN ─▶  bridge (Node; macOS, Linux, Windows)  ─stdio JSON-RPC─▶  agy acp, claude-agent-acp
Android (Compose)   ─┘   (the system Tailscale app, or Leash's built-in node)
```

The apps say "bridge", not "Mac"; the host is "the computer running the bridge". Both apps have a Simplified Chinese (zh-Hans) localisation.

## Layout

| Path | What |
|---|---|
| `bridge/` | Node bridge (macOS, Linux, Windows): spawns the agents, caches per-session transcripts, fans out to devices, relays permission prompts; `test/` runs against a mock agent |
| `ios/` | Xcode project (SwiftUI, iOS 26): app, Live Activity, watch app |
| `android/` | Gradle project (Kotlin, Jetpack Compose, minSdk 29): app, `:tailnet` module that builds the Go node; `PLAN.md`, `PROGRESS.md`, `DESIGN.md` |
| `tailnet/` | Go module (`tsnet`): built-in Tailscale node for both apps, built with `gomobile` into an AAR / xcframework; see `tailnet/README.md` |
| `design/` | App icon sources (`icon/*.svg`); `tools/make-icon.mjs` renders them into the iOS asset catalog |
| `tools/` | Probes of `agy` / ACP on a machine (`probe-*.sh`, `acp-probe*.mjs`, `probe-mac.sh` writes `.probe/`), `embed-preview.mjs` |

## Bridge

Runs on macOS, Linux and Windows (Node.js 20 or newer). Only `bridge/` is needed on the computer: copy or clone it anywhere.

```bash
cd bridge
npm install
node src/index.mjs doctor     # what this machine has: Node, agents, addresses, Bark; run it first on a new computer, changes nothing
node src/index.mjs            # foreground; prints a pairing QR code
node src/index.mjs pair       # show the QR again
node src/index.mjs rotate     # new token (re-pair the phone)
```

Pairing: the QR code (or the link under it, `leash://pair?host=…&port=…&token=…&name=…`) is scanned or pasted in the app. To reach the computer away
from home put Tailscale on it and on the phone, or use the app's built-in Tailscale (below).

Config lives in `~/.leash/config.json` (`port`, `token`, `bind`, optionally `agents`; `%USERPROFILE%\.leash` on Windows).
**Agents are detected**: without `agents` in the config the bridge starts what it finds, Antigravity (`agy` on PATH or in the usual install
folders) and a local Claude Code adapter (see below). One it does not find is logged once at startup with an install hint and is not shown in the app. Once `agents` is in the file (`add-claude`, `add-agy`,
`remove-claude` write it) the list is authoritative: an agent whose command is missing is reported once in the log and shows in the app as
down with the reason and what to install (`Restart` in the app looks again); it is never restarted in a loop.
`bind: "auto"` listens on 127.0.0.1 plus the computer's Tailscale (100.x) and 192.168.x LAN addresses (checked again every 30 s); with neither up it falls back to `0.0.0.0`. The pairing QR uses the Tailscale name when Tailscale is up, else the LAN IP. On the LAN the token travels in plain `ws://`. Set `bind` to `"127.0.0.1,100.x.y.z"` to pin it.

Keep it running in the background:

| OS | How | Log |
|---|---|---|
| macOS | `bash scripts/install-launchagent.sh` (LaunchAgent `dev.leash.bridge`: at login, restarted after a crash; `uninstall` removes it) | `~/Library/Logs/leash-bridge.log` |
| Linux | a systemd user unit or `pm2` (no script provided) | whatever you configure |
| Windows | `powershell -ExecutionPolicy Bypass -File scripts\install-windows-task.ps1` (scheduled task *LeashBridge*: at logon, hidden, restarted after a crash; `-Uninstall` removes it; no administrator rights) | `%USERPROFILE%\.leash\bridge.log` |

What differs on a machine without the author's setup:
* Only `agy` found: only `agy` runs; nothing about Claude Code is shown in the app (the log says once that it was not detected). If nothing at all is found, the app shows one agent, `agy`, as down with the install hint. `agy acp` gets `--app-data-dir antigravity` only when `~/.gemini/antigravity` (the desktop app's data) exists, otherwise plain `agy acp`. If `agy` answers *Authentication required* with that flag, set the args to `["acp"]` (agent `args` in the config; see google-antigravity/antigravity-cli#1672). `add-agy [command]` (re)writes the Antigravity entry.
* Quota (`agy` status line) needs `script`; the busy lock needs `lsof`, `ps` and `sqlite3` and the desktop data dir. A missing one turns that feature off with one log line, nothing else changes. Both are off on Windows.
* Claude Code on the same computer: `npm install --prefix ~/.leash/claude-acp @agentclientprotocol/claude-agent-acp` (the `claude` CLI must be installed and logged in), then restart the bridge; it is found automatically (also `$LEASH_CLAUDE_ACP_DIR`, `bridge/node_modules`, or a global `claude-agent-acp`). `add-claude` with no arguments pins it in the config. Nothing is ever fetched by `npx`.
* Claude Code on another host needs explicit arguments, see "Claude Code" below.

### Windows

Same code, with these differences: `agy.cmd` / `agy.exe`
are found through PATH and PATHEXT plus the usual install folders (`%LOCALAPPDATA%\agy\bin`, `%APPDATA%\npm`, winget links), `.cmd` shims are started
through `cmd.exe`, the `!` shell is PowerShell (`pwsh`, else `powershell.exe`), file paths accept `C:\`, `/C:/` and `file:///C:/` forms, and
the Antigravity quota and busy lock are off (they need `script` / `lsof` / `sqlite3`).

1. Install Node.js LTS (nodejs.org), open a new PowerShell. Install the Antigravity CLI (`agy`) and sign in once (`agy`).
2. Copy the `bridge` folder, e.g. to `C:\leash\bridge`, then `cd C:\leash\bridge; npm install`.
3. `node src\index.mjs doctor`; fix what it lists.
4. `node src\index.mjs pair`, scan the QR code.
5. Autostart: `scripts\install-windows-task.ps1` (above).
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

Leash has no APNs, so the bridge can alert an iPhone (and a paired Apple Watch) through the free
[Bark](https://github.com/Finb/Bark) app (iOS only) when an agent asks for approval / a question, or a turn finishes / fails.
Android needs no Bark: its foreground service and local notifications do this (see "Android").

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

### How it talks to Antigravity (all official agy surfaces)

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
  sandboxed web view (iOS: WKWebView, `Views/AgentEmbedView.swift`; Android: WebView, `ui/embed/`) that copies agy's host contract: theme tokens, `html.light/.dark` +
  `themechange`, CSP allowing only the gstatic Tailwind script, height from content (max 800 pt / dp inline; tap ⤢ for full screen).
  Preview the wrapper in Chromium: `node tools/embed-preview.mjs <widget.html> out.html dark`.

### Claude Code (second agent, on another host)

The bridge can drive several ACP agents at once (`agents` in `~/.leash/config.json`). Claude Code can run on the bridge's own computer (see above); in the
author's setup it runs on a separate Ubuntu box and is reached from the bridge's computer (a Mac) through a zsh function (an ssh wrapper):

```
bridge ──zsh -c 'source ~/.zshrc; <fn> …/bridge/remote/run-acp.sh'──▶ claude-agent-acp ──▶ official claude CLI
```

* Host setup (once, on that host): `mkdir -p ~/.leash/claude-acp && cd ~/.leash/claude-acp && npm i @agentclientprotocol/claude-agent-acp`
* On the bridge's computer: `node src/index.mjs add-claude --fn <zsh-function> --repo <Leash checkout on that host> --home <its home directory>` (or
  `add-claude --repo <path> --home <home> ssh -T user@host`), then restart the bridge. Nothing is assumed: `--repo` and `--home` are required;
  add `--code-mode` to resume sessions started in Claude Code's experimental Code Mode. `add-claude` with no arguments sets up Claude Code on the
  bridge's own computer instead (adapter in `~/.leash/claude-acp`). `remove-claude` takes it out of the config.
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
  app keeps the conversation's spinner, Live Activity (iPhone) and turn-end notification going until then. The chat docks the
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

iOS 26, SwiftUI. Open `ios/Leash.xcodeproj`, pick your signing team for each target (Leash, LeashLiveActivity, LeashWatch; a free
personal team works, profiles last 7 days), run on the device, then Settings → *Scan pairing QR code*. The bundle IDs
derive from one build setting, `LEASH_BUNDLE_ID`. See `HANDOFF-personal-cert.md` for the Live Activity, Dynamic Island
and Apple Watch app (the watch reaches the bridge through the iPhone app over WatchConnectivity).

Background: while a turn runs, a looping silent sound (`BackgroundAudio`, driven by the Live Activity) keeps the app and its connection alive; the app posts local notifications
(requests with Allow / Deny, turn ends), and Bark covers a suspended app. Built-in Tailscale needs one extra build step (below).

## Android

Kotlin, Jetpack Compose, Material 3 with Leash's own design system (`android/DESIGN.md`), single activity, minSdk 29 (Android 10), targetSdk 35, compileSdk 37. At feature
parity with the iPhone app minus the watch; no Google Play Services or FCM (sideloaded APK). Plan, phase log and known issues: `android/PLAN.md`, `android/PROGRESS.md`.

Replacements for the Apple-only surfaces:
* **Foreground service** instead of BackgroundAudio: keeps the WebSocket alive while a turn this phone started runs, while a request is pending, and for a 3-minute
  grace after leaving the app (Settings → *Keep running while an agent works*, default on; off = no service and no ongoing notification).
* **Local notifications** instead of Live Activity: an ongoing notification for a running turn, a high-importance *Needs you* channel for requests (Allow / Deny
  buttons answer without opening the app, questions open the chat) and a *Turn finished* channel; each alert is followed by `bridge/noticed` so Bark skips it.
  `POST_NOTIFICATIONS` is asked once, at the first turn. Deep links: `leash://pair`, `leash://session?id=…&agent=…`.
* **Pairing** (Settings): scan the QR code (CameraX + bundled ML Kit, no Play Services), paste the pairing link, or enter host, port and token.
* **Files**: attach via Photo Picker (JPEG, up to 4 photos) or the document picker; viewer for text (line numbers), images (zoom) and PDF, anything else is handed to another app (Open with / Share).
* **Upload cap 11 MB** (iOS and the bridge allow 25 MB): OkHttp refuses to queue more than 16 MiB of outgoing frames and `bridge/upload` is one base64 call. A chunked upload in the bridge would lift it; not done yet.
* The bridge is plain `ws://` over Tailscale / LAN, so cleartext traffic is allowed by the network security config. The client sends `X-Leash-Client: android`.

### Build

Open `android/` in an Android Studio new enough for AGP 9.2, or from a shell (JDK 17, Android SDK with platform 37 and Build Tools 36; `local.properties` `sdk.dir` or `ANDROID_HOME`):

```bash
cd android
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # app/build/outputs/apk/release/app-release.apk
adb install -r app/build/outputs/apk/release/app-release.apk
```

| Build type | ABI | Notes |
|---|---|---|
| `debug` | x86_64 only | for the emulator, **does not install on an arm64 phone**; adds the demo bridge (Settings → *Demo bridge*, scripted conversations to look at the UI without a bridge) and a scripted Tailscale node; `-PleashIdSuffix=.x` installs a second copy beside it |
| `release` | arm64-v8a only | R8 shrinking + resource shrinking, no obfuscation (`-dontobfuscate`, readable stack traces); signed when `signing/keystore.properties` exists, else left unsigned with a warning; about 30 MiB with the Tailscale node |
| `releaseLike` | x86_64 only | release's R8 rules and shrinking on the debug sources, debug-signed, id `.releaselike`: run it on the emulator after changing keep rules or adding reflective code (`./gradlew assembleReleaseLike`) |

**Release signing.** `android/signing/` is git-ignored as a whole. `android/signing/keystore.properties` has these keys (never commit real values):

```properties
storeFile=leash-release.jks     # relative to android/signing/
storeType=pkcs12                # optional, default pkcs12
storePassword=<password>
keyAlias=leash
keyPassword=<password>          # PKCS12 uses the store password for the key too
```

Create a keystore once with `keytool -genkeypair -v -keystore android/signing/leash-release.jks -storetype PKCS12 -keyalg RSA -keysize 4096 -validity 36500 -alias leash`.
**Back up the keystore and the properties file**: an APK signed with another key cannot update the installed app (uninstall, data lost). `./gradlew verifyReleaseSignature` builds the
release APK and prints its signer certificates (`apksigner verify --print-certs`).

**Tests**: `cd android && ./gradlew testDebugUnitTest` (JVM unit tests: core reducers, bridge connection against a fake transport and MockWebServer, the demo bridge driven through the real AppModel, notifications policy, tailnet controller, ...). `./gradlew compileReleaseKotlin` checks the release stubs; to compile the unit tests against them too: `./gradlew compileReleaseUnitTestKotlin -Pandroid.onlyEnableUnitTestForTheTestedBuildType=false` (AGP 9 does not create that task by default).

**Tailnet AAR.** The built-in Tailscale node is the Go module `tailnet/` built by the `:tailnet` Gradle module with `gomobile bind` (debug: android/amd64, release: android/arm64, API 29, Java package `dev.leash.personal.tailnet`).
It needs the pinned NDK `27.2.12479018` (SDK Manager, or `sdkmanager "ndk;27.2.12479018"`, or `ANDROID_NDK_HOME`); Gradle downloads the pinned Go toolchain (1.27.4, sha256 checked) and `gomobile` itself,
into `android/.gradle/tailnet-toolchain` (survives `clean`; first build a few minutes). The Go archive is pinned for windows-amd64 only: on macOS / Linux add your platform's archive and sha256 to `goArchives` in `android/tailnet/build.gradle.kts`, or build without the node.
`-Ptailnet=on|off` forces it: by default the node is built and linked only when the NDK is found; without it the app builds as before and Settings says built-in Tailscale is *not included in this build*.

## Built-in Tailscale (iPhone and Android)

Sometimes the Tailscale app cannot be used on the phone (another VPN holds the system VPN slot). With this option Leash joins the tailnet itself, in-process, with Tailscale's `tsnet`
(userspace netstack): no VpnService / NetworkExtension, and only Leash's own bridge connection goes through it. Off by default; with it off nothing changes.

* **Enable**: Settings → *Built-in Tailscale* on, paste a Tailscale auth key (`tskey-auth-…`, from the admin console), *Save & connect*. *Advanced* → *Control URL* points it at a Headscale server instead.
  The pairing host is then the bridge computer's Tailscale IP or MagicDNS name (`100.x.y.z` or `<your-mac>.ts.net`); the bridge computer must be on the same tailnet.
  The key is only needed until the node is registered; it is stored in the Keychain (iOS) / sealed by an Android Keystore key (Android) and never logged. The node shows up in the admin console as `leash-ios` / `leash-android`; *Log out* in Settings leaves the tailnet and deletes the saved key.
* **How**: the app starts the node, which listens on `127.0.0.1:<port>` and pipes each connection to the bridge through the tailnet; the app connects to `ws://127.0.0.1:<port>/ws`. Any app on the device can reach that port, so the bridge's bearer token is what authenticates.
  The node runs only while the connection is wanted (on screen, Live Activity audio on iOS, the foreground service on Android).
* **Size**: Android release APK about 30 MiB, of which the arm64 node (`libgojni.so`) is 18.8 MB; the unshrunk debug APK is about 98 MiB. iOS: `Tailnet.xcframework` is 64 MB on disk (device and simulator slices); the app-size increase was not measured.
  `tailnet/build-tags.txt` drops unused Tailscale features (SSH, taildrop, drive, serve/funnel, ACME, ...) to keep it small.
* **Android build**: see "Tailnet AAR" above.
* **iOS build** (on the Mac with Xcode and the iOS + Simulator SDKs, Go on PATH, e.g. `brew install go`):

  ```bash
  cd tailnet && ./build-ios.sh      # a few minutes: device + simulator slices -> ios/Frameworks/Tailnet.xcframework + Tailnet.xcconfig (git-ignored)
  open ../ios/Leash.xcodeproj       # build and run the Leash scheme as usual
  ```

  `ios/Config/Leash.xcconfig` includes the generated `Tailnet.xcconfig` if it exists; without it the app builds with the feature disabled and Settings says so.
  The Swift side is `Core/TailnetNode.swift` (the only file importing the framework), `Core/TailnetController.swift` and `Views/TailnetSettingsSection.swift`.
* **Go tests**: `cd tailnet && go vet ./... && go test ./...` (Go 1.27+; runs tailscale's in-process control and DERP servers, no network). API and details: `tailnet/README.md`.

## Localisation

Both apps ship English and Simplified Chinese (zh-Hans), following the system language. iOS: string catalogs (`ios/Leash/Localizable.xcstrings`, `InfoPlist.xcstrings`). Android: `values-zh-rCN/` (strings and plurals) with
`locales_config` (en, zh-CN), so Android 13+ lists them under per-app language. The app name is 拴绳 in Chinese, Leash otherwise; `bridge`, agent, token, Turbo and Shell stay English.
Names that come from the bridge or agents (modes, models, tool titles) are shown as received.

## Bridge protocol (v1)

JSON text frames on `ws://<bridge>:8787/ws` with `Authorization: Bearer <token>` (and `X-Leash-Client: ios|android`, used in the bridge log).

* phone → bridge: `{op:"call", id, method, params}` (ACP methods like `session/new|list|prompt|cancel|set_config_option`,
  plus `bridge/attach|detach|browse|readFile|restartAgent|shell|btw|unqueue|requeue`), `{op:"reply", key, result}` to answer a permission/elicitation.
* bridge → phone: `ready`, `result`, `update` (`session/update` with `epoch`+`seq`), `request`/`resolved`
  (permission & questions, broadcast to every device), `turn` (running/idle), `shell` (a "!" command running/done), `agent` (agent process state),
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
* `bridge/noticed {key}`: the phone alerted about this pending request itself (Live Activity / local notification), so Bark skips it.
* `{op:"reply", id, key, result}`: with an `id` the bridge answers `{op:"result", id, result:{ok:true}}` (or an error, -32002 when already resolved).
* `bridge/upload {sessionId, name, data}`: stores a phone file (≤ 25 MB, base64) on the agent's host, returns `{path, uri, name, size, mimeType}`.

## Files

* **Attach** (composer + → *Choose Files*): any file goes to the agent's host first — `~/.leash/uploads/<sessionId>/<name>`
  (`helper.mjs upload`, base64 over stdin, byte count checked; over SSH for Claude Code on another host, locally otherwise) — and the
  prompt carries a `resource_link` (`file://…`) that the agent opens with its own tools. Names keep Unicode but lose spaces and
  brackets (Claude Code writes the link as `[@name](file://…)`, which is also how the app recognises it after a reload).
  Uploads are never deleted automatically. The Android app caps files at 11 MB (see "Android").
* **Download**: tapping a file (tool-call location, attachment, a markdown link to a local path) opens the viewer: text with
  line numbers, everything else in QuickLook (iOS) or handed to another app (Android); Share → *Save to Files* / AirDrop (iOS) or the share sheet (Android) gets the whole file.
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

Typing `!cmd` in the composer (or + → *Run shell command*) runs `cmd` with the user's login shell (PowerShell on Windows) in the conversation's
folder on the agent's host — over SSH via `helper.mjs exec` for Claude Code on another host, locally on the bridge's computer otherwise —
with stdin closed, a 120 s timeout and 30 000 characters per stream. `bridge/shell {sessionId, command}` logs it in
Claude Code's own transcript format (`<bash-input>`, `<bash-stdout>`/`<bash-stderr>`, HTML-escaped bodies), so
commands typed in a terminal and on the phone render the same, and prepends both blocks to the next `session/prompt` so
the model sees them, as the CLI does. Refused while the agent is running a turn.

## Tests

```bash
cd bridge && npm test             # node --test against test/mock-agent.mjs, no agy needed (98 tests; platform logic is tested for win32 from any OS)
cd android && ./gradlew testDebugUnitTest
cd tailnet && go vet ./... && go test ./...
```
