# Design system pass: one palette for the app, the watch and the Live Activity

Source: https://x.com/Kubai087/status/2121615225816494303 (full text: .claude/skills/kubai-design-tips/notes/design-system-pass.md)

## Goal
The same colour roles are written three times: `Theme` (Theme.swift, dynamic light/dark via UIColor), the watch's hard-coded RGB (LeashWatch/WatchViews.swift:5-8, dark values only) and the Live Activity's asset colours (`LA`, LeashLiveActivity.swift:59, LAWorking/LAAttention/LASuccess/LADanger in its Assets.xcassets). Make one source of truth.

## Files
Theme.swift, WatchViews.swift, LeashLiveActivity.swift + its Assets.xcassets colour sets, a new shared folder, ios/Leash.xcodeproj/project.pbxproj (synchronized groups: PBXFileSystemSynchronizedRootGroup — see how SharedActivity/SharedWatch are attached to targets).

## Open questions (default picked)
- Where: a new `ios/SharedDesign/` synchronized folder added to all three targets, holding plain hex light/dark pairs per role (no UIKit, so it builds for watchOS) plus the motion curve values (011 task).
- Theme builds its dynamic colours from it; the watch uses the dark values (the watch is always dark).
- Live Activity: first read `git log -S LAWorking` for why asset colours were chosen. If it was a real limitation (dynamic UIColor not resolving in the widget), keep the assets but make their values equal the shared palette and note it; otherwise build `LA` from the shared palette and delete the colour sets.
- Values do not change (this pass keeps the look); where two values for one role differ, keep the app's and list "old -> token".

## Check
- No RGB literals for roles outside the shared file: `rg "Color\(red:|UIColor\(hex: 0x" ios` hits only the shared file / Theme's builder (and the SpaceBackground gradient stops, if left as is — say so).
- pbxproj: the new group appears in each target's fileSystemSynchronizedGroups exactly like the existing shared ones.
