package com.limo1800.realtime.api

import com.limo1800.realtime.config.RealtimeConfig
import com.limo1800.realtime.config.RealtimeConfigRepository
import com.limo1800.realtime.config.RealtimeProvider
import com.limo1800.realtime.config.ConfigCache
import com.limo1800.realtime.protocol.RideStatuses
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RtApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: RtApi

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        api = RtApi(OkHttpClient(), { server.url("/rt/v1").toString() }, { "tok" })
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `rest base is derived from the socket url`() {
        assertEquals("https://rt.1800limo.com/rt/v1", RtApi.restBaseFromSocketUrl("wss://rt.1800limo.com/rt/v1/ws"))
        assertEquals("http://10.0.0.2:9100/rt/v1", RtApi.restBaseFromSocketUrl("ws://10.0.0.2:9100/rt/v1/ws"))
        assertNull(RtApi.restBaseFromSocketUrl(null))
    }

    @Test
    fun `chat history asks for the gap after the last id`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"messages":[{"id":"7","from":"rider","sender_id":"10","receiver_id":"11","body":"hi","client_msg_id":null,"at":5,"read_at":null}]}""",
            ),
        )
        val messages = api.chatHistory(2327, afterId = 6).getOrThrow()
        assertEquals("/rt/v1/rides/2327/messages?after_id=6&limit=50", server.takeRequest().path)
        assertEquals(7L, messages.single().id)
        assertEquals("rider", messages.single().senderRole)
    }

    @Test
    fun `share link and read receipts`() = runTest {
        server.enqueue(MockResponse().setBody("""{"token":"s.abc","expires_at":99}"""))
        server.enqueue(MockResponse().setBody("""{"updated":2}"""))
        assertEquals(ShareLink("s.abc", 99), api.createShareLink(5).getOrThrow())
        assertTrue(api.markRead(5, upToId = 40) is HttpOutcome.Ok)
        server.takeRequest()
        val read = server.takeRequest()
        assertEquals("/rt/v1/rides/5/messages/read", read.path)
        assertEquals("""{"up_to_id":40}""", read.body.readUtf8())
    }

    @Test
    fun `latest location fallback`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"location":{"lat":40.7,"lng":-73.9,"acc_m":5,"speed_mps":-1,"heading":90,"gps_ts":10,"epoch":"e","seq":3,"src":"raw"},"ts":11,"status":"en_route_pu","seq":4}""",
            ),
        )
        val latest = api.latestLocation(5).getOrThrow()
        assertEquals(40.7, latest.fix!!.lat, 0.0)
        assertNull(latest.fix!!.speedMps) // -1 = unknown
        assertEquals("en_route_pu", latest.status)
    }

    @Test
    fun `outcome classification`() {
        assertTrue(HttpOutcome.Error(503, null).isRetriable)
        assertTrue(HttpOutcome.Error(429, null).isRetriable)
        assertFalse(HttpOutcome.Error(409, null).isRetriable)
        assertTrue(HttpOutcome.Error(440, null).isAuthFailure)
        assertTrue(HttpOutcome.NotConfigured.isRetriable)
    }

    @Test
    fun `cancel is offered only before pickup`() {
        listOf("pending", "assigned", "en_route_pu", "on_location").forEach { assertTrue(it, RideStatuses.isCancellable(it)) }
        listOf("start_ride", "passenger_drop", "passenger_pick", "en_route_do", "ended", "finalized", "cancelled", "rejected")
            .forEach { assertFalse(it, RideStatuses.isCancellable(it)) }
        assertFalse(RideStatuses.isCancellable(null))
    }

    @Test
    fun `config follows laravel's realtime block and keeps the cache when unreachable`() = runTest {
        var saved: RealtimeConfig? = null
        val cache = object : ConfigCache {
            override fun load() = saved
            override fun save(config: RealtimeConfig) { saved = config }
        }
        val repo = RealtimeConfigRepository(OkHttpClient(), server.url("/").toString(), { "tok" }, cache)
        server.enqueue(MockResponse().setBody("""{"data":{"realtime":{"provider":"python","url":"wss://rt.x/rt/v1/ws"}}}"""))
        val config = repo.refreshIfStale(force = true)
        assertEquals(RealtimeProvider.PYTHON, config.provider)
        assertTrue(config.usesLimoRt)
        assertEquals("Bearer tok", server.takeRequest().getHeader("Authorization"))

        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(config, repo.refreshIfStale(force = true))

        server.enqueue(MockResponse().setBody("""{"data":{"app":"x"}}"""))
        assertEquals(RealtimeConfig.NODE, repo.refreshIfStale(force = true))
        assertEquals(RealtimeConfig.NODE, saved)
    }
}
