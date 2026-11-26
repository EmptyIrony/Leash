package dev.leash.personal.core

/**
 * Decides from the device's default-network callbacks whether the bridge connection must be redone: the first network seen
 * is nothing new (the connection is made at launch), another network or the same one coming back after a loss is. The
 * old socket is dead then even if it has not noticed yet.
 */
class NetworkChange {
    private var current: Any? = null
    private var lost = false

    /** The default network is [network] now; true when the connection has to be redone. */
    fun available(network: Any): Boolean {
        val changed = current != null && (current != network || lost)
        current = network
        lost = false
        return changed
    }

    fun lost(network: Any) { if (network == current) lost = true }
}
