package dev.leash.personal.ui.design

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Leash's press feedback in place of ripples (DESIGN.md §8): the pressed node shrinks to [scale] and dims to 85% on the
 * press spring, then springs back; with reduced motion it only dims. [overlay] adds iOS's list-row highlight. Keyboard
 * focus draws a 2 dp ring. It transforms what is drawn *after* it in the modifier chain, so put the clickable first.
 */
class LeashIndication(
    private val scale: Float = 0.96f,
    private val reduced: Boolean = false,
    private val overlay: Color = Color.Unspecified,
    private val focusRing: Color = Color.Unspecified,
) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        PressNode(interactionSource, scale, reduced, overlay, focusRing)

    fun copy(scale: Float = this.scale, overlay: Color = this.overlay) = LeashIndication(scale, reduced, overlay, focusRing)

    override fun equals(other: Any?) = other is LeashIndication && other.scale == scale && other.reduced == reduced &&
        other.overlay == overlay && other.focusRing == focusRing
    override fun hashCode() = ((scale.hashCode() * 31 + reduced.hashCode()) * 31 + overlay.hashCode()) * 31 + focusRing.hashCode()
}

private class PressNode(
    private val source: InteractionSource,
    private val scale: Float,
    private val reduced: Boolean,
    private val overlay: Color,
    private val focusRing: Color,
) : Modifier.Node(), DrawModifierNode {
    private val pressed = Animatable(0f)
    /** Where [pressed] was last sent. Not `pressed.targetValue`, which only changes once the launched animation starts. */
    private var pressTarget = 0f
    private var focused = false
    private val layerPaint = Paint()

    override fun onAttach() {
        coroutineScope.launch {
            val presses = mutableListOf<PressInteraction.Press>()
            source.interactions.collect { i ->
                when (i) {
                    is PressInteraction.Press -> presses += i
                    is PressInteraction.Release -> presses -= i.press
                    is PressInteraction.Cancel -> presses -= i.press
                    is FocusInteraction.Focus -> { focused = true; invalidateDraw() }
                    is FocusInteraction.Unfocus -> { focused = false; invalidateDraw() }
                }
                val target = if (presses.isNotEmpty()) 1f else 0f
                if (pressTarget != target) {
                    pressTarget = target
                    launch {
                        pressed.animateTo(target, MotionCurve.Press.spec(reduced, 0.001f))
                    }
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        val p = pressed.value
        if (p <= 0.001f) {
            drawContent()
        } else {
            val s = if (reduced) 1f else 1f - (1f - scale) * p
            scale(s) {
                layerPaint.alpha = 1f - 0.15f * p
                drawIntoCanvas { it.saveLayer(Rect(Offset.Zero, size), layerPaint) }
                this@draw.drawContent()
                if (overlay.isSpecified) drawRect(overlay.copy(alpha = overlay.alpha * p))
                drawIntoCanvas { it.restore() }
            }
        }
        if (focused && focusRing.isSpecified) {
            val w = 2.dp.toPx()
            drawRoundRect(
                focusRing,
                topLeft = Offset(-w, -w),
                size = androidx.compose.ui.geometry.Size(size.width + 2 * w, size.height + 2 * w),
                cornerRadius = CornerRadius(12.dp.toPx()),
                style = Stroke(w),
            )
        }
    }
}

/** How much a press shrinks a control: compact controls, full-width rows, or text (dim only). */
enum class PressScale(val value: Float) { Compact(0.96f), Row(0.98f), Text(1f) }

/**
 * A clickable with Leash's press feedback. [highlight] adds the list-row overlay. Put it before backgrounds and
 * clips so the whole control shrinks.
 */
@Composable
fun Modifier.pressable(
    enabled: Boolean = true,
    scale: PressScale = PressScale.Compact,
    highlight: Boolean = false,
    role: Role? = Role.Button,
    onClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier {
    val reduced = LocalReduceMotion.current
    val colors = Leash.colors
    val indication = remember(scale, highlight, reduced, colors) {
        LeashIndication(scale.value, reduced, if (highlight) colors.pressedOverlay else Color.Unspecified, colors.accent)
    }
    val source = remember { MutableInteractionSource() }
    return this.combinedClickable(
        interactionSource = source,
        indication = indication,
        enabled = enabled,
        role = role,
        onClickLabel = onClickLabel,
        onLongClick = onLongClick,
        onClick = onClick,
    )
}
