# Leash personal-cert edition: handoff

Branch `personal-cert` in the worktree `/Users/jerryzhou/workspace/Leash-personal` (main is untouched). Signed with
your **free personal Apple team** (7-day profiles, no push). Everything Swift here is **written, not compiled**: build
in Xcode and paste the errors.

## What was built

**Project**
- Three targets: `Leash` (iOS app), `LeashLiveActivity` (Widget Extension: Live Activity + Dynamic Island only, no
  Home Screen widgets or controls), `LeashWatch` (single-target watchOS app). Both are embedded in the app
  (Embed Foundation Extensions / Embed Watch Content) and are build dependencies of it.
- Automatic signing, no team set. One base bundle ID as a project build setting: `LEASH_BUNDLE_ID = dev.leash.personal`;
  the extension is `$(LEASH_BUNDLE_ID).LiveActivity`, the watch app `$(LEASH_BUNDLE_ID).watchkitapp`
  (`WKCompanionAppBundleIdentifier = $(LEASH_BUNDLE_ID)`). If Xcode says the ID is taken, change that one value
  (Project > Build Settings > User-Defined).
- The old manual-signing settings (UUID bundle ID, `dump-…` profile, "iPhone Distribution", team 32BCF8AX3M) are gone
  on this branch. Version 0.2.0 (1), set once at project level.
- Folders: `ios/Leash`, `ios/LeashLiveActivity`, `ios/LeashWatch`, plus `ios/SharedActivity` (app + extension:
  activity attributes, button intents) and `ios/SharedWatch` (app + watch: the snapshot / message format).
  `ios/Config/LiveActivity-Info.plist` holds the extension's NSExtension dict. `NSSupportsLiveActivities` is in
  `ios/Config/Info.plist`. Shared scheme `LeashWatch` added for running the watch app.
- No App Groups, no push capability, no entitlements.

**Live Activity / Dynamic Island** (design reviewed against the HIG by a Fable agent)
- One activity for every turn started from this iPhone (send, send-queued, a watch reply), focused on the most urgent
  conversation: one waiting for approval wins, otherwise the most recently started. The focus moves only on a phase
  change. "+N more working" says what else is running.
- Compact: agent mark | elapsed time (fixed width) or an amber hand while it waits. Minimal: the phase symbol
  (cyan sparkle / amber hand). Expanded and Lock Screen: title, agent, elapsed time, the live step line from the chat
  ("Running npm test…", only while that chat is attached; otherwise "Working"), and at most one row of controls.
  Apple Watch Smart Stack (.small): mark + title, then state + time; no buttons there.
- Waiting for approval: the tool title, **Deny** (bordered) and **Allow** (prominent, accent). Allow requires an unlocked
  iPhone (`authenticationPolicy = .requiresAuthentication`), Deny does not. "Allow always" is never offered there.
  Questions say "open Leash to answer" (tap opens the chat). The result of a button shows briefly: Allowed / Denied /
  Already answered / Couldn't reach your Mac.
