package com.limo1800.realtime

import com.limo1800.realtime.api.RtApi
import com.limo1800.realtime.client.ClientIdentity
import com.limo1800.realtime.client.InMemoryCursorStore
import com.limo1800.realtime.client.RealtimeClient
import com.limo1800.realtime.client.Transport
import com.limo1800.realtime.client.TransportFactory
import com.limo1800.realtime.client.TransportListener
import com.limo1800.realtime.config.ConfigCache
import com.limo1800.realtime.config.RealtimeConfig
import com.limo1800.realtime.config.RealtimeConfigRepository
import com.limo1800.realtime.config.RealtimeProvider
import com.limo1800.realtime.outbox.InMemoryOutboxStore
import com.limo1800.realtime.outbox.OutboxKind
import com.limo1800.realtime.outbox.OutboxProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class RealtimeSessionTest {
    private lateinit var server: MockWebServer
    private val requests = CopyOnWriteArrayList<String>()
    @Volatile private var provider = "python"
    @Volatile private var statusCode = 200
    private val opened = CopyOnWriteArrayList<Pair<String, TransportListener>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var token: String? = "tok"
    private var scheduled = 0

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += "${request.method} ${request.path}"
                return when {
                    request.path == "/api/mobile/v1/config" -> MockResponse().setBody(
                        """{"data":{"realtime":{"provider":"$provider","url":"ws://rt.test/rt/v1/ws"}}}""",
                    )
                    request.path!!.endsWith("/status") -> MockResponse().setResponseCode(statusCode).setBody("{}")
                    else -> MockResponse().setResponseCode(204)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private fun session(): RealtimeSession {
        val http = OkHttpClient()
        val cache = object : ConfigCache {
            var saved: RealtimeConfig? = null
            override fun load() = saved
            override fun save(config: RealtimeConfig) { saved = config }
        }
        val config = RealtimeConfigRepository(http, server.url("/").toString(), { token }, cache)
        val client = RealtimeClient(
            ClientIdentity("driver", "1.0", "d"), { token },
            TransportFactory { url, listener ->
                opened += url to listener
                object : Transport {
                    override fun send(text: String) = true
                    override fun close(code: Int, reason: String) = Unit
                }
            },
            InMemoryCursorStore(), scope,
        )
        // REST goes to the mock server whatever the socket URL says.
        val api = RtApi(http, { if (config.config.value.usesLimoRt) server.url("/rt/v1").toString() else null }, { token })
        val outbox = OutboxProcessor(InMemoryOutboxStore(), api)
        return RealtimeSession(
            client, api, outbox, config, scope, "driver", "1.0", { "en-US" }, { token != null }, { scheduled++ },
        )
    }

    private fun eventually(check: () -> Boolean) = runBlocking {
        withTimeout(5_000) { while (!check()) delay(20) }
    }

    @Test
    fun `python flag connects, welcome registers the device and drains the outbox`() {
        val s = session()
        s.onFcmToken("fcm-token-000000000000000001")
        s.start()
        eventually { opened.isNotEmpty() }
        assertEquals("ws://rt.test/rt/v1/ws", opened.single().first)

        // Queued while the socket was still opening (server says 503 at first).
        statusCode = 503
        runBlocking { s.sendStatus(com.limo1800.realtime.outbox.StatusRequest(7, "on_location", eventAtMs = 1)) }
        assertEquals(1, scheduled)
        statusCode = 200

        val listener = opened.single().second
        listener.onOpen()
        listener.onMessage("""{"v":1,"type":"welcome","data":{"user_id":"11","role":"driver","server_time":0}}""")
        eventually { requests.contains("POST /rt/v1/devices") && s.outbox.entries.value.isEmpty() }
    }

    @Test
    fun `node flag keeps the client disconnected`() {
        provider = "node"
        val s = session()
        s.start()
        eventually { requests.contains("GET /api/mobile/v1/config") }
        Thread.sleep(200)
        assertTrue(opened.isEmpty())
        assertTrue(!s.usesLimoRt)
    }

    @Test
    fun `a cancelled ride drops its queued status changes`() {
        statusCode = 503
        val s = session()
        s.start()
        eventually { opened.isNotEmpty() }
        runBlocking { s.sendStatus(com.limo1800.realtime.outbox.StatusRequest(7, "on_location", eventAtMs = 1)) }
        val listener = opened.single().second
        listener.onOpen()
        listener.onMessage("""{"v":1,"type":"welcome","data":{"user_id":"11"}}""")
        listener.onMessage("""{"v":1,"type":"ride.status","ride_id":"7","seq":3,"data":{"status":"cancelled","cancelled_by":"rider"}}""")
        eventually { s.outbox.entries.value.none { it.kind == OutboxKind.STATUS } }
    }
}
