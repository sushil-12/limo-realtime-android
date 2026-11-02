package com.limo1800.realtime.playback

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sqrt

data class GeoPoint(val lat: Double, val lng: Double)

/**
 * A polyline measured in metres along its length, for snapping a position onto the route,
 * walking along it, and reading the road's direction at any point.
 *
 * Uses a local flat projection around the first point: exact enough over a city leg, and
 * cheap enough to run every frame.
 */
class RouteGeometry(points: List<GeoPoint>) {
    val points: List<GeoPoint> = points.filterIndexed { i, p -> i == 0 || p != points[i - 1] }
    private val originLat = this.points.firstOrNull()?.lat ?: 0.0
    private val originLng = this.points.firstOrNull()?.lng ?: 0.0
    private val mPerDegLat = 111_320.0
    private val mPerDegLng = 111_320.0 * cos(Math.toRadians(originLat))
    private val xs = DoubleArray(this.points.size) { (this.points[it].lng - originLng) * mPerDegLng }
    private val ys = DoubleArray(this.points.size) { (this.points[it].lat - originLat) * mPerDegLat }
    /** Distance along the route at each vertex. */
    private val cumulative = DoubleArray(this.points.size).also { c ->
        for (i in 1 until c.size) c[i] = c[i - 1] + hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
    }

    val lengthM: Double get() = cumulative.lastOrNull() ?: 0.0
    val isUsable: Boolean get() = this.points.size >= 2 && lengthM > 1.0

    data class Projection(val alongM: Double, val offsetM: Double)

    /** Nearest point on the route: distance along it and distance from it. */
    fun project(p: GeoPoint): Projection? {
        if (!isUsable) return null
        val px = (p.lng - originLng) * mPerDegLng
        val py = (p.lat - originLat) * mPerDegLat
        var best = Double.MAX_VALUE
        var bestAlong = 0.0
        for (i in 0 until xs.size - 1) {
            val dx = xs[i + 1] - xs[i]
            val dy = ys[i + 1] - ys[i]
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (((px - xs[i]) * dx + (py - ys[i]) * dy) / len2).coerceIn(0.0, 1.0)
            val cx = xs[i] + t * dx
            val cy = ys[i] + t * dy
            val d = hypot(px - cx, py - cy)
            if (d < best) {
                best = d
                bestAlong = cumulative[i] + t * sqrt(len2)
            }
        }
        return Projection(bestAlong, best)
    }

    /** The point [alongM] metres along the route, and the road's bearing there (0° = north). */
    fun pointAt(alongM: Double): Pair<GeoPoint, Double> {
        val d = alongM.coerceIn(0.0, lengthM)
        var i = 0
        while (i < cumulative.size - 2 && cumulative[i + 1] < d) i++
        val segLen = cumulative[i + 1] - cumulative[i]
        val t = if (segLen == 0.0) 0.0 else (d - cumulative[i]) / segLen
        val x = xs[i] + t * (xs[i + 1] - xs[i])
        val y = ys[i] + t * (ys[i + 1] - ys[i])
        val bearing = bearingOf(xs[i + 1] - xs[i], ys[i + 1] - ys[i])
        return GeoPoint(originLat + y / mPerDegLat, originLng + x / mPerDegLng) to bearing
    }

    /** The part of the route still ahead of [alongM] (for trimming the travelled part). */
    fun remainingFrom(alongM: Double): List<GeoPoint> {
        if (!isUsable) return points
        val (start, _) = pointAt(alongM)
        val rest = points.indices.filter { cumulative[it] > alongM }.map { points[it] }
        return listOf(start) + rest
    }

    companion object {
        fun bearingOf(dx: Double, dy: Double): Double {
            val deg = Math.toDegrees(atan2(dx, dy))
            return (deg + 360.0) % 360.0
        }

        fun distanceM(a: GeoPoint, b: GeoPoint): Double {
            val mPerDegLng = 111_320.0 * cos(Math.toRadians((a.lat + b.lat) / 2))
            return hypot((b.lng - a.lng) * mPerDegLng, (b.lat - a.lat) * 111_320.0)
        }

        /** Google encoded polyline → points (ride.eta's route). */
        fun decode(encoded: String): List<GeoPoint> {
            val out = ArrayList<GeoPoint>()
            var index = 0
            var lat = 0
            var lng = 0
            while (index < encoded.length) {
                for (axis in 0..1) {
                    var shift = 0
                    var result = 0
                    while (true) {
                        val b = encoded[index++].code - 63
                        result = result or ((b and 0x1F) shl shift)
                        shift += 5
                        if (b < 0x20) break
                    }
                    val delta = if (result and 1 != 0) (result shr 1).inv() else result shr 1
                    if (axis == 0) lat += delta else lng += delta
                }
                out += GeoPoint(lat / 1e5, lng / 1e5)
            }
            return out
        }
    }
}
