package com.limo1800.realtime.playback

import com.limo1800.realtime.protocol.DriverFix
import com.limo1800.realtime.protocol.RtEvent
import com.limo1800.realtime.protocol.Wire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Analytic scenarios (a car at constant speed on a straight road), so expected positions are
 * exact. The iOS port (PlaybackInterpolatorTests.swift) runs the same cases.
 */
class PlaybackInterpolatorTest {

    // Due north along a meridian: 0.0001° lat ≈ 11.13 m.
    private val start = GeoPoint(40.7000, -73.9900)
    private fun north(m: Double) = GeoPoint(start.lat + m / 111_320.0, start.lng)
    private val road = (0..20).map { north(it * 50.0) } // 1 km straight road

    private fun fix(seq: Long, at: GeoPoint, speed: Double? = 10.0, heading: Double? = 0.0, epoch: String = "e1") =
        DriverFix(at.lat, at.lng, 5.0, speed, heading, gpsTs = 1_000L * seq, epoch = epoch, seq = seq)

    /** A car at 10 m/s, one fix per second, server ts = 1000·n. */
    private fun driving(p: PlaybackInterpolator, seconds: Int) {
        for (n in 0..seconds) p.offer(fix(n.toLong(), north(10.0 * n)), serverTsMs = 1_000L * n)
    }

    private fun metresNorth(pose: CarPose) = (pose.position.lat - start.lat) * 111_320.0

    @Test
    fun `renders D behind the newest fix, between the two fixes around that moment`() {
        val p = PlaybackInterpolator()
        driving(p, 5)
        assertEquals(1_200L, p.delayMs()) // 1.2 × 1 s median
        // At t = 5.0 s the car is drawn at t = 3.8 s → 38 m north.
        val pose = p.pose(5_000)!!
        assertEquals(38.0, metresNorth(pose), 0.3)
        assertFalse(pose.isStale)
        assertEquals(1f, pose.alpha)
    }

    @Test
    fun `moves continuously and never backwards frame to frame`() {
        val p = PlaybackInterpolator()
        driving(p, 10)
        var previous = -1.0
        for (t in 3_000L..10_000L step 16) {
            val m = metresNorth(p.pose(t)!!)
            assertTrue("went back at $t: $m < $previous", m >= previous - 1e-6)
            previous = m
        }
    }

    @Test
    fun `out-of-order and duplicate fixes are dropped and counted`() {
        val p = PlaybackInterpolator()
        driving(p, 3)
        assertFalse(p.offer(fix(2, north(20.0)), serverTsMs = 3_500)) // late arrival of an old fix
        assertFalse(p.offer(fix(3, north(30.0)), serverTsMs = 3_600)) // duplicate
        assertEquals(1, p.stats.backwardJumpsRejected)
        // A new app launch (new epoch) restarting seq at 0 is accepted when its fix is newer.
        assertTrue(p.offer(fix(0, north(40.0), epoch = "e2").copy(gpsTs = 4_000), serverTsMs = 4_000))
    }

    @Test
    fun `extrapolates at most 1 s when the buffer runs dry, then holds`() {
        val p = PlaybackInterpolator()
        driving(p, 4) // last fix 40 m at t = 4 s
        val atLimit = metresNorth(p.pose(4_000 + 1_200 + 1_000)!!) // render t = 5.0 s
        val later = metresNorth(p.pose(4_000 + 1_200 + 5_000)!!)
        assertEquals(50.0, atLimit, 0.5) // 40 m + 10 m/s × 1 s
        assertEquals(atLimit, later, 0.01)
    }

    @Test
    fun `snaps onto the route within 30 m and faces the road`() {
        val p = PlaybackInterpolator()
        p.setRoute(road)
        // Fixes 15 m east of the road: snapped back onto it, bearing north.
        val east = 15.0 / (111_320.0 * Math.cos(Math.toRadians(start.lat)))
        for (n in 0..5) {
            val pt = north(10.0 * n).let { GeoPoint(it.lat, it.lng + east) }
            p.offer(fix(n.toLong(), pt, heading = 45.0), serverTsMs = 1_000L * n)
        }
        val pose = p.pose(5_000)!!
        assertEquals(start.lng, pose.position.lng, 1e-7)
        assertEquals(0.0, pose.bearing, 0.5)
        assertNotNull(pose.alongRouteM)
    }

    @Test
    fun `follows raw points when far from the route`() {
        val p = PlaybackInterpolator()
        p.setRoute(road)
        val east = 80.0 / (111_320.0 * Math.cos(Math.toRadians(start.lat)))
        for (n in 0..5) p.offer(fix(n.toLong(), north(10.0 * n).let { GeoPoint(it.lat, it.lng + east) }), 1_000L * n)
        val pose = p.pose(5_000)!!
        assertEquals(start.lng + east, pose.position.lng, 1e-7)
        assertNull(pose.alongRouteM)
    }

