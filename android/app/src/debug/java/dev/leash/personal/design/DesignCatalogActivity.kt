package dev.leash.personal.design

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.activity.compose.BackHandler
import dev.leash.personal.ui.design.LeashDesignTheme
import dev.leash.personal.ui.design.LocalGlassLensAllowed

/** A pinned live-band phase for deterministic screenshots (null = animate). */
val LocalCatalogPhase = staticCompositionLocalOf<Float?> { null }

/**
 * Debug-only catalog of the design system (DESIGN.md). Extras: `page` (index, glass, dock, colors, type, shapes, controls, list,
 * settings, chat, activity, trays, states, sheet, icon), `theme` (light / dark; default follows the system) and
 * `phase` (0..1 pins the shimmer and live dots). `--ez lens false` draws glass without refraction (as on API 32).
 */
class DesignCatalogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        window.isNavigationBarContrastEnforced = false
        val startPage = intent.getStringExtra("page") ?: "index"
        val theme = intent.getStringExtra("theme")
        val phase = if (intent.hasExtra("phase")) intent.getFloatExtra("phase", 0.4f) else null
        val lens = intent.getBooleanExtra("lens", true)
        setContent {
            val dark = when (theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
            var page by rememberSaveable { mutableStateOf(startPage) }
            BackHandler(enabled = page != "index") { page = "index" }
            LeashDesignTheme(dark = dark) {
                androidx.compose.runtime.CompositionLocalProvider(LocalCatalogPhase provides phase, LocalGlassLensAllowed provides lens) {
                    CatalogPage(page) { page = it }
                }
            }
        }
    }
}

@Composable
private fun CatalogPage(page: String, open: (String) -> Unit) {
    when (page) {
        "glass" -> GlassPage()
        "colors" -> ColorsPage()
        "type" -> TypePage()
        "shapes" -> ShapesPage()
        "controls" -> ControlsPage()
        "list" -> ListPage()
        "settings" -> SettingsPage()
        "chat" -> ChatPage()
        "dock" -> DockPage()
        "activity" -> ActivityPage()
        "trays" -> TraysPage()
        "states" -> StatesPage()
        "sheet" -> SheetPage()
        "icon" -> IconPage()
        else -> IndexPage(open)
    }
}
