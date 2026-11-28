package dev.leash.personal.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.leash.personal.R
import dev.leash.personal.core.Haptic
import dev.leash.personal.core.Issue
import dev.leash.personal.core.Problem
import dev.leash.personal.core.TailnetStatus
import dev.leash.personal.text.problemText
import dev.leash.personal.text.res
import dev.leash.personal.ui.design.Leash
import dev.leash.personal.ui.design.LeashIcons
import dev.leash.personal.ui.design.LocalReduceMotion
import dev.leash.personal.ui.design.Motion
import dev.leash.personal.ui.design.MotionCurve
import dev.leash.personal.ui.design.components.ButtonSize
import dev.leash.personal.ui.design.components.ButtonStyle
import dev.leash.personal.ui.design.components.LeashButton
import dev.leash.personal.ui.design.glass
import dev.leash.personal.ui.design.rememberHaptics
import dev.leash.personal.ui.design.rememberLivePhase
import dev.leash.personal.ui.design.shimmer
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos

internal const val TAILNET_BOOT_TIMEOUT_MS = 15_000L
private const val TAILNET_CONNECTED_BEAT_MS = 900L
private const val TAILNET_EARLY_ACTIONS_DELAY_MS = 1_500L

internal sealed interface TailnetBootPhase {
    data object Connecting : TailnetBootPhase
    data class Connected(val name: String, val address: String) : TailnetBootPhase
    data class NeedsKey(val reason: String?) : TailnetBootPhase
    data class Failed(val message: String, val issue: Issue?) : TailnetBootPhase
    data object TimedOut : TailnetBootPhase
}

internal fun resolveTailnetBootPhase(status: TailnetStatus, timedOut: Boolean): TailnetBootPhase = when (status) {
    is TailnetStatus.Connected -> TailnetBootPhase.Connected(status.name, status.address)
    is TailnetStatus.NeedsKey -> TailnetBootPhase.NeedsKey(status.reason)
    is TailnetStatus.Failed -> TailnetBootPhase.Failed(status.message, status.issue)
    TailnetStatus.Off, TailnetStatus.Stopped, TailnetStatus.Connecting ->
        if (timedOut) TailnetBootPhase.TimedOut else TailnetBootPhase.Connecting
}

/**
 * Cold-start gate shown while built-in Tailscale joins the tailnet (ios TailnetBootView).
 * Sits directly above the shared [SpaceBackground] so entering the main list transitions seamlessly.
 */
