# Leash (拴绳) — control your coding agents from your phone

[简体中文](README.zh-CN.md)

Personal native mobile client (iOS & Android) for **Antigravity (`agy`)** and **Claude Code**.
The phone connects over Tailscale or LAN to a lightweight **bridge** (Node.js 20+) running on your computer (macOS, Linux, or Windows), which drives official `agy acp` and `claude-agent-acp` using your existing local login—no OAuth tokens are copied anywhere.

```
iPhone (SwiftUI)    ─┐
                     ├─ WebSocket + Bearer token (Tailscale / LAN) ─▶  bridge (Node)  ─stdio JSON-RPC─▶  agy acp, claude-agent-acp
Android (Compose)   ─┘   (system Tailscale app or built-in tsnet)
```

## Layout

| Path | Description |
|---|---|
| `bridge/` | Node.js bridge (macOS, Linux, Windows): agent lifecycle, session cache, multi-device fan-out, approvals; `test/` uses a mock agent |
| `ios/` | Xcode project (SwiftUI, iOS 26): main app, Live Activity, Apple Watch app |
| `android/` | Gradle project (Kotlin, Jetpack Compose, minSdk 29): main app + `:tailnet` Go module; see `PLAN.md`, `PROGRESS.md`, `DESIGN.md` |
| `tailnet/` | Go module (`tsnet`) compiled via `gomobile` into an AAR / xcframework for built-in Tailscale; see `tailnet/README.md` |
| `design/` | App icon SVG sources (`tools/make-icon.mjs` renders them into iOS assets) |
| `tools/` | Developer utilities: `embed-preview.mjs` (widget preview) and `make-icon.mjs` |

---

## Bridge

Runs on macOS, Linux, and Windows (Node.js 20+; Node 22.5+ recommended on Windows for built-in `node:sqlite`). Only `bridge/` is needed on the host computer.

```bash
cd bridge
npm install
node src/index.mjs doctor     # check Node, agents, network, Bark (read-only)
node src/index.mjs            # start in foreground and print pairing QR code
node src/index.mjs pair       # print pairing QR code and leash:// link again
node src/index.mjs rotate     # rotate auth token (requires re-pairing)
```

* **Config**: `~/.leash/config.json` (`%USERPROFILE%\.leash\config.json` on Windows) stores `port`, `token`, `bind`, optional `agents`, `bark`, and `quota`.
* **Networking**: `bind: "auto"` listens on `127.0.0.1`, Tailscale (`100.x`), and LAN (`192.168.x`) addresses (falling back to `0.0.0.0`). On Windows, allow Node.js on **Private** networks when Windows Firewall prompts.
* **Auto-detection vs. explicit config**: Without `agents` in `config.json`, the bridge auto-detects `agy` and a local Claude Code adapter. Running `add-agy`, `add-claude`, or `remove-claude` writes an authoritative `agents` list.

### Background Autostart

| OS | Install / Uninstall | Log Path |
|---|---|---|
| macOS | `bash scripts/install-launchagent.sh` (`uninstall` to remove) | `~/Library/Logs/leash-bridge.log` |
| Linux | User systemd service or `pm2` | User-configured |
| Windows | `powershell -ExecutionPolicy Bypass -File scripts\install-windows-task.ps1` (`-Uninstall` to remove) | `%USERPROFILE%\.leash\bridge.log` |

### Agents

* **Antigravity (`agy`)**:
  * Uses `agy acp --app-data-dir antigravity` when `~/.gemini/antigravity` exists (replays desktop history), otherwise `agy acp`. If `--app-data-dir` triggers *Authentication required* (`google-antigravity/antigravity-cli#1672`), set `args: ["acp"]` in `config.json` or run `node src/index.mjs add-agy [command]`.
  * **Quota** (`node src/index.mjs quota`): Fetched on demand only (`bridge/quota`, 2 min cache) by spawning a hidden `agy --settings '{"statusLine":…}'` TUI via `script` (Unix) or PowerShell ConPTY (Windows). Disable with `"quota": {"enabled": false}`.
  * **Desktop busy lock** (`bridge/src/agylock.mjs`): Refuses phone edits (`-32020`) while the desktop app is actively running a turn (`presence/<id>.lock` held outside our `agy acp` tree **and** `conversation_summaries.db` busy), pushing `session_unlocked` when done.
  * **Turbo & embeds**: Per-conversation auto-approval (`auto` mode + bridge approval) and `<agent-embed src="file://…">` HTML widgets (preview with `node tools/embed-preview.mjs <widget.html> out.html dark`).
* **Claude Code**:
  * **Local**: Install `@agentclientprotocol/claude-agent-acp` into `~/.leash/claude-acp` (`npm install --prefix ~/.leash/claude-acp @agentclientprotocol/claude-agent-acp`) with `claude` CLI logged in, then restart the bridge (or run `node src/index.mjs add-claude`). Never uses `npx`.
  * **Remote (SSH / zsh)**: `node src/index.mjs add-claude --fn <zsh-fn> --repo <remote-leash-path> --home <remote-home> [--code-mode]` (or pass `ssh -T user@host` instead of `--fn`). Uses `bridge/remote/helper.mjs` for browsing, uploads, `/btw`, and quota (`claude -p /usage`).
  * Supports ACP mid-turn steering, subagents, `/btw <question>` (throwaway fork), and external session busy locks (Linux PID namespace).

