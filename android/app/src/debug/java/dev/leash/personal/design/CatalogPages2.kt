package dev.leash.personal.design

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.leash.personal.R
import dev.leash.personal.ui.components.AgentIcon
import dev.leash.personal.ui.design.ContinuousShape
import dev.leash.personal.ui.design.GlassScope
import dev.leash.personal.ui.design.Leash
import dev.leash.personal.ui.design.LeashIcons
import dev.leash.personal.ui.design.LeashShapes
import dev.leash.personal.ui.design.RingGauge
import dev.leash.personal.ui.design.SpaceBackground
import dev.leash.personal.ui.design.components.*
import dev.leash.personal.ui.design.GlassSource
import dev.leash.personal.ui.design.semibold

/** Agent prose with inline code, as the markdown renderer will style it. */
@Composable
private fun Prose(vararg parts: String) {
    val c = Leash.colors
    Text(
        buildAnnotatedString {
            parts.forEachIndexed { i, p ->
                if (i % 2 == 1) withStyle(SpanStyle(color = c.codeInk, background = c.codeBg, fontFamily = Leash.type.mono.fontFamily, fontSize = Leash.type.reading.fontSize * 0.92f)) { append(p) }
                else append(p)
            }
        },
        style = Leash.type.reading, color = c.textPrimary, modifier = Modifier.padding(horizontal = 16.dp),
    )
}

/** The chat dock: composer slab + chips, over the bottom edge. */
@Composable
private fun Dock(draft: String, onDraft: (String) -> Unit, running: Boolean, mode: ComposerMode = ComposerMode.Normal, above: (@Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit)? = null, trays: @Composable () -> Unit = {}) {
    val c = Leash.colors
    Box(Modifier.fillMaxWidth()) {
        DockFade()
        Column(Modifier.fillMaxWidth().dockInsets().padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { trays() }
            ComposerSlab(
                draft, onDraft,
                placeholder = if (running) "Steer the agent…" else "Ask Claude…",
                mode = mode, leadingLabel = "Add", above = above, modifier = Modifier.padding(horizontal = 12.dp),
            ) {
                val glyph = when {
                    running && draft.isBlank() -> SendGlyph.Stop
                    mode == ComposerMode.Shell -> if (draft.length > 1) SendGlyph.ShellRun else SendGlyph.ShellDisabled
                    draft.isBlank() -> SendGlyph.Disabled
                    else -> SendGlyph.Send
                }
                if (running && draft.isNotBlank()) SecondaryStopButton("Stop", {})
                SendButton(glyph, "Send", {})
            }
            ChipRow {
                Chip("Sonnet 5.5", {}, leading = { AgentIcon("claude", 14.dp) })
                Chip("High", {}, icon = LeashIcons.effort, chevron = false)
                Chip("Auto", {}, icon = LeashIcons.modeAuto)
                Chip("Week 25%", {}, leading = { RingGauge(0.25f, size = 14.dp, tint = c.attention) }, chevron = false)
            }
        }
    }
}

