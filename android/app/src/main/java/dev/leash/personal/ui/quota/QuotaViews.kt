package dev.leash.personal.ui.quota

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.leash.personal.R
import dev.leash.personal.core.AppModel
import dev.leash.personal.core.Clock
import dev.leash.personal.core.QuotaBucket
import dev.leash.personal.core.QuotaFormat
import dev.leash.personal.core.QuotaPool
import dev.leash.personal.ui.components.AgentIcon
import dev.leash.personal.ui.design.Leash
import dev.leash.personal.ui.design.LeashIcons
import dev.leash.personal.ui.design.RingGauge
import dev.leash.personal.ui.design.Spinner
import dev.leash.personal.ui.design.medium
import dev.leash.personal.ui.design.pressable
import dev.leash.personal.ui.design.semibold
import dev.leash.personal.ui.design.tabular
import dev.leash.personal.ui.design.components.RaisedCard
import dev.leash.personal.ui.design.components.LeashSheet
import kotlinx.coroutines.launch

/** The ink of a quota number or ring: tinted when it is running low, quiet otherwise (ios quotaTint). */
@Composable
fun quotaTint(fraction: Double?): Color {
    val c = Leash.colors
    return when (QuotaFormat.level(fraction)) {
        QuotaFormat.Level.Unknown -> c.textTertiary
        QuotaFormat.Level.Danger -> c.danger
        QuotaFormat.Level.Attention -> c.attention
        QuotaFormat.Level.Normal -> c.textSecondary
    }
}

/** A ring that is tinted only when tight, else the accent. */
@Composable
fun quotaRingTint(fraction: Double?): Color = if (QuotaFormat.isTight(fraction)) quotaTint(fraction) else Leash.colors.accent

/**
 * Two concentric rings, outer = weekly, inner = 5-hour, for the most constrained pool (the list's top-right circle). [known]:
 * quota has been read at all, so an empty pool shows dashed rings rather than a full one.
 */
@Composable
fun QuotaRings(pool: QuotaPool?, known: Boolean, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    val tint = quotaRingTint(pool?.minFraction)
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        RingGauge((pool?.weekly?.fraction ?: if (known) pool?.minFraction else null)?.toFloat(), size = size, stroke = 2.5.dp, tint = tint)
        RingGauge(pool?.fiveHour?.fraction?.toFloat(), size = size - 10.dp, stroke = 2.5.dp, tint = tint)
    }
}

/** The pool's label and what is left of it, for TalkBack. */
@Composable
fun quotaRingsDescription(pool: QuotaPool?): String =
    pool?.let { stringResource(R.string.quota_rings_desc, it.label, QuotaFormat.percent(it.minFraction ?: 0.0)) } ?: stringResource(R.string.quota_unknown)

/**
 * Quota of every agent, pool by pool (ios QuotaSheet): a card per pool with a bar per window (5-hour, weekly) and when it
 * resets; refresh asks the bridge for fresh numbers (it reuses data younger than two minutes unless forced).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuotaSheet(model: AppModel, onDismiss: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    val ids = if (state.agents.isEmpty()) listOf("agy") else state.agents.map { it.id }
    val c = Leash.colors
    val refreshLabel = stringResource(R.string.quota_refresh)
    LeashSheet(
        onDismiss = onDismiss, title = stringResource(R.string.quota_title), doneLabel = stringResource(R.string.done), onDone = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        leadingAction = {
            Box(
                Modifier.size(44.dp).pressable(!refreshing, onClickLabel = refreshLabel) { scope.launch { refreshing = true; model.refreshQuota(force = true); refreshing = false } },
                contentAlignment = Alignment.Center,
            ) {
                if (refreshing) Spinner(size = 18.dp) else Icon(LeashIcons.refresh, refreshLabel, Modifier.size(22.dp), tint = c.accentControl)
            }
        },
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            ids.forEach { id -> AgentQuota(id, ids.size > 1, state.agentLabel(id), state.quotas[id], state.quotaErrors[id]) }
        }
    }
}

@Composable
private fun AgentQuota(id: String, showName: Boolean, label: String, quota: dev.leash.personal.core.QuotaInfo?, error: String?) {
    val c = Leash.colors
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showName) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AgentIcon(id, 16.dp)
            Text(label, style = Leash.type.subheadline.semibold(), color = c.textSecondary)
        }
        if (quota != null) {
            quota.pools.forEach { PoolCard(it) }
            val updated = stringResource(R.string.quota_updated, QuotaFormat.ago(quota.fetchedAtMs, Clock.now()))
            Text(updated + (quota.planTier?.let { " · $it" } ?: ""), style = Leash.type.footnote, color = c.textTertiary)
            if (error != null) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                Icon(LeashIcons.warning, null, Modifier.size(14.dp), tint = c.attention)
                Text(error, style = Leash.type.caption, color = c.attention)
            }
        } else Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (error == null) Spinner(size = 18.dp) else Icon(LeashIcons.warning, null, Modifier.size(18.dp), tint = c.attention)
            Text(error ?: stringResource(R.string.quota_reading), style = Leash.type.footnote, color = c.textSecondary)
        }
    }
}

@Composable
private fun PoolCard(pool: QuotaPool) {
    val c = Leash.colors
    RaisedCard(Modifier.fillMaxWidth()) {
        Text(pool.label, style = Leash.type.headline, color = c.textPrimary)
        pool.buckets.forEach { b -> BucketRow(b) }
    }
}

@Composable
private fun BucketRow(b: QuotaBucket) {
    val c = Leash.colors
    val f = b.fraction
    val desc = stringResource(R.string.quota_bucket_desc, b.windowLabel, f?.let { QuotaFormat.percent(it) } ?: 0)
    Column(Modifier.semantics(mergeDescendants = true) { contentDescription = desc }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(b.windowLabel, Modifier.weight(1f), style = Leash.type.subheadline.medium(), color = c.textPrimary)
            Text(f?.let { stringResource(R.string.quota_left, QuotaFormat.percent(it)) } ?: "—", style = Leash.type.subheadline.tabular(), color = quotaTint(f))
        }
        CapacityBar(f ?: 0.0, if (QuotaFormat.isTight(f)) quotaTint(f) else c.accent)
        b.resetAtMs?.let { at ->
            val r = QuotaFormat.reset(at, Clock.now())
            Text(
                if (r == null) stringResource(R.string.quota_resetting) else stringResource(R.string.quota_resets, r.day, r.left),
                style = Leash.type.caption, color = c.textTertiary,
            )
        }
    }
}

/** A 6 dp capsule that fills with what is left (at least 4 dp, so an empty one still shows). */
@Composable
fun CapacityBar(fraction: Double, tint: Color, modifier: Modifier = Modifier) {
    val c = Leash.colors
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(c.hairline)) {
        Box(Modifier.width((maxWidth * fraction.toFloat().coerceIn(0f, 1f)).coerceAtLeast(4.dp)).height(6.dp).clip(CircleShape).background(tint))
    }
}
