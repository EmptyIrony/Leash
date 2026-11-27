# 016 Removing a queued message can be undone

Tip: https://vibedesign.tips/016-toast-or-notification/ — "Can the action be undone? Then do it at once and offer Undo … instead of asking first."

## Goal
QueueTray's xmark (QueueTray.swift ~68) and context-menu Delete (~79) remove a queued message at once with no way back. Keep the one-tap delete, add Undo.

## Files
- ios/Leash/Views/QueueTray.swift, whoever supplies `delete` (ChatView.swift / AppModel / ChatModels), the bridge queue API it calls (read bridge/src to know what re-queueing means; prefer an iOS-only change).
- ios/Leash/Localizable.xcstrings

## Open questions (default picked)
- Undo form: iOS has no toast. Default: the removed row stays in place for 4 s as a dimmed one-line "Removed" + "Undo" button (`Motion.swap` between the two), then collapses with `Motion.disclose`; also register the action with the environment `UndoManager` so shake-to-undo works.
- Order on undo: back at its original position if the bridge API allows it, otherwise at the end; say which in the commit.
- If the queue starts sending during the 4 s (turn ended), the undo row disappears; nothing is re-queued.
- VoiceOver: announce "Removed" and expose Undo as an action.

## Check
- Remove a queued message: row turns into "Removed · Undo" for ~4 s, then collapses.
- Undo: the message is queued again (position as decided).
- Reduce Motion: cross-fades only.
