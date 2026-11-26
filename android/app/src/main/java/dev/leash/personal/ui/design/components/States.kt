package dev.leash.personal.ui.design.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.leash.personal.ui.design.Leash
import dev.leash.personal.ui.design.LeashShapes
import dev.leash.personal.ui.design.Spinner
import dev.leash.personal.ui.design.rememberLivePhase
import dev.leash.personal.ui.design.shimmer
import kotlinx.coroutines.delay

/** Centred empty state: outline glyph, title, message in secondary ink, optional prominent action. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    val c = Leash.colors
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, Modifier.size(44.dp), tint = c.textTertiary)
        Text(title, style = Leash.type.title3, color = c.textPrimary, textAlign = TextAlign.Center)
        if (message != null) Text(message, style = Leash.type.subheadline, color = c.textSecondary, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 280.dp))
        if (actionLabel != null) LeashButton(actionLabel, onAction, Modifier.padding(top = 8.dp))
    }
}

/** First load: a quiet spinner + label, shown only after [delayMs] so quick loads don't flash. */
@Composable
fun LoadingState(label: String, modifier: Modifier = Modifier, delayMs: Long = 300) {
    var show by remember { mutableStateOf(delayMs <= 0) }
    LaunchedEffect(Unit) { delay(delayMs); show = true }
    Box(modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
        AnimatedVisibility(show, enter = fadeIn()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Spinner(size = 16.dp)
                Text(label, style = Leash.type.footnote, color = Leash.colors.textSecondary)
            }
        }
    }
}

/** Skeleton rows for a list that is loading: resting-fill capsules with the live band at half strength. */
@Composable
fun SkeletonRows(count: Int = 3, modifier: Modifier = Modifier, fixedPhase: Float? = null) {
    val phase = rememberLivePhase(true, fixedPhase)
    val fill = Leash.colors.textPrimary.copy(alpha = 0.07f)
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        repeat(count) {
            Column(Modifier.shimmer(true, phase), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth(0.62f).height(14.dp).clip(LeashShapes.capsule).background(fill))
                Box(Modifier.fillMaxWidth(0.3f).height(10.dp).clip(LeashShapes.capsule).background(fill))
            }
        }
    }
}
