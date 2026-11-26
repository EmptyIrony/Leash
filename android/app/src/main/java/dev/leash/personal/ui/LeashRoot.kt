package dev.leash.personal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import dev.leash.personal.ui.theme.LeashTheme

/** The empty shell: the space background and the app name. Navigation and screens arrive in later phases. */
@Composable
fun LeashRoot() {
    val scheme = MaterialTheme.colorScheme
    val colors = LeashTheme.colors
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(scheme.background, colors.bgGradientEnd)))
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        listOf(scheme.primary.copy(alpha = 0.10f), androidx.compose.ui.graphics.Color.Transparent),
                        center = Offset(size.width, 0f),
                        radius = 320.dp.toPx(),
                    ),
                )
            }
            .safeDrawingPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Leash", style = MaterialTheme.typography.displaySmall, color = scheme.onBackground)
            Text(
                "Not connected",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
