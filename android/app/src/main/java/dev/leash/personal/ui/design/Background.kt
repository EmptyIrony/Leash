package dev.leash.personal.ui.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The page: a barely-there vertical gradient, a soft accent glow from the top-end corner, and in dark mode 60 faint
 * fixed stars (iOS SpaceBackground). [plain] drops glow and stars (high contrast).
 */
@Composable
fun SpaceBackground(modifier: Modifier = Modifier, stars: Boolean = true, plain: Boolean = false) {
    val c = Leash.colors
    if (plain) {
        Box(modifier.fillMaxSize().background(c.bgBase))
        return
    }
    Canvas(modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(c.bgBase, c.bgGradientEnd)))
        val rtl = layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl
        drawRect(
            Brush.radialGradient(
                listOf(c.accent.copy(alpha = 0.10f), Color.Transparent),
                center = Offset(if (rtl) 0f else size.width, 0f),
                radius = 320.dp.toPx(),
            ),
        )
        if (stars && c.dark) {
            val rng = SeededRandom(42)
            repeat(60) {
                val x = rng.next() * size.width
                val y = rng.next() * size.height
                val r = (0.5f + rng.next() * 0.25f).dp.toPx()
                // dimmer than iOS's 25–60%: at full strength they read as stray punctuation through dark cards and text
                val o = 0.12f + rng.next() * 0.23f
                drawCircle(Color.White.copy(alpha = o), r, Offset(x, y))
            }
        }
    }
}

/** The same LCG as iOS's SeededRandom, so the starfield is the same sky on both. */
class SeededRandom(seed: Long) {
    private var state: ULong = seed.toULong()
    fun next(): Float {
        state = state * 6364136223846793005uL + 1442695040888963407uL
        return ((state shr 33) and 0xFFFFFFuL).toFloat() / 0xFFFFFF.toFloat()
    }
}
