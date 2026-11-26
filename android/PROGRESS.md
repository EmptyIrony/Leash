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
- UI is a shell only: `LeashRoot` (connection line + conversation list with running spinner, pending badge, unread dot) and `SettingsSheet` (paste link, address/port/masked token, Connect, Forget bridge, demo switch).

### Verified
- `android_test` unit: 126 tests, 0 failures (JsonTest, QuotaTest, PairingLinkTest, BridgeConnectionTest with a fake transport and virtual time, OkHttpTransportTest against MockWebServer incl. the full stack and a 401, ChatStoreTest, AppModelTest with a fake link, DemoBridgeTest in `src/testDebug` driving the demo through the real AppModel).
- Emulator, debug build installed with `android_build install=true`: launches, no AndroidRuntime/FATAL in logcat. Screenshots (scratchpad/shots): unpaired state, settings sheet, demo list (running spinners, pending badge, lock note). Switching Demo on and pairing another bridge both start from a clean live state (tested).
- Real bridge: pairing by deep link works (the link is entered through `adb shell am start -a VIEW`, token never written anywhere in the repo). Connecting never completed: the ts.net name resolves in the emulator to a fake-IP gateway address, TCP connects (to any port), and the connection is closed without a byte ("unexpected end of stream"); the same happens to a plain HTTP request to `/health` with netcat. So the path emulator -> bridge host is not reachable right now (bridge or Tailscale down, or the gateway can't forward). Not verified: ready, session/list and attach against the real bridge. After the check the app data was cleared (`clear_data`), so no token or host is left on the device. No bridge call was made (nothing connected).

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
- Phase 2b (done, see below): composer, queue tray, pending cards, pickers, command palette, "New conversation here", "Show on computer", subagent tray. "Run shell" ("!" mode) moved to phase 4 with the other composer extras.
- Fixed in 2b: expanding a step group while the list follows the bottom no longer scrolls its header away.
- Open folder sections are remembered across recreation but not across a process restart (iOS persists them); easy to move into DataStore.
- The live "working" cue is a subtle pulse on the header, not iOS's dot-matrix shimmer.
- Predictive back uses the NavHost defaults (fade + slide); not checked frame by frame.
- Material icon deprecation warnings (AutoMirrored variants of CallSplit, DriveFileMove, InsertDriveFile) are left for a cleanup.
- Relative times follow the system language (the emulator is Chinese); a bridge timestamp ahead of the phone's clock is clamped to "now".


## Phase 2b: Act — done (IME layout and a dropped connection not verified on the emulator, see below)

### What exists (package `dev.leash.personal.ui.chat`, plus small core changes)
- Composer (`Composer.kt`, `ComposerLogic.kt`): multi-line field (1 to 8 lines), placeholder by state (Ask Claude / Ask Antigravity / Steer the agent / Queue a message / "X is not running" / "Bridge offline"), disabled while the agent is down or the transcript is not attached. One circle is Send, Stop or (greyed) nothing; with a draft mid-turn a small Stop sits next to Send and touch-and-hold on Send offers "Interrupt and send" (also a TalkBack custom action). Ctrl/Cmd+Enter sends. Composer error line (a refused or failed send) with a dismiss. Drafts persist per conversation (on leave, on ON_STOP, cleared when empty). A queued message taken back lands in front of the draft. "Sending…" under the optimistic bubble after 2 s.
- Queue tray: "N queued" + "Interrupt and send" / "Send now", rows (tap = take back into the composer, cross or long-press menu = remove), the removed row stays in place as "Removed · Undo" for 6 s (20 s with TalkBack), "+N more", collapsed to its header under an approval or question tray.
- Request trays (`RequestTrays.kt`): permission (title, command preview, "Parser.kt +2 −1 · View diff" opening a sheet with the full diff, Deny / Always allow / Allow, stacked at font scale above 1.15) and question (options as radio / checkbox rows, "Other answer…", Skip / Submit, "Answer…" opening a bottom sheet with every question: choices, text, number, switch, the `_custom` free-text pairing; Skip and Submit stay pinned) plus URL elicitation (Decline / Done). "1 of 3" counter, a subagent's request names the subagent above the title and opens its transcript. Same stack in the subagent screen, filtered by lineage.
- Subagent tray ("N agents still running", first 3 rows open the subagent). Other-conversations banner above the transcript (distinct conversations, opens the first).
- Chips under the composer (hidden while a request tray shows): model (grouped bottom sheet by provider), thinking level (stepped slider sheet with blurbs), permission mode (menu; Turbo for Antigravity), context usage (menu with the numbers). Approve-everything modes (Turbo, Bypass permissions) ask once (AlertDialog); the confirmation is stored in DataStore (`Settings.turboConfirmed`/`bypassConfirmed`, `AppModel.needsApproveAllConfirmation/confirmApproveAll`).
- Command palette for a bare "/word" (agent's commands in its order, only while idle; picking inserts "/name " and shows "/name · hint" under the field; never sends).
- ⋮ menu: New conversation here (replaces the chat on the stack, Back goes to the list; only if the user is still on that chat), Browse files, Copy folder path, Show on computer (with "Already in Claude Code's /resume").
- Haptics: `AndroidHooks` (app package) implements `AppHooks.haptic` through the activity's decor view (send, stop, selection, warning when a request arrives, success after a turn of 8 s or more, error); phase 3 extends the same class with notifications.
- Pure, JVM-tested logic: `ElicitationSpec`/`ElicitationAnswers`, permission option split, `ComposerLogic` (send gating, glyph, placeholder, palette, picked command), `queueRows`.
- Wording: every user-facing and internal "Mac" in android/ is now "bridge" (strings, `bridgeName`, `BridgeUnreachable`, comments, test fixtures; the DataStore key changed from `macName` to `bridgeName`, so an old stored name is dropped).
- Fix from 2a: opening a step group or run while the list follows the bottom ends the following instead of scrolling the tapped header away; the follow effect also reacts to viewport changes (bottom bar growth, keyboard).
- Demo bridge additions (debug only): config options kept per session (picking an effort no longer overwrites the model), modes per session, a running parent with a subagent that asks for permission ("Index the repository"), prompt words trigger states: "permission" (+ "diff" for an edit with a diff), "questions" (multi-question form), "refuse" (error line), "locked elsewhere" (-32020), "changed elsewhere" (-32021 once, then reload + resend works).

### Verified
- `android_build install=true launch=…` succeeds, `android_test mode=unit`: 176 tests, 0 failures (151 + ElicitationTest 13, ComposerLogicTest 11, an AppModel test for the confirmations; DemoBridgeTest counts updated).
- Emulator, demo bridge, screenshots in scratchpad/shots (all by driving the real UI with adb input and uiautomator dumps): approval tray (3 buttons fit), question tray (select, Submit), steering send mid-turn, idle send, Stop (the "Stopped" line, bubble kept), queue tray (remove, row "Removed · Undo" seen; Undo itself and Interrupt and send checked), question form sheet (submit continues the turn), approval with diff sheet, Deny ("Okay, I skipped it"), model sheet, effort sheet (slider to High, model chip untouched), mode menu and Turbo confirmation (chip tinted), command palette and picked command line, error line for a refused prompt (draft restored), changed elsewhere (reloaded and resent, a turn ran), locked elsewhere (lock card, composer dimmed, draft restored), subagent request with attribution and tray, the subagent screen with its tray, New conversation here, other-conversations banner (opens it, Back goes to the list), expanding a step group keeps its header in place, dark mode with a request tray.
- Real bridge (read-only, LAN address): paired by deep link through adb, list loaded ("2 agents ready"), one idle conversation opened: transcript, model/effort/mode chips from the real config options. Nothing was sent, answered, switched or created, no taps in the composer area. App data cleared afterwards, logcat buffer cleared.
- logcat after the last session: no AndroidRuntime / FATAL entries for dev.leash.personal.

### Not verified / left
- IME layout: the emulator's only keyboard (Sogou) reports itself shown but never draws, so the composer above the keyboard (Scaffold with `imePadding`, bottom bar with `navigationBarsPadding`) is not seen on screen. A quick check on a real device or an AOSP-keyboard image is worth doing.
- Not connected: the offline placeholder and disabled composer are covered by `ComposerLogicTest` and share `isReady`, but a mid-chat drop was not staged on the emulator.
- Still phase 4 (placeholders or absent): attachments (the "+" menu, photo and file chips, `bridge/upload`), "!" shell mode (`AppModel.runShell` exists), /btw side questions and their tray (`/btw` typed now goes out as an ordinary prompt), quota chip and quota in the model sheet, file viewer behind paths in diffs and requests, full changes view.
- Emulator has a second app, `dev.leash.personal.design`, used by another agent: it can take the foreground between adb calls. Scripts here check `dumpsys window | grep mCurrentFocus` before tapping.
- The device locale is Chinese, so English plurals use the "other" form ("1 agents still running"); English devices are right.
- `MainActivity`/`LeashApplication` are the only places phase 3 needs to touch for the service and notifications.
