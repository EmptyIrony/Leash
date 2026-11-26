package dev.leash.personal

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import dev.leash.personal.core.AppModel
import dev.leash.personal.core.LeashLink
import dev.leash.personal.core.TailnetDebug
import dev.leash.personal.ui.LeashRoot
import dev.leash.personal.ui.design.LeashDesignTheme

class MainActivity : ComponentActivity() {
    private val model: AppModel get() = (application as LeashApplication).model
    private val app: LeashApplication get() = application as LeashApplication
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        // transparent bars in both modes; LeashDesignTheme sets the icon colour per theme
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        // 3-button navigation stays transparent too: content scrolls on under it (DESIGN.md §7)
        window.isNavigationBarContrastEnforced = false
        super.onCreate(savedInstanceState)
        // debug builds: `--ez dark true|false` on the launching intent forces the theme (screenshots in both modes without touching the
        // device's setting); read before the link handling below replaces the intent
        val debuggable = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        val forceDark: Boolean? = if (debuggable && intent.hasExtra("dark")) intent.getBooleanExtra("dark", false) else null
        app.hooks.view = window.decorView
        app.hooks.permissionAsker = { if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
        // a link that started the activity; after a recreation it was handled already
        if (savedInstanceState == null) { TailnetDebug.apply(model, app.appScope, intent); handleLink(intent) }
        setContent {
            LeashDesignTheme(dark = forceDark ?: androidx.compose.foundation.isSystemInDarkTheme()) {
                LeashRoot(model)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        TailnetDebug.apply(model, app.appScope, intent)
        handleLink(intent)
    }

    override fun onDestroy() {
        app.hooks.run {
            if (view === window.decorView) { view = null; permissionAsker = null }
        }
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        model.sceneChanged(true)
        app.background.sceneChanged(true)
    }

    override fun onStop() {
        model.sceneChanged(false)
        // a rotation stops and restarts the activity: that is not leaving
        app.background.sceneChanged(false, leaving = !isChangingConfigurations)
        super.onStop()
    }

    private fun handleLink(intent: Intent?) {
        val link = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString?.let { LeashLink.parse(it) } ?: return
        model.open(link)
        // the link can carry the token: don't leave it in the activity's intent
        setIntent(Intent(this, MainActivity::class.java))
    }
}
