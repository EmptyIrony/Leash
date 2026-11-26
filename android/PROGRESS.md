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


## Phase 2b: Act — done (IME layout not verified on the emulator, see below)

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
- Emulator, demo bridge, screenshots in scratchpad/shots (all by driving the real UI with adb input and uiautomator dumps): approval tray (3 buttons fit), question tray (select, Submit), steering send mid-turn, idle send, Stop (the "Stopped" line, bubble kept), queueing from the composer ("3 queued"), taking a queued row back (text lands in the composer, count drops), remove and Undo (row "Removed · Undo", Undo restores both rows), the queue tray's Interrupt and send, touch-and-hold on Send with a draft mid-turn then the "Interrupt and send" item (queue and draft go out as one message, new turn runs), question form sheet (submit continues the turn), approval with diff sheet, Deny ("Okay, I skipped it"), model sheet, effort sheet (slider to High, model chip untouched), mode menu and Turbo confirmation (chip tinted), command palette and picked command line, error line for a refused prompt (draft restored), changed elsewhere (reloaded and resent, a turn ran), locked elsewhere (lock card, composer dimmed, draft restored), not connected (unpaired, `leash://session` link: chat opens with a dimmed composer "Bridge offline…"), subagent request with attribution and tray, the subagent screen with its tray, New conversation here, other-conversations banner (opens it, Back goes to the list), dark mode with a request tray.
- Scroll fix: in a scrolled list that follows the bottom (a stopped turn, header "2 steps" at y 1035) tapping the header leaves it at y 1035 while the steps open below it.
- Real bridge (read-only, LAN address): paired by deep link through adb, list loaded ("2 agents ready"), one idle conversation opened: transcript, model/effort/mode chips from the real config options. Nothing was sent, answered, switched or created, no taps in the composer area. App data cleared afterwards, logcat buffer cleared.
- logcat after the last session: no AndroidRuntime / FATAL entries for dev.leash.personal.

### Not verified / left
- IME layout: the emulator's only keyboard (Sogou) reports itself shown but never draws, so the composer above the keyboard (Scaffold with `imePadding`, bottom bar with `navigationBarsPadding`) is not seen on screen. A quick check on a real device or an AOSP-keyboard image is worth doing.
- A connection that drops while a chat is open was not staged (only the never-connected case was); both use the same `isReady` gate.
- The question sheet's number field keeps the typed text (so "1." can become "1.5"); no demo field exercises it.
- Still phase 4 (placeholders or absent): attachments (the "+" menu, photo and file chips, `bridge/upload`), "!" shell mode (`AppModel.runShell` exists), /btw side questions and their tray (`/btw` typed now goes out as an ordinary prompt), quota chip and quota in the model sheet, file viewer behind paths in diffs and requests, full changes view.
- Emulator has a second app, `dev.leash.personal.design`, used by another agent: it can take the foreground between adb calls. Scripts here check `dumpsys window | grep mCurrentFocus` before tapping.
- The device locale is Chinese, so English plurals use the "other" form ("1 agents still running"); English devices are right.
- `MainActivity`/`LeashApplication` are the only places phase 3 needs to touch for the service and notifications.

## Phase 3: Background + notifications — implemented and committed; the emulator hung at the end, so the closing cleanup is still to do (see "Emulator state")

