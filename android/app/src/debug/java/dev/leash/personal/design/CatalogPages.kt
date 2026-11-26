package dev.leash.personal.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.leash.personal.R
import dev.leash.personal.ui.design.ContinuousShape
import dev.leash.personal.ui.design.GlassScope
import dev.leash.personal.ui.design.Leash
import dev.leash.personal.ui.design.LeashIcons
import dev.leash.personal.ui.design.LeashShapes
import dev.leash.personal.ui.design.RingGauge
import dev.leash.personal.ui.design.SpaceBackground
import dev.leash.personal.ui.design.Spinner
import dev.leash.personal.ui.design.components.*
import dev.leash.personal.ui.design.glass
import dev.leash.personal.ui.design.GlassSource
import dev.leash.personal.ui.design.medium
import dev.leash.personal.ui.design.semibold
import dev.leash.personal.ui.design.softBottomEdge
import dev.leash.personal.ui.components.AgentIcon

/** A page: space background, content scrolling under a floating top bar (glass sees the content). */
@Composable
private fun Page(
    title: String,
    subtitle: String? = null,
    bottom: (@Composable BoxScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassScope(Modifier.fillMaxSize()) {
        GlassSource(Modifier.fillMaxSize()) {
            SpaceBackground()
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(top = 72.dp, bottom = if (bottom != null) 150.dp else 32.dp).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
        TopBar(
            title = title, subtitle = subtitle,
            navigation = { BackButton("Back", onClick = {}) },
            actions = { GlassCircleButton(LeashIcons.more, "More", onClick = {}, tint = Leash.colors.accentControl) },
        )
        if (bottom != null) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), content = bottom)
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = Leash.type.caption.medium(), color = Leash.colors.textSecondary, modifier = Modifier.padding(horizontal = 16.dp))
}