    @Test
    fun `turns by the shortest arc at no more than 90 degrees per second`() {
        val p = PlaybackInterpolator()
        // Heading 350° then a fix pointing at 10°: the turn goes through north, 20° total.
        p.offer(fix(0, start, speed = 0.0, heading = 350.0), 0)
        assertEquals(350.0, p.pose(1_000)!!.bearing, 0.01)
        p.offer(fix(1, start, speed = 0.0, heading = 10.0), 100)
        p.offer(fix(2, start, speed = 0.0, heading = 10.0), 200)
        val after100ms = p.pose(1_100)!!.bearing // at most 9° in 0.1 s
        assertEquals(359.0, after100ms, 0.01)
        val later = p.pose(2_000)!!.bearing
        assertEquals(10.0, later, 0.01)
    }

    @Test
    fun `a jump over 500 m fades instead of sliding`() {
        val p = PlaybackInterpolator()
        p.offer(fix(0, north(0.0)), 0)
        p.offer(fix(1, north(10.0)), 1_000)
        p.offer(fix(2, north(2_000.0)), 2_000) // reconnect after driving out of coverage
        // Render time crosses the midpoint of the 1 s → 2 s pair at server t = 1.5 + D.
        val d = p.delayMs()
        val before = p.pose(1_400 + d)!!
        val atSwitch = p.pose(1_500 + d)!!
        val after = p.pose(1_700 + d)!!
        assertEquals(10.0, metresNorth(before), 0.5) // held, not sliding 2 km
        assertTrue(atSwitch.alpha < 0.1f)
        assertEquals(2_000.0, metresNorth(after), 0.5)
        assertEquals(1f, after.alpha)
    }

    @Test
    fun `stale after 15 s without a fix`() {
        val p = PlaybackInterpolator()
        driving(p, 2)
        assertFalse(p.pose(16_000)!!.isStale)
        val stale = p.pose(17_100)!!
        assertTrue(stale.isStale)
        assertEquals(0.5f, stale.alpha)
    }

    @Test
    fun `delay follows the real fix interval, clamped`() {
        val slow = PlaybackInterpolator()
        for (n in 0..5) slow.offer(fix(n.toLong(), north(n * 30.0)), 3_000L * n)
        assertEquals(2_500L, slow.delayMs()) // 1.2 × 3 s, capped
        val fast = PlaybackInterpolator()
        for (n in 0..5) fast.offer(fix(n.toLong(), north(n * 2.0)), 200L * n)
        assertEquals(800L, fast.delayMs()) // floored
    }

    @Test
    fun `gaps are counted for telemetry`() {
        val p = PlaybackInterpolator()
        p.offer(fix(0, north(0.0)), 0)
        p.offer(fix(1, north(10.0)), 1_000)
        p.offer(fix(2, north(20.0)), 9_000)
        assertEquals(1, p.stats.gaps)
        assertEquals(8_000L, p.stats.longestGapMs)
    }

    @Test
    fun `route trimming keeps only what is ahead`() {
        val geo = RouteGeometry(road)
        val ahead = geo.remainingFrom(275.0)
        assertEquals(275.0, (ahead.first().lat - start.lat) * 111_320.0, 0.5)
        assertEquals(road.last(), ahead.last())
        assertEquals(1_000.0, geo.lengthM, 0.5)
    }

    @Test
    fun `polyline decoding matches Google's example`() {
        val points = RouteGeometry.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(listOf(GeoPoint(38.5, -120.2), GeoPoint(40.7, -120.95), GeoPoint(43.252, -126.453)), points)
    }

    @Test
    fun `ride eta frame is parsed`() {
        val env = Wire.decode(
            """{"v":1,"type":"ride.eta","ride_id":"7","seq":null,"ts":1000,"data":{"eta_v":1,"leg":"stop",
               "target":{"kind":"stop","index":0,"number":1,"total":2},"duration_s":300,"distance_m":2000,
               "arrive_at":301000,"final":{"duration_s":1320,"distance_m":10000,"arrive_at":1321000},
               "route":{"polyline":"_p~iF~ps|U"},"reason":"initial"}}""",
        )!!
        val eta = RtEvent.from(env, isReplay = false, hadCursor = true) as RtEvent.Eta
        assertEquals("stop", eta.leg)
        assertEquals(1, eta.stopNumber)
        assertEquals(300, eta.durationS)
        assertEquals(1_321_000L, eta.finalArriveAtMs)
        assertEquals("_p~iF~ps|U", eta.routePolyline)
    }
}
