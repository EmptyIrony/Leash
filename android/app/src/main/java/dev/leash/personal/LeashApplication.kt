package dev.leash.personal

import android.app.Application
import dev.leash.personal.core.AppModel
import dev.leash.personal.core.BridgeConnection
import dev.leash.personal.core.DataStoreSettings
import dev.leash.personal.core.DemoTransport
import dev.leash.personal.core.OkHttpTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Owns the app-scoped model, so the UI and (from phase 3) the foreground service share one bridge connection. */
class LeashApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var model: AppModel
        private set

    override fun onCreate() {
        super.onCreate()
        model = AppModel(appScope, DataStoreSettings(this), BridgeConnection(appScope, OkHttpTransport()), DemoTransport.factory(appScope))
        model.start()
    }
}
