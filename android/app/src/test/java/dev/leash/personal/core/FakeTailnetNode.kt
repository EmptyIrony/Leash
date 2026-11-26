package dev.leash.personal.core

import kotlinx.coroutines.CompletableDeferred

/** A TailnetNode the test scripts: what it was asked, what it answers. */
class FakeTailnetNode : TailnetNode {
    data class Started(val stateDir: String, val authKey: String, val controlUrl: String, val hostname: String)

    val starts = mutableListOf<Started>()
    var stops = 0
    var logouts = 0
    val proxied = mutableListOf<Pair<String, Int>>()

    var startError: Exception? = null
    var waitError: Exception? = null
    var logoutError: Exception? = null
    var port = 40123
    var statusJson = """{"state":"connected","ips":["fd7a:115c:a1e0::1","100.64.0.9"],"name":"leash-android"}"""
    /** when set, start() waits for it */
    var startGate: CompletableDeferred<Unit>? = null

    override suspend fun start(stateDir: String, authKey: String, controlUrl: String, hostname: String) {
        starts += Started(stateDir, authKey, controlUrl, hostname)
        startGate?.await()
        startError?.let { throw it }
    }

    override fun stop() { stops++ }
    override suspend fun logout() { logouts++; logoutError?.let { throw it } }
    override suspend fun proxyPort(host: String, port: Int): Int { proxied += host to port; return this.port }
    override suspend fun waitConnected(seconds: Int) { waitError?.let { throw it } }
    override suspend fun status(): String = statusJson
}
