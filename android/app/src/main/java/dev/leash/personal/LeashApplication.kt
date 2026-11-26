package dev.leash.personal

import android.app.Application

/** Owns the app-scoped state (the AppModel arrives in phase 1b) so the UI and the foreground service share it. */
class LeashApplication : Application()
