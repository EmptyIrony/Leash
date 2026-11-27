# 016 Chat errors stay where they happen

Tip: https://vibedesign.tips/016-toast-or-notification/ (full text: .claude/skills/kubai-design-tips/tips/016-toast-or-notification.md) — "A failure the person has to fix: keep it on the item, with a way to fix it"; "a form error … next to the field".

## Goal
Today every failure (send refused, attachment too large / unreadable, upload failed, attach failed, config change failed) is written to `ChatStore.error` and drawn as one red line at the very end of the transcript (ChatView.swift ~315), cleared only by the next attach (AppModel.swift:528). Show each error next to what it is about, with a way out, and clear it when it no longer applies.

## Files
- ios/Leash/Core/ChatModels.swift (ChatStore.error), ios/Leash/Core/AppModel.swift (sites ~538, 666, 703, 774, 797, 814, 838), ios/Leash/Core/AppModel+Remote.swift:219
- ios/Leash/Views/ChatView.swift (transcript ~294-325; attachment errors ~583-584; upload ~858; composer `slab` ~518)
- ios/Leash/Localizable.xcstrings

## Open questions (default picked)
- Split the one field by where it belongs. Default: `composerError` (anything caused by what is in the composer: send refused before any turn started — the draft is already restored —, attachments too large/unreadable, upload failed) and `loadError` (the conversation could not be attached/loaded). Classify every other site by the same question; a failure of a turn already running stays a transcript notice as the bridge sends it.
- Composer error look: one line inside the composer slab, above the text field, like the existing shell/btw status lines (`Motion.reveal`), `Theme.danger` icon + caption text, a trailing xmark button (44pt hit area, `.pressable`) to dismiss. Cleared on dismiss, on the next send, and when the attachment it names is removed.
- Load error look: in place of the transcript content (where "Loading conversation…" was), a `ContentUnavailableView` with the message and a "Try Again" button that calls `model.attach(store)` — like the existing lockedBy card's Retry. Default: yes.
- Haptics stay as they are.

## Steps
1. Replace `ChatStore.error` with the two fields (no shim), update every writer and the clears.
2. Composer line + dismiss; load-error view with Try Again; delete the bottom-of-transcript red label.
3. Strings + zh-Hans.

## Check
- Attach a >25 MB file: the error shows in the composer, goes when dismissed or when sending.
- Send while the Mac refuses before the turn starts: draft restored, error line in the composer above it.
- Open a conversation while the bridge errors on attach: error in place of the transcript, Try Again reloads.
