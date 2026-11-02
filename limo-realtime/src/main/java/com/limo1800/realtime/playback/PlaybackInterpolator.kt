package com.limo1800.realtime.playback

import com.limo1800.realtime.protocol.DriverFix
import kotlin.math.abs
import kotlin.math.min

/** What the map draws this frame. */
data class CarPose(
    val position: GeoPoint,
    /** Degrees, 0 = north, clockwise. */
    val bearing: Double,
    /** 1 = normal; dimmed when stale; dips to 0 across a teleport. */
    val alpha: Float,
    /** No fix for [PlaybackConfig.staleAfterMs]: show "Updating location…". */
    val isStale: Boolean,
    /** Distance along the route (for trimming the travelled polyline), when snapped. */
    val alongRouteM: Double?,
)

data class PlaybackConfig(
    /** Render this long behind the newest fix: 1.2 × the median fix interval, clamped. */
    val delayFactor: Double = 1.2,
    val minDelayMs: Long = 800,
    val maxDelayMs: Long = 2_500,
    val defaultDelayMs: Long = 1_200,
    /** If the buffer runs dry, keep moving for at most this long, then hold. */
    val maxExtrapolationMs: Long = 1_000,
    /** Snap onto the route when the fix is this close to it. */
    val snapDistanceM: Double = 30.0,
    /** Faster than this the car turns no more (shortest arc). */
    val maxTurnDegPerSec: Double = 90.0,
    /** A jump longer than this between fixes is a teleport: fade, don't slide. */
    val teleportDistanceM: Double = 500.0,
    val teleportFadeMs: Long = 300,
    val staleAfterMs: Long = 15_000,
    val staleAlpha: Float = 0.5f,
    val maxSamples: Int = 32,
)

/** Measured per ride, for the telemetry the spec asks for. */
data class PlaybackStats(
    val fixes: Int,
    /** Fixes that arrived out of order (older than one already played) and were dropped. */
    val backwardJumpsRejected: Int,
    /** Gaps between consecutive fixes longer than 5 s. */
    val gaps: Int,
    val longestGapMs: Long,
)

/**
 * Buffered playback of the driver's position (the passenger's live map).
 *
 * Fixes are placed on the SERVER clock (limo-rt stamps `loc` frames with its own `ts`, so the
 * two phones' clocks never matter). Each frame the car is drawn at `serverNow − D`, between the
 * two real fixes around that moment — never ahead of the data, so it never has to go back.
 * Along a known route both fixes are snapped (when within 30 m) and the car moves along the
 * road between them, facing the road. If fixes stop, it extrapolates at most 1 s, then holds.
 *
 * Pure Kotlin: no Android, no clocks of its own. The map calls [pose] from its frame loop;
 * the same rules are implemented in Swift for iOS.
 */
class PlaybackInterpolator(private val config: PlaybackConfig = PlaybackConfig()) {

    private data class Sample(val tMs: Long, val point: GeoPoint, val heading: Double?, val speedMps: Double?, val fix: DriverFix)

    private val samples = ArrayList<Sample>()
    private var route: RouteGeometry? = null
    private var lastBearing: Double? = null
    private var lastPoseTimeMs: Long? = null
    private var lastPlayed: DriverFix? = null

    private var fixes = 0
    private var backward = 0
    private var gaps = 0
    private var longestGap = 0L

    val stats: PlaybackStats get() = PlaybackStats(fixes, backward, gaps, longestGap)

    /** The leg's route (from ride.eta). Empty or null = follow raw points. */
    fun setRoute(points: List<GeoPoint>?) {
        route = points?.let(::RouteGeometry)?.takeIf { it.isUsable }
    }

    /**
     * One fix. [serverTsMs] = when limo-rt received it (the envelope's `ts`), or for a
     * location from another source the best server-clock estimate available.
     * Returns false when it was dropped as older than what's already buffered.
     */
    fun offer(fix: DriverFix, serverTsMs: Long): Boolean {
        val newest = samples.lastOrNull()
        if (newest != null && !fix.isNewerThan(newest.fix)) {
            if (fix.epoch == newest.fix.epoch && fix.seq == newest.fix.seq) return false // duplicate
            backward++
            return false
        }
        val point = GeoPoint(fix.lat, fix.lng)
        // Keep server timestamps strictly increasing even if two fixes share a millisecond.
        val t = if (newest != null && serverTsMs <= newest.tMs) newest.tMs + 1 else serverTsMs
        if (newest != null) {
            val gap = t - newest.tMs
            if (gap > 5_000) gaps++
            if (gap > longestGap) longestGap = gap
        }
        samples += Sample(t, point, fix.heading, fix.speedMps, fix)
        while (samples.size > config.maxSamples) samples.removeAt(0)
        fixes++
        return true
    }

    /** The playback delay right now: 1.2 × median interval of recent fixes, clamped. */
    fun delayMs(): Long {
        if (samples.size < 3) return config.defaultDelayMs
        val intervals = samples.zipWithNext { a, b -> b.tMs - a.tMs }.takeLast(10).sorted()
        val median = intervals[intervals.size / 2]
        return (median * config.delayFactor).toLong().coerceIn(config.minDelayMs, config.maxDelayMs)
    }

    fun reset() {
        samples.clear()
        lastBearing = null
        lastPoseTimeMs = null
        lastPlayed = null
    }

