# 004 Radius tokens

Tip: https://vibedesign.tips/004-continuous-corners/ + design system pass note. HIG verdict (Fable): define role tokens in Theme; don't use ConcentricRectangle for these trays/thumbnails (corners far from the container corner resolve to 0, and tray 22−14=8 is below today's 12).

## Goal
Radii are hard-coded across ios/Leash (8, 10, 12, 14, 16, 18, 20, 22, 26). Put them behind a few role tokens and merge near-duplicates.

## Defaults
- `Theme.Radius` (or similar): tray 22 (glass trays: PendingCard, QueueTray, SideQuestionTray, CommandPalette), composer 26, card 16, row 12 (selectable rows, thumbnails, chips, ImageTile default), inset 10 (code blocks, diff, sunken boxes); bubble 20 with tail 6 for UserBubble. Merge 8→10? 14→16? 18→20? decide per element and list each as "element: old -> new".
- Pills, capsules, circles and the 1pt quote bar stay as they are. `RoundedRectangle` already defaults to .continuous in SwiftUI; keep explicit styles consistent.
- Watch and Live Activity: leave unless trivial.
- Also delete dead code in EntryViews.swift: `MessageView`, `ToolCard`, `StatusBadge` (no callers — verify with rg first), and strings only they used if Xcode would mark them stale (optional).

## Check
`rg "cornerRadius: [0-9]" ios/Leash` only hits Theme's token definitions; the commit lists every changed radius.
