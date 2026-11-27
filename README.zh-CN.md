# Leash（拴绳）—— 在手机上掌控你的编程 Agent

[English](README.md)

面向编程 Agent（Antigravity、Claude Code）的个人原生客户端，支持 iPhone 与 Android，思路类似 Claude Code Remote / Codex remote。
手机端通过 WebSocket 连接运行在 Agent 所在电脑（macOS、Linux 或 Windows）上的轻量 **bridge**（Node.js）；bridge 负责驱动官方的 `agy acp`（基于 stdio 的 Agent Client Protocol）以及可选的 Claude Code。一切仍然通过该电脑上 Agent 自身的 CLI 与你本机的登录态运行，不会复制任何 OAuth Token。

```
iPhone (SwiftUI)    ─┐
                     ├─ WebSocket + token, over Tailscale / LAN ─▶  bridge (Node; macOS, Linux, Windows)  ─stdio JSON-RPC─▶  agy acp, claude-agent-acp
Android (Compose)   ─┘   （系统 Tailscale App，或 Leash 内置节点）
```

App 内统一称为 "bridge"，而不称 "Mac"；宿主机统称为“运行 bridge 的电脑”。两个客户端均内置简体中文（zh-Hans）本地化。

## 目录结构

| 路径 | 说明 |
|---|---|
| `bridge/` | Node bridge（macOS、Linux、Windows）：拉起 Agent、按会话缓存聊天记录、向多设备广播、转发权限审批；`test/` 使用 mock agent 运行测试 |
| `ios/` | Xcode 工程（SwiftUI，iOS 26）：主 App、实时活动（Live Activity）、Apple Watch App |
| `android/` | Gradle 工程（Kotlin、Jetpack Compose，minSdk 29）：主 App、构建 Go 节点的 `:tailnet` 模块；含 `PLAN.md`、`PROGRESS.md`、`DESIGN.md` |
| `tailnet/` | Go 模块（`tsnet`）：供双端使用的内置 Tailscale 节点，通过 `gomobile` 编译为 AAR / xcframework；详见 `tailnet/README.md` |
| `design/` | App 图标源文件（`icon/*.svg`）；`tools/make-icon.mjs` 用于渲染进 iOS 资源目录 |
| `tools/` | 本机 `agy` / ACP 探测脚本（`probe-*.sh`、`probe-agylock-win.ps1`、`acp-probe*.mjs`、`probe-mac.sh` 会输出到 `.probe/`）以及 `embed-preview.mjs` |

## Bridge

可运行在 macOS、Linux 与 Windows（需要 Node.js 20 及以上）。电脑上只需要 `bridge/` 目录：复制或克隆到任意位置即可。

```bash
cd bridge
npm install
node src/index.mjs doctor     # 检查本机环境（Node、Agent、网络地址、Bark）；在新电脑上先运行它，不会修改任何文件
node src/index.mjs            # 前台启动，并打印配对二维码
node src/index.mjs pair       # 重新显示配对二维码
node src/index.mjs rotate     # 生成新的 token（需要重新配对手机）
```

配对方式：用手机 App 扫描二维码，或粘贴二维码下方的链接（`leash://pair?host=…&port=…&token=…&name=…`）。如需在外出时连接电脑，可在电脑和手机上安装 Tailscale，或使用 App 内置的 Tailscale 节点（见下文）。

配置文件位于 `~/.leash/config.json`（包含 `port`、`token`、`bind` 及可选的 `agents`；Windows 下位于 `%USERPROFILE%\.leash`）。
**自动检测 Agent**：如果配置文件里没有 `agents`，bridge 会自动启动检测到的 Agent——包括 Antigravity（PATH 或常见安装目录下的 `agy`）和本地 Claude Code 适配器（见下文）。未检测到的 Agent 只会在启动时打印一次安装提示，不会出现在 App 中。一旦配置文件写入了 `agents`（通过 `add-claude`、`add-agy`、`remove-claude`），该列表即为权威配置：找不到命令的 Agent 会在日志中报错一次，并在 App 中显示为不可用及安装说明（App 内点击 *Restart* 可重新检测），bridge 不会陷入无限重启循环。
`bind: "auto"` 会监听 `127.0.0.1` 以及本机的 Tailscale（`100.x`）和 `192.168.x` 局域网地址（每 30 秒重新检查一次）；如果两者都未启用，则回退监听 `0.0.0.0`。配对二维码在 Tailscale 可用时优先使用 Tailscale 域名，否则使用局域网 IP。局域网下 token 通过明文 `ws://` 传输。也可手动设置 `bind`（如 `"127.0.0.1,100.x.y.z"`）来固定监听地址。

后台常驻运行：

| 系统 | 方式 | 日志位置 |
|---|---|---|
| macOS | `bash scripts/install-launchagent.sh`（LaunchAgent `dev.leash.bridge`：登录自启、崩溃重启；传 `uninstall` 可卸载） | `~/Library/Logs/leash-bridge.log` |
| Linux | systemd user unit 或 `pm2`（未提供现成脚本） | 取决于你的配置 |
| Windows | `powershell -ExecutionPolicy Bypass -File scripts\install-windows-task.ps1`（计划任务 *LeashBridge*：登录时后台静默运行、崩溃后重启；`-Uninstall` 卸载；无需管理员权限） | `%USERPROFILE%\.leash\bridge.log` |

