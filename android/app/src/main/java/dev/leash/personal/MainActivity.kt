package dev.leash.personal

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.leash.personal.core.AppModel
import dev.leash.personal.core.LeashLink
import dev.leash.personal.ui.LeashRoot
import dev.leash.personal.ui.theme.LeashTheme

class MainActivity : ComponentActivity() {
    private val model: AppModel get() = (application as LeashApplication).model

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // a link that started the activity; after a recreation it was handled already
        if (savedInstanceState == null) handleLink(intent)
        setContent {
            LeashTheme {
                LeashRoot(model)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    override fun onStart() {
        super.onStart()
        model.sceneChanged(true)
    }

    override fun onStop() {
        model.sceneChanged(false)
        super.onStop()
    }

    private fun handleLink(intent: Intent?) {
        val link = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString?.let { LeashLink.parse(it) } ?: return
        model.open(link)
        // the link can carry the token: don't leave it in the activity's intent
        setIntent(Intent(this, MainActivity::class.java))
    }
}
