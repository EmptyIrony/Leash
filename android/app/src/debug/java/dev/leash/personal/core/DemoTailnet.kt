package dev.leash.personal.core

import kotlinx.coroutines.delay

/**
 * Debug builds without the Tailnet AAR: a scripted node, so the Settings section can be driven and looked at. A key containing
 * "bad" is rejected (needs a new key); anything else registers after a moment as leash-android-demo. It has no route to any
 * bridge.
 */
object DemoTailnet {
    fun create(): TailnetNode? = DemoNode()
}

private class DemoNode : TailnetNode {
    private var key = ""
    private var startedAt = 0L
    private var up = false

    override suspend fun start(stateDir: String, authKey: String, controlUrl: String, hostname: String) {
        key = authKey; startedAt = System.currentTimeMillis(); up = true
    }

    override fun stop() { up = false }
    override suspend fun logout() {}
    override suspend fun proxyPort(host: String, port: Int): Int = throw TailnetException("The demo node has no route to $host")

    override suspend fun waitConnected(seconds: Int) {
        delay(2_500)
        if ("bad" in key) throw TailnetException("tailnet: invalid authkey")
    }

    override suspend fun status(): String = when {
        !up -> """{"state":"stopped","ips":[]}"""
        "bad" in key -> """{"state":"needs_key","ips":[],"error":"invalid authkey"}"""
        System.currentTimeMillis() - startedAt < 2_500 -> """{"state":"starting","ips":[]}"""
        else -> """{"state":"connected","ips":["100.64.0.7","fd7a:115c:a1e0::7"],"name":"leash-android-demo"}"""
    }
}