    /** Where to draw the car at [serverNowMs]. Null until the first fix. */
    fun pose(serverNowMs: Long): CarPose? {
        if (samples.isEmpty()) return null
        val renderT = serverNowMs - delayMs()
        val newest = samples.last()
        val stale = serverNowMs - newest.tMs > config.staleAfterMs

        var alpha = if (stale) config.staleAlpha else 1f
        val (point, targetBearing, along) = when {
            samples.size == 1 || renderT <= samples.first().tMs -> placeAt(samples.first())
            renderT >= newest.tMs -> extrapolate(newest, renderT - newest.tMs)
            else -> {
                val i = samples.indexOfLast { it.tMs <= renderT }
                val a = samples[i]
                val b = samples[i + 1]
                val f = (renderT - a.tMs).toDouble() / (b.tMs - a.tMs).toDouble()
                if (RouteGeometry.distanceM(a.point, b.point) > config.teleportDistanceM) {
                    // Teleport: hold A, switch to B at the midpoint, fading through zero.
                    val switchT = a.tMs + (b.tMs - a.tMs) / 2
                    val half = config.teleportFadeMs / 2
                    val d = abs(renderT - switchT)
                    if (d < half) alpha = min(alpha, d.toFloat() / half)
                    if (renderT < switchT) placeAt(a) else placeAt(b)
                } else {
                    between(a, b, f)
                }
            }
        }

        val bearing = turnTowards(targetBearing, serverNowMs)
        return CarPose(point, bearing, alpha, stale, along)
    }

    private data class Placement(val point: GeoPoint, val bearing: Double?, val along: Double?)

    private fun snapped(p: GeoPoint): RouteGeometry.Projection? =
        route?.project(p)?.takeIf { it.offsetM <= config.snapDistanceM }

    private fun placeAt(s: Sample): Placement {
        val proj = snapped(s.point)
        if (proj != null) {
            val (pt, roadBearing) = route!!.pointAt(proj.alongM)
            return Placement(pt, roadBearing, proj.alongM)
        }
        return Placement(s.point, s.heading, null)
    }

    private fun between(a: Sample, b: Sample, f: Double): Placement {
        val pa = snapped(a.point)
        val pb = snapped(b.point)
        // Along the road when both ends are on it and the car isn't going backwards on it.
        if (pa != null && pb != null && pb.alongM >= pa.alongM - 2.0) {
            val along = pa.alongM + f * (pb.alongM - pa.alongM)
            val (pt, roadBearing) = route!!.pointAt(along)
            return Placement(pt, roadBearing, along)
        }
        val lat = a.point.lat + f * (b.point.lat - a.point.lat)
        val lng = a.point.lng + f * (b.point.lng - a.point.lng)
        val moving = RouteGeometry.distanceM(a.point, b.point) > 2.0
        val bearing = if (moving) bearingBetween(a.point, b.point) else (b.heading ?: a.heading)
        return Placement(GeoPoint(lat, lng), bearing, null)
    }

    private fun extrapolate(s: Sample, overMs: Long): Placement {
        val dt = min(overMs, config.maxExtrapolationMs) / 1000.0
        val speed = s.speedMps ?: 0.0
        val proj = snapped(s.point)
        if (proj != null && speed > 0.5) {
            val along = proj.alongM + speed * dt
            val (pt, roadBearing) = route!!.pointAt(along)
            return Placement(pt, roadBearing, along)
        }
        if (s.heading != null && speed > 0.5) {
            val dist = speed * dt
            val rad = Math.toRadians(s.heading)
            val dLat = dist * Math.cos(rad) / 111_320.0
            val dLng = dist * Math.sin(rad) / (111_320.0 * Math.cos(Math.toRadians(s.point.lat)))
            return Placement(GeoPoint(s.point.lat + dLat, s.point.lng + dLng), s.heading, null)
        }
        return placeAt(s)
    }

    /** Shortest-arc turn towards [target], at most maxTurnDegPerSec since the last frame. */
    private fun turnTowards(target: Double?, nowMs: Long): Double {
        val previous = lastBearing
        val prevT = lastPoseTimeMs
        lastPoseTimeMs = nowMs
        if (target == null) return previous ?: 0.0
        if (previous == null || prevT == null) {
            lastBearing = normalize(target)
            return lastBearing!!
        }
        val maxStep = config.maxTurnDegPerSec * ((nowMs - prevT).coerceAtLeast(0) / 1000.0)
        val delta = shortestArc(previous, target)
        val step = delta.coerceIn(-maxStep, maxStep)
        lastBearing = normalize(previous + step)
        return lastBearing!!
    }

    companion object {
        fun normalize(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0

        /** Signed shortest rotation from [from] to [to], in (-180, 180]. */
        fun shortestArc(from: Double, to: Double): Double {
            var d = normalize(to) - normalize(from)
            if (d > 180) d -= 360
            if (d <= -180) d += 360
            return d
        }

        fun bearingBetween(a: GeoPoint, b: GeoPoint): Double {
            val dx = (b.lng - a.lng) * 111_320.0 * Math.cos(Math.toRadians((a.lat + b.lat) / 2))
            val dy = (b.lat - a.lat) * 111_320.0
            return RouteGeometry.bearingOf(dx, dy)
        }
    }
}
