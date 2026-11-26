package dev.leash.personal.core

import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.leash.personal.tailnet.Tailnet

/** The Tailnet AAR is linked into this build (tailnet/ built by gomobile). The only file that touches the generated class. */
object NativeTailnet {
    fun create(): TailnetNode? = GomobileNode
}

/**
 * The Go calls block (Start and Logout hold the node's lock for seconds, WaitConnected waits for the tailnet). The ones that
 * change the node go through one thread, in the order they were made (a Stop issued before a Start also runs before it); the
 * ones that only read run on the IO pool, so a pending Start never delays the status.
 */
private object GomobileNode : TailnetNode {
    private val control = Executors.newSingleThreadExecutor { Thread(it, "tailnet-control").apply { isDaemon = true } }.asCoroutineDispatcher()
    private val controlScope = CoroutineScope(SupervisorJob() + control)

    private suspend fun <T> call(dispatcher: kotlin.coroutines.CoroutineContext, what: String, body: () -> T): T =
        withContext(dispatcher) {
            try { body() } catch (e: Exception) { throw TailnetException(e.message ?: what) }
        }

    override suspend fun start(stateDir: String, authKey: String, controlUrl: String, hostname: String) =
        call(control, "Tailscale did not start") { Tailnet.start(stateDir, authKey, controlUrl, hostname) }

    override fun stop() { controlScope.launch { Tailnet.stop() } }

    override suspend fun logout() = call(control, "Tailscale did not log out") { Tailnet.logout() }

    override suspend fun proxyPort(host: String, port: Int): Int =
        call(control, "Tailscale has no route to the bridge") { Tailnet.proxyPort(host, port.toLong()).toInt() }

    override suspend fun waitConnected(seconds: Int) =
        call(Dispatchers.IO, "Tailscale did not connect") { Tailnet.waitConnected(seconds.toLong()) }

    override suspend fun status(): String = withContext(Dispatchers.IO) { Tailnet.status() }
}
