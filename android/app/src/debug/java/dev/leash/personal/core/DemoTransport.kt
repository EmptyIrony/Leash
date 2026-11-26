package dev.leash.personal.core

import kotlinx.coroutines.CoroutineScope

/** Debug builds can run against the scripted demo bridge (Settings > Demo bridge). */
object DemoTransport {
    fun factory(scope: CoroutineScope): (() -> BridgeLink)? = { DemoBridge(scope) }
}