非作者同款环境时的差异说明：
* 只检测到 `agy`：只运行 `agy`；App 中不会出现 Claude Code（仅日志记录一次未检测到）。如果什么都没找到，App 会显示一个不可用的 `agy` 并附安装提示。仅当 `~/.gemini/antigravity`（桌面端数据目录）存在时，bridge 才会给 `agy acp` 传 `--app-data-dir antigravity`，否则直接运行 `agy acp`。若加上该参数后 `agy` 报 *Authentication required*，请将配置中该 Agent 的 `args` 改为 `["acp"]`（参见 `google-antigravity/antigravity-cli#1672`）。`add-agy [command]` 可重写 Antigravity 配置项。
* 额度查询（`agy` status line）依赖 `script`；桌面端忙碌锁依赖 `lsof`、`ps`、`sqlite3` 以及桌面端数据目录。缺少任一项时对应功能会自动关闭并打印一行日志，其余功能不受影响。这两项功能在 Windows 上默认关闭。
* 同机运行 Claude Code：执行 `npm install --prefix ~/.leash/claude-acp @agentclientprotocol/claude-agent-acp`（需提前安装并登录 `claude` CLI），然后重启 bridge，系统会自动发现它（也会查找 `$LEASH_CLAUDE_ACP_DIR`、`bridge/node_modules` 或全局 `claude-agent-acp`）。不带参数执行 `add-claude` 可将其固定写入配置。bridge 绝不会使用 `npx` 暗中下载包。
* 在另一台机器上运行 Claude Code 需要显式传参，参见下文“Claude Code”章节。

### Windows

同一套代码，有以下平台差异：通过 `PATH`、`PATHEXT` 以及常见安装目录（`%LOCALAPPDATA%\agy\bin`、`%APPDATA%\npm`、winget links）查找 `agy.cmd` / `agy.exe`；`.cmd` 垫片通过 `cmd.exe` 启动；`!` 命令使用 PowerShell（优先 `pwsh`，否则 `powershell.exe`）；文件路径同时兼容 `C:\`、`/C:/` 和 `file:///C:/`；Antigravity 的额度查询和桌面端忙碌锁处于关闭状态（因为依赖 `script` / `lsof` / `sqlite3`）。

#### 平台功能对比表（Bridge 与客户端）

| 功能 | macOS | Linux | Windows | 说明 |
|---|---|---|---|---|
| **Antigravity (`agy`) 桌面端忙碌锁** | ✅ 支持 | ✅ 支持（需 `lsof`、`ps`、`sqlite3`） | ❌ **关闭** | `bridge/src/agents.mjs` 对 `win32` 直接关闭；`agylock.mjs` 依赖 `lsof`、`ps`、`sqlite3` |
| **Antigravity (`agy`) 额度查询 (`Quota`)** | ✅ 支持 | ✅ 支持（需 `script`） | ❌ **关闭** | `bridge/src/agents.mjs` 对 `win32` 直接关闭；`quota.mjs` 依赖 `script` 提供 PTY |
| **本地 Claude Code 忙碌锁 (`helper.mjs locks`)** | ❌ **不支持** | ✅ 支持（同 PID namespace） | ❌ **不支持** | 读取 Linux 专有的 `/proc/self/ns/pid` 与 `/proc/<pid>/stat` |
| **本地 Claude Code 额度与 `/btw`** | ✅ 支持 | ✅ 支持 | ⚠️ **仅限 `.exe`** | `helper.mjs` 直接 `execFile(claude, ...)`；若 `claude` 为 npm `.cmd` 垫片会在 Windows 失败 |
| **远程 Claude Code (`add-claude --fn`)** | ✅ 支持（`zsh -ic`） | ✅ 支持（需 `zsh`） | ❌ **不支持 `--fn`** | `--fn` 硬编码调用 `zsh -ic`；若本机有 `ssh`，可用 `ssh -T` 前缀连接远端 |
| **核心 ACP / 对话 / 审批 / 上传 / 嵌入预览** | ✅ 支持 | ✅ 支持 | ✅ 支持 | 已通过 `bridge/src/platform.mjs` 适配 `.cmd` 启动、`taskkill /T /F` 与 CRLF |
| **`!` Shell 模式** | 登录 Shell | 登录 Shell | PowerShell（`pwsh` / `powershell.exe`） | 已保留 UTF-8 输出与退出码；`agy.cmd` 会拒绝含 `"`、`%` 或换行的参数 |
| **文件浏览与读取 (`browse` / `read`)** | `$HOME` 内 | `$HOME` 内 | 仅 `%USERPROFILE%` 内 | `safePath` 将访问限制在用户目录下，其他盘符（如 `D:\`）会被拒绝 |
| **后台自启脚本** | LaunchAgent | 手动配置（systemd / pm2） | 计划任务（`install-windows-task.ps1`） | 日志分别位于 `~/Library/Logs/leash-bridge.log` 与 `%USERPROFILE%\.leash\bridge.log` |
| **Bark 推送通知** | ✅ 可配（推向 iOS） | ✅ 可配（推向 iOS） | ✅ 可配（推向 iOS） | 仅 iOS 客户端使用；Android 使用自身前台服务与本地通知 |

客户端（iPhone 与 Android）差异：两端均已支持在会话列表展示锁图标，并在进入会话收到 `-32020` 时显示锁定提示卡片；Bark、灵动岛、锁屏实时活动与 Apple Watch 为苹果生态独占，Android 端通过前台服务与本地通知实现后台保活和提醒。

Windows 端首次配置步骤：
1. 安装 Node.js LTS（nodejs.org），新开一个 PowerShell 窗口。安装 Antigravity CLI（`agy`）并至少登录一次（`agy`）。
2. 复制 `bridge` 目录（如 `C:\leash\bridge`），执行 `cd C:\leash\bridge; npm install`。
3. 执行 `node src\index.mjs doctor`，按提示修复问题。
4. 执行 `node src\index.mjs pair`，用手机扫码配对。
5. 开机自启：运行 `scripts\install-windows-task.ps1`（见上表）。
6. **Windows 防火墙**：bridge 首次监听时系统会弹出 Node.js 防火墙提示，请勾选允许**专用网络（Private）**，否则手机无法连接。

Windows 电脑首次运行检查清单（目前尚未在真实 Windows 上完整实测）：
1. `node src\index.mjs doctor` 显示 `agy` 为 `[ok]` 并输出路径和版本（`agy.exe` 或 `agy.cmd`），`Quota` / `Busy lock` 显示为 off。
2. `node src\index.mjs` 打印 `agy` 的 `agent ready: {...}`，手机上显示就绪。若报 *Authentication required*，在 `%USERPROFILE%\.leash\config.json` 中把 `agy` 的 `args` 设为 `["acp"]`。
3. 在手机 App 中：在用户目录下新建一个对话（浏览目录正常，跨盘符目录应被拒绝），发一条提示词，并审批一次工具调用。
4. 在 App 中点击停止（Stop），去任务管理器确认没有残留 `agy` / `cmd.exe` 进程（走 `taskkill /T` 路径清理）。
5. 在对话中执行 `!dir` 或 `!Get-Location`，应在对应目录通过 PowerShell 执行，且中文等非 ASCII 输出不乱码。
6. 从手机发送一张图片：应保存至 `%USERPROFILE%\.leash\uploads\<id>\`，Agent 可读取（`file:///C:/...`）。
7. `!echo "a b"`（含双引号的命令）能正常输出 `a b`。
8. 若 Agent 输出了 HTML 小组件（`<agent-embed src="file:///C:/Users/...`），App 能正常渲染。
9. `install-windows-task.ps1`：注销并重新登录后，无控制台黑窗且手机可重连；`%USERPROFILE%\.leash\bridge.log` 有日志；手动杀掉 `node.exe` 后一两分钟内会自动拉起。
10. Bark：`node src\index.mjs bark <key>` 能收到测试推送。

