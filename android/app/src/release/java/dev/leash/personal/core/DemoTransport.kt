package dev.leash.personal.core

import kotlinx.coroutines.CoroutineScope

/** Release builds have no demo bridge. */
object DemoTransport {
    fun factory(scope: CoroutineScope): (() -> BridgeLink)? = null
}
