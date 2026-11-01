package com.limo1800.realtime.client

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** One WebSocket connection. Callbacks may arrive on any thread. */
interface Transport {
    fun send(text: String): Boolean
    fun close(code: Int, reason: String)
}

interface TransportListener {
    fun onOpen()
    fun onMessage(text: String)
    fun onClosed(code: Int, reason: String)
    fun onFailure(error: Throwable)
}

fun interface TransportFactory {
    fun open(url: String, listener: TransportListener): Transport
}

/**
 * OkHttp's WebSocket. Protocol-level pings are off here: liveness is the client's own
 * `ping` frame every 20 s plus "no frame for 30 s = dead", which also catches a half-open
 * socket after a cell handover that OkHttp alone would notice much later.
 */
class OkHttpTransportFactory(baseClient: OkHttpClient) : TransportFactory {
    private val client = baseClient.newBuilder().pingInterval(0, java.util.concurrent.TimeUnit.SECONDS).build()

    override fun open(url: String, listener: TransportListener): Transport {
        val socket = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen()
                override fun onMessage(webSocket: WebSocket, text: String) = listener.onMessage(text)
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, null)
                    listener.onClosed(code, reason)
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = listener.onClosed(code, reason)
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = listener.onFailure(t)
            },
        )
        return object : Transport {
            override fun send(text: String): Boolean = socket.send(text)
            override fun close(code: Int, reason: String) {
                if (!socket.close(code, reason)) socket.cancel()
            }
        }
    }
}
