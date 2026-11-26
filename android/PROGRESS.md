# Leash for Android — progress

## Phase 1a: Scaffold — done

### What exists
- Gradle project in `android/` (Kotlin DSL, version catalog, wrapper 8.14.8 with verified jar/dist sha256), module `:app`,
  app id/namespace `dev.leash.personal`, name "Leash", minSdk 29, targetSdk 35, **compileSdk 36**.
- Single `MainActivity` (edge-to-edge) hosting Compose `LeashRoot` (empty shell: space gradient + "Leash / Not connected").
- `LeashApplication` (empty; will own AppModel in 1b).
- Theme: `ui/theme/LeashPalette.kt` (port of `ios/SharedDesign/LeashPalette.swift`, Compose-free) and `Theme.kt`
  (Material 3 light/dark ColorScheme from the palette, `LeashTheme.colors` for roles Material lacks, `LeashRadius`, Shapes).
  Light/dark follows the system; no dynamic colour. Window theme (`Theme.Leash`, day/night) paints the bg colour to avoid a flash.
- Manifest: INTERNET + ACCESS_NETWORK_STATE, cleartext allowed via `res/xml/network_security_config.xml`, no backup
  (`allowBackup=false` + data extraction rules), predictive back enabled, `leash://pair` and `leash://session` VIEW filters.
- Placeholder adaptive launcher icon (vector ring + lead); replace later if wanted.
- Unit tests: `LeashPaletteTest` (3 tests).

### Toolchain findings (important for later phases)
- Windows SDK has platform 36 (compileSdk 36 builds). It does NOT have 37.
- Versions in use: AGP 8.13.2, Gradle 8.14.8, Kotlin 2.3.21, Compose BOM **2025.12.01** (Material3 1.4.x), core-ktx 1.17.0,
  activity-compose 1.11.0, lifecycle 2.9.4, navigation-compose 2.9.5.
- **Do not bump to the newest BOM (2026.11.01) or core-ktx 1.19**: they require AGP 9.1+ and compileSdk 37. Stay on AGP 8.13.x / SDK 36.
- Applying `org.jetbrains.kotlin.android` is required with AGP 8.x (the `kotlin {}` block needs it).
- First build on the host downloads Gradle + dependencies (~7 min); incremental builds ~45 s; unit tests ~35 s.
- Emulator: 1080x1920, API 35 (confirmed via getprop).
- Commit trailer used: `Co-Authored-By: Claude Sonnet 5.5` (the executing model; PLAN.md names Opus 5.5).

### Verified (emulator)
- `android_build install=true launch=dev.leash.personal/.MainActivity`: BUILD SUCCESSFUL, installed, launched.
- `android_test mode=unit`: 3 tests, 0 failures.
- logcat: no AndroidRuntime/FATAL entries; Displayed MainActivity.
- Screenshot (light mode) shows the title and status text, status bar insets respected.
  Dark mode was not screenshotted.

### Known issues / left
- Dark mode not visually checked on the emulator.


## Phase 1b: Core port — done (real-bridge check blocked by the network path, see below)

