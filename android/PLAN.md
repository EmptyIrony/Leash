# Leash for Android — build plan

A native Android client for the existing Leash bridge, at feature parity with the iOS app **minus the Apple Watch**
(and minus Apple-only surfaces, replaced as listed below). The iOS app (`ios/Leash`) is the reference
implementation; the bridge protocol in `README.md` ("Bridge protocol (v1)" and the sections after it) is the contract.

## Ground rules (every phase)

- **Do not run `gradle`/`./gradlew` locally** — this container has no Android SDK. Build, test and install only through the
  `win-android` MCP tools: `android_build` (`project_dir=/config/workspace/Leash/android`, `install=true`,
  `launch="dev.leash.personal/.MainActivity"`), `android_test` (unit), `android_gradle`, `android_adb`
  (`screenshot` then Read the PNG, `logcat`, `shell`). The Windows host has JDK 17 and the Android SDK.
- Target device: x86_64 emulator, Android 15 (API 35), 1080x1920. `minSdk 29`, `targetSdk 35`; pick `compileSdk`
  from the platforms the Windows SDK actually has (probe with a build; record it in PROGRESS.md). Gate API 36 features (Live Updates) behind `Build.VERSION` checks.
- **Do not change the bridge** unless the Android app truly cannot work otherwise; if you must, keep `cd bridge && npm test`
  green and say why in the commit. The bridge has no client-specific code; send `X-Leash-Client: android`.
- Never touch `ios/`, the modified `ios/Leash.xcodeproj/xcshareddata/xcschemes/Leash.xcscheme`, `.claude/` or `kubai-tasks/`.
  Stage files explicitly (`git add android ...`), never `git add -A`/`.`.
- Commit on `main` after each coherent step (small commits, imperative subject prefixed `android:`, English, ending with
  the line `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`). Never push.
- Wording: the app connects to "the bridge", not "the Mac" (the bridge is Node and can run on any host). Never say "Mac"
  in UI strings, errors or settings ("Connect to bridge", "Bridge address", "Not connected to the bridge"); fix any
  already-written "Mac" wording when touching that code.
- No backward-compat shims (personal project). Code, comments and docs in English.
- The bridge is plain `ws://` (over Tailscale/LAN): allow cleartext via a network security config (`cleartextTrafficPermitted`),
  otherwise targetSdk 35 + OkHttp refuse the connection.
- Every agent's first step: `android_env_status`. If the win-android tools are missing or fail, stop and end the report
  with a line starting `BLOCKED:`. Same for anything else that makes the phase impossible.
- The emulator is shared and remote: never toggle Wi-Fi/airplane mode/network (`svc wifi`, `cmd connectivity`, …) or reboot it —
  that once took it offline until a manual restart. Test reconnect logic with unit tests or the demo bridge instead.
- Screenshots go to `/tmp/claude-501/-config-workspace-Leash/7983988f-39e1-40df-b9e6-1f96e0afa4c0/scratchpad/shots/`
  (pass it as `output_path`), never into the repo or /tmp.
- Personal-use app: no Google Play Services / Firebase dependency (sideloaded APK, no FCM). No analytics.
- Keep `android/PROGRESS.md` updated at the end of every phase: what's done, what's verified on the emulator (with how),
  what's left, known issues. The next phase starts by reading it.

## Stack