### Platform Support Matrix

| Feature | macOS | Linux | Windows | Notes |
|---|---|---|---|---|
| **Core ACP / prompts / approvals / uploads / embeds** | Yes | Yes | Yes | Windows supports `.exe`/`.cmd`, `taskkill /T /F`, `C:\` & `file:///C:/` |
| **`!` shell mode** | Login shell | Login shell | PowerShell | Uses `pwsh`, else `powershell.exe` on Windows; UTF-8 preserved |
| **File browse / read (`safePath`)** | Under `$HOME` | Under `$HOME` | Under `%USERPROFILE%` | Other drives (e.g. `D:\`) are refused |
| **Antigravity (`agy`) quota** | Yes | Yes (`script`) | Yes | Unix uses `script(1)`; Windows uses PowerShell ConPTY |
| **Antigravity (`agy`) desktop busy lock** | Yes | Yes (`lsof`, `ps`, `sqlite3`) | Yes (Node 22.5+ or `sqlite3`) | Windows uses `rstrtmgr.dll`, `Win32_Process`, and `node:sqlite` |
| **Local Claude Code busy lock** | Unsupported | Yes | Unsupported | Relies on Linux `/proc` PID namespaces |
| **Local Claude Code quota & `/btw`** | Yes | Yes | `.exe` only | npm `.cmd` shims fail with direct `execFile` |
| **Remote Claude Code (`add-claude --fn`)** | Yes | Yes (`zsh`) | Unsupported (`--fn`) | Use `ssh -T user@host` prefix on Windows if `ssh` is available |
| **Autostart & Bark** | Yes | Manual / Yes | Yes | Bark is iOS-only; Android uses local notifications |

### Notifications (Bark, iOS)

Android uses its own foreground service and local notifications; iOS uses [Bark](https://github.com/Finb/Bark) as fallback when the app is suspended or not on screen:

```bash
node src/index.mjs bark https://api.day.app/<key>/   # set key (or bare <key>) and send a test push
node src/index.mjs bark encrypt [key|off]            # enable AES-256-CBC (pkcs7, random 16-char IV)
node src/index.mjs bark off                          # disable Bark
```

With encryption enabled, `detail` (conversation/tool titles) defaults to on; without it, pushes omit titles. Self-hosted `bark-server`, `when: "always"`, `sound`, `lang`, and `url` can be configured under `bark` in `config.json`.

---

## Mobile Clients

Both apps support English and Simplified Chinese (`zh-Hans`), lock indicators, mid-turn queueing/steering, inline image rendering, file viewer, `!cmd` shell execution, and built-in Tailscale (`tsnet`).

### iPhone & Apple Watch (`ios/`)

* Open `ios/Leash.xcodeproj`, select your signing team for `Leash`, `LeashLiveActivity`, and `LeashWatch` (free personal team works; `LEASH_BUNDLE_ID` controls bundle IDs), and run on device.
* See [HANDOFF-personal-cert.md](HANDOFF-personal-cert.md) for Live Activity, Dynamic Island, `BackgroundAudio` keep-alive, and WatchConnectivity details.

### Android (`android/`)

* Open `android/` in Android Studio (AGP 9.2+) or build via CLI (JDK 17, Android SDK platform 37 / Build Tools 36):
  ```bash
  cd android
  ./gradlew assembleDebug      # x86_64 emulator build (includes Demo bridge)
  ./gradlew assembleRelease    # arm64-v8a release APK
  ```
* Put release signing config in `android/signing/keystore.properties` (git-ignored) and verify with `./gradlew verifyReleaseSignature`.
* Uses a foreground service + local notifications instead of `BackgroundAudio`/Bark; single-file uploads are capped at 11 MB (25 MB on iOS/bridge).

### Built-in Tailscale (`tsnet`)

* Enable in **Settings → Built-in Tailscale** with a Tailscale Auth Key (`tskey-auth-…`) or custom Headscale Control URL, and pair using your bridge's Tailscale IP/MagicDNS name.
* **iOS build**: `cd tailnet && ./build-ios.sh` (requires Go 1.27+ and Xcode) generates `ios/Frameworks/Tailnet.xcframework` (git-ignored).
* **Android build**: Built automatically by `:tailnet` when NDK `27.2.12479018` is present (override with `-Ptailnet=on|off`; non-Windows hosts need their Go archive added to `goArchives` in `android/tailnet/build.gradle.kts`).

---

## Tests

```bash
cd bridge && npm test                  # node --test with mock agent (simulates win32 paths/locks/quota on any OS)
cd android && ./gradlew testDebugUnitTest
cd tailnet && go vet ./... && go test ./...
```
