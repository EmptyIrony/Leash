# Leash（拴绳）—— 在手机上掌控你的编程 Agent

[English](README.md)

面向 **Antigravity（`agy`）** 与 **Claude Code** 的个人原生移动客户端（iPhone & Android）。
手机通过 Tailscale 或局域网连接运行在电脑（macOS、Linux 或 Windows）上的轻量 **bridge**（Node.js 20+），由 bridge 驱动官方 `agy acp` 与 `claude-agent-acp`。所有请求仍走你本机的 CLI 与登录态，不会复制任何 OAuth Token。

```
iPhone (SwiftUI)    ─┐
                     ├─ WebSocket + Bearer token（Tailscale / LAN）─▶  bridge (Node)  ─stdio JSON-RPC─▶  agy acp, claude-agent-acp
Android (Compose)   ─┘   （系统 Tailscale App 或内置 tsnet 节点）
```

## 目录结构

| 路径 | 说明 |
|---|---|
| `bridge/` | Node.js bridge（macOS、Linux、Windows）：拉起 Agent、缓存会话记录、多端广播与审批；`test/` 使用 mock agent |
| `ios/` | Xcode 工程（SwiftUI，iOS 26）：主 App、灵动岛/实时活动、Apple Watch App |
| `android/` | Gradle 工程（Kotlin、Jetpack Compose，minSdk 29）：主 App 与 `:tailnet` Go 模块；详见 `PLAN.md`、`PROGRESS.md`、`DESIGN.md` |
| `tailnet/` | Go 模块（`tsnet`）：通过 `gomobile` 编译为 AAR / xcframework 供双端内置 Tailscale 使用；详见 `tailnet/README.md` |
| `design/` | App 图标 SVG 源文件（`tools/make-icon.mjs` 负责渲染进 iOS 资源目录） |
| `tools/` | 开发者辅助工具：`embed-preview.mjs`（小组件预览）与 `make-icon.mjs` |

---

## Bridge

支持 macOS、Linux 与 Windows（需要 Node.js 20+；Windows 推荐 Node 22.5+ 以使用内置 `node:sqlite`）。电脑上只需部署 `bridge/` 目录。

```bash
cd bridge
npm install
node src/index.mjs doctor     # 检查 Node、Agent、网络与 Bark 配置（只读）
node src/index.mjs            # 前台启动并打印配对二维码
node src/index.mjs pair       # 重新打印配对二维码与 leash:// 链接
node src/index.mjs rotate     # 重置鉴权 token（需重新扫码配对）
```

* **配置**：`~/.leash/config.json`（Windows 下为 `%USERPROFILE%\.leash\config.json`），保存 `port`、`token`、`bind` 及可选的 `agents`、`bark`、`quota`。
* **网络**：`bind: "auto"` 自动监听 `127.0.0.1`、Tailscale（`100.x`）和局域网（`192.168.x`），不可用时回退 `0.0.0.0`。Windows 首次弹防火墙提示时请允许**专用网络（Private）**。
* **自动检测与显式配置**：未配置 `agents` 时会自动检测 `agy` 与本地 Claude Code 适配器；执行 `add-agy`、`add-claude`、`remove-claude` 后会写入权威 `agents` 列表。

### 后台自启

| 系统 | 安装 / 卸载 | 日志位置 |
|---|---|---|
| macOS | `bash scripts/install-launchagent.sh`（传 `uninstall` 卸载） | `~/Library/Logs/leash-bridge.log` |
| Linux | systemd user service 或 `pm2` | 取决于你的配置 |
| Windows | `powershell -ExecutionPolicy Bypass -File scripts\install-windows-task.ps1`（传 `-Uninstall` 卸载） | `%USERPROFILE%\.leash\bridge.log` |

### Agent 说明

* **Antigravity（`agy`）**：
  * 检测到 `~/.gemini/antigravity` 时默认以 `agy acp --app-data-dir antigravity` 启动以回放桌面端历史，否则运行 `agy acp`。若报 *Authentication required*（`google-antigravity/antigravity-cli#1672`），可在配置中将 `args` 设为 `["acp"]` 或执行 `node src/index.mjs add-agy [command]`。
  * **额度查询**（`node src/index.mjs quota`）：仅在手机端主动请求时触发（`bridge/quota`，缓存 2 分钟），通过 `script`（Unix）或 PowerShell ConPTY（Windows）拉起隐藏 `agy --settings '{"statusLine":…}'` TUI 读取额度后立即销毁，不发提示词。可用 `"quota": {"enabled": false}` 关闭。
  * **桌面端忙碌锁**（`bridge/src/agylock.mjs`）：当桌面端正在某个对话运行回合时（`presence/<id>.lock` 被非本 bridge 进程树持有 **且** `conversation_summaries.db` 处于忙碌状态），拒绝手机端写入（`-32020`），完成后推送 `session_unlocked`。
  * **Turbo 与内联组件**：支持按对话开启自动审批（`auto` 模式 + bridge 自动放行），并支持 `<agent-embed src="file://…">` 沙箱 HTML 小组件（可用 `node tools/embed-preview.mjs <widget.html> out.html dark` 预览）。
* **Claude Code**：
  * **本机**：登录 `claude` CLI 后执行 `npm install --prefix ~/.leash/claude-acp @agentclientprotocol/claude-agent-acp` 并重启 bridge（或执行 `node src/index.mjs add-claude`），不使用 `npx`。
  * **远端（SSH / zsh）**：`node src/index.mjs add-claude --fn <zsh-fn> --repo <远端仓库路径> --home <远端家目录> [--code-mode]`（也可用 `ssh -T user@host` 替代 `--fn`），通过远端 `bridge/remote/helper.mjs` 提供文件读写、`/btw` 与额度查询（`claude -p /usage`）。
  * 支持回合中注入（steering）、子 Agent（subagents）、`/btw <问题>` 无痕旁白提问，以及他端忙碌锁（Linux PID namespace）。

