package com.limo1800.realtime.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EtaPresenterTest {

    @Test
    fun `nothing until the server has spoken`() {
        assertNull(EtaPresenter().display(0))
    }

    @Test
    fun `counts down with the clock between refreshes`() {
        val p = EtaPresenter()
        p.onServerEta(arriveAtMs = 240_000, nowMs = 0)
        assertEquals(4, p.display(0)!!.minutes)
        assertEquals(4, p.display(30_000)!!.minutes) // 3.5 min → shows 4 (ceil)
        assertEquals(3, p.display(60_000)!!.minutes)
        assertEquals(240_000L, p.display(60_000)!!.arriveAtMs)
    }

    @Test
    fun `under a minute is arriving now`() {
        val p = EtaPresenter()
        p.onServerEta(arriveAtMs = 50_000, nowMs = 0)
        val d = p.display(0)!!
        assertTrue(d.arrivingNow)
        assertEquals(1, d.minutes)
    }

    @Test
    fun `small server changes move the display by one minute at a time`() {
        val p = EtaPresenter()
        p.onServerEta(arriveAtMs = 300_000, nowMs = 0) // 5 min
        assertEquals(5, p.display(0)!!.minutes)
        p.onServerEta(arriveAtMs = 420_000, nowMs = 1_000) // now 7 min: within the jump threshold
        assertEquals(5, p.display(1_000)!!.minutes) // stepped too recently (15 s rule)
        assertEquals(6, p.display(16_000)!!.minutes)
        assertEquals(6, p.display(20_000)!!.minutes)
        assertEquals(7, p.display(31_000)!!.minutes)
    }

    @Test
    fun `a big server change jumps once`() {
        val p = EtaPresenter()
        p.onServerEta(arriveAtMs = 300_000, nowMs = 0) // 5 min
        p.display(0)
        p.onServerEta(arriveAtMs = 900_000, nowMs = 1_000) // 15 min: traffic jam
        assertEquals(15, p.display(1_000)!!.minutes)
    }

    @Test
    fun `a car stuck at a light doesn't count down as if moving`() {
        val p = EtaPresenter()
        p.onServerEta(arriveAtMs = 600_000, nowMs = 0) // 10 min
        p.display(0)
        // 2 minutes later the clock says 8 min, but 3 km remain at a recent 3 m/s (≈ 16.7 min).
        p.onProgress(remainingDistanceM = 3_000.0, speedMps = 3.0)
        val shown = (0..10).map { p.display(120_000L + it * 16_000L)!!.minutes }.last()
        assertTrue("blended estimate should stay above the bare countdown: $shown", shown >= 9)
        assertFalse(p.display(300_000)!!.arrivingNow)
    }
}
