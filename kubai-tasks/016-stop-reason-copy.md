# Stop reason in plain words (no tip covers this)

## Goal
ChatView.swift (~312) shows `Label("Stopped · \(stop)")` with the raw ACP stop reason (max_tokens, max_turn_requests, refusal, cancelled). Show plain, localized words.

## Defaults
- max_tokens → "Stopped: the reply hit its length limit"; max_turn_requests → "Stopped: too many steps in one turn"; refusal → "The agent declined to continue"; cancelled → "Stopped"; anything else → "Stopped". zh-Hans for each.
- Keep the stop.circle symbol, caption, Theme.textTertiary.

## Check
Each reason reads as a sentence in en and zh-Hans; no raw identifiers on screen.