/** Dense text running on under the dock to the screen edge: what shows through the glass, and between and below it. */
@Composable
fun DockPage(initialDraft: String = "", queued: Boolean = false) {
    var draft by remember { mutableStateOf(initialDraft) }
    GlassScope(Modifier.fillMaxSize()) {
        GlassSource(Modifier.fillMaxSize()) {
            SpaceBackground()
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState(initial = 200)).statusBarsPadding().padding(top = 64.dp, bottom = 220.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    "• OkHttp 4.12.0 的 WebSocket 连 bridge。", "• kotlinx.serialization 1.9.0 处理 JSON。", "• kotlinx.coroutines 1.10.2 做异步。",
                    "• DataStore 1.1.7 存设置。", "Tailscale", "• 内置的 tailnet 是 Go 写的，用 gomobile 编成 AAR，通过 Gradle 接进来，不是 Kotlin 库。",
                    "• Haze 2.1.0 负责毛玻璃和折射。", "工具链", "• AGP 9.2.1、Gradle 9.4.1、Kotlin 2.4.21，compileSdk 37。", "• minSdk 29，targetSdk 35。",
                ).forEach { Prose(it) }
                Spacer(Modifier.height(4.dp))
                CodeBlock("fun parse(s: String): Node {\n    require(s.isNotBlank()) { \"empty input\" }\n    return Node(s.trim())\n}", Modifier.padding(horizontal = 16.dp), language = "kotlin")
                listOf("• 内置的 tailnet 是 Go 写的，用 gomobile 编成 AAR，通过 Gradle 接进来。", "• 发布版用 R8 压缩，签名证书不变。", "• 模拟器是 Android 15，分辨率 1080×1920。", "• 真机帧率和耗电还要再测一次。").forEach { Prose(it) }
            }
        }
        TopBar(title = "Dock", navigation = { BackButton("Back", {}) })
        Box(Modifier.align(Alignment.BottomCenter)) {
            Dock(draft, { draft = it }, running = false, trays = {
                if (queued) TrayShell(blur = false, padding = androidx.compose.foundation.layout.PaddingValues(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp)) {
                    QueueHeader("2 queued", "Send Now", {})
                    QueueRow("Then run the tests", {}, {}, "Remove")
                }
            })
        }
    }
}

@Composable
fun ChatPage() {
    val c = Leash.colors
    var draft by remember { mutableStateOf("") }
    GlassScope(Modifier.fillMaxSize()) {
        GlassSource(Modifier.fillMaxSize()) {
            SpaceBackground()
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(top = 64.dp, bottom = 190.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Prose("Because ", "crontab", " has only this one task, if you want to stop it for good you can run ", "crontab -r", " in a terminal.")
                Spacer(Modifier.height(6.dp))
                UserBubble("删掉这个", Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(6.dp))
                ActivityHeader("1 step", ActivityState.Done, Modifier.padding(horizontal = 16.dp), subLine = "Ran 1 command ›")
                Prose("Done, and ", "crontab -l", " confirms nothing is left (output ", "crontab: no crontab for jerryzhou", "). The 13:35 reminder won't pop up again.")
                CodeBlock("fun parse(s: String): Node {\n    require(s.isNotBlank()) { \"empty input\" }\n    return Node(s.trim())\n}", Modifier.padding(horizontal = 16.dp), language = "kotlin")
                ChangesRow("1 file changed", 2, 1, {}, Modifier.padding(horizontal = 16.dp))
                StatsLine("Sonnet 5.5 · high · ↑12.4k ↓1.1k · 47 tok/s", Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(6.dp))
                UserBubble("Also clean the build directory", Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(6.dp))
                ActivityHeader("Working · 12s", ActivityState.Live, Modifier.padding(horizontal = 16.dp), subLine = "Running ./gradlew clean", checklist = 1 to 3, checklistLabel = "1/3 steps", fixedPhase = LocalCatalogPhase.current)
            }
        }
        TopBar(
            title = "排查未知定时推送来源", subtitle = "Antigravity · ~",
            navigation = { BackButton("Back", {}) },
            actions = { GlassCircleButton(LeashIcons.more, "More", {}, tint = c.accentControl) },
        )
        Box(Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 200.dp)) { ScrollToBottomButton("Scroll to bottom", {}) }
        Box(Modifier.align(Alignment.BottomCenter)) { Dock(draft, { draft = it }, running = true) }
    }
}

