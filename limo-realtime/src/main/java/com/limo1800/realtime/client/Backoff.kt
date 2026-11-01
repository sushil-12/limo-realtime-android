package com.limo1800.realtime.client

import kotlin.random.Random

/**
 * Reconnect delays: full jitter over 0.5 → 1 → 2 → 4 → 8 → … s, capped at
 * [rideCapMs] while the user has a live ride (a stale map costs more than a few extra
 * connection attempts) and at [idleCapMs] otherwise. Never below [floorMs], so a burst of
 * failures can't spin.
 */
class Backoff(
    private val baseMs: Long = 500,
    private val rideCapMs: Long = 15_000,
    private val idleCapMs: Long = 30_000,
    private val floorMs: Long = 100,
    private val random: Random = Random.Default,
) {
    /** [attempt] is 1 for the first retry. */
    fun delayMs(attempt: Int, duringRide: Boolean): Long {
        val cap = if (duringRide) rideCapMs else idleCapMs
        val shift = (attempt - 1).coerceIn(0, 20)
        val ceiling = (baseMs shl shift).coerceAtMost(cap)
        return random.nextLong(floorMs, ceiling.coerceAtLeast(floorMs) + 1)
    }

    /** Upper bound for [attempt], for tests and logs. */
    fun ceilingMs(attempt: Int, duringRide: Boolean): Long =
        (baseMs shl (attempt - 1).coerceIn(0, 20)).coerceAtMost(if (duringRide) rideCapMs else idleCapMs)
}
