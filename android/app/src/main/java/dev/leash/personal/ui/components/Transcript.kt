package dev.leash.personal.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import dev.leash.personal.core.AppModel

/** The conversation a transcript belongs to: inline images, embeds and relative paths are read from its agent's host (ios `transcriptSession` + `embedAgent`). */
data class Transcript(val agent: String, val sessionId: String?)

val LocalTranscript = staticCompositionLocalOf { Transcript("agy", null) }

/**
 * Called when something in a transcript row grew after it was laid out (an image or a widget finished loading), so a chat that
 * follows the newest output can keep the bottom in view.
 */
val LocalContentGrew = staticCompositionLocalOf<() -> Unit> { {} }

/** The app model, for views deep in a transcript that fetch files (provided once at the root). */
val LocalAppModel = staticCompositionLocalOf<AppModel?> { null }
