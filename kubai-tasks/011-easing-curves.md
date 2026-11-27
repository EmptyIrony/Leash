# 011 Every animation from one vocabulary, in every target

Tip: https://vibedesign.tips/011-easing-curves/ (full text: .claude/skills/kubai-design-tips/tips/011-easing-curves.md)

## Goal
`Motion` (Theme.swift) is the app's only animation vocabulary, but:
- RootView.swift:15 and :54 use a bare `withAnimation { path = … }` (default curve);
- LeashLiveActivity.swift:226-227 and LeashWatch/WatchModel.swift:56 copy `Motion.layout`'s numbers inline.

## Files
RootView.swift, LeashLiveActivity.swift, WatchModel.swift, a shared file reachable by the app, the Live Activity and the watch (see ds-shared-palette.md, done by the same agent), Theme.swift.

## Open questions (default picked)
- RootView swap animation: `Motion.layout`.
- Sharing: one shared source of the curve values (e.g. `LeashMotionCurves`: layout = smooth 0.34 / reduced easeInOut 0.2) used by `Motion` and by the two extensions; each target still reads Reduce Motion its own way.

## Check
`rg "withAnimation \{|\.smooth\(duration|\.snappy\(duration|easeInOut\(duration" ios` only hits the shared definitions.