@Composable
fun TailnetBootGate(
    status: TailnetStatus,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    onContinueAnyway: () -> Unit,
    onConnectedDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Leash.colors
    val reduced = LocalReduceMotion.current
    val haptics = rememberHaptics()
    var timedOut by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var showEarlyActions by remember { mutableStateOf(false) }

    val phase = resolveTailnetBootPhase(status, timedOut)

    // 15s UI timeout while connecting; resets on retry
    LaunchedEffect(attempt, phase is TailnetBootPhase.Connecting) {
        if (phase is TailnetBootPhase.Connecting) {
            delay(TAILNET_BOOT_TIMEOUT_MS)
            timedOut = true
        }
    }

    // Subtle secondary links appear softly after 1.5s so first frames stay calm
    LaunchedEffect(attempt) {
        showEarlyActions = false
        delay(TAILNET_EARLY_ACTIONS_DELAY_MS)
        showEarlyActions = true
    }

    // Play brief confirmation beat + haptic when Tailscale connects, then hand off smoothly
    LaunchedEffect(phase is TailnetBootPhase.Connected) {
        if (phase is TailnetBootPhase.Connected) {
            haptics.event(Haptic.Success)
            if (!reduced) delay(TAILNET_CONNECTED_BEAT_MS)
            onConnectedDone()
        }
    }

    // Haptic on entering an actionable error or timeout state
    LaunchedEffect(phase) {
        if (phase is TailnetBootPhase.NeedsKey || phase is TailnetBootPhase.TimedOut) {
            haptics.event(Haptic.Warning)
        } else if (phase is TailnetBootPhase.Failed) {
            haptics.event(Haptic.Error)
        }
    }

    val accentColor by animateColorAsState(
        when (phase) {
            is TailnetBootPhase.Connecting -> c.accent
            is TailnetBootPhase.Connected -> c.success
            is TailnetBootPhase.NeedsKey, TailnetBootPhase.TimedOut -> c.attention
            is TailnetBootPhase.Failed -> c.danger
        },
        animationSpec = Motion.quick(),
        label = "bootAccent",
    )

    val livePhase = rememberLivePhase(active = phase is TailnetBootPhase.Connecting && !reduced)

    // Block pointer input from reaching the hidden NavHost underneath
    Box(
        modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .clickable(remember { MutableInteractionSource() }, indication = null) {},
    ) {
        Column(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 28.dp)
                .animateContentSize(Motion.layout()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            OrbitBootEmblem(phase = phase, tint = accentColor, reduced = reduced)

            Spacer(Modifier.height(28.dp))

            val title = when (phase) {
                is TailnetBootPhase.Connecting -> stringResource(R.string.tailnet_boot_connecting)
                is TailnetBootPhase.Connected -> stringResource(R.string.tailnet_boot_connected)
                is TailnetBootPhase.TimedOut -> stringResource(R.string.tailnet_boot_timed_out)
                is TailnetBootPhase.NeedsKey -> stringResource(R.string.tailnet_boot_needs_key)
                is TailnetBootPhase.Failed -> stringResource(R.string.tailnet_boot_failed)
            }

            Text(
                text = title,
                style = Leash.type.title3,
                color = c.textPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.shimmer(phase is TailnetBootPhase.Connecting, livePhase),
            )

            Spacer(Modifier.height(8.dp))

            val detail: String? = when (phase) {
                is TailnetBootPhase.Connecting -> stringResource(R.string.tailnet_boot_connecting_detail)
                is TailnetBootPhase.Connected -> listOf(phase.name, phase.address).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { null }
                is TailnetBootPhase.TimedOut -> stringResource(R.string.tailnet_boot_timed_out_detail)
                is TailnetBootPhase.NeedsKey -> phase.reason?.takeIf { it.isNotBlank() } ?: stringResource(R.string.tailnet_needs_key_detail)
                is TailnetBootPhase.Failed -> res().problemText(Problem(phase.message, phase.issue))
            }

            if (detail != null) {
                val isMono = phase is TailnetBootPhase.Connected
                Text(
                    text = detail,
                    style = if (isMono) Leash.type.monoSmall else Leash.type.subheadline,
                    color = if (isMono) c.success else c.textSecondary,
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 300.dp),
                )
            }

            val actionable = phase is TailnetBootPhase.TimedOut || phase is TailnetBootPhase.NeedsKey || phase is TailnetBootPhase.Failed
            AnimatedVisibility(
                visible = actionable,
                enter = Motion.layoutEnter(reduced),
                exit = Motion.layoutExit(reduced),
            ) {
                Column(
                    Modifier
                        .widthIn(max = 280.dp)
                        .fillMaxWidth()
                        .padding(top = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    LeashButton(
                        text = stringResource(R.string.tailnet_boot_open_settings),
                        onClick = onOpenSettings,
                        modifier = Modifier.fillMaxWidth(),
                        style = ButtonStyle.Prominent,
                        size = ButtonSize.Large,
                        icon = LeashIcons.settings,
                    )
                    LeashButton(
                        text = stringResource(R.string.retry),
                        onClick = {
                            timedOut = false
                            attempt++
                            onRetry()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        style = ButtonStyle.Tinted,
                        size = ButtonSize.Regular,
                        icon = LeashIcons.refresh,
                    )
                    LeashButton(
                        text = stringResource(R.string.tailnet_boot_continue),
                        onClick = onContinueAnyway,
                        style = ButtonStyle.Plain,
                        size = ButtonSize.Regular,
                    )
                }
            }
        }

        // Quiet escape hatch while still connecting
        AnimatedVisibility(
            visible = phase is TailnetBootPhase.Connecting && showEarlyActions,
            enter = fadeIn(MotionCurve.Layout.spec(reduced)),
            exit = fadeOut(MotionCurve.Quick.spec(reduced)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LeashButton(
                    text = stringResource(R.string.settings),
                    onClick = onOpenSettings,
                    style = ButtonStyle.Plain,
                    size = ButtonSize.Small,
                )
                Box(
                    Modifier
                        .size(3.dp)
                        .clip(CircleShape)
                        .background(c.textTertiary.copy(alpha = 0.5f)),
                )
                LeashButton(
                    text = stringResource(R.string.tailnet_boot_continue),
                    onClick = onContinueAnyway,
                    style = ButtonStyle.Plain,
                    size = ButtonSize.Small,
                )
            }
        }
    }
}

@Composable
private fun OrbitBootEmblem(
    phase: TailnetBootPhase,
    tint: androidx.compose.ui.graphics.Color,
    reduced: Boolean,
) {
    val c = Leash.colors
    val connecting = phase is TailnetBootPhase.Connecting
    val transition = rememberInfiniteTransition(label = "orbitBoot")
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart),
        label = "orbitSpin",
    )
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart),
        label = "orbitPulse",
    )
    val breath = if (connecting && !reduced) 0.5f - 0.5f * cos(2f * PI.toFloat() * pulse) else 0f
    val arcAlpha by animateFloatAsState(if (connecting && !reduced) 1f else 0f, Motion.quick(), label = "orbitArcAlpha")

    Box(Modifier.size(136.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val outerR = size.minDimension * (0.44f + 0.04f * breath)
            val midR = size.minDimension * 0.36f
            drawCircle(
                color = tint.copy(alpha = if (connecting) 0.08f + 0.10f * breath else 0.14f),
                radius = outerR,
                center = center,
                style = Stroke(width = 1.dp.toPx()),
            )
            drawCircle(
                color = tint.copy(alpha = if (connecting) 0.20f else 0.35f),
                radius = midR,
                center = center,
                style = Stroke(width = 1.5.dp.toPx()),
            )
            if (arcAlpha > 0.01f) {
                val stroke = 2.5.dp.toPx()
                val arcSize = Size(midR * 2, midR * 2)
                val topLeft = Offset(center.x - midR, center.y - midR)
                drawArc(
                    color = tint.copy(alpha = arcAlpha),
                    startAngle = spin,
                    sweepAngle = 78f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }

        Box(
            Modifier
                .size(72.dp)
                .glass(CircleShape, tint = c.bgRaised.copy(alpha = 0.65f), blur = false)
                .border(1.dp, tint.copy(alpha = 0.28f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = phase,
                transitionSpec = {
                    val enter = if (reduced) fadeIn(MotionCurve.Quick.spec(true))
                    else scaleIn(MotionCurve.Quick.spec(false), initialScale = 0.78f) + fadeIn(MotionCurve.Quick.spec(false))
                    val exit = if (reduced) fadeOut(MotionCurve.Quick.spec(true))
                    else scaleOut(MotionCurve.Quick.spec(false), targetScale = 0.78f) + fadeOut(MotionCurve.Quick.spec(false))
                    enter togetherWith exit
                },
                label = "bootGlyph",
            ) { p ->
                val icon = when (p) {
                    is TailnetBootPhase.Connecting -> LeashIcons.tailnet
                    is TailnetBootPhase.Connected -> LeashIcons.check
                    is TailnetBootPhase.NeedsKey, TailnetBootPhase.TimedOut -> LeashIcons.warning
                    is TailnetBootPhase.Failed -> LeashIcons.failed
                }
                Icon(icon, null, Modifier.size(30.dp), tint = tint)
            }
        }
    }
}
