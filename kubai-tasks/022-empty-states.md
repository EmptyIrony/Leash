# 022 Empty states — conversation list

Tip: https://vibedesign.tips/022-empty-states/ (full text: .claude/skills/kubai-design-tips/tips/022-empty-states.md)

## Goal
The conversation list never goes blank: a search with no matches says so, and the first-use empty state has a button that starts a conversation.

## Files
- ios/Leash/Views/SessionListView.swift — `SessionListView.body` overlay (~line 129), `filtered`, `needsYou`, `grouped`; `FolderSessionsView` (~line 290).
- ios/Leash/Localizable.xcstrings

## Open questions (default picked)
- No-results look: `ContentUnavailableView.search(text: search)` (system; names the query, keeps the search field as typed). Default: yes.
- Agent filter set, search empty, but that agent has no conversations: default `ContentUnavailableView("No conversations", …)` same as first use, without the button? → show the first-use view (the button starts a conversation anyway).
- First-use button: label "New Conversation" (reuse an existing string key if one exists, e.g. in NewSessionView), style `.glassProminent`, opens `showNew = true`. Description: drop "Start one with the compose button." (the button says it). Default: drop it.
- Folder screen (FolderSessionsView) search with no matches: same `.search(text:)` overlay.

## Steps
1. In SessionListView, overlay: if `searching && needsYou.isEmpty && grouped.isEmpty` → `ContentUnavailableView.search(text: search)`; else the existing first-use condition → view with the action button.
2. Same no-results overlay in FolderSessionsView.
3. Strings + zh-Hans.

## Check
- Search "zzzz" in the list and in a folder's Show All screen: system no-results view naming "zzzz", search text kept.
- Clear the search: list returns, nothing else moves.
- No conversations at all: icon, title, one prominent button that opens New Conversation.