@Composable
fun ActivityPage() {
    val c = Leash.colors
    var open by remember { mutableStateOf(true) }
    var stepOpen by remember { mutableStateOf(true) }
    val phase = LocalCatalogPhase.current
    Box(Modifier.fillMaxSize()) {
        SpaceBackground()
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(top = 12.dp, bottom = 32.dp).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ActivityHeader("Working · 21s", ActivityState.Live, subLine = "Reading Parser.kt", fixedPhase = phase)
            ActivityHeader("Working · 21s", ActivityState.Blocked, subLine = "Waiting for your approval")
            ActivityHeader("Worked for 1m 3s", ActivityState.Failed, subLine = "Explored 3 files, ran 2 commands, 1 failed ›")
            ActivityHeader("Stopped after 8s", ActivityState.Stopped)
            ActivityHeader("Worked for 18s", ActivityState.Done, expanded = open, subLine = "Explored 2 files, ran 1 command, edited 1 file ›", checklist = 4 to 4, checklistLabel = "4/4 steps", onClick = { open = !open })
            if (open) StepRail {
                StepRow(LeashIcons.think, "The parser accepts anything; tighten the callers before tightening it.", StepKind.Thought)
                StepRow(LeashIcons.read, "Read 2 files", StepKind.Other)
                StepRow(LeashIcons.search, "Search parse(", StepKind.Other)
                StepRow(LeashIcons.edit, "Parser.kt", StepKind.Edit, adds = 2, dels = 1, autoApproved = true, open = stepOpen, onClick = { stepOpen = !stepOpen }) {
                    DiffBlock(
                        "app/src/main/java/dev/leash/Parser.kt", 2, 1,
                        listOf(
                            DiffLineModel.Gap("⋯ 12 unchanged lines"),
                            DiffLineModel.Same("fun parse(s: String): Node {"),
                            DiffLineModel.Del("    return Node(s)"),
                            DiffLineModel.Add("    require(s.isNotBlank()) { \"empty input\" }"),
                            DiffLineModel.Add("    return Node(s.trim())"),
                            DiffLineModel.Same("}"),
                        ),
                        toggleLabel = "Show whole file",
                    )
                }
                StepRow(LeashIcons.terminal, "./gradlew test --tests ParserTest", StepKind.Command, failed = true)
                StepRow(LeashIcons.terminal, "./gradlew assembleDebug", StepKind.Command, running = true)
            }
            ShellBubble("git status --short")
            CommandCapsule("/compact keep the plan")
            RaisedCard {
                CardHeader("Plan", LeashIcons.modePlan, count = "1/3", open = true)
                Text("✓ Read the parser", style = Leash.type.footnote, color = c.textTertiary)
                Text("◉ Tighten blank input", style = Leash.type.footnote, color = c.textPrimary)
                Text("○ Run the tests", style = Leash.type.footnote, color = c.textPrimary)
            }
        }
    }
}

@Composable
fun TraysPage() {
    val c = Leash.colors
    var choice by remember { mutableIntStateOf(0) }
    var draft by remember { mutableStateOf("!ls -la") }
    GlassScope(Modifier.fillMaxSize()) {
        GlassSource(Modifier.fillMaxSize()) {
            SpaceBackground()
            Column(Modifier.fillMaxSize().statusBarsPadding().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Prose("Content under the dock scrolls on to the screen bottom, faded and blurred, so nothing sharp shows between the slab and the chips. ", "rm -rf build", " needs approval.")
            }
        }
        Box(Modifier.align(Alignment.BottomCenter)) {
            Dock(draft, { draft = it }, running = false, mode = if (draft.startsWith("!")) ComposerMode.Shell else ComposerMode.Normal,
                above = { if (draft.startsWith("!")) ComposerStatus("Shell · ~/workspace/leash", LeashIcons.terminal, c.shell) },
                trays = {
                    TrayShell(blur = false) {
                        TrayHeader("Agent asks", LeashIcons.question, c.accent)
                        OptionRow("Keep the cache", choice == 0, { choice = 0 }, detail = "Faster next build")
                        OptionRow("Delete everything", choice == 1, { choice = 1 })
                    }
                    TrayShell(blur = false) {
                        TrayHeader("Run rm -rf build", LeashIcons.needsYou, c.attention, counter = "1 of 2")
                        CommandPreview("rm -rf build && ./gradlew clean")
                        TrayActions(listOf(TrayAction("Deny", ButtonStyle.Destructive) {}, TrayAction("Allow", ButtonStyle.Prominent) {}))
                    }
                    TrayShell(blur = false, padding = androidx.compose.foundation.layout.PaddingValues(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp)) {
                        QueueHeader("2 queued", "Send Now", {})
                        QueueRow("Then run the tests", {}, {}, "Remove")
                        RemovedRow("Removed", "Undo", {})
                    }
                })
        }
    }
}

