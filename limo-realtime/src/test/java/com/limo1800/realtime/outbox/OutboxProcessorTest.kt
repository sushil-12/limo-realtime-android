package com.limo1800.realtime.outbox

import com.limo1800.realtime.api.RtApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import java.util.concurrent.TimeUnit

class OutboxProcessorTest {

    private lateinit var server: MockWebServer
    private val store = InMemoryOutboxStore()
    private var token: String? = "tok"
    private lateinit var processor: OutboxProcessor

    /** Scripted replies per path, consumed in order; then the default. */
    private val script = mutableMapOf<String, ArrayDeque<MockResponse>>()
    private var default = MockResponse().setResponseCode(200).setBody("""{"success":true}""")
    private val seen = mutableListOf<RecordedRequest>()
    private val bodies = mutableListOf<String>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(seen) { seen += request; bodies += request.body.readUtf8() }
                val path = request.path!!.substringBefore('?')
                return script[path]?.removeFirstOrNull() ?: default
            }
        }
        server.start()
        val http = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build()
        val api = RtApi(http, { server.url("/rt/v1").toString() }, { token })
        processor = OutboxProcessor(store, api, nowMs = { 1_000 })
    }

    @After
    fun tearDown() = server.shutdown()

    private fun reply(path: String, vararg responses: MockResponse) {
        script.getOrPut(path) { ArrayDeque() }.addAll(responses)
    }

    private fun code(c: Int) = MockResponse().setResponseCode(c).setBody("""{"message":"m$c"}""")

    private fun statuses(): List<String> = seen.indices
        .filter { seen[it].path!!.contains("/status") }
        .map { Json.parseToJsonElement(bodies[it]).jsonObject["status"]!!.jsonPrimitive.content }

    private fun body(i: Int) = Json.parseToJsonElement(bodies[i]).jsonObject

    @Test
    fun `status is sent at once with idempotency key and bearer`() = runTest {
        val result = processor.sendStatus(StatusRequest(2327, "on_location", eventAtMs = 5, latitude = 1.0, longitude = 2.0))
        assertEquals(SendResult.Sent, result)
        val req = seen.single()
        assertEquals("/rt/v1/driver/rides/2327/status", req.path)
        assertEquals("Bearer tok", req.getHeader("Authorization"))
        assertEquals("2327-on_location-5", req.getHeader("Idempotency-Key"))
        val body = body(0)
        assertEquals("1.0", body["location"]!!.jsonObject["latitude"]!!.jsonPrimitive.content)
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `offline status is kept and later sent in order with the same key`() = runTest {
        reply("/rt/v1/driver/rides/1/status", code(503), code(503))
        assertEquals(SendResult.Queued, processor.sendStatus(StatusRequest(1, "on_location", eventAtMs = 10)))
        // A later transition must not overtake the stuck one.
        assertEquals(SendResult.Queued, processor.sendStatus(StatusRequest(1, "start_ride", eventAtMs = 20)))
        assertEquals(listOf("on_location", "on_location"), statuses())

        val drained = processor.drain()
        assertEquals(2, drained.sent)
        assertEquals(listOf("on_location", "on_location", "on_location", "start_ride"), statuses())
        val keys = seen.map { it.getHeader("Idempotency-Key") }
        assertEquals(listOf("1-on_location-10", "1-on_location-10", "1-on_location-10", "1-start_ride-20"), keys)
    }

    @Test
    fun `one ride's stuck queue doesn't hold back another ride`() = runTest {
        // Every drain retries ride 1 first; it keeps failing.
        reply("/rt/v1/driver/rides/1/status", code(500), code(500))
        processor.sendStatus(StatusRequest(1, "on_location", eventAtMs = 1))
        assertEquals(SendResult.Sent, processor.sendStatus(StatusRequest(2, "en_route_pu", eventAtMs = 2)))
        assertEquals(1, store.all().size)
    }

    @Test
    fun `permanent refusal drops a status and reports it`() = runTest {
        reply("/rt/v1/driver/rides/1/status", code(422))
        val result = processor.sendStatus(StatusRequest(1, "ended", eventAtMs = 1))
        assertEquals(SendResult.Rejected(422, "m422"), result)
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `auth failure parks everything instead of dropping it`() = runTest {
        // Refused on the first send, and again when the chat enqueue drains.
        reply("/rt/v1/driver/rides/1/status", code(401), code(401))
        assertEquals(SendResult.Queued, processor.sendStatus(StatusRequest(1, "on_location", eventAtMs = 1)))
        processor.sendChat(1, "hi", "c-1")
        val stillQueued = store.all()
        assertEquals(2, stillQueued.size)
        assertTrue(stillQueued.all { it.state == OutboxState.PENDING })
        // Token refreshed: everything goes, in order.
        val drained = processor.drain()
        assertEquals(2, drained.sent)
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `no token or no limo-rt url keeps entries queued`() = runTest {
        token = null
        assertEquals(SendResult.Queued, processor.sendStatus(StatusRequest(1, "on_location", eventAtMs = 1)))
        assertTrue(seen.isEmpty())
        token = "tok"
        assertEquals(1, processor.drain().sent)
    }

    @Test
    fun `same transition enqueued twice is stored once`() = runTest {
        reply("/rt/v1/driver/rides/1/status", code(503), code(503))
        processor.sendStatus(StatusRequest(1, "on_location", eventAtMs = 7))
        processor.sendStatus(StatusRequest(1, "on_location", eventAtMs = 7))
        assertEquals(1, store.all().size)
    }

    @Test
    fun `chat carries client_msg_id, failed chat is kept for retry`() = runTest {
        reply("/rt/v1/rides/9/messages", code(403))
        val id = processor.sendChat(9, "  On my way  ", "client-abc")
        assertEquals("client-abc", id)
        val body = body(0)
        assertEquals("On my way", body["body"]!!.jsonPrimitive.content)
        assertEquals("client-abc", body["client_msg_id"]!!.jsonPrimitive.content)
        val failed = processor.entries.value.single()
        assertEquals(OutboxState.FAILED, failed.state)

        default = MockResponse().setResponseCode(201).setBody(
            """{"message":{"id":"55","from":"driver","sender_id":"11","body":"On my way","client_msg_id":"client-abc","at":1}}""",
        )
        processor.retry(failed.id)
        assertTrue(processor.entries.value.isEmpty())
        assertEquals("client-abc", processor.lastDeliveredChat.value?.clientMsgId)
        assertEquals(55L, processor.lastDeliveredChat.value?.id)
    }

    @Test
    fun `dropRide removes queued statuses for a cancelled ride but keeps chat`() = runTest {
        default = code(503)
        processor.sendStatus(StatusRequest(3, "on_location", eventAtMs = 1))
        processor.sendChat(3, "sorry", "c-2")
        processor.dropRide(3)
        assertEquals(listOf(OutboxKind.CHAT), store.all().map { it.kind })
    }

    @Test
    fun `stop info is sent with the keys the status endpoint reads`() = runTest {
        processor.sendStatus(
            StatusRequest(
                4, "passenger_drop", eventAtMs = 1,
                stop = StatusRequest.StopRef(1, 2, 3, "Central Park West", 40.78, -73.97),
            ),
        )
        val stop = body(0)["stop_info"]!!.jsonObject
        assertEquals(setOf("index", "stop_number", "total_stops", "address", "latitude", "longitude"), stop.keys)
        assertEquals("4-passenger_drop-stop1-1", seen.single().getHeader("Idempotency-Key"))
    }
}