### What exists (package `dev.leash.personal.core`, no `android.*` except DataStoreSettings, so it is all JVM-testable)
- `Json.kt`: null-safe accessors on kotlinx `JsonElement` (`u["a"]["b"].string`), builders (`jsonObj`, `j(...)`). `Quota.kt`, `PairingLink.kt` (`leash://pair|session|open`), `Time.kt` (ISO parse, pinnable `Clock`).
- Transport: `FrameTransport` (text frames) <- `OkHttpTransport` (headers `Authorization` + `X-Leash-Client: android`, 15 s ping) and `BridgeConnection` above it: call/result correlation with timeouts, BridgeError mapping (-32020 with holder, -32021, -32024, -32002), 401 = final, 10 s ready watchdog, backoff 1 to 15 s, generation guard, presence on ready and on change, pending calls failed on drop. `BridgeLink` is the interface the app and the demo bridge share.
- Models: `Models.kt` (items, ActivityGroup, TurnChanges, StepRowModel, ConfigOption, queue types, FileRef), `LineDiff.kt`, `ChatStore.kt` (reducers: update/turn/queue/subagent routing, echo adoption, displayItems + live timeline). Changes bump `ChatStore.version` (StateFlow); UI collects it and re-reads plain fields.
- `AppModel.kt`: all bridge-facing logic of AppModel.swift and AppModel+Remote.swift (ready/turn/request/resolved/queue/lock frames, session list, attach with catch-up, send incl. locked / changed-elsewhere retry, steering/queue/interrupt, unqueue + undo, config/mode/turbo, permissions and elicitations, browse/readFile, /btw, "!" shell, cli listing, remote `answer`, `noticed`, unread tracking, drafts). State is one immutable `AppState` StateFlow. Owned by `LeashApplication`.
- `AppHooks`: phase-3 seams (alert a request / turn end off screen, turn started/changed/aborted, haptics). All no-ops now; nothing for Live Activity, watch or BackgroundAudio was ported.
- Settings: `DataStoreSettings` (host, port, token, name, demo flag, drafts, lastSeen, markedUnread; no-backup via data extraction rules). Deep links `leash://pair` / `leash://session` handled in `MainActivity` (onCreate and onNewIntent; the intent is replaced afterwards so the token does not linger).
- Demo bridge (debug only: `src/debug` `DemoBridge`/`DemoScript`, `src/release` has a null stub): 6 sessions (finished refactor with thinking, tools, diff, markdown with code and an image link, stats; a running one with a permission request and a question; a running agy one with 2 queued messages; a subagent one; a locked one; an empty one). Answers every call the app makes; `session/prompt` streams a turn (steering for claude, queue for agy, interrupt, cancel, permission wait when the text contains "permission"). Settings > Demo bridge switches to it.
- UI is a shell only: `LeashRoot` (connection line + conversation list with running spinner, pending badge, unread dot) and `SettingsSheet` (paste link, address/port/masked token, Connect, Forget Mac, demo switch).

