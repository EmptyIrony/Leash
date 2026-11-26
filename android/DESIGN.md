# Leash for Android — design system

The iOS app is the reference. This document says what makes it feel good, and how each piece translates to Compose
without turning Android into a fake iPhone: the **look** is Leash's own (palette, type rhythm, shapes, glass, motion),
the **behaviour** is Android's (back gesture, insets, IME, TalkBack, notifications, overscroll, long-press menus).

Code lives in \`dev.leash.personal.ui.design\` (theme, tokens, shapes, motion, haptics, components). The old
\`ui/theme\` stays until D2 switches every screen over; nothing here changes an existing screen by itself.
A debug-only **Design catalog** activity (\`src/debug\`) shows every component in light and dark.

References: iOS screenshots \`.shots/IMG_5521–5524.JPG\` (list, chat, settings sheet, chat with long answer; all light
mode — the dark spec below comes from the palette and the SwiftUI code, not from a screenshot),
\`ios/Leash/Theme/Theme.swift\`, \`ios/SharedDesign/LeashPalette.swift\`, \`LeashMotionCurves.swift\`, \`ios/Leash/Views/*\`.

## 1. What makes the iOS version feel good

1. **Content first, chrome floats.** No bars with backgrounds: the large title sits on the page, toolbar buttons are
   48 pt glass circles, the composer is a glass slab with chips under it, and content scrolls *under* all of it with a
   soft blurred edge. The transcript reads like a document, not a chat app.
2. **One accent, colour means something.** Ink in three levels (primary / secondary / tertiary) does all hierarchy.
   Indigo is the only "brand" colour (send, links, selection, user bubble tint). Cyan = working, amber = needs you,
   green/red = outcome and diff, pink = turbo/approve-all, teal = shell. Never decorative.
3. **Few boxes.** Agent text has no bubble. Activity is a quiet header ("Worked for 18s ⌄ / Explored 2 files ›") in
   secondary ink. Only the user's message is a tinted bubble; only sunken things (code, diff, shell output) and a few
   raised things (plan, table, grouped list) get containers. Boxes have hairlines, not shadows.
4. **Continuous corners at a few fixed radii** (26 composer, 22 trays, 20 bubbles, 16 cards, 12 rows, 10 insets,
   capsules for controls). Everything looks machined from one set of tools.
5. **Calm, physical motion.** Four critically-damped or barely-bouncy springs for everything; trays materialise,
   rows reveal 6 pt from above, controls pop from 0.9, text swaps through a blur. Presses shrink 4% and dim; nothing
   ripples. Reduce Motion turns movement into short cross-fades.
6. **One live signal.** A running turn shows a single light band sweeping across the 21-dot glyph and into the
   "Working · 12s" words on one clock — not a spinner per row.
7. **Typography with restraint.** System font, a 16 pt reading size for agent text with generous line spacing,
   monospace only for code/paths/commands, numbers monospaced-digit where they tick.
8. **Space background.** A barely-there vertical gradient with a soft indigo glow top-trailing (and 60 faint stars in
   dark mode) makes plain surfaces feel like a place.

The current Android build (screenshots \`scratchpad/shots/*.png\`) is the stock Material template: outlined segmented
buttons with a check mark, filled Material icons, a big indigo extended FAB, outlined text fields, flat TopAppBars,
ripples, Roboto at Material sizes, no glass, no floating chrome. Structure and data are right; finish is not.

## 2. Colour

Values are \`LeashPalette\` (shared with iOS; \`ui/theme/LeashPalette.kt\` stays the single source). Derived tokens
(alphas over the role) are named so D2 never invents an alpha.

| Token | Light | Dark | Use |
|---|---|---|---|
| bgBase | #F4F5FA | #07080D | page, top of space gradient, sheet background |
| bgGradientEnd | #E9ECF8 | #0B0E1A | bottom of space gradient |
| bgRaised | #FFFFFF | #12141C @ 85% | grouped lists, cards, plan, tables |
| bgSunken | #ECEEF5 | #0C0D14 | code, diff, shell output, inline errors, text-field wells |
| hairline | #0B1020 @ 8% | #FFFFFF @ 8% | separators, card strokes, disabled send fill, rail |
| textPrimary | #0B0D14 | #F2F3F8 | titles, body |
| textSecondary | #5A6072 | #9AA0B4 | subtitles, activity header, section labels |
| textTertiary | #8F95A6 | #646A7E | timestamps, chevrons, step icons, stats |
| accent | #4B50E6 | #8088FF | send, links, selection, toggles, unread dot |
| running | #0A9CB8 | #4FD8EE | plan mode chip, working states outside the live glyph |
| attention | #C96A00 | #FFB340 | needs you: approvals, questions, banner |
| success | #1E9E5A | #3DDC84 | +adds, done |
| danger | #D43A3A | #FF6B6B | −dels, failures, destructive |
| turbo | #C0267E | #FF5CA8 | approve-all mode, auto-approved bolt |
| codeInk | #3D41A8 | #B9BDFF | inline code text |
| codeBg | accent @ 7% | accent @ 12% | inline code background |
| shell | #0F7B6C | #5EE6C8 | "!" shell mode |

Derived:

- \`onAccent\` white (light) / #07080D (dark). Stop button: fill textPrimary, glyph bgBase.
- \`bubbleFill\` accent @ 16%; \`shellBubbleFill\` shell @ 13%; \`commandFill\` hairline.
- \`selectedFill\` accent @ 12% (choice rows, picker rows @ 10%); \`restFill\` textPrimary @ 4%.
- \`pressedOverlay\` textPrimary @ 6% (full-width rows only, see press feedback).
- \`diffAddBg\` success @ 15%, \`diffDelBg\` danger @ 15%.
- \`needsYouBadge\` attention @ 15% circle behind the hand glyph.
- \`glassTint\` white @ 72% (light) / #1A1D29 @ 70% (dark); \`glassStroke\` white @ 60% (light) / white @ 10% (dark),
  0.5 dp inner stroke; \`glassFallback\` (blur off) white @ 94% / #161925 @ 96%.
- \`scrim\` (behind sheets) #0B1020 @ 25% (light) / black @ 50% (dark).
- Status dot: success = connected, attention = connecting, danger = failed, textTertiary = idle.

No dynamic colour. Light/dark follows the system; the catalog can force either.
High contrast (Android "high contrast text" / contrast level > 0 on API 34+): live glyph base alpha 0.7 instead of 0.5,
hairline 14%.

## 3. Typography

System sans (Roboto / the OEM sans; CJK falls back to the system CJK font, which carries most of the user's text) and
system monospace. No bundled font: a Latin webfont next to system CJK would mismatch, and the iOS app also uses the
system face. Sizes follow iOS Dynamic Type "Large" one step tighter for body sizes (the emulator is 360 dp wide vs the
440 pt iPhone in the shots); everything in \`sp\` so the system font scale applies.

| Role | sp / line | Weight | iOS source |
|---|---|---|---|
| largeTitle | 32 / 40 | Bold, −0.4 sp tracking | navigation large title "Leash" |
| title1 | 26 / 32 | SemiBold | empty-state titles |
| title2 | 21 / 27 | SemiBold | sheet titles |
| title3 | 19 / 24 | SemiBold | markdown h1 |
| headline | 16 / 22 | SemiBold | inline nav title, row emphasis, md h2 |
| body | 16 / 22 | Regular | composer text, list titles (Medium), choices |
| reading | 16 / 24 | Regular | agent markdown, user bubble (iOS readingSize 16 + lineSpacing 3–4) |
| callout | 15 / 20 | Regular | tray rows, shell bubble |
| subheadline | 14 / 19 | Regular / Medium / SemiBold | activity header (Medium), section labels (SemiBold), tray titles |
| footnote | 13 / 18 | Regular / Medium | sub-lines, step titles, row dates, chips (Medium) |
| caption | 12 / 16 | Regular / Medium | composer status lines, counters |
| caption2 | 11 / 13 | Regular | diff gap labels, stats grid labels |
| mono | 13 / 18 | Regular | commands, paths, step titles for edits/commands, code blocks |
| monoSmall | 11 / 14 | Regular | diff lines, stats line, tool output |

Rules: tracking −0.4 sp on largeTitle, −0.2 sp on title1–3, 0 elsewhere. Tabular (monospaced) digits
(\`fontFeatureSettings = "tnum"\`) for ticking numbers: elapsed time, counters, quota %, +/−. Section labels are
sentence case (no all-caps Material overline). Weights use the variable system font on API 31+ (true 500/600);
older versions snap to the nearest face.

## 4. Spacing and layout

4 dp grid. Tokens: \`xxs 2, xs 4, s 6, m 8, l 12, xl 16, xxl 20, xxxl 24, huge 32\`.

- Page margin 16 dp (transcript text, large title, grouped-list outer inset).
- Grouped list card: inset 16 dp each side, rows 16 dp horizontal padding, separator inset to the text start.
- Transcript: 12 dp between a user bubble and the turn before it; 6 dp header ↔ sub-line; agent paragraphs 10 dp.
  User bubble keeps ≥ 48 dp free on its leading side.
- Composer area: 12 dp side inset, 10 dp above, 6 dp below the chip row; chip spacing 8 dp.
- Minimum touch target 48 dp (Android) even when the drawn control is 28–34 dp (expand the hit area, not the look).
- Activity rows: 18 dp glyph column + 8 dp gap; step rows indent 6 dp, 16 dp icon column, min height 28 dp (hit area
  extended to 40 dp by the row's own padding), rail 1.5 dp hairline at x = 9 dp.

## 5. Shapes

All rounded rectangles are **continuous** (squircle) corners: \`ContinuousShape(radius, smoothing = 0.6)\`, a
figma-squircle style curve (cubic → arc → cubic per corner) that matches iOS \`.continuous\`. Radii larger than half
the short side clamp to a capsule. Per-corner radii supported (bubble tail).

| Role | Radius | Where |
|---|---|---|
| composer | 26 dp | composer slab |
| sheet | 28 dp top corners | bottom sheets |
| group | 24 dp | grouped list cards, settings groups |
| tray | 22 dp | approval / question / queue / subagent / side-question / command trays, menus |
| bubble | 20 dp, tail corner 6 dp | user message (tail = bottom-end) |
| card | 16 dp | plan, table, quota, load/lock notices, images in a bubble |
| row | 12 dp | selectable rows, file chips, thumbnails, picker rows |
| inset | 10 dp | code, diff, shell output, inline error boxes |
| capsule | 50% | chips, buttons, segmented control, banner, status pill |
| circle | — | toolbar buttons, send/stop, scroll-to-bottom, badges |

## 6. Surfaces, glass and elevation

- **Space background**: vertical gradient bgBase → bgGradientEnd, plus a radial accent @ 10% glow centred on the
  top-end corner, radius 320 dp; dark mode adds 60 seeded stars (r 0.5–0.75 dp, white @ 25–60%). Reduce
  transparency has no Android switch; the high-contrast setting drops glow and stars.
- **Raised**: bgRaised fill + 1 dp hairline stroke (cards) or no stroke (grouped list, like iOS).
- **Sunken**: bgSunken + 1 dp hairline stroke, radius inset.
- **Glass** (where iOS uses Liquid Glass: toolbar circles, composer slab, chips, trays, scroll-to-bottom, banner,
  "starting" capsule, menus): on API 31+ a real backdrop blur via Haze (\`hazeSource\` on the scrolling content,
  \`hazeEffect\` on the glass) — blur 24 dp, \`glassTint\` on top, noise 0, plus a 0.5 dp \`glassStroke\` and the
  floating shadow. Below API 31 (and when blur is off) the solid \`glassFallback\` fill. Glass never sits on glass
  (chips are separate pieces under the slab, not inside it). Tints only for meaning: shell slab shell @ 16%, side
  question accent @ 14%, turbo chip turbo @ 45% → turbo @ 25% on Android (blur is weaker), banner attention @ 35%.
- **Top edge**: content scrolls under a transparent top bar; on API 31+ a progressive blur (mask: opaque at the
  status bar, transparent 24 dp below the bar) with a bgBase gradient scrim; below 31 only the gradient scrim.
- **Elevation**: no Material tonal elevation anywhere. Shadows only on floating things: glass controls
  (ambient: y 2 dp, blur 10 dp, #1E2250 @ 10% light / black @ 35% dark), segmented thumb and switch thumb
  (y 1 dp, blur 3 dp, black @ 12%). Cards and lists: hairlines.

## 7. Motion

The four iOS curves (SwiftUI \`spring(duration:bounce:)\` → stiffness (2π/d)², damping ratio 1 − bounce):

| Token | iOS | Compose spring | Reduced motion |
|---|---|---|---|
| layout | .smooth(0.34) | dampingRatio 1.0, stiffness 341.5 | tween 200 ms, FastOutSlowIn (≈ easeInOut) |
| disclose | .snappy(0.30) | dampingRatio 0.85, stiffness 438.6 | tween 180 ms |
| quick | .smooth(0.22) | dampingRatio 1.0, stiffness 815.6 | tween 150 ms |
| press | .snappy(0.18) | dampingRatio 0.85, stiffness 1218.5 | tween 100 ms, ease-out |

Reduced motion = \`ValueAnimator.areAnimatorsEnabled() == false\` (Developer options / "Remove animations"), read
once per composition into \`LocalReduceMotion\`.

Transitions (enter / exit):

- **layout**: trays, banners, rows moving — expandVertically + fade (layout spring).
- **reveal**: content under a disclosure — fade + slide from −6 dp in; fade out.
- **pop**: a control appearing in place (stop, scroll-to-bottom, badges) — scale 0.9 → 1 + fade.
- **swap**: one line replaced by another (live status, tray contents) — cross-fade with an 8 dp blur on the outgoing
  and incoming content (API 31+ \`Modifier.blur\`; fade only below).
- **sent**: a just-sent message — slide up 24 dp + fade in.
- **trailingPop**: secondary stop sliding out from behind send — scale 0.6 from the end edge + fade.
- **banner**: slide down from the top + fade.
- **materialize** (glass tray appearing): scale 0.96 → 1 + fade + blur 8 → 0 on the content.

Navigation: Compose Navigation with predictive back. Push = new screen slides in from the end (layout spring), the
old one shifts 25% and dims; back (gesture or button) plays it in reverse and is scrubbed by the predictive back
gesture. Sheets use the M3 sheet with its predictive-back shrink.

Live signals:

- **Shimmer** (the "Working · 12s" header): a band \`max(45% of span, 60 dp)\` wide sweeps leading → trailing every
  2.0 s, linear, painted \`SrcAtop\` onto the text's own pixels with a horizontal gradient clear → glint @ 90% → clear;
  glint = 50/50 mix of accent and textPrimary. Off with reduced motion.
- **LiveDots** (glyph before the header): 21 accent dots (5×5 minus corners) in the header's glyph box; each dot
  reads the same band on the same clock: v = triangle(band position) ∈ 0…1, diameter = pitch × (0.5 + 0.29 v),
  alpha = base + (1 − base) v, base 0.5 (0.7 high contrast). Reduced motion: no sweep, the whole glyph breathes
  alpha 0.55 → 1 on the same 2 s period. Both take an optional fixed phase so screenshots are deterministic.
- Spinners: the platform's indeterminate circular indicator, 2 dp stroke, textSecondary, sized to the line
  (11/12/16 dp). Spinners are for "busy", not for a live turn.
- Numbers that tick use \`AnimatedContent\` with a vertical roll (numericText).

## 8. Press feedback and indication

- No ripples. \`LeashIndication\` scales the pressed node (compact controls 0.96, full-width rows 0.98, text links
  and disclosure headers 1.0) and dims it to 85% with the press spring; with reduced motion only the dim.
- Full-width rows inside grouped lists additionally get \`pressedOverlay\` (iOS list rows highlight).
- Keyboard / D-pad focus: a 2 dp accent focus ring outside the shape (Android accessibility expectation; iOS has none).
- Wrapped M3 pieces (sheet, menu, pull-to-refresh) run inside \`LeashDesignTheme\`, which provides
  \`LocalIndication = LeashIndication\` and \`LocalRippleConfiguration = null\`, so they don't ripple either.

## 9. Haptics

Same moments as iOS (\`ChatView\` / \`Haptics\`), through \`View.performHapticFeedback\` (honours the system
"touch feedback" setting) and the app's own Haptics switch. Mapping of the core \`Haptic\` enum:

| Haptic | iOS | Android |
|---|---|---|
| Selection | picking a value, attaching, mode/model change, tray dismiss | SEGMENT_TICK (34+) / CLOCK_TICK |
| Light | message sent, copy | CONTEXT_CLICK |
| Medium | new conversation created | VIRTUAL_KEY |
| Heavy | stop a turn (rigid) | GESTURE_THRESHOLD_ACTIVATE (34+) / LONG_PRESS |
| Soft | — (reserved) | CLOCK_TICK |
| Success | long task finished on screen, paired, shown on computer | CONFIRM (30+) |
| Warning | agent needs you (new pending request here) | REJECT? no — see review |
| Error | send failed, bad pairing link, upload error | REJECT (30+) |

Never for decoration, always paired with a visible change.

## 10. Iconography

SF Symbols map to Material Symbols' **Rounded/Outlined** style at weight 400–500, never the filled default set.
\`LeashIcons\` names every glyph the app uses (by iOS meaning: \`handRaised\`, \`squareAndPencil\`, \`chevronDown\`, …)
so D2 never picks an icon ad hoc. Rules: outline by default, filled only where iOS uses \`.fill\` (hand.raised.fill,
lock.fill, stop.fill, checkmark.circle.fill in checklists, xmark.circle.fill on thumbnails). Sizes follow the text
they sit with: caption2 → 12 dp, footnote → 16 dp, body → 20 dp, toolbar → 22 dp. Chevrons are small and tertiary
(12 dp, semibold-looking). Back stays the Android arrow (\`ArrowBack\`, auto-mirrored) inside the glass circle —
an Android affordance in Leash's material.

## 11. Components

Each component takes all text as parameters (no strings in the design package).

**Top bars.** Two kinds, both transparent with the soft top edge (§6):
- *Large* (list): 48 dp glass circle buttons in a row at the top (leading: connection glyph with a 7 dp status dot;
  trailing: quota rings / settings), then the largeTitle 16 dp from the start and a footnote subtitle in
  textSecondary ("bridge · 2 agents ready"). The title scrolls away with the content; when it is gone an inline
  title (headline) fades in between the circles.
- *Inline* (chat, settings, subagent): 48 dp glass circle back button at the start, centred two-line title
  (headline, 1 line, ellipsis; footnote subtitle textSecondary) and up to two 48 dp glass circle actions at the end
  (overflow = "more horiz" dots in accent, like iOS).

**Grouped list (inset grouped).** Section header outside the card: optional 14 dp glyph + subheadline SemiBold
textSecondary, sentence case, trailing disclosure chevron (rotates 180° with disclose) or a count. Card: bgRaised,
radius group, 16 dp outer inset. Rows separated by a 0.5 dp hairline inset to the text start; last row none. Footer
text: footnote textSecondary, 16 dp inset.

**Session row.** 9 dp unread-dot gutter (dot accent, scales 0.4 → 1 + fades, title never shifts) · title body Medium,
2 lines · meta line footnote: agent icon 11 dp, "Draft: …" (SemiBold word + Regular snippet), relative time in
textSecondary · trailing status (26 dp): needs you = hand.fill attention in a needsYouBadge circle; running = 16 dp
spinner; locked = lock.fill tertiary; else a 12 dp chevron.right tertiary. Min height 64 dp, vertical padding 12 dp.
"Show all (15)" row: body accent + chevron.

**Segmented control.** Capsule track \`textPrimary @ 6%\` (dark: white @ 8%), 36 dp high, 2 dp padding; thumb is a
capsule in bgRaised (dark: #2A2D3A) with the thumb shadow, sliding between segments with the disclose spring;
labels subheadline Medium, selected textPrimary, others textSecondary. No check marks, no outline.

**Buttons.**
- *Prominent*: accent fill, onAccent text body SemiBold, capsule, height 50 dp (large) / 44 dp (regular) /
  32 dp (small, subheadline SemiBold, for "Send Now" in the queue tray).
- *Tinted* (iOS bordered): \`textPrimary @ 6%\` fill, accent text; *destructive tinted*: danger @ 12% fill, danger text.
- *Plain*: accent text only, 48 dp hit area (settings actions like "Save & connect", "Try Again").
- *Glass circle*: 48 dp (toolbar) / 36 dp (scroll-to-bottom), glass surface, 22/18 dp icon textPrimary.
- *Send / stop*: one 34 dp circle that morphs (quick spring): send = accent fill + arrow.up white (shell: shell fill +
  return glyph); disabled = hairline fill + tertiary glyph, scale 0.92; stop = textPrimary fill + stop.fill in bgBase.
  While running with a draft, a 28 dp secondary stop (hairline 1.5 dp ring, stop glyph secondary) pops out of the
  send circle's leading side (trailingPop). Long-press on send while running = "Interrupt and send" menu.
- Disabled = 40% alpha (iOS dims to 0.5 on the composer).

**Chips (composer row).** Glass capsule 32 dp, 12 dp horizontal padding, 14 dp icon + footnote Medium text + 9 dp
chevron.down at 60% alpha; tints: turbo (approve-all), running (plan); quota chip shows a 14 dp ring + "Week 77%"
with tabular digits, ring tint by remaining fraction (accent > 40%, attention ≤ 40%, danger ≤ 15%).

**Toggle.** Leash's switch, not the M3 one: 51 × 31 dp capsule, track hairline-on → accent, 27 dp white thumb with
the thumb shadow, travel on the quick spring, Selection haptic. TalkBack role Switch.

**Text field rows (settings).** In a grouped card: label body textPrimary at the start, value body textPrimary right-
aligned, editable in place; placeholder textTertiary. Multi-line token: monospaced body, sunken well inside the row.
No outlined Material fields.

**User bubble.** reading text, 14 / 10 dp padding, bubbleFill, ContinuousShape(20, tail bottom-end 6). Shell input:
"!" mono Bold in shell + command mono callout, shellBubbleFill. Slash command: capsule commandFill, mono callout
textSecondary with a command glyph. Images: radius card, max 220 dp. Long-press: Copy / Select text menu.

**Agent answer.** No container. Markdown on the reading style: h1 19 SemiBold, h2 17 SemiBold, h3 16 SemiBold,
6 dp extra top; bullets 4 dp tertiary dots, 16 dp indent per level; quote 2 dp accent @ 35% capsule bar + secondary
text; rule 1 dp hairline; inline code codeInk on codeBg, 4 dp radius, mono at 0.92× the text size; links accent.

**Code block.** Sunken inset box: header row (caption2 language in textTertiary · copy glyph 14 dp), code mono 13 sp
textPrimary, 2 dp line spacing, 10 dp padding, horizontal scroll, never wraps.

**Activity header.** 18 dp glyph column (LiveDots while live; checkmark.circle tertiary when done; exclamation
circle attention on failure; stop.circle on cancel; hand.raised.fill attention pulsing when blocked) · "Working · 12s"
/ "Worked for 1m 3s" subheadline Medium textSecondary (+ shimmer while live) · chevron.down 10 dp tertiary rotating
180° · optional trailing checklist ring (13 dp, 1.8 dp stroke) + "2/4 steps" footnote tabular · sub-line footnote
textTertiary (textSecondary live, attention when blocked), 1 line, middle ellipsis, swap transition.

**Step row.** Rail + rows: 16 dp tertiary icon, title footnote (thought: italic secondary; command/edit: mono 13
textPrimary; other: footnote textPrimary), 1 line middle-ellipsis (unlimited when open), trailing +a −d monoSmall in
success/danger, turbo bolt 9 dp, failure xmark danger, 11 dp spinner in progress. Press scale 0.98 (1.0 when open).
Details reveal 24 dp indented.

**Diff.** Sunken inset box with 1 dp hairline; header strip on hairline fill: path caption mono (head-truncated) +
"+a −d"; lines monoSmall with 8 dp padding, add/del rows on diffAddBg / diffDelBg with "+"/"−" sign column, gaps
"⋯ 12 unchanged lines" caption2 tertiary; "Show whole file" plain caption button.

**Cards.** Plan / table / quota / notices: bgRaised, radius card, 1 dp hairline, 14 dp padding; header subheadline
SemiBold with a trailing count (caption tertiary) and a disclosure chevron.

**Changes row.** "N files changed +a −d": bgSunken, radius row, hairline, 44 dp min height, 12 dp padding,
subheadline + tabular counts.

**Trays (above the composer).** Glass, radius tray, 14 dp padding (queue: 14 start / 6 end / 8 vertical), docked
12 dp from the sides, materialize transition, contents swap with blur when one request replaces another (the shell
stays, its height animates on the layout spring). Approval: hand.raised.fill attention + title subheadline SemiBold
(2 lines) + "1 of 3" caption tabular; command in a sunken mono box (4 lines); buttons side by side (Deny tinted
destructive, Always tinted, Allow prominent, all 44 dp), stacked with Allow first when they don't fit. Question:
questionmark bubble glyph accent + "Agent asks"; option rows radius row, restFill, selected = selectedFill with a
radio/check glyph (selection never by colour alone), 44 dp min. Queue: clock + "2 queued" + small prominent
"Send Now"/"Interrupt and Send"; rows callout with an × (44 dp hit); removed row "Removed · Undo".

**Composer.** Glass slab, radius composer, min height 48 dp, 8 / 6 dp padding: leading 34 dp "+" (secondary; shell
mode → terminal glyph in shell; side question → bubbles glyph in accent), text field body (mono in shell mode),
1–8 lines growing upward, buttons stay on the last line; trailing send/stop. Above the field inside the slab: status
lines (caption Medium: error with × , shell cwd, side-question note) and attachment thumbnails (56 dp, radius row).
Whole slab 50% alpha when disabled. Rides the IME (\`imePadding\`), never covered.

**Sheets.** M3 \`ModalBottomSheet\` restyled: container bgBase, top corners sheet radius, grabber 36 × 5 dp capsule
textTertiary @ 40%, scrim token, no tonal elevation; header row: centred title (headline) and a glass "Done" capsule
at the end (accent text) when the sheet has edits, else none (drag/back closes). Content uses grouped lists.
Predictive back shrinks it (platform).

**Menus.** Long-press and overflow menus: M3 \`DropdownMenu\` restyled to a glass panel (radius tray, 0.5 dp stroke,
floating shadow), items 48 dp, body text + trailing 20 dp outline icon (iOS order), destructive items danger,
dividers hairline 8 dp gaps.

**Banner.** Other conversation needs you: glass capsule 40 dp tinted attention, hand glyph attention + subheadline
Medium text + chevron, 16 dp side inset, banner transition.

**Empty state.** Centred: 44 dp outline glyph textTertiary, title2-ish headline (title3 SemiBold) textPrimary,
message subheadline textSecondary (max 280 dp), optional prominent button. Search empty: "No results for “…”".

**Loading state.** First load: centred 16 dp spinner + caption "Loading…" in textSecondary after 300 ms (no flash
for quick loads). Lists may show skeleton rows: restFill capsules (title 60% width, meta 30%) with the shimmer band
at 50% strength.

**Status pill.** Capsule hairline fill, 8 dp dot + caption Medium text, 10 / 5 dp padding.

**Unread dot / badges.** Unread 9 dp accent; collapsed-section dot 7 dp; counts subheadline tabular tertiary.

**Ring gauge.** 14 dp default, 2 dp stroke, track textTertiary @ 35% (dashed 2/2 when unknown), progress round cap
from 12 o'clock.

## 12. Android behaviour that stays native

Edge-to-edge with transparent system bars (icon colour per theme); insets via \`WindowInsets\` (status bar above the
top bar, navigation bar below the composer, IME lifts the composer); predictive back everywhere; long-press opens
menus (no iOS context-menu previews); overscroll stretch (Android 12+) instead of rubber-banding; pull-to-refresh
uses the M3 pull-to-refresh state with a glass-circle indicator; text selection handles and cursor in accent;
TalkBack labels and roles on every custom control; system font scale and display size respected; notifications use
system templates tinted with accent (phase 3).

## 13. App icon

Adaptive icon from \`design/icon\`: background layer = the light Orbit surface (vertical gradient #F7F8FC → #E2E6F6
with the white→indigo radial glow), foreground = the 16 × 16 pixel lead glyph scaled into the 66 dp safe circle, with
a soft offset shadow approximated by stacked translucent copies (vector drawables have no blur filter). Monochrome
layer (Android 13 themed icons) = the glyph with each pixel's grey from \`leash-icon-tinted.svg\` mapped to alpha, so
the launcher tint keeps the shading.

## 14. Implementation map

- \`ui/design/ContinuousShape.kt\` (+ pure geometry in \`SquircleGeometry\`, unit-tested).
- \`ui/design/Tokens.kt\` — colours (\`LeashDesignColors\`), spacing, radii, type scale (\`LeashType\`).
- \`ui/design/Motion.kt\` — spring tokens (pure conversion, unit-tested), transitions, \`LocalReduceMotion\`.
- \`ui/design/Theme.kt\` — \`LeashDesignTheme(dark)\`: provides colours, type, indication, ripple off, haze state,
  plus a Material colour scheme mapped from the tokens so wrapped M3 pieces pick Leash colours.
- \`ui/design/Haptics.kt\`, \`Indication.kt\`, \`Glass.kt\`, \`Background.kt\`, \`Live.kt\` (shimmer, LiveDots, ring), \`Icons.kt\`.
- \`ui/design/components/*\` — the components above.
- \`src/debug/.../DesignCatalogActivity\` — pages × light/dark via intent extras.
