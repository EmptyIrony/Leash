package dev.leash.personal.core

import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Debug builds: `am start … --es tailnet_key <auth key> [--es tailnet_hostname <name>] [--es tailnet_control <url>]` switches built-in
 * Tailscale on with that key, so a test does not have to type it on the emulator. The extras are removed from the intent. Nothing
 * here logs them.
 */
object TailnetDebug {
    fun apply(model: AppModel, scope: CoroutineScope, intent: Intent?) {
        val t = model.tailnet ?: return
        val key = intent?.getStringExtra("tailnet_key") ?: return
        val hostname = intent.getStringExtra("tailnet_hostname")
        val control = intent.getStringExtra("tailnet_control") ?: ""
        intent.removeExtra("tailnet_key"); intent.removeExtra("tailnet_hostname"); intent.removeExtra("tailnet_control")
        scope.launch {
            if (hostname != null) t.setHostname(hostname)
            t.saveConfig(key, control)
            t.setEnabled(true)
            model.reconfigure()
        }
    }
}