### Verified
- `android_test` unit: 126 tests, 0 failures (JsonTest, QuotaTest, PairingLinkTest, BridgeConnectionTest with a fake transport and virtual time, OkHttpTransportTest against MockWebServer incl. the full stack and a 401, ChatStoreTest, AppModelTest with a fake link, DemoBridgeTest in `src/testDebug` driving the demo through the real AppModel).
- Emulator, debug build installed with `android_build install=true`: launches, no AndroidRuntime/FATAL in logcat. Screenshots (scratchpad/shots): unpaired state, settings sheet, demo list (running spinners, pending badge, lock note). Switching Demo on and pairing another Mac both start from a clean live state (tested).
- Real bridge: pairing by deep link works (the link is entered through `adb shell am start -a VIEW`, token never written anywhere in the repo). Connecting never completed: the ts.net name resolves in the emulator to a fake-IP gateway address, TCP connects (to any port), and the connection is closed without a byte ("unexpected end of stream"); the same happens to a plain HTTP request to `/health` with netcat. So the path emulator -> Mac is not reachable right now (bridge or Tailscale down, or the gateway can't forward). Not verified: ready, session/list and attach against the real bridge. After the check the app data was cleared (`clear_data`), so no token or host is left on the device. No bridge call was made on the Mac (nothing connected).

### Decisions and caveats for later phases
- OkHttp queues at most 16 MiB of outgoing frames; `send` then fails. A base64 `bridge/upload` over about 12 MB of file needs chunking or another route (phase 4). Incoming frames have no limit (the bridge allows 48 MB).
- kotlinx-coroutines-test: `advanceUntilIdle()` does not run `backgroundScope` work. The tests use `advanceTimeBy(n); runCurrent()`.
- Core text ("Explored 3 files", "Needs your input", error texts) is English literals, not string resources; move them when the screens are built (2a).
- Model code assumes one thread: the app scope is `Dispatchers.Main.immediate`, OkHttp callbacks hop onto it.
- Haptics are the `Haptic` enum through `AppHooks.haptic`; mapping to `performHapticFeedback` is the UI's job. `queueUndoWindowMs` should be 20 s with TalkBack on.
- Versions added: kotlinx-coroutines 1.10.2, kotlinx-serialization-json 1.9.0 (no plugin needed), OkHttp 4.12.0, DataStore 1.1.7.
- Per `git add` rule only `android/` files were staged; commits carry the executing model's Co-Authored-By line.


## Phase 2a: Browse + read — done

### What exists (package `dev.leash.personal.ui`)
- Navigation (`LeashRoot`, Compose Navigation, string routes with URI-encoded ids/paths): list, folder, chat, subagent, new conversation, folder browser, project files, settings, scanner. A `leash://session` link or notification tap (`AppState.openRequest`) shows the conversation with the list under it. Transparent Scaffolds over the Leash gradient; text colour is provided at the root (a dark-mode bug otherwise).
- List (`list/`): "Needs you" first, then one collapsible section per agent + folder (first launch: only the newest folder open; "General" = the agent's home), "Show all (N)" past 5 (+7) rows with running ones always shown, agent filter (segmented, with several agents), search by title or folder, pull to refresh, unread dot, draft line, status glyph (hand / spinner / lock), long-press Mark as read/unread, long-press a header for collapse/expand all, status cards (not paired, failed, agent down with Restart, list error). `SessionGrouping` is pure and tested.
- New conversation (`newsession/`): agent, recent folders, folder browser (start here, git badge, folders, first 30 files); one `NewSessionController` shared by both screens. Project files (`files/`): folders push on the stack; files are listed only.
- Settings (`settings/`): QR scan (CameraX + ML Kit bundled barcode model, no Play services; permission flow, no-camera and denied states), paste link, manual address/port/token (token masked, show toggle), connection pill + per-agent status with Restart, Reconnect, Forget bridge (confirm), demo switch (debug). The Lock Screen / Watch sections have no Android counterpart yet (phase 3 adds the notification settings).
- Chat transcript (`chat/`, read-only): attach on open, `watch`/`unwatch` for presence, follows the newest output until you scroll up (jump-to-bottom button), loading / load error + Try Again / empty / locked (per holder) / stop-reason states, a bar saying N requests wait. Rows: user bubble (images, file chips, "!" shell input/output, slash commands via `UserSegment`), activity header ("Working · 12s" ticking, "Worked for 1m 3s", failed/stopped glyph, sub-line summary or live action, checklist ring + list, expandable step list with the 3 + 10 split past 30 steps, folded reads, thought/note rows), live segments, answer (markdown + attachments), stats line (tap for details), notice, plan card, "N files changed +a −d" row, subagent rows. Tool detail: command, a compact line diff (12 lines, expandable), text output, raw output, locations.
- Subagent screen: prompt quote, live line, checklist, flat step list, reply (`foldSubagent`, tested); nested subagents open the next screen.
- Markdown (`markdown/`): own parser (headings, lists, quotes, fenced code, GFM tables with alignment, rules, images, `<agent-embed>`, mentioned image paths) and own inline parser (bold, italic, strike, code, links, file paths in code spans become tappable); renderer in Compose (code blocks with copy, tables sized to their longest cell and scrolling sideways, links open in the browser).
- Provider icons: Claude, Gemini (with its gradients) and OpenAI marks as vector drawables from the iOS assets (`components/Common.kt`, `AgentIcon`, `ProviderIcon`).
- Strings: all new UI text is in `res/values/strings.xml` and `plurals.xml`; connection error texts in core now say "bridge" (iOS did the same). Still English literals in core, re-deferred: "Explored 3 files…", "Needs your input", "Working…"-style live actions, subagent secondary line, `BridgeError` messages.
- Compose skipping: core items mutate in place and rows receive the same instance every time, so a row that shows one calls `ObserveChat()` first, an *inline* composable that reads `LocalChatVersion` (provided by the screen from `ChatStore.version`) in the row's own scope; as a normal composable it would only re-run itself and the row would be skipped. Verified with a streamed turn in the demo (answer grows, completes, header goes Working to Worked, the list follows). Do the same in every new row of phase 2b.
- Dependencies added: CameraX 1.4.2 (core, camera2, lifecycle, view), ML Kit barcode-scanning 17.3.0 (bundled), material-icons-extended (from the BOM). Resolved on the host without trouble; debug APK is about 91 MB.
- Demo bridge: five more "Older conversation N" sessions in the Claude folder so "Show all" and the folder screen can be checked.

### Verified
- `android_build install=true launch=…` succeeds; `android_test mode=unit`: 151 tests, 0 failures (126 from 1b + MarkdownParserTest 12, UserSegmentTest 5, SessionGroupingTest 5, FormatTest, SubagentFoldTest in testDebug).
- logcat (checked without clearing after the last session, which ran the streamed-turn check, the list, settings, scanner, chats and search): no AndroidRuntime / FATAL entries.
- Emulator screenshots (scratchpad/shots, demo bridge unless noted): unpaired list, settings (top, status, demo), scanner (permission prompt, camera opening: the emulator's camera is a blank grey, so decoding a real QR is not verified), list with sections and Show all, folder screen, new conversation, folder browser, a new conversation created in the demo, finished chat (markdown, code, image placeholder, changes row, stats), group expanded with a diff, running chat waiting for approval, running agy chat, subagent parent + subagent screen, locked chat, empty chat, search, dark list and dark chat.
- Real bridge (read-only): paired by deep link through adb (token never written to the repo or here; app data cleared afterwards, logcat buffer cleared). The ts.net name still resolves to the proxy's fake IP and drops the connection from the emulator; the LAN address from the pairing note works. The list loaded ("2 agents ready", one folder with 32 conversations, one locked by Claude Code in a terminal), one idle conversation was opened and its transcript screenshotted (CJK markdown, inline code, bullets, changes row, user bubble render correctly). Nothing was sent, answered or created.

### Left / known issues
- Deferred to phase 4 on purpose, parsed or placeholdered now: file viewer (tapping a path, file chip or file row shows a snackbar), inline images (placeholder chip "Image · name"), `<agent-embed>` (placeholder), changes sheet and full diff view, quota rings.
- Phase 2b: composer, queue tray, pending cards (a bar only says N requests wait), pickers, command palette, "New conversation here", "Show on computer", Run shell. Pull the `openSubagent` trays from iOS's `SubagentsTray` with them.
- Expanding a step group while the list follows the bottom scrolls to the bottom and can hide the header you tapped.
- Open folder sections are remembered across recreation but not across a process restart (iOS persists them); easy to move into DataStore.
- The live "working" cue is a subtle pulse on the header, not iOS's dot-matrix shimmer.
- Predictive back uses the NavHost defaults (fade + slide); not checked frame by frame.
- Material icon deprecation warnings (AutoMirrored variants of CallSplit, DriveFileMove, InsertDriveFile) are left for a cleanup.
- Relative times follow the system language (the emulator is Chinese); a bridge timestamp ahead of the phone's clock is clamped to "now".


## Phase D1: Design system — implemented, built, unit-tested; never run on a device

Branch `android-design` (worktree `/config/workspace/Leash-design`), not merged: D2 starts after 2b lands.

### What exists
- `android/DESIGN.md`: what makes the iOS app feel good and the Android translation (colour tokens light/dark incl. the
  derived alphas, type scale, spacing, continuous-corner shapes, glass and its blur budget, elevation, motion springs,
  haptics, iconography, every component, native behaviour, app icon), reviewed by Fable (review log at the end).
- `ui/design` (new package, nothing existing uses it yet): `ContinuousShape` + pure `SquircleGeometry` (figma-squircle
  curve, smoothing 0.6, per-corner radii, RTL), `LeashShapes`/`LeashRadius`, `Tokens.kt` (`LeashDesignColors` incl.
  `accentControl` = iOS's AccentColor asset, `LeashTypography`, `LeashSpacing`, `Leash.colors`/`Leash.type`),
  `Motion.kt` (the four iOS springs converted exactly, transitions, `LocalReduceMotion`), `Indication.kt`
  (`LeashIndication` press-scale + dim instead of ripples, `Modifier.pressable`), `Haptics.kt` (touch vs event haptics,
  Warning waveform), `Glass.kt` (Haze 1.7.1 backdrop blur on API 32+, solid fallback, rim, `dropShadow`, soft top and
  bottom edges, `GlassScope`/`glassSource`), `Background.kt` (space gradient, glow, iOS's seeded starfield), `Live.kt`
  (shimmer band + LiveDots on one frame clock with a fixed-phase option, ring gauge, thin spinner), `Icons.kt`
  (`LeashIcons` by iOS meaning, outline/rounded Material glyphs + a square.and.pencil vector), `Theme.kt`
  (`LeashDesignTheme`: tokens, ripple off, indication, Material scheme mapped from the tokens, system-bar icons).
- `ui/design/components`: buttons (prominent/tinted/destructive/plain × L/R/S), glass circle buttons, the morphing send /
  stop circle + secondary stop, segmented control, M3 switch in Leash colours, composer chips, status pill, unread dot,
  needs-you badge, banner; grouped list (section header/footer, card, row with 1 px inset separators), session row,
  draft text, show-all, value/field/toggle rows; top bar (transparent, soft edge, centred or start title), large title,
  back button, bottom actions, scroll-to-bottom, `LeashSheet`, `LeashMenu`; user/shell/command bubbles, activity header
  (live dots + shimmer, done/failed/stopped/blocked), step rows + rail, diff block, code block, raised card + header,
  changes row, stats line; tray shell/header/command preview/actions (≤ 2 across, else stacked), option row, queue
  header/row/removed row, composer slab (normal/shell/side question) + status line + chip row; empty, loading, skeleton.
- Debug-only `DesignCatalogActivity` (`src/debug`, own manifest): pages index, colors, type, shapes, controls, list,
  settings, chat, activity, trays, states, sheet, icon; extras `page`, `theme` (light/dark), `phase` (pins the live band).
  Launch: `adb shell am start -n dev.leash.personal.design/dev.leash.personal.design.DesignCatalogActivity --es page chat --es theme dark --ef phase 0.4`
  (without the suffix: `dev.leash.personal/...`).
- Real app icon: `drawable/ic_launcher_background|foreground|monochrome.xml` generated from `design/icon/*.svg`
  (3.5 dp per pixel inside the safe circle, merged same-colour paths, half-pixel indigo shadow; themed-icon layer with
  three alpha levels); `mipmap-anydpi` adaptive icons use them. The old placeholder colour is gone.
- Build: `applicationIdSuffix` from `-PleashIdSuffix` in the debug build type (a second debug app beside the main one);
  `dev.chrisbanes.haze:haze:1.7.1` (the newest that keeps compileSdk 36 / activity 1.11; 1.7.2+ and 2.x need SDK 37).
- Unit tests: `DesignMathTest` (squircle construction sums/clamps/continuity, SwiftUI spring conversion, live band).

### Toolchain finding
- On the shared Windows host the Kotlin compile **daemon** hung twice (no output for 30 min, nothing packaged) while the
  other workspace was building. `-Pkotlin.compiler.execution.strategy=in-process` fixed it (compile 87 s, assemble 40 s);
  pass it on every build/test of this workspace. Never abort an `android_build` call client-side (it leaves Gradle running).
  Unknown whether the two hung runs (one aborted client-side, one ended by the 30 min MCP idle timeout) were cleaned
  up on the host; a leftover Kotlin daemon in the `leash-design` workspace is possible.

### Verified
- `android_build` assembleDebug (`project=leash-design`, `-PleashIdSuffix=.design`): BUILD SUCCESSFUL.
- `android_test` unit: 183 tests, 0 failures.
- Emulator: NOT verified. The shared emulator went offline at ~05:20 UTC (both the container relay and the Windows
  host's own adb report `device offline`) and was still offline at 05:56; nothing was installed, launched or
  screenshotted, so no component has been seen rendered. Next step: install with `-PleashIdSuffix=.design` and
  screenshot every catalog page in light and dark.

### What D2 must do
- Wrap the app in `LeashDesignTheme` (replacing `ui/theme`'s `LeashTheme`, then delete `ui/theme/Theme.kt`; keep
  `LeashPalette.kt`), and reskin screen by screen against `.shots`: list (large title, segmented control, grouped
  sections, session rows, bottom glass actions instead of the FAB), chat (top bar, `GlassScope` with the transcript as
  `glassSource`, bubbles, activity header with LiveDots + shimmer instead of the pulse, steps, diff/code blocks, bottom
  dock with composer slab + chips + trays and the soft bottom edge), subagent, settings (sheet-like grouped rows,
  `FieldRow` instead of outlined fields, M3 switch in Leash colours), new conversation, folder browser, scanner.
- Replace every Material default still visible: `TopAppBar`, `ExtendedFloatingActionButton`, `SegmentedButton`,
  `OutlinedTextField`, `OutlinedButton`/`TextButton`/`Button`, `Surface` cards, `AlertDialog` styling, `SearchBar`,
  pull-to-refresh indicator, filled `Icons.Filled.*` (use `LeashIcons`), ripples (theme provides none).
- Navigation transitions: push slide + fade, platform predictive-back pop (DESIGN.md §7); `isNavigationBarContrastEnforced = false`.
- Window/splash background per theme in `res/values*/themes.xml` (no white flash).
- Haptics: map `AppHooks.haptic` to `HapticsPlayer` (touch vs event); phase 3 adds the `VIBRATE` permission.
- Copy the `applicationIdSuffix` line and the Haze dependency when merging (they are part of this branch).
- Check every screen at font scale 1.3 and 2.0 and in dark mode; finish with a Fable review of light + dark screenshots.
