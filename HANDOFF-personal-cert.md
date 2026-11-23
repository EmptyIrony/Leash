# Leash personal-cert edition: handoff

Branch `personal-cert` (worktree /Users/jerryzhou/workspace/Leash-personal on the Mac). Work in progress; this file is
updated as each part lands.

## Status
- [x] Project: free personal team, automatic signing, three targets (app, Live Activity extension, watch app)
  - one base bundle ID, project build setting `LEASH_BUNDLE_ID = dev.leash.personal`; children `$(LEASH_BUNDLE_ID).LiveActivity`
    and `$(LEASH_BUNDLE_ID).watchkitapp`. The old UUID ID, manual profile and "iPhone Distribution" identity are gone.
  - folders (synchronized): `Leash/` (app), `LeashLiveActivity/` (extension), `LeashWatch/` (watch app),
    `SharedActivity/` (app + extension), `SharedWatch/` (app + watch). `Config/LiveActivity-Info.plist` holds NSExtension.
- [x] Live Activity + Dynamic Island (written, not compiled): one activity for all turns started from this iPhone;
  Allow/Deny/Refresh buttons are LiveActivityIntents run in the app process; stale after 75 s without an update
- [ ] Watch app
- [x] Bark deep link (bridge, additive, tested) + app URL routing (`leash://session?id=…&agent=…`)
