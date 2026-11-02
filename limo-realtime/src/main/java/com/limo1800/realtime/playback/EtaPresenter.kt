package com.limo1800.realtime.playback

import kotlin.math.abs
import kotlin.math.ceil

/** What the passenger sees: big "4 min" and "Arrives 6:42 PM" (the app formats [arriveAtMs]). */
data class EtaDisplay(
    /** Whole minutes, never 0 (below a minute it's [arrivingNow]). */
    val minutes: Int,
    val arriveAtMs: Long,
    val arrivingNow: Boolean,
)

/**
 * Turns server ETAs (`ride.eta`, refreshed every 30–60 s) into a steady countdown.
 *
 * - The server's traffic-aware `arrive_at` is the source of truth.
 * - Between refreshes the remaining time counts down with the clock, blended with
 *   "remaining route distance ÷ recent speed" so a car stuck at a light doesn't count down as if
 *   it were moving.
 * - The displayed minutes never move by more than 1 at a time, unless a new server value differs
 *   from what's shown by [jumpThresholdMin] or more (then it jumps once, to the server's value).
 * - Under a minute: "Arriving now".
 */
class EtaPresenter(
    private val jumpThresholdMin: Int = 3,
    private val distanceBlend: Double = 0.3,
    /** At most one 1-minute step this often (display() runs every frame). */
    private val stepIntervalMs: Long = 15_000,
) {
    private var lastStepAtMs = Long.MIN_VALUE / 2
    private var serverArriveAtMs: Long? = null
    private var shownMinutes: Int? = null
    private var allowJump = false
    private var remainingM: Double? = null
    private var recentSpeedMps: Double? = null

    fun onServerEta(arriveAtMs: Long, nowMs: Long) {
        val previous = shownMinutes
        serverArriveAtMs = arriveAtMs
        val serverMinutes = minutesFor(((arriveAtMs - nowMs) / 1000.0).coerceAtLeast(0.0))
        allowJump = previous == null || abs(serverMinutes - previous) >= jumpThresholdMin
    }

    /** Remaining route distance and a smoothed recent speed, from the map's playback. */
    fun onProgress(remainingDistanceM: Double?, speedMps: Double?) {
        remainingM = remainingDistanceM
        if (speedMps != null && speedMps >= 0) {
            recentSpeedMps = recentSpeedMps?.let { it * 0.8 + speedMps * 0.2 } ?: speedMps
        }
    }

    fun clear() {
        serverArriveAtMs = null
        shownMinutes = null
        remainingM = null
        recentSpeedMps = null
    }

    fun display(nowMs: Long): EtaDisplay? {
        val arriveAt = serverArriveAtMs ?: return null
        var seconds = ((arriveAt - nowMs) / 1000.0).coerceAtLeast(0.0)
        val remaining = remainingM
        val speed = recentSpeedMps
        if (remaining != null && speed != null && speed > 2.0) {
            seconds = (1 - distanceBlend) * seconds + distanceBlend * (remaining / speed)
        }
        if (seconds < 60) {
            shownMinutes = 1
            return EtaDisplay(1, nowMs + (seconds * 1000).toLong(), arrivingNow = true)
        }
        val target = minutesFor(seconds)
        val shown = shownMinutes
        val canStep = nowMs - lastStepAtMs >= stepIntervalMs
        val next = when {
            shown == null || allowJump -> target
            !canStep -> shown
            target > shown -> shown + 1
            target < shown -> shown - 1
            else -> shown
        }
        if (next != shown) lastStepAtMs = nowMs
        allowJump = false
        shownMinutes = next
        // The clock time is the estimate itself; only the minute count is rate-limited.
        return EtaDisplay(next, nowMs + (seconds * 1000).toLong(), arrivingNow = false)
    }

    private fun minutesFor(seconds: Double): Int = ceil(seconds / 60.0).toInt().coerceAtLeast(1)
}
