package com.limo1800.realtime.client

import com.limo1800.realtime.protocol.DriverFix
import com.limo1800.realtime.protocol.RtEvent
import com.limo1800.realtime.protocol.Wire
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class RealtimeClientTest {

    private class FakeTransport(val listener: TransportListener) : Transport {
        val sent = mutableListOf<String>()
        var closed = false
        override fun send(text: String): Boolean { sent += text; return !closed }
        override fun close(code: Int, reason: String) { closed = true }
        fun frames(type: String): List<JsonObject> =
            sent.map { Json.parseToJsonElement(it).jsonObject }.filter { it["type"]?.jsonPrimitive?.content == type }
    }

    private class FakeFactory : TransportFactory {
        val opened = mutableListOf<FakeTransport>()
        val last get() = opened.last()
        override fun open(url: String, listener: TransportListener): Transport = FakeTransport(listener).also { opened += it }
    }

    private inner class Harness(val scope: TestScope, token: String? = "tok-123") {
        val factory = FakeFactory()
        val cursors = InMemoryCursorStore()
        val events = mutableListOf<RtEvent>()
        var token: String? = token
        val client = RealtimeClient(
            identity = ClientIdentity(app = "rider", appVersion = "1.2.3", deviceId = "dev-1"),
            tokenProvider = { this.token },
            transports = factory,
            cursors = cursors,
            scope = scope.backgroundScope,
            // Deterministic jitter: always the ceiling.
            backoff = Backoff(random = object : Random() {
                override fun nextBits(bitCount: Int) = 0
                override fun nextLong(from: Long, until: Long) = until - 1
            }),
            nowMs = { scope.testScheduler.currentTime },
        )

        init {
            scope.backgroundScope.launch { client.events.collect { events += it } }
        }

        fun server(text: String) { factory.last.listener.onMessage(text); scope.runCurrent() }

        fun connectAndWelcome(url: String = "wss://rt.test/rt/v1/ws") {
            client.connect(url)
            scope.runCurrent()
            factory.last.listener.onOpen()
            scope.runCurrent()
            server(welcome())
        }
    }

    private fun welcome(pingS: Int = 20) =
        """{"v":1,"type":"welcome","data":{"user_id":"10","role":"rider","active_ride":{"ride_id":"100","status":"en_route_pu"},"server_time":0,"ping_interval_s":$pingS}}"""

    private fun status(seq: Long, status: String = "on_location", ride: Long = 100) =
        """{"v":1,"type":"ride.status","ride_id":"$ride","seq":$seq,"ts":1,"data":{"status":"$status","prev":null,"at":1,"stop":null}}"""

    private fun subscribed(seq: Long, ride: Long = 100) =
        """{"v":1,"type":"subscribed","ride_id":"$ride","data":{"seq":$seq,"status":"en_route_pu"}}"""

    @Test
    fun `hello carries the token and identity, welcome connects`() = runTest {
        val h = Harness(this)
        h.connectAndWelcome()
        val hello = h.factory.last.frames("hello").single()["data"]!!.jsonObject
        assertEquals("tok-123", hello["token"]!!.jsonPrimitive.content)
        assertEquals("rider", hello["app"]!!.jsonPrimitive.content)
        assertEquals("android", hello["platform"]!!.jsonPrimitive.content)
        assertEquals(ConnectionState.Connected("10"), h.client.state.value)
        val w = h.events.filterIsInstance<RtEvent.Welcome>().single()
        assertEquals(100L, w.activeRide?.rideId)
    }

    @Test
    fun `no token means no connection attempt`() = runTest {
        val h = Harness(this, token = null)
        h.client.connect("wss://rt.test/rt/v1/ws")
        runCurrent()
        assertTrue(h.factory.opened.isEmpty())
        assertEquals(ConnectionState.Disconnected, h.client.state.value)
    }

    @Test
    fun `subscribe sends the stored cursor as last_seq`() = runTest {
        val h = Harness(this)
        h.cursors.put(100, 7)
        h.client.subscribe(100)
        h.connectAndWelcome()
        val sub = h.factory.last.frames("subscribe").single()
        assertEquals("100", sub["ride_id"]!!.jsonPrimitive.content)
        assertEquals(7L, sub["data"]!!.jsonObject["last_seq"]!!.jsonPrimitive.long)
    }

    @Test
    fun `replayed and duplicate events are delivered once, acked, and flagged as replay`() = runTest {
        val h = Harness(this)
        h.cursors.put(100, 1)
        h.client.subscribe(100)
        h.connectAndWelcome()
        h.server(status(1)) // already seen
        h.server(status(2)) // replay
        h.server(subscribed(2))
        h.server(status(2)) // same frame live again
        h.server(status(3, "start_ride")) // live
        val statuses = h.events.filterIsInstance<RtEvent.RideStatus>()
        assertEquals(listOf(2L, 3L), statuses.map { it.seq })
        assertTrue(statuses[0].isReplay && statuses[0].hadCursor && statuses[0].isNews)
        assertFalse(statuses[1].isReplay)
        assertEquals(listOf(2L, 3L), h.factory.last.frames("ack").map { it["seq"]!!.jsonPrimitive.long })
        assertEquals(3L, h.cursors.get(100))
    }

    @Test
    fun `history replay without a cursor is not news`() = runTest {
        val h = Harness(this)
        h.client.subscribe(100)
        h.connectAndWelcome()
        h.server(status(1))
        assertFalse(h.events.filterIsInstance<RtEvent.RideStatus>().single().isNews)
    }

    @Test
    fun `cursor ahead of the server is rebased on subscribed`() = runTest {
        val h = Harness(this)
        h.cursors.put(100, 50)
        h.client.subscribe(100)
        h.connectAndWelcome()
        h.server(subscribed(3))
        assertEquals(3L, h.cursors.get(100))
        h.server(status(4))
        assertEquals(1, h.events.filterIsInstance<RtEvent.RideStatus>().size)
    }

    @Test
    fun `resync and legacy ride_cancelled are surfaced`() = runTest {
        val h = Harness(this)
        h.client.subscribe(100)
        h.connectAndWelcome()
        h.server("""{"v":1,"type":"resync.required","ride_id":"100","data":{"reason":"gap_too_large"}}""")
        h.server("""{"v":1,"type":"ride.cancelled","ride_id":"100","seq":9,"data":{"by":"rider","at":5}}""")
        assertEquals("gap_too_large", h.events.filterIsInstance<RtEvent.ResyncRequired>().single().reason)
        val cancel = h.events.filterIsInstance<RtEvent.RideStatus>().single()
        assertTrue(cancel.isCancelled)
        assertEquals("rider", cancel.cancelledBy)
    }

    @Test
    fun `unknown types are passed through, never fatal`() = runTest {
        val h = Harness(this)
        h.connectAndWelcome()
        h.server("""{"v":1,"type":"ride.eta","ride_id":"100","data":{"duration_s":240}}""")
        h.server("not json")
        assertEquals("ride.eta", h.events.filterIsInstance<RtEvent.Unknown>().single().type)
        assertTrue(h.client.state.value is ConnectionState.Connected)
    }

    @Test
    fun `pings after 20s and declares the socket dead after 30s of silence`() = runTest {
        val h = Harness(this)
        h.connectAndWelcome()
        val first = h.factory.last
        advanceTimeBy(20_500)
        assertEquals(1, first.frames("ping").size)
        assertFalse(first.closed)
        advanceTimeBy(9_000)
        assertFalse(first.closed) // 29.5 s: still alive
        advanceTimeBy(1_600)
        assertTrue(first.closed) // > 30 s without a frame (checked on a 1 s tick)
        assertFalse(h.client.state.value is ConnectionState.Connected)
    }

    @Test
    fun `a busy sender still pings when nothing comes back`() = runTest {
        val h = Harness(this)
        h.connectAndWelcome()
        val fix = DriverFix(1.0, 2.0, 5.0, 10.0, 90.0, 1, "e", 1)
        repeat(21) {
            h.client.sendLocation(100, fix.copy(seq = it.toLong()))
            advanceTimeBy(1_000)
        }
        assertTrue(h.factory.last.frames("ping").isNotEmpty())
    }

    @Test
    fun `connect timeout without welcome reconnects`() = runTest {
        val h = Harness(this)
        h.client.connect("wss://rt.test/rt/v1/ws")
        runCurrent()
        h.factory.last.listener.onOpen()
        advanceTimeBy(16_500)
        assertTrue(h.factory.last.closed)
        assertTrue(h.client.state.value is ConnectionState.WaitingToReconnect)
    }

    @Test
    fun `backoff grows 0_5 1 2 4 8 and caps at 15s during a ride`() = runTest {
        val h = Harness(this)
        h.client.subscribe(100)
        h.connectAndWelcome()
        val delays = mutableListOf<Long>()
        repeat(7) {
            h.factory.last.listener.onFailure(RuntimeException("down"))
            runCurrent()
            val waiting = h.client.state.value as ConnectionState.WaitingToReconnect
            delays += waiting.inMs
            advanceTimeBy(waiting.inMs + 1)
        }
        assertEquals(listOf(500L, 1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 15_000L), delays)
        assertEquals(8, h.factory.opened.size)
    }

    @Test
    fun `backoff caps at 30s with no ride`() {
        val b = Backoff()
        assertEquals(30_000L, b.ceilingMs(10, duringRide = false))
        assertEquals(15_000L, b.ceilingMs(10, duringRide = true))
        repeat(200) { assertTrue(b.delayMs(10, duringRide = true) in 100..15_000) }
    }

    @Test
    fun `welcome resets the attempt counter and resubscribes`() = runTest {
        val h = Harness(this)
        h.client.subscribe(100)
        h.connectAndWelcome()
        h.factory.last.listener.onFailure(RuntimeException("down"))
        runCurrent()
        advanceTimeBy(600)
        h.factory.last.listener.onOpen(); runCurrent()
        h.server(welcome())
        assertEquals(1, h.factory.last.frames("subscribe").size)
        assertEquals(1, h.client.reconnects.value)
        h.factory.last.listener.onFailure(RuntimeException("down again"))
        runCurrent()
        assertEquals(500L, (h.client.state.value as ConnectionState.WaitingToReconnect).inMs)
    }

    @Test
    fun `reconnectNow skips the pending backoff`() = runTest {
        val h = Harness(this)
        h.client.subscribe(100)
        h.connectAndWelcome()
        repeat(4) { h.factory.last.listener.onFailure(RuntimeException("x")); runCurrent(); advanceTimeBy(20_000) }
        h.factory.last.listener.onFailure(RuntimeException("x")); runCurrent()
        val before = h.factory.opened.size
        h.client.reconnectNow("network changed")
        runCurrent()
        assertEquals(before + 1, h.factory.opened.size)
        assertTrue(h.client.state.value is ConnectionState.Connecting)
    }

    @Test
    fun `foreground reconnects at once when not connected`() = runTest {
        val h = Harness(this)
        h.connectAndWelcome()
        h.client.onBackground(); runCurrent()
        assertEquals("background", h.factory.last.frames("app_state").last()["data"]!!.jsonObject["state"]!!.jsonPrimitive.content)
        h.factory.last.listener.onFailure(RuntimeException("frozen")); runCurrent()
        val before = h.factory.opened.size
        h.client.onForeground(); runCurrent()
        assertEquals(before + 1, h.factory.opened.size)
    }

    @Test
    fun `unauthorized close waits a minute before retrying`() = runTest {
        val h = Harness(this)
        h.connectAndWelcome()
        h.factory.last.listener.onClosed(RealtimeClient.CLOSE_UNAUTHORIZED, "unauthorized")
        runCurrent()
        assertEquals(60_000L, (h.client.state.value as ConnectionState.WaitingToReconnect).inMs)
    }

    @Test
    fun `forbidden ride is dropped from resubscription`() = runTest {
        val h = Harness(this)
        h.client.subscribe(100)
        h.connectAndWelcome()
        h.server("""{"v":1,"type":"error","ride_id":"100","data":{"code":"forbidden_ride","retry":false}}""")
        h.factory.last.listener.onFailure(RuntimeException("x")); runCurrent()
        advanceTimeBy(1_000)
        h.factory.last.listener.onOpen(); runCurrent()
        h.server(welcome())
        assertTrue(h.factory.last.frames("subscribe").isEmpty())
    }

    @Test
    fun `location is dropped while disconnected and encoded exactly per contract when connected`() = runTest {
        val h = Harness(this)
        val fix = DriverFix(40.7, -73.9, 8.0, -1.0, 271.5, 1_700_000_000_000, "epoch-1", 42, DriverFix.SRC_RAW)
        assertFalse(h.client.sendLocation(100, fix))
        h.connectAndWelcome()
        assertTrue(h.client.sendLocation(100, fix))
        val data = h.factory.last.frames("loc.update").single()["data"]!!.jsonObject
        assertEquals(setOf("lat", "lng", "acc_m", "speed_mps", "heading", "gps_ts", "epoch", "seq", "src"), data.keys)
        assertEquals(-1.0, data["speed_mps"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals("raw", data["src"]!!.jsonPrimitive.content)
    }

    @Test
    fun `reset forgets rides and cursors`() = runTest {
        val h = Harness(this)
        h.client.subscribe(100)
        h.connectAndWelcome()
        h.server(status(1))
        h.client.reset(); runCurrent()
        assertEquals(null, h.cursors.get(100))
        assertEquals(ConnectionState.Disconnected, h.client.state.value)
    }

    @Test
    fun `wire round trip keeps ride ids as strings`() {
        val text = Wire.encode("ack", rideId = 2327, seq = 5)
        val env = Wire.decode(text)!!
        assertEquals(2327L, env.rideId)
        assertEquals(5L, env.seq)
        assertTrue(text.contains("\"ride_id\":\"2327\""))
    }
}
