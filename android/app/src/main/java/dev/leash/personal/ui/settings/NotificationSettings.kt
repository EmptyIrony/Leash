package dev.leash.personal.ui.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.leash.personal.LeashApplication
import dev.leash.personal.R
import dev.leash.personal.core.AppModel
import dev.leash.personal.notify.LeashNotifier
import dev.leash.personal.ui.components.SectionGap
import dev.leash.personal.ui.design.Leash
import dev.leash.personal.ui.design.LeashIcons
import dev.leash.personal.ui.design.components.GroupCard
import dev.leash.personal.ui.design.components.SectionFooter
import dev.leash.personal.ui.design.components.SectionHeader
import dev.leash.personal.ui.design.components.ToggleRow
import dev.leash.personal.ui.design.components.ValueRow

/** The notification state and the one app-side switch; which kinds ring is the system's per-channel setting. */
@Composable
internal fun NotificationsSection(model: AppModel, keepAlive: Boolean) {
    val context = LocalContext.current
    val c = Leash.colors
    val notifier = (context.applicationContext as LeashApplication).notifier
    // the system settings can change while this screen is paused
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { refresh++; onPauseOrDispose { } }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    @Suppress("UNUSED_EXPRESSION") refresh
    val on = notifier.canAlert(LeashNotifier.NEEDS_YOU) || notifier.canAlert(LeashNotifier.TURN_FINISHED)
    val someOff = on && notifier.blockedKinds().isNotEmpty()

    SectionGap()
    SectionHeader(stringResource(R.string.notifications_section))
    GroupCard {
        NoteRow(
            stringResource(if (!on) R.string.notifications_off else if (someOff) R.string.notifications_blocked_type else R.string.notifications_on),
            c.textSecondary,
        )
        if (!notifier.permissionGranted && Build.VERSION.SDK_INT >= 33) {
            ValueRow(stringResource(R.string.allow_notifications), labelColor = c.accent, onClick = { ask.launch(Manifest.permission.POST_NOTIFICATIONS) })
        }
        ValueRow(
            stringResource(R.string.notification_settings), labelColor = c.accent,
            trailing = { Icon(LeashIcons.openExternal, null, Modifier.size(18.dp), tint = c.textTertiary) },
            onClick = { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) },
        )
        ToggleRow(stringResource(R.string.keep_alive), keepAlive, { model.setKeepAlive(it) }, last = true)
    }
    SectionFooter(stringResource(R.string.keep_alive_detail))
}

