package dev.leash.personal

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import dev.leash.personal.core.AppModel
import dev.leash.personal.core.BridgeConnection
import dev.leash.personal.core.DataStoreSettings
import dev.leash.personal.core.DemoTransport
import dev.leash.personal.core.NetworkChange
import dev.leash.personal.core.OkHttpTransport
import dev.leash.personal.notify.BackgroundController
import dev.leash.personal.notify.LeashNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Owns the app-scoped model, so the UI, the foreground service and the notification actions share one bridge connection. A
 * notification action can start this process with no activity: everything it needs is created here, not in MainActivity.
 */
class LeashApplication : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var model: AppModel
        private set

    val hooks = AndroidHooks(this)
    val notifier by lazy { LeashNotifier(this) }
    val background by lazy { BackgroundController(this, appScope, model, notifier) }

    override fun onCreate() {
        super.onCreate()
        notifier.createChannels()
        model = AppModel(appScope, DataStoreSettings(this), BridgeConnection(appScope, OkHttpTransport()), DemoTransport.factory(appScope))
        model.hooks = hooks
        model.start()
        background.start()
        watchNetwork()
    }

    /** The bridge is reached over Wi-Fi, mobile data or Tailscale: when the default network changes the old socket is dead. */
    private fun watchNetwork() {
        val change = NetworkChange()
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { appScope.launch { if (change.available(network)) model.networkChanged() } }
            override fun onLost(network: Network) { appScope.launch { change.lost(network) } }
        })
    }
}