### 平台功能矩阵

| 功能 | macOS | Linux | Windows | 说明 |
|---|---|---|---|---|
| **核心 ACP / 对话 / 审批 / 上传 / 内联组件** | ✅ | ✅ | ✅ | Windows 适配 `.exe`/`.cmd`、`taskkill /T /F`、`C:\` 与 `file:///C:/` |
| **`!` Shell 模式** | 登录 Shell | 登录 Shell | PowerShell | Windows 优先 `pwsh`，否则 `powershell.exe`，保留 UTF-8 输出 |
| **文件浏览与读取（`safePath`）** | `$HOME` 内 | `$HOME` 内 | `%USERPROFILE%` 内 | 拒绝跨盘符路径（如 `D:\`） |
| **Antigravity（`agy`）额度查询** | ✅ | ✅（需 `script`） | ✅ | Unix 用 `script(1)`；Windows 用 PowerShell ConPTY |
| **Antigravity（`agy`）桌面端忙碌锁** | ✅ | ✅（`lsof`/`ps`/`sqlite3`） | ✅（Node 22.5+ 或 `sqlite3`） | Windows 用 `rstrtmgr.dll` + `Win32_Process` + `node:sqlite` |
| **本地 Claude Code 忙碌锁** | ❌ | ✅ | ❌ | 依赖 Linux `/proc` PID namespace |
| **本地 Claude Code 额度与 `/btw`** | ✅ | ✅ | 仅 `.exe` | npm `.cmd` 垫片在直接 `execFile` 下会失败 |
| **远端 Claude Code（`add-claude --fn`）** | ✅ | ✅（需 `zsh`） | ❌ 不支持 `--fn` | Windows 若有 `ssh` 可改用 `ssh -T user@host` 前缀 |
| **开机自启与 Bark 推送** | ✅ | 手动 / ✅ | ✅ | Bark 仅用于 iOS；Android 使用本地通知 |

### 推送通知（Bark，仅 iOS）

Android 使用自身前台服务与本地通知；iOS 在 App 退后台被挂起或未停留在前台时通过 [Bark](https://github.com/Finb/Bark) 兜底提醒：

```bash
node src/index.mjs bark https://api.day.app/<key>/   # 配置 key（或直接填 <key>）并发送测试推送
node src/index.mjs bark encrypt [key|off]            # 开启 AES-256-CBC 端到端加密（pkcs7，随机 16 位 IV）
node src/index.mjs bark off                          # 关闭推送
```

开启加密后默认在通知中附带对话与工具标题（`detail`），未加密时默认隐藏。可在 `config.json` 的 `bark` 字段配置自建 `server`、`when: "always"`、`sound`、`lang` 与 `url`。

---

## 移动客户端

双端均支持英文与简体中文、会话锁图标、回合中排队/注入、流内图片按需加载、文件查看器、`!cmd` Shell 与内置 Tailscale（`tsnet`）。

### iPhone 与 Apple Watch（`ios/`）

* 使用 Xcode 打开 `ios/Leash.xcodeproj`，为 `Leash`、`LeashLiveActivity`、`LeashWatch` 选择签名团队（个人免费账号即可，通过 `LEASH_BUNDLE_ID` 统一派生 Bundle ID）后真机运行。
* 实时活动（Live Activity）、灵动岛、后台保活（`BackgroundAudio`）与手表通信细节见 [HANDOFF-personal-cert.md](HANDOFF-personal-cert.md)。

### Android（`android/`）

* 用 Android Studio（AGP 9.2+）打开 `android/`，或在命令行构建（JDK 17、Android SDK Platform 37 / Build Tools 36）：
  ```bash
  cd android
  ./gradlew assembleDebug      # x86_64 模拟器包（含 Demo bridge）
  ./gradlew assembleRelease    # arm64-v8a 正式包
  ```
* Release 签名配置在 `android/signing/keystore.properties`（已 git 忽略），可通过 `./gradlew verifyReleaseSignature` 校验指纹。
* 使用前台服务与本地通知替代 `BackgroundAudio` 和 Bark；单文件上传上限 11 MB（iOS 与 bridge 为 25 MB）。

### 内置 Tailscale（`tsnet`）

* 在手机端 **设置 → 内置 Tailscale** 填入 Tailscale Auth Key（`tskey-auth-…`）或自定义 Headscale Control URL，配对主机填 bridge 的 Tailscale IP 或 MagicDNS 域名。
* **iOS 编译**：执行 `cd tailnet && ./build-ios.sh`（需 Go 1.27+ 与 Xcode）生成 `ios/Frameworks/Tailnet.xcframework`（已 git 忽略）。
* **Android 编译**：检测到 NDK `27.2.12479018` 时由 `:tailnet` 自动编译（可用 `-Ptailnet=on|off` 强制开关；在 macOS/Linux 构建需将对应 Go 安装包补全到 `android/tailnet/build.gradle.kts` 的 `goArchives`）。

---

## 测试

```bash
cd bridge && npm test                  # 使用 mock agent 运行 node --test（可在任意系统模拟 win32 路径/锁/额度逻辑）
cd android && ./gradlew testDebugUnitTest
cd tailnet && go vet ./... && go test ./...
```
