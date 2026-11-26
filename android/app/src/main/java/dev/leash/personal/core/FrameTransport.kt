package dev.leash.personal.core

/** Where the bridge is and how to authenticate. */
data class Endpoint(val url: String, val token: String)

interface FrameSocket {
    /** false when the frame could not be queued (closing, or the outbound queue is full). */
    fun send(text: String): Boolean
    fun close()
}

/**
 * The WebSocket under BridgeConnection, at the level of text frames. Callbacks may arrive on any thread; BridgeConnection
 * hops to its own scope. OkHttp implements it for the app, tests use a fake.
 */
interface FrameTransport {
    interface Listener {
        fun onOpen()
        fun onText(text: String)
        /** the socket died; `httpStatus` is the handshake's response code when it got that far (401: bad token) */
        fun onFailure(error: Throwable, httpStatus: Int?)
        /** the peer closed it */
        fun onClosed(reason: String)
    }

    fun open(endpoint: Endpoint, listener: Listener): FrameSocket
}