@Composable
fun StatesPage() {
    val c = Leash.colors
    var menu by remember { mutableStateOf(true) }
    Box(Modifier.fillMaxSize()) {
        SpaceBackground()
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(top = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Banner("2 other conversations need you", {}, Modifier.padding(horizontal = 16.dp))
            EmptyState(LeashIcons.note, "No conversations", message = "Start one on the bridge's computer or here.", actionLabel = "New Conversation")
            LoadingState("Loading…", delayMs = 0)
            SkeletonRows(2, fixedPhase = LocalCatalogPhase.current)
            Box(Modifier.padding(horizontal = 16.dp)) {
                Text("Menu (long-press / overflow):", style = Leash.type.footnote, color = c.textSecondary)
                LeashMenu(menu, { menu = false }) {
                    LeashMenuItem("New Conversation Here", {}, LeashIcons.squareAndPencil)
                    LeashMenuDivider()
                    LeashMenuItem("Browse Files", {}, LeashIcons.folder)
                    LeashMenuItem("Run Shell Command", {}, LeashIcons.terminal)
                    LeashMenuItem("Show on Computer", {}, LeashIcons.computer, supporting = "Adds it to Claude Code's /resume")
                    LeashMenuItem("Forget bridge", {}, LeashIcons.delete, destructive = true)
                }
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SheetPage() {
    var show by remember { mutableStateOf(true) }
    var haptics by remember { mutableStateOf(true) }
    Box(Modifier.fillMaxSize()) {
        SpaceBackground()
        Column(Modifier.statusBarsPadding().padding(16.dp)) {
            LeashButton("Open sheet", { show = true })
        }
        if (show) {
            LeashSheet(onDismiss = { show = false }, title = "Settings", doneLabel = "Done", onDone = { show = false }) {
                Column(Modifier.padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SectionHeader("Pairing")
                    GroupCard {
                        Box(Modifier.padding(12.dp)) { LeashButton("Scan pairing QR code", {}, Modifier.fillMaxWidth(), icon = LeashIcons.scan) }
                    }
                    SectionHeader("Feedback")
                    GroupCard { ToggleRow("Haptics", haptics, { haptics = it }, last = true) }
                    SectionFooter("Subtle taps for sending, stopping, approvals and finished tasks.")
                }
            }
        }
    }
}

@Composable
fun IconPage() {
    val c = Leash.colors
    val masks = listOf<Pair<String, Shape>>("circle" to CircleShape, "squircle" to ContinuousShape(36.dp), "rounded" to RoundedCornerShape(24.dp))
    Box(Modifier.fillMaxSize()) {
        SpaceBackground()
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Adaptive icon under launcher masks", style = Leash.type.headline)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                masks.forEach { (name, shape) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        // 108 dp layers, 72 dp visible: draw at 1.5x the visible size and clip
                        Box(Modifier.size(96.dp).clip(shape), contentAlignment = Alignment.Center) {
                            Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.size(144.dp))
                            Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(144.dp))
                        }
                        Text(name, style = Leash.type.caption, color = c.textSecondary)
                    }
                }
            }
            Text("Themed (monochrome layer, launcher-tinted)", style = Leash.type.headline)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                listOf(0xFF1F2A44 to 0xFFC8D6FF, 0xFFE3E8F7 to 0xFF2B3A67).forEach { (bg, fg) ->
                    Box(Modifier.size(96.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Color(bg)), contentAlignment = Alignment.Center) {
                        Image(painterResource(R.drawable.ic_launcher_monochrome), null, Modifier.size(144.dp), colorFilter = ColorFilter.tint(androidx.compose.ui.graphics.Color(fg)))
                    }
                }
            }
            Text("Small sizes", style = Leash.type.headline)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(48, 36, 24).forEach { s ->
                    Box(Modifier.size(s.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
                        Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.size((s * 1.5f).dp))
                        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size((s * 1.5f).dp))
                    }
                }
            }
        }
    }
}
