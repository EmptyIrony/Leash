package dev.leash.personal.notify

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.leash.personal.LeashApplication

/**
 * Keeps Leash's process (and with it the connection to the bridge) alive while a turn works, a request waits, and for a short
 * grace after you leave the app. Its notification is the ongoing "working" notification. [BackgroundController] starts, updates
 * and stops it; it never restarts by itself (a killed process starts clean and the bridge's `ready` brings it up to date).
 */
class LeashService : Service() {
    override fun onCreate() {
        super.onCreate()
        (application as LeashApplication).background.serviceCreated(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as LeashApplication
        val n = app.background.ongoingNotification()
        if (Build.VERSION.SDK_INT >= 34) startForeground(LeashNotifier.ONGOING_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(LeashNotifier.ONGOING_ID, n)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        (application as LeashApplication).background.serviceDestroyed(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
