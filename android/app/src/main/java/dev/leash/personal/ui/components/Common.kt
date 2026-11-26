package dev.leash.personal.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.leash.personal.R
import dev.leash.personal.core.AppState
import dev.leash.personal.core.LinkState
import dev.leash.personal.ui.design.Leash

/** Which family a model belongs to (drives the icon and the picker's grouping). */
enum class Provider {
    Gemini, Anthropic, OpenAI, Other;

    companion object {
        fun of(modelValue: String?): Provider {
            val v = (modelValue ?: "").lowercase()
            if ("claude" in v || listOf("opus", "sonnet", "haiku", "fable").any { it in v }) return Anthropic
            if ("gpt" in v || "oss" in v || "openai" in v) return OpenAI
            if ("gemini" in v || "orchestrator" in v) return Gemini
            return Other
        }
    }
}

/** Official provider marks (vector drawables from the iOS assets). OpenAI's is monochrome and takes the text colour. */
@Composable
fun ProviderIcon(provider: Provider, size: Dp = 16.dp, modifier: Modifier = Modifier) {
    when (provider) {
        Provider.Gemini -> Image(painterResource(R.drawable.provider_gemini), null, modifier.size(size))
        Provider.Anthropic -> Image(painterResource(R.drawable.provider_claude), null, modifier.size(size))
        Provider.OpenAI -> Image(painterResource(R.drawable.provider_openai), null, modifier.size(size), colorFilter = ColorFilter.tint(Leash.colors.textPrimary))
        Provider.Other -> Box(modifier.size(size).background(Leash.colors.textTertiary, CircleShape))
    }
}

/** Agent glyph: Claude Code = the Claude mark, Antigravity (and anything else) = the Gemini mark. */
@Composable
fun AgentIcon(agent: String, size: Dp = 16.dp, modifier: Modifier = Modifier) =
    ProviderIcon(if (agent == "claude") Provider.Anthropic else Provider.Gemini, size, modifier)


/** Every row that shows a mutable transcript item reads this: core items change in place and only the store's version moves. */
val LocalChatVersion = compositionLocalOf { 0L }

/** Inline on purpose: the read must happen in the calling row's own scope, or a change of the version would only re-run this helper and the row (given the same item instance) would be skipped. */
@Composable
inline fun ObserveChat(): Long = LocalChatVersion.current

enum class StatusKind { Ok, Warn, Bad, Idle }

data class ConnectionStatus(val kind: StatusKind, val text: String)

/** The connection line for toolbars (ios AppModel.statusColor / statusText). */
@Composable
fun connectionStatus(s: AppState): ConnectionStatus {
    val bridge = s.bridgeName.ifEmpty { stringResource(R.string.status_bridge) }
    return when (val c = s.connection) {
        LinkState.Connected -> {
            val agents = s.agents
            val kind = when {
                agents.isNotEmpty() -> if (agents.all { it.isReady }) StatusKind.Ok else if (agents.any { it.isReady }) StatusKind.Warn else StatusKind.Bad
                s.agentState == "ready" -> StatusKind.Ok
                s.agentState == "starting" -> StatusKind.Warn
                else -> StatusKind.Bad
            }
            val text = when {
                s.useDemo -> stringResource(R.string.status_demo)
                agents.size > 1 -> {
                    val ready = agents.count { it.isReady }
                    if (ready == agents.size) stringResource(R.string.status_agents_ready, bridge, ready) else stringResource(R.string.status_agents_some_ready, bridge, ready, agents.size)
                }
                s.agentState == "ready" -> stringResource(R.string.status_agent_ready, bridge)
                s.agentState == "starting" -> stringResource(R.string.status_agent_starting, bridge)
                else -> stringResource(R.string.status_agent_down, bridge)
            }
            ConnectionStatus(kind, text)
        }
        LinkState.Connecting -> ConnectionStatus(StatusKind.Warn, stringResource(R.string.status_connecting))
        is LinkState.Waiting -> ConnectionStatus(StatusKind.Warn, stringResource(R.string.status_reconnecting))
        is LinkState.Failed -> ConnectionStatus(StatusKind.Bad, stringResource(R.string.status_not_connected))
        LinkState.Idle -> ConnectionStatus(StatusKind.Idle, stringResource(R.string.status_offline))
    }
}

@Composable
fun StatusKind.color(): Color {
    val c = Leash.colors
    return when (this) {
        StatusKind.Ok -> c.success
        StatusKind.Warn -> c.attention
        StatusKind.Bad -> c.danger
        StatusKind.Idle -> c.textTertiary
    }
}