- No push: the app updates it while running. Every update sets `staleDate` 75 s out and a heartbeat refreshes it
  every 30 s while connected; stale content dims the timer, shows "Updated … ago" and a **Refresh** button.
  The buttons are `LiveActivityIntent`s: iOS starts Leash in the background, it connects, does the one thing, updates
  the activity and closes the socket again (so Bark's away detection stays right).
- End: when every tracked turn is over it shows Done / Failed / Stopped with the duration and ends: dismissed at once if
  Leash is on screen, otherwise after 15 minutes. A turn that ended while Leash was suspended is found on the next
  `ready`; its outcome comes from the session list (new bridge field) within 5 s, else "Turn ended" and gone.
- Alert (sound, expanded island) only when a request arrives while the app is not in the foreground; the app then
  calls `bridge/noticed` so Bark doesn't push the same request.
- Tapping it opens that conversation. Settings > "Lock Screen & Watch" has a Live Activity switch (default on) and
  shows whether the watch app is installed.

**Deep links**: `leash://session?id=…&agent=…` (Bark taps, the Live Activity) dismisses sheets and shows list > that
chat (replaces the stack; nothing happens if it is already open). `leash://pair?…` still pairs; `leash://open` just
opens. Unsent drafts now survive leaving a chat.

**Apple Watch app**
- Transport: **relay through the iPhone app over WatchConnectivity** (decision). watchOS blocks WebSockets /
  Network.framework for ordinary apps (Apple TN3135; only URLSession HTTP is allowed), the bridge is WebSocket-only, and
  a watch message *wakes the iPhone app in the background* (Apple's `sendMessage` docs), so no bridge change and no
  token on the watch.
- One list: Needs You (approvals and questions), Working (with a ticking timer), Recent (6). Request screen: command
  (6 lines max), **Allow** (prominent) above **Deny**; the tapped button spins, both disable, the screen stays until the
  phone confirms, then goes back. Conversation screen: status, its requests, **Reply** (dictation via TextFieldLink).
  First row explains why actions are off: iPhone not reachable / Mac not connected / pair first.
- Haptics: success / error on confirmation, notification for a new request while the app is open. Nothing else.
- No complication (it would need another widget extension target).

**Bridge (additive; old app keeps working)**: Bark tap URL `leash://session?id=&agent=` when `detail` is on (the
default with encryption; a configured `bark.url` still wins; otherwise `leash://open`); `bridge/noticed {key}`;
`session/list` rows carry `_leash.stopReason` / `turnEndedAt`. 58 node tests pass (5 new).

## Mac steps, in order

1. `cd ~/workspace/Leash-personal` and open `ios/Leash.xcodeproj` (this worktree, not ~/workspace/Leash).
2. Xcode > Settings > Accounts: your Apple ID is there (Personal Team).
3. Select the project > each target **Leash**, **LeashLiveActivity**, **LeashWatch** > Signing & Capabilities: tick
   "Automatically manage signing", Team = your Personal Team. (Or set `DEVELOPMENT_TEAM` once at project level.)
   No capabilities need adding. If "bundle identifier is not available", change `LEASH_BUNDLE_ID` in the project's
   build settings and retry.
4. iPhone: Developer Mode on; delete the **old Leash (p12 build)** first, both apps register `leash://`, and iOS
   picks an undefined one for Bark taps.
5. Scheme **Leash** > your iPhone > Run. First launch: Settings > General > VPN & Device Management > trust your
   Apple ID, run again.
6. Pair again: new bundle ID and team = new keychain, so the token is gone. On the Mac:
   `node ~/workspace/Leash/bridge/src/index.mjs pair`, then scan in Leash > Settings. (No bridge restart needed for this.)
7. Watch: Developer Mode on the watch (Settings > Privacy & Security), keep it unlocked near the iPhone, then scheme
   **LeashWatch** > your watch > Run (the first install over the phone can take minutes). Open it once with the
   iPhone app paired.
8. Allow Live Activities when asked (or Settings > Leash > Live Activities).
9. **Every 7 days** the free profile expires (apps stop launching): connect, open the project, Run **Leash** and
   **LeashWatch** again. Data stays; no re-pairing needed unless you delete the app.

**Bridge changes need deploying**: they are only on this branch. To get them: merge `personal-cert` (or just
`bridge/`) into the running checkout at ~/workspace/Leash, then restart the LaunchAgent as the last step
(`launchctl kickstart -k gui/$(id -u)/dev.leash.bridge`; it cuts the remote sessions). Without them the app still
works: Bark taps just open the app, Bark may also push a request the Live Activity already alerted about, and a turn
that ended unseen shows as "Turn ended".

## Verified vs unverified

Verified here: pbxproj integrity script (every object reference defined and reachable, braces balanced, each target has
both build configurations, its three phases and product; the app depends on and embeds both products; proxies match);
every plist, asset Contents.json and .xcstrings parses; every new UI string has zh-Hans in its own target's catalog;
`node --test` 58/58.

Unverified (needs Xcode / device): all Swift compiles; the project opens with folders shared between targets; free-team
signing of three IDs; Live Activity appearance in every presentation, `staleDate` behaviour, intent buttons waking the
app (Allow asking to unlock), the alert; watch install and WatchConnectivity wake-up; TextFieldLink dictation; deep
link routing; draft restore.

## Known limits and risks

- No push: while Leash is suspended the Live Activity cannot change. The timer keeps counting, it turns "stale" after
  75 s, and Bark tells you when something happens; Refresh or opening the app catches up.
- An activity can only *start* while Leash is on screen (ActivityKit), so a turn started from the watch has no
  Live Activity until you open the phone app.
- The step line exists only while that chat is open in Leash (the bridge sends step updates to attached clients).
- Free team: 7-day expiry; App ID limits (10 new IDs per 7 days; this uses 3) and the three-app limit on a free team
  are Apple's rules I could not check here; the base ID may already be taken.
- Watch needs the iPhone in Bluetooth / Wi-Fi range; replies and approvals take a few seconds (phone wakes, connects to
  the Mac). The watch waits up to 45 s; the phone gives up on a reply after about 25 s, so a retry never doubles a
  message that was still on its way. Questions (forms) are answered on the iPhone.
- If Xcode refuses a synchronized folder in two targets, set target membership of `SharedActivity` (Leash +
  LeashLiveActivity) and `SharedWatch` (Leash + LeashWatch) in the File inspector.
- Out of scope, unchanged: the "send hangs with a table on screen" bug.