- Kotlin, Jetpack Compose, Material 3 (use the Expressive APIs where the stable/latest-rc BOM has them), single Activity,
  Compose Navigation. Gradle Kotlin DSL + version catalog (`gradle/libs.versions.toml`), Gradle wrapper committed.
  (No local gradle: write `gradle/wrapper/gradle-wrapper.properties` and fetch the matching `gradle-wrapper.jar`
  + `gradlew`/`gradlew.bat` from the Gradle distribution / gradle GitHub tag with curl; check the jar's sha256 against
  https://services.gradle.org/distributions/gradle-<ver>-wrapper.jar.sha256.)
- Networking: OkHttp WebSocket. JSON: kotlinx.serialization `JsonElement` (the protocol is dynamic, mirror `Core/JSON.swift`).
- State: coroutines + `StateFlow`; an app-scoped `AppModel` (mirrors `Core/AppModel.swift`) owned by the Application,
  so the foreground service and UI share one connection.
- Storage: DataStore (preferences) for host/port/token and UI prefs; mark it no-backup.
- QR: `com.google.mlkit:barcode-scanning` (bundled model, works without Play Services) + CameraX — plus manual entry and
  "paste pairing link" (the emulator has no useful camera). Pairing link format: see `pairingInfo()` in `bridge/src/index.mjs`
  (`leash://pair?...`). Register the `leash` scheme too (`leash://pair`, `leash://session?id=…&agent=…`).
- Markdown: port `Views/MarkdownView.swift` behaviour (code blocks, inline images, local-path links → file viewer,
  `<agent-embed>`) — a library is fine only if it allows all of that; otherwise a small own parser + Compose renderer.

## Design

Same product look as iOS (Leash's own visual language, see phases D1/D2 and `android/DESIGN.md` once it exists), Android system behaviour: dynamic type (sp), edge-to-edge with proper insets,
IME-aware composer, predictive back, light/dark following the system. Use Leash's palette
(`ios/SharedDesign/LeashPalette.swift`, `ios/Leash/Theme/Theme.swift`) as the brand/seed colors instead of dynamic color.
Provider icons from `ios/Leash/Assets.xcassets/Provider*.imageset` (convert to vector drawables or PNG resources).
Motion: Material motion; the "working" state can echo iOS's shimmer idea in Compose, keep it subtle.

## Apple surface → Android replacement

- Live Activity / Dynamic Island → an ongoing notification for a running turn (title, elapsed, state; API 36:
  `setRequestPromotedOngoing` Live Update), updated from the app process.
- BackgroundAudio hack → a foreground service (type `specialUse` with the subtype property; `FOREGROUND_SERVICE_SPECIAL_USE`)
  that keeps the WebSocket alive while a turn runs, a request is pending, or for a grace period after leaving the app.
  Same presence semantics as iOS: `visible` true only while on screen; ping every 15 s.
- Local notifications (`Core/LocalNotifier.swift`): channels for "Needs you" (high importance; permission requests with
  Allow / Deny actions handled by a BroadcastReceiver without opening the app; questions open the chat) and "Turn finished".
  After alerting, send `bridge/noticed {key}` like iOS. Ask for `POST_NOTIFICATIONS` at a sensible moment.
- App Intents (`SharedActivity/LeashIntents.swift`) → notification actions / receiver.
- QuickLook → own text viewer with line numbers; other files: save to cache + `FileProvider` + ACTION_VIEW / share sheet.
- WKWebView embeds (`Views/AgentEmbedView.swift`) → Android WebView with the same host contract (theme tokens,
  `html.light/.dark` + `themechange`, CSP, auto height ≤ 800dp inline, fullscreen view). No file/content access, JS only as needed.
- Photos/Files picker → Photo Picker + `ACTION_OPEN_DOCUMENT` for "Choose Files" uploads (`bridge/upload`).
- Haptics → `HapticFeedback`/`View.performHapticFeedback` at the same moments.

## Phases (one agent each, in order)

1a. **Scaffold.** Gradle project in `android/` (app id/namespace `dev.leash.personal`, app name "Leash"), wrapper,
   version catalog, `.gitignore` for build outputs, network security config, Material 3 theme from Leash's palette, an
   empty single-Activity Compose app. Done when it builds, installs, launches and a screenshot shows it. Find out which
   `compileSdk` the Windows SDK has. Small and fast: toolchain problems must surface here.
1b. **Core port.** Port `Core/`: JSON, BridgeConnection (auth header, `/ws`, reconnect with backoff, call/result
   correlation + timeout, errors incl. -32020/-32021/-32024, ping/presence, 64 MB frames), ChatModels, AppModel (+Remote),
   Quota. Settings storage + pairing-link parse + manual entry. JVM unit tests for the reducers (feed recorded
   `update`/`turn`/`request`/`queue` frames; `bridge/test/mock-agent.mjs` and `bridge/test/bridge.test.mjs` show realistic
   shapes). **Demo transport** (debug builds only): a fake bridge behind the same interface that answers `initialize`,
   `session/list`, `bridge/attach` and `session/prompt` from scripted frames (a few sessions; a turn with thinking, a
   tool call with a diff, markdown with code and an image link, a permission request, a question, queued messages, a
   subagent) so later phases can verify UI by screenshot without a reachable Mac. Selectable in settings ("Demo bridge").
   A minimal screen showing connection state + session list proves the loop. If a real pairing link is in PROGRESS.md
   or the plan, also try it; never edit the bridge config on the Mac.
2a. **Browse + read.** Session list (`SessionListView`), new session + project browser, settings + pairing UI
   (QR scan, paste link, manual), chat transcript read-only: `ChatView` layout, `EntryViews`, `ActivityViews`,
   `SubagentViews`, `MarkdownView`, provider icons.
2b. **Act.** Composer with send / steering / queue tray / cancel / interrupt / Undo (`QueueTray`), pending cards for
   permissions and questions (`PendingCard`), pickers (mode/model/config options, `PickerPopovers`), command palette,
   error states (locked elsewhere, changed elsewhere → reload + resend, not connected).
D1. **Design system (after 2b, before 3).** The 2a/2b UI is functional but looks like a stock Material template; the iOS app's
   finish is far above it. Goal: the Android app looks like the same product as iOS (Leash's own visual language), while
   system-level behaviour stays Android (back gesture, insets, IME, notifications). References: iOS screenshots in
   `.shots/*.JPG` (look at every one with Read), `ios/Leash/Theme/*`, `ios/SharedDesign/*` (palette, motion curves),
   the shimmer/dot-matrix glyph and other custom views in `ios/Leash/Views`, `design/icon/*.svg` (real app icon: adaptive
   icon + monochrome). Write `android/DESIGN.md` (tokens: colours light/dark, type scale, spacing, shapes incl. continuous
   corners, elevation/translucency, motion specs as Compose springs/easings, haptic moments, per-component specs for list
   rows, chat entries, activity steps, cards, composer, sheets, buttons, chips) and implement it as a Compose design system
   (`ui/theme` + `ui/components`): no default Material look leaking through (custom shapes, colours, typography,
   ripple/indication, top bars). Translucent/blurred surfaces only where iOS uses glass and only on API 31+ with a solid
   fallback. Get the spec reviewed by a Fable agent before implementing (Agent tool, `model: "fable"`, give it DESIGN.md and
   the screenshot paths) and on any design doubt later.
D2. **Reskin.** Apply the design system to every existing screen, one screen at a time, each compared side by side with the
   matching `.shots` screenshot (and the iOS SwiftUI code for screens without one) until spacing, hierarchy, colour and
   motion match. Finish with a Fable review of the final emulator screenshots (light + dark) and fix what it flags.
   Phases 3 and 4 then build only with this design system.
3. **Background + notifications.** Foreground service, ongoing turn notification, local notifications with actions,
   `bridge/noticed`, deep links, reconnect on network change, behaviour after process death.
4. **Everything else.** Attachments (upload, `resource_link`), file viewer + download/share, inline images, diff and changes
   views, agent embeds (WebView), quota sheet, /btw side questions, "!" shell mode, select-text sheet. Then a parity pass:
   walk every file in `ios/Leash/Views` and `ios/Leash/Core` and list anything still missing in PROGRESS.md.

## Definition of done per phase

`android_build` succeeds with `install=true`, `android_test` unit tests pass, the app launches without crash (check
`logcat`), new screens checked by screenshot, PROGRESS.md updated, work committed.
