package com.limo1800.realtime.client

import android.content.Context
import android.content.SharedPreferences

/**
 * Highest durable `seq` handled per ride, kept across launches so a reconnect (or a cold
 * start after process death) replays exactly the gap. Sent as `last_seq` on subscribe.
 */
interface CursorStore {
    fun get(rideId: Long): Long?
    fun put(rideId: Long, seq: Long)
    fun clear()
}

class InMemoryCursorStore : CursorStore {
    private val map = mutableMapOf<Long, Long>()
    @Synchronized override fun get(rideId: Long): Long? = map[rideId]
    @Synchronized override fun put(rideId: Long, seq: Long) { map[rideId] = seq }
    @Synchronized override fun clear() = map.clear()
}

/** At most [maxRides] cursors; ride ids grow, so the lowest ids are the oldest rides. */
class SharedPrefsCursorStore(
    context: Context,
    private val maxRides: Int = 50,
) : CursorStore {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("limo_rt_cursors_v1", Context.MODE_PRIVATE)

    @Synchronized
    override fun get(rideId: Long): Long? =
        prefs.getLong(rideId.toString(), -1).takeIf { it >= 0 }

    @Synchronized
    override fun put(rideId: Long, seq: Long) {
        val editor = prefs.edit().putLong(rideId.toString(), seq)
        val ids = prefs.all.keys.mapNotNull { it.toLongOrNull() }.toMutableSet().apply { add(rideId) }
        if (ids.size > maxRides) {
            ids.sorted().take(ids.size - maxRides).forEach { editor.remove(it.toString()) }
        }
        editor.apply()
    }

    @Synchronized
    override fun clear() {
        prefs.edit().clear().apply()
    }
}