@Composable
fun IndexPage(open: (String) -> Unit) {
    val pages = listOf("colors", "type", "shapes", "controls", "list", "settings", "chat", "activity", "trays", "states", "sheet", "icon")
    Page("Design catalog", "Leash design system") {
        GroupCard {
            pages.forEachIndexed { i, p -> ValueRow(p, last = i == pages.lastIndex, chevron = true, onClick = { open(p) }) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorsPage() {
    val c = Leash.colors
    val swatches = listOf(
        "bgBase" to c.bgBase, "bgGradientEnd" to c.bgGradientEnd, "bgRaised" to c.bgRaised, "bgSunken" to c.bgSunken,
        "hairline" to c.hairline, "textPrimary" to c.textPrimary, "textSecondary" to c.textSecondary, "textTertiary" to c.textTertiary,
        "accent" to c.accent, "accentControl" to c.accentControl, "running" to c.running, "attention" to c.attention,
        "success" to c.success, "danger" to c.danger, "turbo" to c.turbo, "shell" to c.shell,
        "codeInk" to c.codeInk, "codeBg" to c.codeBg, "bubbleFill" to c.bubbleFill, "selectedFill" to c.selectedFill,
        "diffAddBg" to c.diffAddBg, "diffDelBg" to c.diffDelBg, "glassTint" to c.glassTint, "glint" to c.glint,
    )
    Page("Colours", if (c.dark) "Dark" else "Light") {
        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 4) {
            swatches.forEach { (name, color) ->
                Column(Modifier.width(76.dp)) {
                    Box(Modifier.fillMaxWidth().height(44.dp).clip(LeashShapes.row).background(color).border(1.dp, c.hairline, LeashShapes.row))
                    Text(name, style = Leash.type.caption2, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
fun TypePage() {
    val t = Leash.type
    val c = Leash.colors
    val rows = listOf(
        "largeTitle 32" to t.largeTitle, "title1 26" to t.title1, "title2 21" to t.title2, "title3 19" to t.title3,
        "headline 16" to t.headline, "body 16" to t.body, "callout 15" to t.callout, "subheadline 14" to t.subheadline,
        "footnote 13" to t.footnote, "caption 12" to t.caption, "caption2 11" to t.caption2, "mono 13" to t.mono, "monoSmall 11" to t.monoSmall,
    )
    Page("Type", "System sans + mono") {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            rows.forEach { (n, s) -> Text("$n · Leash 拴绳", style = s, color = c.textPrimary, maxLines = 1) }
            Spacer(Modifier.height(6.dp))
            Text("reading 16/24 — 推测的原因是这样的：没缓存过的对话打开时，先显示「正在加载」，然后整段记录一次性进来。Mixed Latin and CJK keep the line height.", style = t.reading, color = c.textPrimary)
            Text("Ink: primary", style = t.body, color = c.textPrimary)
            Text("Ink: secondary — sub-lines, labels", style = t.body, color = c.textSecondary)
            Text("Ink: tertiary — glyphs and timestamps only", style = t.body, color = c.textTertiary)
        }
    }
}

@Composable
fun ShapesPage() {
    val c = Leash.colors
    Page("Shapes", "Continuous corners") {
        Label("Continuous (left) vs plain rounded (right), 26 dp")
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(150.dp, 96.dp).clip(ContinuousShape(26.dp)).background(c.accent))
            Box(Modifier.size(150.dp, 96.dp).clip(RoundedCornerShape(26.dp)).background(c.accent))
        }
        Label("Radii by role: composer 26 · group 24 · tray 22 · bubble 20/6 · card 16 · row 12 · inset 10 · capsule")
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            listOf<Shape>(LeashShapes.composer, LeashShapes.group, LeashShapes.tray, LeashShapes.bubble, LeashShapes.card, LeashShapes.row, LeashShapes.inset).forEach {
                Box(Modifier.size(40.dp, 56.dp).clip(it).background(c.bgRaised).border(1.dp, c.hairline, it))
            }
            Box(Modifier.size(28.dp, 56.dp).clip(LeashShapes.capsule).background(c.bgRaised).border(1.dp, c.hairline, LeashShapes.capsule))
        }
        Label("Surfaces: raised · sunken · glass over content")
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(100.dp, 72.dp).clip(LeashShapes.card).background(c.bgRaised).border(1.dp, c.hairline, LeashShapes.card), contentAlignment = Alignment.Center) { Text("raised", style = Leash.type.footnote) }
            Box(Modifier.size(100.dp, 72.dp).clip(LeashShapes.inset).background(c.bgSunken).border(1.dp, c.hairline, LeashShapes.inset), contentAlignment = Alignment.Center) { Text("sunken", style = Leash.type.footnote) }
            Box(Modifier.size(100.dp, 72.dp).glass(LeashShapes.tray), contentAlignment = Alignment.Center) { Text("glass", style = Leash.type.footnote) }
        }
        Label("Glass blurs what scrolls under it (API 32+):")
        Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(120.dp)) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(5) { Text("Content under glass · 内容在玻璃下 · `code` line $it", style = Leash.type.body, color = if (it % 2 == 0) c.accent else c.textPrimary) }
            }
            Box(Modifier.align(Alignment.Center).size(220.dp, 64.dp).glass(LeashShapes.composer, blur = false), contentAlignment = Alignment.Center) {
                Text("tint + rim only (inside a band)", style = Leash.type.footnote, color = c.textSecondary)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ControlsPage() {
    val c = Leash.colors
    var seg by remember { mutableIntStateOf(1) }
    var on by remember { mutableStateOf(true) }
    Page("Controls") {
        Label("Buttons: prominent L / R / S, tinted, destructive, plain, disabled")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LeashButton("Scan pairing QR code", {}, Modifier.fillMaxWidth(), size = ButtonSize.Large, icon = LeashIcons.scan)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LeashButton("Deny", {}, Modifier.weight(1f), style = ButtonStyle.Destructive)
                LeashButton("Allow", {}, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                LeashButton("Always allow", {}, style = ButtonStyle.Tinted)
                LeashButton("Send Now", {}, size = ButtonSize.Small)
                LeashButton("Save & connect", {}, style = ButtonStyle.Plain)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LeashButton("Disabled", {}, enabled = false)
                LeashButton("Disabled", {}, style = ButtonStyle.Tinted, enabled = false)
            }
        }
        Label("Segmented control · switch")
        SegmentedControl(listOf("All", "Antigravity", "Claude Code"), seg, { seg = it }, Modifier.padding(horizontal = 16.dp).fillMaxWidth())
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            LeashSwitch(on, { on = it })
            LeashSwitch(!on, { on = !it })
            StatusPill("Connected", c.success)
            NeedsYouBadge()
            UnreadDot(true)
        }
        Label("Composer chips (glass, tints only for meaning)")
        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Sonnet 5.5", {}, leading = { AgentIcon("claude", 14.dp) })
            Chip("High", {}, icon = LeashIcons.effort, chevron = false)
            Chip("Auto", {}, icon = LeashIcons.modeAuto)
            Chip("Turbo", {}, icon = LeashIcons.turbo, tint = c.turbo)
            Chip("Plan", {}, icon = LeashIcons.modePlan, tint = c.running)
            Chip("Week 77%", {}, leading = { RingGauge(0.77f, size = 14.dp) }, chevron = false)
        }
        Label("Glass circles: toolbar 44 · bottom 52 · scroll 36")
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassCircleButton(LeashIcons.server, "Bridge", {}, badge = { Box(Modifier.padding(4.dp).size(9.dp).clip(CircleShape).background(c.success)) })
            BackButton("Back", {})
            GlassCircleButton(LeashIcons.more, "More", {}, tint = c.accentControl)
            GlassCircleButton(LeashIcons.squareAndPencil, "New", {}, size = 52.dp, tint = c.accentControl)
            ScrollToBottomButton("Scroll to bottom", {})
        }
        Label("Send circle: send · shell · disabled · stop · secondary stop")
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            SendButton(SendGlyph.Send, "Send", {})
            SendButton(SendGlyph.ShellRun, "Run", {})
            SendButton(SendGlyph.Disabled, "Send", {})
            SendButton(SendGlyph.Stop, "Stop", {})
            SecondaryStopButton("Stop", {})
        }
    }
}