### What exists
- **Notifications** (`notify/LeashNotifier.kt`, wording in `core/NotificationContent.kt`, ios `LocalNotifier.swift`): three channels, created at app start: *Needs you* (high), *Turn finished* (default), *Working in the background* (low, the service's). A permission request or a question off screen posts a "Claude Code needs approval" / "has a question" notification (conversation as sub text, tool title and command in the big text, 300 chars), private on the lock screen with a public stub. Approvals carry **Allow** (allow once, needs the device unlocked) and **Deny** (reject once), never "always"; without an allow-once option (or for a question) a tap just opens the chat. A turn that ended (not a Stop you pressed) posts "finished / hit an error / stopped" with "Took 1m 4s". Every alert returns true to `AppModel` only when notifications are enabled **and** that channel is not blocked (`canAlert`), and only then `bridge/noticed` goes out (request: `key`; turn end: `turnEnd:true`), so Bark stays in charge otherwise.
- **Allow / Deny without opening the app** (`NotificationActionReceiver`, not exported, immutable unique PendingIntents): `goAsync()`, waits for the stored settings (the process may have been started for this), connects, `AppModel.answer`, all bounded to 8.5 s. The notification then becomes "Allowed" / "Denied" for 6 s (the bridge's `resolved` frame does not wipe it), "Couldn't reach the bridge" / "Couldn't send your answer · Open Leash to answer." on failure, and goes away if it was already answered. A request answered in the app, elsewhere, or missing from a new `ready` loses its notification (`requestResolved`, `requestsReconciled`); opening a conversation clears its "finished" one (`conversationSeen`); a different bridge clears all (`reset`).
- **Foreground service** (`notify/LeashService.kt`, type `specialUse` + the subtype property, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `START_NOT_STICKY`) driven by `BackgroundController` and the pure `BackgroundPolicy`: it runs while a turn this phone started works or a request that arrived live waits, and for 3 minutes after you leave the app; in the foreground only while something is busy. Its notification is the **ongoing one**: "Claude Code is working" (indeterminate progress, the system's chronometer for the elapsed time, nothing ticks in the app) or "needs approval / has a question" when a request waits (that one takes the focus, as on iOS), conversation as text, "+N more" for others, tap opens the chat. Updates only when the content changed. 15 s after nothing needs the connection and the app is off screen the link is released (`releaseConnection`); coming back reconnects. A start the system refuses (Android 12+, from the background) is caught and retried when the app is on screen. API 36: `setRequestPromotedOngoing(true)` (Live Update) behind `Build.VERSION.SDK_INT >= 36`, manifest permission `POST_PROMOTED_NOTIFICATIONS`.
- **Deep links and one activity**: `MainActivity` is `singleTask` (checked with dumpsys: one instance after notification taps and links). Notification taps are `leash://session?id=…&agent=…` VIEW intents; the existing link handling opens the chat on a cold start too (checked after the process was killed).
- **Network change** (`LeashApplication.watchNetwork`, pure `NetworkChange`): a different default network, or the same one back after a loss, makes `AppModel.networkChanged()` drop the socket and reconnect right away (`BridgeLink.reconnect()`); the first callback is ignored.
- **Permission**: `POST_NOTIFICATIONS` is asked once, when you start your first turn (`AppHooks.askNotificationPermission`, the activity launches it; remembered in DataStore). Settings has a *Notifications* section (`ui/settings/NotificationSettings.kt`): on / off / some kinds off, *Allow notifications*, *Notification settings* (the system's per-channel page) and the switch *Keep running while an agent works* (`Settings.keepAlive`, default on; off = no service, no ongoing notification, no grace).
- **Process death**: the service is not sticky. A killed process starts clean and `ready` brings it current; request notifications that were already posted stay and their Allow / Deny start the process through the receiver. Turns that ended while the process was dead produce no local notification (Bark covers them, since nothing said `noticed`). The ongoing notification is not rebuilt after a death.
- **Plural quirk fixed**: plurals are resolved with English rules (`ui/components/Plurals.kt`, `pluralText`), so "1 agent still running" is right on a Chinese-locale device.
- Demo bridge (debug): a prompt containing "slow" waits 8 s before the request and before the turn ends, so you can leave the app first ("permission slow" for an approval, "slow" for a finish).

### Verified
- `android_test mode=unit`: 211 tests, 0 failures, run on the final code (new: NotificationContentTest, BackgroundPolicyTest (policy, focus, tracker), NetworkChangeTest (+ platform names), AppModelTest additions: no `noticed` when not alerted, hooks on ready / seen / reset, permission asked once, keepAlive stored, network change and release, awaiting settings, a cold-process answer that waits for the settings and the `ready` frame and then sends the confirmed reply, and the already-answered path).
- `android_build install=true launch=…`: BUILD SUCCESSFUL, installed, launched (the last install of the final commit, which only adds a log line, timed out when the emulator was already hung; the same code compiled and the tests ran). Logcat: an early scan after the first runs showed no AndroidRuntime / FATAL (that command also printed "Segmentation fault", which came from the adb shell pipe, not from the app); later scans were narrow greps (`LeashBackground`, `ForegroundServiceStartNotAllowed`, process start lines) with no hits. A full crash scan after the last session was not possible (see "Emulator state").
- Emulator (API 35), demo bridge, driven with adb (screenshots in scratchpad/shots, `dumpsys notification --noredact` and `dumpsys activity services` for the content): the system permission prompt at the first turn; "permission slow", HOME, ongoing notification with chronometer and the service foreground (`types=0x40000000`), request notification (high, two actions) with Allow tapped in the shade → "Allowed", turn continues and finishes → "finished · Took 48s" with the service in its grace ("Connected to the bridge"); Deny the same ("Denied"); tapping "finished" or the request opens the chat in the single activity; answering in the app removes the request notification; the process killed (`run-as … kill -9`) with a request pending → ongoing notification gone, request notification stays, Allow starts the process for the broadcast (the demo restarts empty, so the outcome is "already answered" and the notification goes); the service stops 3 min after leaving with nothing running (checked after 3:05); leaving the app idle starts the grace service from `onStop` on API 35; the *Keep running* switch off = no service while the request notification still comes; "1 agent still running" and "Ran 1 agent" on the Chinese-locale emulator.
- Real bridge, read-only (LAN address, paired through a deep link with adb; token never written to the repo or here): the app was already installed with demo data, so `POST_NOTIFICATIONS` was revoked with `pm revoke` before pairing (so nothing could alert or say `noticed` and swallow a Bark push). The list loaded ("2 agents ready"), leaving and returning kept / re-established the connection, the grace service ended after 3 min and the app was connected again on return (it was not checked whether the link had been released in between). Nothing was sent, answered or created. **App data was NOT cleared afterwards** (see below).

### Emulator state (read this first when you pick the emulator up)
- The last on-device experiment, `svc wifi disable; sleep 6; svc wifi enable` on the shared emulator, left its guest unresponsive: adb shows "device" but every shell / exec-out / install (also from the Windows host's own adb) times out, and a fresh `adb connect` reports "device offline". It was still down 23 minutes later (05:22 against the toggle at 04:59). It needs a restart on the Windows host.
- The emulator then still holds the **real bridge pairing (host, token) in this app's private data** and has `POST_NOTIFICATIONS` revoked. After the restart run, before anything else: `android_adb clear_data dev.leash.personal` (or `pm clear dev.leash.personal`) and `logcat -c`. Then start the app once and confirm the unpaired screen and an empty `AndroidRuntime|FATAL` logcat.
- Never toggle Wi-Fi or airplane mode on the shared emulator again.

### Not verified / left
- **Android 16 Live Update** (promotion, status-bar chip): the emulator is API 35 and compileSdk 36.0's android.jar has neither `Manifest.permission.POST_PROMOTED_NOTIFICATIONS` nor `Notification.EXTRA_REQUEST_PROMOTED_ONGOING` (they are in 36.1); the code uses `NotificationCompat.Builder.setRequestPromotedOngoing` and the permission name from the docs. Notification must also be Standard / BigText style, ongoing, with a title: it is.
- **A real network switch on the device**: the one attempt hung the emulator (see "Emulator state"); the logic is covered by `NetworkChangeTest` and `AppModelTest` only. The reconnect on a network change logs `LeashNetwork` when it fires.
- Allow on a **locked** device (`setAuthenticationRequired`), the lock-screen public stub, Do Not Disturb, and a real device's OEM background limits are untested.
- Heads-up behaviour depends on the channel the user keeps: a user who silences *Needs you* in the system settings makes `canAlert` still true (the channel exists), so `bridge/noticed` goes out although the alert was silent; importance NONE is detected, not "silent".
- The ongoing notification follows only turns this phone started (as the iOS Live Activity), plus requests that arrived live; a turn started on the computer is covered by the grace service and Bark only. A request that sits unanswered keeps the service up until it is answered or the setting is turned off.
- Notification and settings texts are in `strings.xml`, the notification wording in core is English literals like the other core texts. The notification icon is the placeholder ring-and-lead; the design pass should replace it.

## Phase D1: Design system — implemented, built, unit-tested; never run on a device

Merged into `main` (5e21836); first seen on the device in D2 (below).

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


## Phase D2: Reskin — done

### What changed (every screen now uses `ui/design`; the old `ui/theme` is deleted)
- Catalog first seen on the device (light + dark, fixed shimmer phase) and fixed: `Hairline` used `Dp.Hairline` as a height
  (0 dp, no separators anywhere) and now draws 1 px; the top soft edge hides behind a resting large title (it smeared
  "Leash"); the chip row scrolls on to the screen edge; dark prominent buttons / send / shell circles use `onAccent`
  (#07080D; white was 2.7:1), a disabled prominent button turns neutral, dark segmented thumb #3A3D4B (Fable).
- App: `MainActivity` in `LeashDesignTheme`, transparent bars incl. 3-button navigation
  (`isNavigationBarContrastEnforced = false`), window + splash background = bgBase per theme (`values*/themes.xml`);
  `LeashRoot` on `SpaceBackground` with the push (slide 30% from the end + fade, layout spring) and pop (scale 0.9 +
  fade) transitions; core haptics go through `LeashHaptics` (Warning / Error as event vibrations, the rest touch).
- Screens: list + folder (large title handing over to the inline title, segmented filter, inset-grouped lazy sections
  via `Modifier.groupItem`, design `SessionRow`, bottom glass search / compose circles instead of the FAB, bottom
  `SearchField` instead of the Material SearchBar, `LeashRefreshBox`); settings / notification rows / scanner
  (grouped rows, `FieldRow` instead of outlined fields, status pill, `ToggleRow`, `LeashDialog` instead of AlertDialog,
  glass back circle over the camera); new conversation, folder browser, project browser (`LazyScreen` /
  `ScrollScreen` in `ui/components/Screens.kt`); chat (floating top bar + overflow `LeashMenu`, banner, transcript
  under a glass dock: trays, 48 dp composer slab, chips, scroll-to-bottom glass circle, `LeashSnackbar`); transcript
  (design bubbles, activity header with LiveDots + shimmer instead of the pulse, step rows on a drawn rail, design diff
  blocks, sunken output, raised plan card, stats line, markdown on the reading scale, inline code backgrounds painted
  from the text layout with rounded insets: `InlineText`); trays and sheets (approval with two-across / stacked 48 dp
  Leash buttons, question option rows with radio/checkbox semantics, question sheet with `TextArea` + `LeashSwitch`,
  diff sheet, model / thinking sheets, mode menu with checks, context ring chip, command palette, subagent tray);
  subagent screen (prompt quote, live line with dots + shimmer).
- Design system additions: `groupItem`, `SearchField`, `LeashRefreshBox`, `LeashDialog`, `LeashSnackbar`, `TextArea`,
  `InlineText`, `TextStyle.capScale` (top bar, banner and segmented labels grow only to 1.3×), `ComposerSlab` over a
  `TextFieldValue` with a field modifier, `QueueRow` with icons / long-press menu, checkable `LeashMenuItem`,
  `StepRail` drawn behind its rows (the IntrinsicSize version crashed when a diff opened), `LocalOnSheet` (cards on
  dark sheets use the panel fill), `softBottomEdge(blur = false)` for the list.
- Notifications: accent colour (`R.color.leash_accent`) on every notification; the status icon is the app's pixel lead.
- `LeashPalette` and its test moved to `ui/design`; `DESIGN.md` updated (paths, onAccent labels, dimmer dark stars
  12–35%, review log).

### Verified
- `android_build install=true`: BUILD SUCCESSFUL; `android_test` unit: 220 tests, 0 failures.
- Crashes: one during the work (StepRail asked a lazy diff for intrinsics when a step opened; fixed, re-checked). The
  crash buffer (`logcat -b crash`, cleared right after that fix) holds no `dev.leash.personal` entry at the end; its only
  entries are native segfaults of `uiautomator` (the adb UI dump used to drive the emulator).
- Screenshots (scratchpad/shots/d2, demo bridge): every catalog page light + dark; list (rest, scrolled, search),
  folder screen ("Show all") and its bottom search, settings, scanner (camera permission state, after the status-bar
  change), new conversation, folder browser, project browser (from the chat ⋯ menu), the ⋯ menu, empty and locked
  chats, finished chat (top with steps + diff, bottom with changes row + stats), a streamed turn (Working with live dots,
  rows update, turn ends), permission tray, permission with "View diff" and the diff sheet, question tray (selected
  option), queue tray, subagent parent + subagent screen, command palette, model sheet, thinking sheet, mode menu, the
  Bypass confirmation dialog, the "file viewer later" snackbar; final set in light and dark (`F-*.png`; dark also the
  open diff); font scale 1.3 and 2.0 (list, permission tray, then again after the fixes). Side-by-side with `.shots`
  (`*-vs-ios.png`).
- Notifications: `dumpsys activity services` shows the foreground notification with `color=0xff4b50e6` (the accent).
  The shade itself was not seen with a Leash notification (the permission prompt was denied by a mis-tap; after
  `pm grant` no new notification was posted before the check), so the new pixel status icon is unverified on screen.
- Not reached in the demo: the multi-question sheet (needs a "questions" prompt; its components are the same as the
  tray's and the diff sheet's) and the dark changes row (same component as light).
- Real bridge, read-only (LAN address via deep link, notifications revoked first, nothing sent / answered / created):
  list loaded ("2 agents ready", 32 + 131 + … conversations), one finished CJK transcript screenshotted in light and
  dark (inline code, bold, bullets, changes row). App data cleared afterwards (`pm clear`), emulator back to light
  mode and font scale 1.0.
- Fable reviewed the catalog (dark accent contrast) and the final light + dark + font-scale screenshots; all flagged
  items fixed except the quota rings (below).

### Left / known issues
- The list's trailing quota-ring circle (DESIGN.md §11) is not there: quota rings and the quota sheet are phase 4.
- The composer has no leading "+" until attachments (phase 4); the slot exists (`showLeading`).
- Fixed in the last pass: tapping a path in an opened step collapsed the row (its extended touch target covered the
  detail's first line); TalkBack "Interrupt and send" moved onto the send circle's own semantics.
- Predictive back pop (scale 0.9 + fade) is not checked frame by frame; the dock's IME behaviour was not re-checked
  with a real keyboard on screen beyond typing via adb.
- Open folder sections still survive recreation only (state from a previous bridge can leave all sections closed).
- Demo data quirk: the Antigravity demo conversation shows a Claude model chip (DemoScript config), not a UI bug.


## Phase 4: Everything else — done (parity list below)

### What exists
- **Files in and out** (`core/FileTransfer.kt`, `ui/files/*`). `AppModel.fetchFile` (bridge/readFile base64, 32 MB), `readTextPreview`, `upload`. The viewer is a full-screen window above the conversation (`ui/components/FullScreen.kt`, a Compose `Dialog`, so the chat stays composed underneath: presence and the approval tray keep working, the back gesture closes it). Text with a line-number gutter, "File.kt:42" highlight and wrap toggle; images with pinch / double-tap zoom (bounded `ImageDecoder`, EXIF applied); PDF pages (`PdfRenderer`); everything else a hand-over card (Open with, Share, Save through `CreateDocument`). Too-large files (-32007) fall back to the first 512 KB of a text file or a "File too large" state. Files go to other apps through a `FileProvider` (`${applicationId}.files`, cache folder only, swept after a day). Every path in a transcript opens it (markdown links and code spans, tool locations, diff headers, request trays, plan card, attachment chips, project browser rows); long press on a chip copies the path.
- **Attachments**: composer "+" menu (Photo library via the Photo Picker, Choose files via `OpenMultipleDocuments`, Ask a side question, Run a shell command). Photos become JPEG 80 at 1600 px (max 4), files are checked against the cap by `OpenableColumns.SIZE` before any byte is read. Thumbnail / file-tile strip inside the slab with remove crosses, files grey out with a spinner while uploading, a failed upload keeps everything and says why, taken-back queue items return their photos (`unsentImages`). Sent files show as the accent file chip, photos as tiles that open in the viewer.
- **Upload cap**: OkHttp refuses to queue more than 16 MiB of outgoing frames and `bridge/upload` is one call with no chunking, so the phone caps files at **11 MB** (base64 + JSON = about 14.7 MB), not iOS's 25 MB. `OkHttpTransportTest` sends the largest allowed frame through a real socket and shows a frame past the limit is refused. A chunked upload (offset/append) in the bridge would lift the cap: not done, the bridge was not touched.
- **Inline images** (`ui/markdown/MarkdownImage.kt`): `![alt](src)` and listed paths (quiet mode: shown only when they load), web (OkHttp, 16 MB cap), `data:` and host paths; LRU cache keyed agent|conversation|src, refetch after 30 s, failures remembered for 30 s, never upscaled (1 px = 1 dp), max 480 x 320; failed rows with Open / Retry. A row that grows after load tells the chat (`LocalContentGrew`) so a following transcript keeps the bottom in view.
- **Agent embeds** (`ui/embed/*`): the iOS host contract in a locked-down WebView (JS on, no file/content access, no DOM storage, no windows, links go to the browser, no navigation away), theme tokens + `html.light/.dark` + `themechange`, the CSP, height from content (`webkit.messageHandlers.agHost.postMessage` shimmed onto one string-argument JS interface), inline up to 800 dp, full screen with reload, html + height cached.
- **Diff / changes**: `ChangesRow` opens the per-file sheet (expandable per file, "Show whole file" up to 5000 lines, Commit on the newest turn that changed files and when idle with no request pending).
- **/btw side questions**: "/btw" typed (or from "+") turns the slab into the accent side-question box, works mid-turn, tray above the composer ("1 of 2", answer clipped with a fade, Copy / Show all sheet, up to 3 open), palette lists /btw first. **"!" shell mode**: shell-tinted slab, run button, status line (cwd / "Running…" / "run when the agent finishes"), output rendered by the existing shell views.
- **Quota**: the list's top-right circle shows the two rings of the tightest pool and opens the sheet (cards per pool, bar per window, reset day + time left, "Updated … ago", refresh); a quota chip in the chat's chip row (limiting bucket for the selected model, tinted under 40 %) and quota in the model picker's group headers.
- **Select-text sheet**: long press on a message or answer gives Copy / Select text; the sheet is a platform `TextView` with the system handles and loupe (`MarkdownPlain` strips the syntax).
- Debug: `--ez dark true|false` on the launching intent forces the theme. Demo bridge: files (PNG / PDF / widget / source / zip / 20 MB / a too-large log / missing), uploads, shell, /btw (`long` makes a tall answer, `fail` refuses), conversations "Review the attached spec" (demo-files), "Rename the helpers" (demo-changes), "Chart the benchmark results" (demo-embed), a Claude quota with low buckets.
- No new dependencies (WebView, PdfRenderer, ImageDecoder, Photo Picker are platform).

### Verified
- `android_test` unit: **267 tests, 0 failures** (new: FileTransferTest, FileKindsTest, EmbedHostTest, ImageSourcesTest, AttachmentPolicyTest, ComposerLogicTest additions, QuotaFormatTest, MarkdownPlainTest, OkHttpTransportTest upload-cap test, DemoBridgeTest additions).
- `android_build install=true launch=…`: BUILD SUCCESSFUL, installed, launched. `logcat -b crash` and `AndroidRuntime`: only the native segfaults of `uiautomator` (the adb UI dump used to drive the emulator), nothing for dev.leash.personal.
- Emulator, demo bridge, driven with adb (screenshots in scratchpad/shots/p4): PDF, text with highlight line, image, zip and 20 MB file hand-over cards, 512 KB preview of a too-large log, missing-file error, uploaded file back in the viewer; inline image, failed image row, mentioned image; widget inline (script ran: theme text, button), widget full screen, light and dark; changes sheet (edit / new / deleted, expand, dark); "+" menu, real system document picker (a real file attached, sent, chip shown, notification permission prompt denied), a file over the cap refused with the 11 MB message; shell run (`!pwd`), /btw short and long answer, "Show all" sheet; photo thumbnail from a taken-back queue item; quota rings, sheet, chip and model picker headers; long-press menu and select-text sheet.
- Real bridge, read-only (LAN address by deep link through adb, app data cleared before and after, no notification permission): list loaded ("2 agents ready"), real quota sheet and ring, two conversations opened, the changes sheet of a finished turn with a real diff, a real file opened from its path (text viewer, line gutter) and one real path error ("path outside home directory": the bridge only reads under $HOME, shown as the error state). Nothing was sent, answered, uploaded, run or created. `pm clear` at the end.
- Fable reviewed the screenshots (light + dark); findings in "Design review" below.

### Not verified / open issues
- **Photo Picker round trip**: the emulator has no photos (MediaStore only holds a few files), so photos were checked through a taken-back queue item (thumbnail strip), not through the picker. Pushing a photo would change device state, so I did not.
- IME layout, rotation and a real network drop are still not checked on a device.
- The WebView of this emulator is Chromium 110; newer widget JS is untested.
- A chunked upload for 11 MB to 25 MB files needs a bridge change (offset upload); report to the bridge owner if wanted.
- Real-bridge images / embeds: the conversations I opened had none, so inline images and embeds on the real bridge are only verified on the demo bridge.
- Reset times use the device locale (Chinese emulator shows "周日").

### Parity pass (ios/Leash/Views and Core, left over after phase 4)
- Missing, by choice: Apple Watch (WatchRelay), Live Activity controller (replaced by the ongoing notification), BackgroundAudio (foreground service), QuickLook (own viewer), App Intents.
- Missing, small: swipe-to-mark-read on list rows (long-press menu exists); the image tile's context menu (Copy / Copy path / Save to Photos; the viewer has Share and Save); the command capsule's Copy; message-menu preview; shake-to-undo of a removed queued message (the Undo row exists); "Lock Screen / Watch" settings sections (the Notifications section replaces them); the Antigravity-only "Turbo" naming is done, Haptics on attach is done.
- Core: everything in ChatModels / AppModel / BridgeConnection / Quota / JSON is ported; `LocalNotifier` and Live Activity behaviour is phase 3.

## Phase 5b: Built-in Tailscale (Android) — implemented and unit-tested; the AAR build and the real test are BLOCKED on the Windows host's NDK

### What exists
- **`:tailnet` Gradle module** (`android/tailnet/build.gradle.kts`): `installGo` downloads Go 1.27.4 for windows-amd64 (sha256 pinned, checked) into `android/.gradle/tailnet-toolchain` (survives `clean`; verified on the host: download + unpack ~85 s once), `installGomobile` runs `go install` for gomobile and gobind at the versions pinned in `tailnet/go.mod` (verified, ~40 s once), `bindTailnet` runs `gomobile bind -target android/arm64,android/amd64 -androidapi 29 -javapkg dev.leash.personal -tags <build-tags.txt> -trimpath -ldflags "-s -w"` into `tailnet/build/outputs/tailnet.aar`. Inputs/outputs are declared (Go sources, go.mod/go.sum, build-tags.txt, Go version, API, NDK), so bind only reruns when they change; the Go build cache lives in the same toolchain dir. `checkTailnetHost` fails first with `BLOCKED: no Android NDK …` and the exact sdkmanager package. `:tailnet:tailnetDoctor` prints what the host has.
- **Go sources reach the host through `android/tailnet/sync-go.sh`** (run it before `android_build` / `android_test` / `android_gradle` whenever `tailnet/*.go` changes): the MCP sync uploads only `project_dir` (git-ignored files included), so the script copies `tailnet/{*.go (no tests),go.mod,go.sum,build-tags.txt}` into `android/tailnet/go/` (git-ignored). `tailnet/` stays the single source of truth; without `go/` the module falls back to `../../tailnet`. No binaries in git.
- **The app links the AAR only when it can be built** (`tailnetLinked` in `tailnet/build.gradle.kts`: the pinned NDK `27.2.12479018` is installed; `-Ptailnet=on|off` overrides, e.g. `tasks: ["assembleDebug", "-Ptailnet=off"]`). Linked: source set `src/tailnet` (`NativeTailnet.kt`, the only file that touches the generated `dev.leash.personal.tailnet.Tailnet`), `ndkVersion`, `implementation(files(aar).builtBy(":tailnet:bindTailnet"))`. Not linked: `src/noTailnet` and Settings says "Not included in this build" (like iOS without the xcframework). Deviation from "the app depends on the AAR": this keeps `android_build` / `android_test` working on a host without the NDK. The task graph with `-Ptailnet=on` was checked with `--dry-run` (`compileDebugKotlin` depends on `bindTailnet`).
- **Core** (`core/Tailnet.kt`, `TailnetController.kt`, `TailnetStorage.kt`; port of the iOS controller): `TailnetController` starts the node when the bridge connection asks for a route and returns the loopback `ProxyPort`; polls `Status()` (1.5 s while starting, 15 s when connected); a rejected/expired key is `needs_key` and is not retried (`TunnelError(retry=false)` makes the link `Failed`); a changed key/URL/hostname restarts the node; a stop that overtakes a start leaves nothing running; Log out starts from the saved identity without a key, calls `Logout()`, forgets the key and switches the feature off (a control error is shown as a note). `BridgeConnection.route` is asked on every attempt (`ws://127.0.0.1:<port>/ws`, the bridge's own token); `AppModel.reconfigure` sets it when the switch is on, `releaseConnection` and `unpair` stop the node (so it runs while the app is visible or the foreground service / grace keeps the link; the service's 3-minute grace ends in `releaseConnection`).
- **Secrets**: the auth key is sealed with an AES-GCM key in the Android Keystore (`KeystoreSecretBox`, non-exportable) and stored in its own DataStore (`tailnet`, excluded from backups); the UI never receives it back (only "Saved"), the field is not kept in saved instance state, `TailnetConfig.toString` redacts it, nothing logs it (the Go side redacts `tskey-…` too). State dir: `noBackupFilesDir/tailnet`. Hostname `leash-android`.
- **Settings** (`ui/settings/TailnetSettings.kt`, design-system rows): switch "Use built-in Tailscale", Auth key (masked, show/hide), Advanced (disclosure) with Control URL, Status pill (Not connected / Connecting… / Connected + "name 100.x" / Needs a new auth key + control's reason / Error + message), Save & connect, Log out (confirmation). The connection status card also has a Tailscale row, and a tunnel failure reaches the connection note as the reason.
- **Debug only**: `TailnetDebug` (intent extras `tailnet_key`, `tailnet_hostname`, `tailnet_control` switch it on with that key; the extras are removed from the intent) and `DemoTailnet` (a scripted node used when the AAR is not linked, so the section can be driven: a key containing "bad" is rejected). Release builds have neither.
- **tailnet/**: one file, `android.go` (`//go:build android`): blank-imports `tailscale.com/feature/androidbin`. Android 11+ denies `net.Interfaces` to apps (golang/go#40569), tsnet does not link tailscale's fallback by default (netmon interface fallback + Android DNS), and without it netmon cannot start. Compiles for android/arm64 (`go build` with the tags, CGO off) and `go test ./...` still passes; the effect on a device is untested.

### Verified
- `android_test unit`: 302 tests, 0 failures (35 new: `TailnetControllerTest`, `AppModelTailnetTest`, route cases in `BridgeConnectionTest`).
- `android_build install=true`: BUILD SUCCESSFUL, installed, launched. Emulator (API 35) with the scripted node: Settings > Built-in Tailscale: off, switch on (key field, Advanced, Status "Connecting…", Save & connect, Log out), typed key (masked), "Connected" with "leash-android-demo 100.64.0.7". `pm clear` afterwards.
- Keystore path on the emulator (scripted node): a typed key, Save & connect, force-stop, relaunch shows "Saved"; the `tailnet` DataStore holds only the sealed blob (no plaintext in files/, none in logcat); Log out dialog, then switch off and no key stored; `pm clear` afterwards.
- Incremental: the second `:tailnet:installGomobile` is UP-TO-DATE; the Go tools run in a private copy (`tailnet/build/gosrc`) because `-mod=mod` rewrites go.sum, which would otherwise change the declared inputs and rerun bind every time. The first bind's UP-TO-DATE behaviour is still to be checked once the NDK is there.
- `NativeTailnet.kt` was compiled once against `gobind -lang=java -javapkg=dev.leash.personal` output (package `dev.leash.personal.tailnet`, class `Tailnet`) copied temporarily into the debug source set, then removed.
- APK (debug, no AAR linked): 94.37 MiB (98,958,219 bytes) with this change; the build before it was 95.53 MiB (100,169,367 bytes), so the Kotlin side adds nothing measurable (the 95.53 MiB was a leftover artifact on the host, not a clean baseline). **With the AAR: not measured** (the Go library is about 17 MB per ABI stripped, two ABIs, stored uncompressed by default: expect roughly +30 to +35 MB; `packaging.jniLibs.useLegacyPackaging = true` would compress it at the cost of install size).

### BLOCKED / not verified
- **NDK is not installed on the Windows host** (`F:\Android\Sdk` has build-tools 34/35, platforms 34-36, cmdline-tools/latest, no `ndk/`). Needs, on that host: `sdkmanager "ndk;27.2.12479018"` (package id checked in Google's repository2-3.xml, stable channel). Then: run `android/tailnet/sync-go.sh`, `android_gradle tasks=[":tailnet:bindTailnet"]` (first bind compiles tailscale for two ABIs with cgo: expect several minutes; check it is UP-TO-DATE on the second run), then `android_build install=true`. `gomobile init` is deliberately not run (in this gomobile it reinstalls gobind@latest over the pinned one; bind needs only gobind on PATH). The bind step itself, the AAR, the JNI loading and everything on the device are therefore **untested**.
- **Real test not run** (needs the AAR): plan is `am start -n dev.leash.personal/.MainActivity --es tailnet_key <key> --es tailnet_hostname leash-android-test` with the key read from the scratchpad inside a script (never printed), pair with the pairing link's ts.net host (the emulator's own path to ts.net is broken, so a connection proves tsnet), open one conversation read-only, then Log out in Settings (removes the node's key; the device must be deleted in the Tailscale admin by the user), `pm clear`, and grep logcat for `tskey-`. Watch `Tailnet.logs()` / logcat for netmon or DNS errors on the first run: if `android.go` is not enough the fix belongs in `tailnet/`.
- The node starts at the first connection attempt: a cold start (registration) takes seconds, so a notification action that needs the connection (Allow / Deny, 8.5 s budget) may time out on a cold node; the foreground service keeps it warm while a turn runs.