### 通知推送（Bark）

Leash 没有使用 APNs，因此 bridge 可以通过免费的 [Bark](https://github.com/Finb/Bark)（仅限 iOS）在 Agent 需要审批/提问，或一轮对话完成/失败时向 iPhone（及配对的 Apple Watch）发送推送提醒。
Android 端无需 Bark：它通过前台服务与本地通知完成（见“Android”章节）。

```bash
node src/index.mjs bark https://api.day.app/<key>/   # Bark App 里显示的 URL（或直接填 <key>）；会自动发一条测试推送
node src/index.mjs bark                              # 再次测试；`bark off` 关闭推送
```

修改配置后需要重启 bridge。仅当没有任何 Leash 客户端停留在前台界面时才会推送（App 每 15 秒发送一次心跳，静默约 25 秒视为离开；在这段宽限期内被处理掉的请求不会推送）。你自己手动点的 Stop 不会推送。允许推送标题时（`detail`，开启加密后默认打开），点击通知会直接跳转到对应对话（`leash://session?id=…&agent=…`），否则只打开 Leash；也可以在配置 `bark` 块中设置 `url` 覆盖。当 Leash 在后台保活运行时，App 会自己弹本地通知（支持直接点允许/拒绝，以及回合结束通知）并向 bridge 上报 `bridge/noticed`；当有客户端在后台连接时，bridge 会等待 4 秒，确认已上报就跳过 Bark。因此 Bark 主要兜底 App 被系统挂起、杀掉或关闭通知权限的场景。`~/.leash/config.json` 中 `bark` 字段支持的可选项：`server`（自建 bark-server，默认 `https://api.day.app`）、`when: "always"`、`attention` / `turnEnd: false`、`sound`、`lang: "en"` 以及 `detail`（在通知文案中包含对话和工具标题）。

**端到端加密**（推荐）：执行 `node src/index.mjs bark encrypt` 会生成一个 32 位密钥（也可自行传入 16/24/32 位密钥）并写入 `bark.encrypt`。在 Bark App → 推送加密 中选择 AES256、CBC、pkcs7，IV 填任意 16 个字符即可。之后每条推送都会用随机新 IV 进行 AES-CBC 加密，Bark 服务端与 APNs 只能看到设备 key 和密文，且 `detail` 默认开启。未加密时 `detail` 默认关闭，推送只会写哪个 Agent 需要你以及本轮耗时。如果密钥配置不合法，推送会被关闭而不会降级成明文发送；手机收到 "Decryption Failed" 说明 Bark 里填的密钥不一致。

### 如何与 Antigravity 通信（全部采用官方 agy 接口）

* **对话**：`agy acp --app-data-dir antigravity` —— 基于 stdio 的 ACP，指向桌面端数据目录以完整回放桌面端对话历史（普通 `agy acp` 只能回放 CLI 创建的对话）。
* **额度（5 小时 / 周额度）**：仅在 App 主动请求时触发（`bridge/quota`：连接成功、回到前台、打开对话、在额度弹窗下拉刷新时；2 分钟内的结果会直接复用）。bridge 会通过 `script` 启动一个隐藏的 `agy` TUI，并传入仅本次生效的 `--settings '{"statusLine":…}'`；`agy` 会将包含 `quota` 的状态 JSON 通过管道输出给 sink 脚本，bridge 只保留额度部分（绝不读取邮箱），随后杀掉 TUI。整个过程不发提示词，也不会定时或每轮结束后自动轮询。可在 `~/.leash/config.json` 中配置 `"quota": {"enabled": false}` 关闭。
* **Turbo**（沿用 Antigravity 命名，因此仅限 Antigravity）：在 bridge 侧按对话维度的自动批准开关（叠加 `agy` 的 `auto` 模式）。Claude Code 则原样展示自己的模式名称（Manual、Accept edits、Plan、Auto、Bypass permissions，来自 `claude-agent-acp`）；bridge 会拒绝针对 Claude Code 的 `bridge/setAutoApprove`。
* `agy` 的工具调用使用 `name` / `args` 而非 ACP 标准的 `title` / `rawInput`，bridge 会将其统一规范化。
* **内联小组件（生成式 UI）**：`agy` 将 HTML 文件写入 `~/.gemini/antigravity/brain/<id>/`，并在回复中插入 `<agent-embed src="file:///…/x.html"></agent-embed>`。App 通过 `bridge/readFile` 读取后，在沙箱 WebView 中运行（iOS：`WKWebView`，`Views/AgentEmbedView.swift`；Android：`WebView`，`ui/embed/`），完整复刻 `agy` 的宿主契约：主题 CSS 变量、`html.light/.dark` 与 `themechange` 事件、仅放行 gstatic Tailwind 脚本的 CSP、自适应内容高度（内联最高 800 pt / dp，点击 ⤢ 可全屏）。可在 Chromium 中用 `node tools/embed-preview.mjs <widget.html> out.html dark` 预览包装效果。

### Claude Code（第二个 Agent，可部署在另一台主机）

bridge 可以同时驱动多个 ACP Agent（在 `~/.leash/config.json` 的 `agents` 中配置）。Claude Code 既可以跑在 bridge 所在的同一台电脑上（见上文），也可以像作者的日常配置那样跑在另一台 Ubuntu 主机上，由 Mac 上的 bridge 通过 zsh 函数（SSH 包装）连过去：

```
bridge ──zsh -c 'source ~/.zshrc; <fn> …/bridge/remote/run-acp.sh'──▶ claude-agent-acp ──▶ 官方 claude CLI
```

* 远端主机初始化（只需执行一次）：`mkdir -p ~/.leash/claude-acp && cd ~/.leash/claude-acp && npm i @agentclientprotocol/claude-agent-acp`
* 在 bridge 所在电脑配置：`node src/index.mjs add-claude --fn <zsh-function> --repo <远端主机上的 Leash 目录> --home <远端主机的家目录>`（或者 `add-claude --repo <path> --home <home> ssh -T user@host`），然后重启 bridge。没有默认假设，`--repo` 与 `--home` 均为必填项；加上 `--code-mode` 可恢复以 Claude Code 实验性 Code Mode 启动的会话。若不带参数执行 `add-claude`，则会在 bridge 本机配置 Claude Code（适配器位于 `~/.leash/claude-acp`）。`remove-claude` 可将其从配置中移除。
* `bridge/remote/helper.mjs` 运行在 Agent 所在主机上，负责目录浏览、文件读写/上传以及额度查询（`claude -p /usage`：本地命令，消耗 0 token）。
* 以 Code Mode 启动的会话通过 `_meta.claudeCode.options` 传入 `settings.env.CLAUDE_CODE_EXPERIMENTAL_CODE_MODE=1` 来恢复。
* 适配器使用该主机上你自己的 Claude 登录态（`run-acp.sh` 中会取消设置 `ANTHROPIC_API_KEY`）。
* **Subagents（子 Agent）**：bridge 声明了 `clientCapabilities.subagents`，因此 `claude-agent-acp` 会把每个 Agent/Task 子任务作为子会话运行，并通过 `subagent_update` 通知（加载会话时也会回放子会话历史）。bridge 会用 `_meta.leash.subagent = <child id>` 将子会话更新归档到根对话下，并将子会话的权限/提问请求挂在根对话上（`request.subagent = {sessionId, title, description}`），同时丢弃会覆盖根对话状态的更新（命令列表、用量、模式、标题）。后台子 Agent 在主请求返回后仍可继续运行；此时根对话依然算忙碌状态，因此空闲清理与锁检查都不会关闭它，回合也不会提前结束：`turn` 广播会携带 `subagents`（仍在运行的数量，配合 `running`），`turn_end`（Bark 推送）会等到最后一个子 Agent 结束才触发，App 也会一直保留转圈动画、灵动岛（iPhone）和完成通知。聊天界面会在输入框上方停靠正在运行的子 Agent 托盘。
* **他端忙碌锁**：当 Claude Code 正在另一处（终端或 Claude 桌面端；session 文件 `status` 不为 `idle`）*运行回合*时，手机端无法打开该会话或发送消息（报错 `-32020`，列表页显示锁图标）；仅仅在终端打开但处于空闲状态并不会锁定。`helper.mjs locks` 会读取 Claude Code 的 `~/.claude/sessions/<pid>.json`（非公开接口；仅当对应 PID 在 helper 所属 PID namespace 中存活且启动时间一致时才算有效持有；`sdk-*` 条目是 bridge 自身启动的）。如果上次 bridge 加载后有其他客户端修改过该会话，bridge 会释放本地缓存并在发送提示词时返回 `-32021`，App 会自动重新加载并重发。检查出错时默认放行（fail-open）。可在对应 Agent 配置中设置 `"lock": false` 关闭。

**Antigravity 忙碌锁**（`bridge/src/agylock.mjs`，运行在本地电脑；缺少 `lsof` / `sqlite3` 时以及 Windows 上均关闭）：当桌面端 App 正在某个对话里*运行任务*时，手机端会被拒绝操作。判定信号（通过 `tools/probe-agy*-mac.sh` 探测所得）：桌面端 `language_server` 会为每个已加载的对话保持打开 `~/.gemini/antigravity/presence/<id>.lock`（直到退出 App），同时 `conversation_summaries.db` 在整个回合期间保持 `status = CASCADE_RUN_STATUS_RUNNING` / `not_fully_idle = 1`。当存在非本 bridge `agy acp` 进程树持有的 presence 锁 **且** 数据库状态忙碌时，即判定为被占用。整个过程完全只读：使用 `lsof +d`、`ps` 和 `sqlite3`（若在线库无法只读打开则回退到临时副本）。当桌面端回合结束时，bridge 会推送 `session_unlocked`，App 自动重新加载该对话。

## iPhone

基于 iOS 26 与 SwiftUI。打开 `ios/Leash.xcodeproj`，为各个 target（`Leash`、`LeashLiveActivity`、`LeashWatch`）选择你的签名团队（免费的个人开发账号即可，描述文件有效期 7 天），真机运行后前往 设置 → *扫描配对二维码*。所有 Bundle ID 均派生自同一编译设置 `LEASH_BUNDLE_ID`。关于实时活动（Live Activity）、灵动岛与 Apple Watch App（手表通过 WatchConnectivity 经由 iPhone App 与 bridge 通信），请参见 `HANDOFF-personal-cert.md`。

后台保活：当有回合在运行时，由实时活动驱动的循环静音音频（`BackgroundAudio`）会保持 App 与连接存活；App 自行发送本地通知（支持允许/拒绝的请求通知、回合结束通知），App 被系统挂起后由 Bark 接管提醒。内置 Tailscale 需要额外一步编译步骤（见下文）。

## Android

基于 Kotlin、Jetpack Compose、Material 3 与 Leash 自研设计系统（`android/DESIGN.md`），单 Activity 架构，`minSdk 29`（Android 10）、`targetSdk 35`、`compileSdk 37`。功能与 iPhone 端基本对齐（不含手表），不依赖 Google Play Services 或 FCM（以 APK 侧载安装）。规划、阶段记录与已知问题见 `android/PLAN.md`、`android/PROGRESS.md`。

对苹果生态专属能力的替代实现：
* **前台服务**替代 `BackgroundAudio`：当本机发起的回合仍在运行或有待审批请求时保持 WebSocket 连接存活。如果离开 App 时 bridge 上仍有任意对话在运行（无论在哪一端发起），也会保持连接直到全部结束；空闲时离开则会在 15 秒后断开（设置 → *Agent 工作时保持后台运行*，默认开启；关闭后不再启用前台服务及常驻通知）。
* **本地通知**替代实时活动：运行中的常驻通知、高优先级 *需要你确认* 通道（通知栏直接点击允许/拒绝无需打开 App；提问类通知点击进入聊天）以及 *回合已结束* 通道；每次弹出本地通知都会上报 `bridge/noticed` 以避免 Bark 重复推送。在第一次启动回合时请求 `POST_NOTIFICATIONS` 权限。支持深层链接：`leash://pair`、`leash://session?id=…&agent=…`。
* **配对**（设置页）：扫码（CameraX + 内置 ML Kit，无需 Play Services）、粘贴配对链接，或手动输入 host、port 与 token。
* **文件**：支持通过系统照片选择器（JPEG，最多 4 张）或文件选择器添加附件；内置文本（带行号）、图片（可缩放）与 PDF 查看器，其他类型交由系统其他应用打开（打开方式 / 分享）。
* **上传上限 11 MB**（iOS 与 bridge 允许 25 MB）：OkHttp 会拒绝排队超过 16 MiB 的出站帧，而 `bridge/upload` 当前是单次 base64 调用。后续可在 bridge 侧做分片上传来解除限制，暂未实现。
* bridge 在 Tailscale / 局域网下使用明文 `ws://`，因此网络安全配置放行了明文流量。客户端请求头会携带 `X-Leash-Client: android`。

### 构建

使用支持 AGP 9.2 的 Android Studio 打开 `android/`，或在终端中构建（需要 JDK 17、含 platform 37 与 Build Tools 36 的 Android SDK，以及 `local.properties` 中的 `sdk.dir` 或环境变量 `ANDROID_HOME`）：

```bash
cd android
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # app/build/outputs/apk/release/app-release.apk
adb install -r app/build/outputs/apk/release/app-release.apk
```

| 构建类型 | ABI | 说明 |
|---|---|---|
| `debug` | 仅 x86_64 | 供模拟器使用，**无法安装在 arm64 真机上**；包含 Demo bridge（设置 → *Demo bridge*，无需连接真实 bridge 即可用预设脚本体验 UI）及模拟 Tailscale 节点；传 `-PleashIdSuffix=.x` 可并存安装第二份副本 |
| `release` | 仅 arm64-v8a | 开启 R8 裁剪与资源压缩，不做代码混淆（`-dontobfuscate`，保留可读堆栈）；存在 `signing/keystore.properties` 时自动签名，否则输出未签名包并警告；包含 Tailscale 节点时约 30 MiB |
| `releaseLike` | 仅 x86_64 | 将 release 的 R8 规则与裁剪应用在 debug 源码上，使用 debug 签名、包名后缀 `.releaselike`：修改混淆保留规则或新增反射代码后可在模拟器跑一遍（`./gradlew assembleReleaseLike`） |

**Release 签名**：整个 `android/signing/` 已被 git 忽略。`android/signing/keystore.properties` 包含以下字段（切勿提交真实密码）：

```properties
storeFile=leash-release.jks     # 相对于 android/signing/
storeType=pkcs12                # 可选，默认 pkcs12
storePassword=<password>
keyAlias=leash
keyPassword=<password>          # PKCS12 模式下 key 密码通常与 store 密码一致
```

可用如下命令一次性生成密钥库：`keytool -genkeypair -v -keystore android/signing/leash-release.jks -storetype PKCS12 -keyalg RSA -keysize 4096 -validity 36500 -alias leash`。
**务必备份 keystore 和 properties 文件**：签名不一致的 APK 无法覆盖更新已安装的 App（只能卸载重装并丢失数据）。执行 `./gradlew verifyReleaseSignature` 会构建 release APK 并打印其签名证书指纹（`apksigner verify --print-certs`）。

**测试**：`cd android && ./gradlew testDebugUnitTest`（JVM 单元测试：核心 reducer、基于假传输层和 MockWebServer 的 bridge 连接、驱动真实 `AppModel` 的 Demo bridge、通知策略、tailnet 控制器等）。`./gradlew compileReleaseKotlin` 可检查 release 桩代码编译；若要同时针对 release 桩代码编译单元测试，可执行 `./gradlew compileReleaseUnitTestKotlin -Pandroid.onlyEnableUnitTestForTheTestedBuildType=false`（AGP 9 默认不创建该任务）。

**Tailnet AAR**：内置 Tailscale 节点位于 Go 模块 `tailnet/`，由 `:tailnet` Gradle 模块调用 `gomobile bind` 编译（debug：`android/amd64`，release：`android/arm64`，API 29，Java 包名 `dev.leash.personal.tailnet`）。
构建需要固定版本的 NDK `27.2.12479018`（可通过 SDK Manager、`sdkmanager "ndk;27.2.12479018"` 或 `ANDROID_NDK_HOME` 指定）；Gradle 会自动下载固定版本的 Go 工具链（1.27.4，带 sha256 校验）和 `gomobile` 到 `android/.gradle/tailnet-toolchain`（执行 `clean` 不会删除；首次构建耗时几分钟）。目前 Go 压缩包仅固定了 `windows-amd64`：在 macOS / Linux 上构建时，请把对应平台的安装包和 sha256 补全到 `android/tailnet/build.gradle.kts` 的 `goArchives` 中，或关闭该节点单独构建。
通过 `-Ptailnet=on|off` 可强制开关：默认仅在检测到 NDK 时才会编译并链接该节点；未包含时 App 仍可照常编译，设置页会显示“当前构建未包含内置 Tailscale”。

## 内置 Tailscale（iPhone 与 Android）

有时手机无法启用系统级 Tailscale App（例如系统 VPN 槽位被其他 VPN 占用）。开启此选项后，Leash 会通过 Tailscale 的 `tsnet`（用户态网络栈）在进程内部直接加入 tailnet：不需要占用 VpnService / NetworkExtension，且只有 Leash 自身连接 bridge 的流量会走它。默认关闭；关闭时一切行为不变。

* **启用**：设置 → 打开 *内置 Tailscale*，填入 Tailscale Auth Key（`tskey-auth-…`，从管理后台生成），点击 *保存并连接*。在 *高级* → *Control URL* 中可指向自建的 Headscale 服务器。
  配对主机需填写 bridge 所在电脑的 Tailscale IP 或 MagicDNS 域名（`100.x.y.z` 或 `<your-mac>.ts.net`）；bridge 电脑必须在同一个 tailnet 中。
  Auth Key 仅在节点首次注册时需要；密钥保存在系统 Keychain（iOS）或由 Android Keystore 加密存储（Android），绝不会打进日志。节点在 Tailscale 后台显示为 `leash-ios` / `leash-android`；在设置中点击 *退出登录* 会离开 tailnet 并删除本地保存的密钥。
* **原理**：App 启动节点后会在本地监听 `127.0.0.1:<port>`，把每条连接通过 tailnet 转发给 bridge；App 自身连接 `ws://127.0.0.1:<port>/ws`。由于同一台设备上的其他应用也能访问该本地端口，因此鉴权依然依赖 bridge 的 Bearer Token。
  该节点仅在需要连接时运行（App 在前台、iOS 实时活动后台音频期间，或 Android 前台服务期间）。
* **体积**：Android release APK 约 30 MiB，其中 arm64 节点（`libgojni.so`）占 18.8 MB；未裁剪的 debug APK 约 98 MiB。iOS 侧 `Tailnet.xcframework` 在磁盘上占 64 MB（含真机与模拟器架构），未单独统计包体积增量。
  `tailnet/build-tags.txt` 裁剪掉了用不到的 Tailscale 功能（SSH、taildrop、drive、serve/funnel、ACME 等）以控制体积。
* **Android 构建**：参见上文“Tailnet AAR”。
* **iOS 构建**（需在装有 Xcode、iOS + 模拟器 SDK 及 Go 的 Mac 上，例如 `brew install go`）：

  ```bash
  cd tailnet && ./build-ios.sh      # 耗时数分钟：生成真机 + 模拟器架构 -> ios/Frameworks/Tailnet.xcframework 与 Tailnet.xcconfig（均被 git 忽略）
  open ../ios/Leash.xcodeproj       # 照常构建并运行 Leash scheme
  ```

  如果存在生成出的 `Tailnet.xcconfig`，`ios/Config/Leash.xcconfig` 会自动引入它；没有该文件时仍可正常构建，只是内置 Tailscale 功能被禁用并在设置页提示。
  Swift 侧相关代码在 `Core/TailnetNode.swift`（唯一导入该 framework 的文件）、`Core/TailnetController.swift` 和 `Views/TailnetSettingsSection.swift`。
* **Go 测试**：`cd tailnet && go vet ./... && go test ./...`（需 Go 1.27+；使用 Tailscale 内存中的控制面与 DERP 服务，无需外网）。API 与细节见 `tailnet/README.md`。

## 本地化

两个客户端均提供英文与简体中文（zh-Hans），默认跟随系统语言。iOS 使用 String Catalog（`ios/Leash/Localizable.xcstrings`、`InfoPlist.xcstrings`）；Android 使用 `values-zh-rCN/`（strings 与 plurals）配合 `locales_config`（en、zh-CN），支持 Android 13+ 的按应用语言切换。中文下应用名称为“拴绳”，英文为 “Leash”；`bridge`、agent、token、Turbo 和 Shell 保留英文。
来自 bridge 或 Agent 本身的名称（模式名、模型名、工具标题等）按原样显示。

## Bridge 协议（v1）

基于 `ws://<bridge>:8787/ws` 的 JSON 文本帧，携带 `Authorization: Bearer <token>`（以及用于 bridge 日志区分端的 `X-Leash-Client: ios|android`）。

* phone → bridge：`{op:"call", id, method, params}`（ACP 方法如 `session/new|list|prompt|cancel|set_config_option`，以及 `bridge/attach|detach|media|browse|readFile|restartAgent|shell|btw|unqueue|requeue`）、`{op:"reply", key, result}` 用于回复权限/表单请求。
* bridge → phone：`ready`、`result`、`update`（带 `epoch` + `seq` 的 `session/update`）、`request` / `resolved`（权限与提问，广播至所有设备）、`turn`（running/idle）、`shell`（`!` 命令运行中/结束）、`agent`（Agent 进程状态）、`queue`（等待当前回合结束后发送的消息队列）。
* 回合进行中发送 `session/prompt`：如果 Agent 声明支持 ACP steering（`initialize` 中的 `_meta.steering.supported`，如 Claude Code），bridge 会通过 `_session/steering` 将消息直接注入正在运行的回合（返回 `{steered:true}`）；否则（如 `agy`）会在 bridge 排队（返回 `{queued:true, id}`），并在本回合结束瞬间合并成一条提示词发出——其间 `running` 保持为 `true`。带 `interrupt:true` 会先入队并取消当前回合，从而紧接着发出（空 `prompt` 表示仅发送队列内容）。普通 `session/cancel` 会保留队列等待下次发送，也可通过 `bridge/unqueue {sessionId, id}` 撤回（返回原 `prompt` 内容块）或 `bridge/requeue {sessionId, id, prompt, index}` 放回队列（对应手机端的撤销）。`bridge/attach` 会返回当前 `queue`；Agent 摘要信息携带 `steering`、`turbo` 与 `btw` 能力标志。
* `bridge/attach {sessionId, epoch, sinceSeq}`：断线重连后只返回漏掉的事件，或返回一份压缩后的完整快照（JSON 不超过 2 MiB）。超出上限时，快照会按如下顺序从旧到新裁剪内容：将过长工具输出截断至 16 KB；精简子 Agent 的工具调用；省略子 Agent 的聊天正文（保留占位行及内部提示）；精简主对话的工具调用；最后才从最开头丢弃历史记录（最新事件始终保留）。如果开头有缺失（在此处或因 bridge 自身事件上限被裁剪），快照第一条会是一个携带 `_meta.leash.historyTruncated` 的 `notice`。增量补齐（catch-up）只做长工具文本截断；若仍不够，则直接返回全量快照（`reset: true`）。
* 图片传输：推送给手机的更新里绝不直接内嵌图片字节。图片内容块会以 `{type:"image", mimeType, ref, size, width?, height?}` 下发（`ref` 为 base64 哈希；`size` 为字节数；PNG、JPEG、GIF、WebP 会附带宽高），当图片出现在屏幕上时由手机端按需拉取。`bridge/media {sessionId, ref, offset?}`（即便是子 Agent 的图片也传根会话 ID）返回 `{ref, mimeType, offset, total, data}`：每次从 `offset` 起最多返回 131072 个 base64 字符；客户端循环在 `offset + data.length` 处继续请求直到 `total`，再整体解码。参数错误返回 `-32602`，不可用返回 `-32004`（bridge 启动后未加载过该会话，或已被日志淘汰）。用户发送的提示词及 `bridge/unqueue` 返回的 `prompt` 中仍直接包含内联图片。
* `bridge/readFile {path, sessionId?, mode?, maxBytes?}`：读取文本（默认，前 512 KB）或 `mode: "base64"`（整文件转为 `data`，上限 32 MB，超出返回 `-32007`）；相对路径基于对应会话目录解析。所有读取均限制在 `$HOME`（Windows 下为 `%USERPROFILE%`）内。
* `bridge/noticed {key}`：告知 bridge 手机已针对该待处理请求弹出本地通知（实时活动 / 本地通知），因此 Bark 可以跳过推送。
* `{op:"reply", id, key, result}`：携带 `id` 时 bridge 会应答 `{op:"result", id, result:{ok:true}}`（若已被其他端处理则返回 `-32002`）。
* `bridge/upload {sessionId, name, data}`：将手机上传的文件（≤ 25 MB，base64）保存到 Agent 所在主机，返回 `{path, uri, name, size, mimeType}`。

## 文件功能

* **附件上传**（输入框 + → *选择文件*）：任何文件都会先上传至 Agent 所在主机——`~/.leash/uploads/<sessionId>/<name>`（由 `helper.mjs upload` 通过 stdin 读取 base64 并校验字节数；远端 Claude Code 走 SSH，本地直写）——随后在提示词中附带指向它的 `resource_link`（`file://…`），由 Agent 用自己的工具打开。文件名保留 Unicode 字符，但会去掉空格和括号（Claude Code 会将该链接记为 `[@name](file://…)`，App 重载会话时也靠此格式识别）。上传文件不会自动清理。Android 端单文件上限为 11 MB（见“Android”章节）。
* **查看与下载**：点击文件（工具调用路径、附件或正文里指向本地路径的 Markdown 链接）会打开查看器：纯文本带行号展示，其他文件在 iOS 使用 QuickLook，在 Android 交给系统其他应用；通过分享 → *存储到“文件”* / AirDrop（iOS）或系统分享面板（Android）可导出完整文件。
* **内联图片**：回复中的 `![alt](src)`、`<img src>` 以及纯文本里列出的图片路径（行内代码、链接、裸 `/…` / `~/…` 路径）都会直接渲染在消息流中——远程 URL 直接加载，宿主机路径通过 `bridge/readFile` 加载；仅当路径确实能加载成功时才展示图片。

## `/btw` 旁白提问（Claude Code）

在输入框中输入 `/btw <问题>`（或点击 + → *旁白提问*）可以在不污染当前对话记录的前提下针对该会话提问，且支持在主回合运行期间使用，行为与 Claude Code 自身的 `/btw` 一致。`bridge/btw {sessionId, question}` 会在主机上执行 `helper.mjs btw`：在会话目录中运行 `claude -p --resume <id> --fork-session --no-session-persistence --tools "" --strict-mcp-config --max-turns 1`，沿用当前会话的模型与 Code Mode 设置。该分叉会话读取磁盘上已有的对话记录，不使用任何工具或 MCP 服务器直接作答一次，且结果绝不落盘保存，因此不会干扰原对话、当前回合或锁定状态；也不会记录到聊天流或同步给其他设备（仅发起提问的手机收到 `{text, durationMs, model}`）。答案以浮层卡片展示在输入框上方，关闭即消失。由于这是一次独立请求，它会重新读取上下文，不会命中主回合的 Prompt Cache。`agy` 没有无痕分叉能力，因此 Antigravity 不支持 `/btw`。

## `!` Shell 模式

在输入框中输入 `!cmd`（或点击 + → *运行 Shell 命令*）会在 Agent 主机的当前会话目录下，通过用户的登录 Shell（Windows 下为 PowerShell）执行 `cmd`——远端 Claude Code 通过 SSH 调用 `helper.mjs exec`，本地则直接在本机执行——关闭 stdin、超时 120 秒、标准输出与标准错误各自截断 30,000 字符。`bridge/shell {sessionId, command}` 会按 Claude Code 原生记录格式（`<bash-input>`、`<bash-stdout>` / `<bash-stderr>`，正文做 HTML 转义）写入日志，使终端输入与手机输入的渲染完全一致，并在下一次 `session/prompt` 时把输出拼在最前面让模型看到，与 CLI 行为一致。Agent 正在运行回合期间会拒绝执行 `!` 命令。

## 测试

```bash
cd bridge && npm test             # 基于 test/mock-agent.mjs 运行 node --test，无需安装 agy（含在任意系统上模拟 win32 路径/平台逻辑的测试）
cd android && ./gradlew testDebugUnitTest
cd tailnet && go vet ./... && go test ./...
```