@Composable
fun ListPage() {
    val c = Leash.colors
    var seg by remember { mutableIntStateOf(1) }
    GlassScope(Modifier.fillMaxSize()) {
        GlassSource(Modifier.fillMaxSize()) {
            SpaceBackground()
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(top = 60.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                LargeTitle("Leash", subtitle = "JerrydeMac-mini · 2 agents ready")
                Spacer(Modifier.height(12.dp))
                SegmentedControl(listOf("All", "Antigravity", "Claude Code"), seg, { seg = it }, Modifier.padding(horizontal = 16.dp).fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                SectionHeader("Needs you", icon = LeashIcons.needsYou)
                GroupCard {
                    SessionRow("Clean up the build directory", status = RowStatus.NeedsYou, last = true) {
                        AgentIcon("claude", 12.dp); Text("1 hour ago")
                    }
                }
                SectionHeader("General", icon = LeashIcons.general, expanded = true, onClick = {})
                GroupCard {
                    SessionRow("Mac ADB Bridge For Ubuntu Docker", unread = true) { Text("2 hours ago") }
                    SessionRow("Migrate the database schema", status = RowStatus.Running) { DraftText("Draft:", "also check the indexes before") ; Text("·"); Text("21h") }
                    SessionRow("排查未知定时推送来源", status = RowStatus.Locked) { Text("22 hours ago") }
                    ShowAllRow("Show all (15)", {})
                }
                SectionHeader("RE_Roguelike", icon = LeashIcons.folder, expanded = false, onClick = {}, trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Spinner(size = 12.dp); UnreadDot(true, size = 7.dp); Text("8", style = Leash.type.subheadline, color = c.textTertiary)
                    }
                })
            }
        }
        TopBar(
            title = "Leash", titleVisible = false,
            navigation = { GlassCircleButton(LeashIcons.server, "Bridge", {}, badge = { Box(Modifier.padding(4.dp).size(9.dp).clip(CircleShape).background(c.success)) }) },
            actions = { GlassCircleButton(LeashIcons.more, "Quota", {}, tint = c.accentControl) },
        )
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(110.dp).softBottomEdge())
        BottomActions(
            Modifier.align(Alignment.BottomCenter),
            start = { GlassCircleButton(LeashIcons.search, "Search", {}, size = 52.dp) },
            end = { GlassCircleButton(LeashIcons.squareAndPencil, "New conversation", {}, size = 52.dp, tint = c.accentControl) },
        )
    }
}

@Composable
fun SettingsPage() {
    var haptics by remember { mutableStateOf(true) }
    var live by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf("100.98.221.39") }
    var token by remember { mutableStateOf("") }
    Page("Settings") {
        SectionHeader("Pairing")
        GroupCard {
            Box(Modifier.padding(12.dp)) { LeashButton("Scan pairing QR code", {}, Modifier.fillMaxWidth(), icon = LeashIcons.scan) }
            Hairline(Modifier.padding(start = 16.dp, end = 16.dp))
            ValueRow("Paste pairing link", icon = LeashIcons.paste, labelColor = Leash.colors.accent, last = true, onClick = {})
        }
        SectionFooter("On the computer running the bridge, run node ~/workspace/Leash/bridge/src/index.mjs pair")
        SectionHeader("Bridge")
        GroupCard {
            FieldRow("Address", host, { host = it })
            ValueRow("Port", value = "8787")
            FieldRow("Token", token, { token = it }, placeholder = "Required", mono = true)
            ValueRow("Save & connect", labelColor = Leash.colors.accent, last = true, onClick = {})
        }
        SectionHeader("Feedback")
        GroupCard { ToggleRow("Haptics", haptics, { haptics = it }, last = true) }
        SectionFooter("Subtle taps for sending, stopping, approvals and finished tasks.")
        SectionHeader("Status")
        GroupCard {
            ValueRow("Connection", trailing = { StatusPill("Demo bridge", Leash.colors.success) })
            ToggleRow("Ongoing notification", live, { live = it }, last = true)
        }
    }
}
