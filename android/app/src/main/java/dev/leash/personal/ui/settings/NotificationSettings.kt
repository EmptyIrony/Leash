package dev.leash.personal.ui.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.leash.personal.LeashApplication
import dev.leash.personal.R
import dev.leash.personal.core.AppModel
import dev.leash.personal.notify.LeashNotifier

/** The notification state and the one app-side switch; which kinds ring is the system's per-channel setting. */
@Composable
internal fun NotificationsSection(model: AppModel, keepAlive: Boolean) {
    val context = LocalContext.current
    val notifier = (context.applicationContext as LeashApplication).notifier
    // the system settings can change while this screen is paused
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { refresh++; onPauseOrDispose { } }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    @Suppress("UNUSED_EXPRESSION") refresh
    val on = notifier.canAlert(LeashNotifier.NEEDS_YOU) || notifier.canAlert(LeashNotifier.TURN_FINISHED)
    val someOff = on && notifier.blockedKinds().isNotEmpty()

    Section(stringResource(R.string.notifications_section)) {
        Text(
            stringResource(if (!on) R.string.notifications_off else if (someOff) R.string.notifications_blocked_type else R.string.notifications_on),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!notifier.permissionGranted && Build.VERSION.SDK_INT >= 33) {
            Button(onClick = { ask.launch(Manifest.permission.POST_NOTIFICATIONS) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.allow_notifications)) }
        }
        OutlinedButton(
            onClick = { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.notification_settings)) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.keep_alive), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.keep_alive_detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = keepAlive, onCheckedChange = { model.setKeepAlive(it) })
        }
    }
}
