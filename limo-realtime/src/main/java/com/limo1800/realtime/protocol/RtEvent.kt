package com.limo1800.realtime.protocol

import kotlinx.serialization.json.JsonObject

/**
 * Everything the realtime client delivers to the app, already deduplicated.
 *
 * Durable events ([seq] non-null) arrive at most once per install: anything at or below the
 * stored cursor is dropped. [isReplay] is true while a subscribe's replay is being delivered;
 * [hadCursor] says whether we held a cursor for the ride before that subscribe — a replay
 * without one is just the ride's history, not news (don't notify on it).
 */
sealed interface RtEvent {
    val rideId: Long?

    sealed interface Durable : RtEvent {
        override val rideId: Long
        val seq: Long
        val isReplay: Boolean
        val hadCursor: Boolean

        /** Worth a banner or notification (live, or genuinely missed while away). */
        val isNews: Boolean get() = !isReplay || hadCursor
    }

    data class Welcome(
        val userId: String?,
        val role: String?,
        val activeRide: ActiveRideRef?,
        val serverTimeMs: Long?,
    ) : RtEvent {
        override val rideId: Long? get() = activeRide?.rideId
    }

    /** The replay for [rideId] is complete; live frames follow. */
    data class Subscribed(override val rideId: Long, val seq: Long, val status: String?) : RtEvent

    /** The gap was too large to replay: reload this ride's state and chat over REST. */
    data class ResyncRequired(override val rideId: Long, val reason: String?) : RtEvent

    /**
     * Driver position. [snapshot] = the last known fix re-sent on subscribe (not a live fix).
     * [serverTsMs] = when limo-rt received it, on the server clock.
     */
    data class Location(
        override val rideId: Long,
        val fix: DriverFix,
        val serverTsMs: Long?,
        val snapshot: Boolean,
    ) : RtEvent

    data class RideStatus(
        override val rideId: Long,
        override val seq: Long,
        val status: String,
        val previous: String?,
        val atMs: Long?,
        val stop: StopInfo?,
        /** `rider`, `driver`, `admin` or `system` when [status] is `cancelled`. */
        val cancelledBy: String?,
        override val isReplay: Boolean,
        override val hadCursor: Boolean,
    ) : Durable {
        val isCancelled: Boolean get() = status == RideStatuses.CANCELLED
    }

    data class DriverChanged(
        override val rideId: Long,
        override val seq: Long,
        val driverId: String?,
        val name: String?,
        override val isReplay: Boolean,
        override val hadCursor: Boolean,
    ) : Durable

    /** e.g. `kind = approaching`, with `threshold` / `meters` / `leg` in [data]. */
    data class Notice(
        override val rideId: Long,
        override val seq: Long,
        val kind: String?,
        val data: JsonObject,
        override val isReplay: Boolean,
        override val hadCursor: Boolean,
    ) : Durable

    data class Chat(
        override val rideId: Long,
        override val seq: Long,
        val message: ChatMessage,
        override val isReplay: Boolean,
        override val hadCursor: Boolean,
    ) : Durable

    data class DriverPresence(override val rideId: Long, val online: Boolean, val lastSeenMs: Long?) : RtEvent

    /** A personal notice (`{id, title, body, kind, priority, data}`), also stored in the inbox. */
    data class Notification(
        override val rideId: Long?,
        val id: String?,
        val title: String,
        val body: String,
        val kind: String,
        val priority: String?,
        val data: JsonObject,
    ) : RtEvent

    data class ServerError(override val rideId: Long?, val code: String, val retry: Boolean) : RtEvent

    /** A type this build doesn't know. Kept so newer servers never break older apps. */
    data class Unknown(override val rideId: Long?, val type: String, val seq: Long?, val data: JsonObject) : RtEvent

    companion object {
        /** Maps a decoded envelope; null for frames the client handles itself (pong, …). */
        fun from(env: Envelope, isReplay: Boolean, hadCursor: Boolean): RtEvent? {
            val d = env.data
            val ride = env.rideId
            val seq = env.seq
            return when (env.type) {
                "pong", "subscribed", "welcome", "error" -> null
                "loc" -> {
                    val fix = DriverFix.fromData(d) ?: return null
                    Location(ride ?: return null, fix, env.ts, d.bool("snapshot") == true)
                }
                "ride.status" -> RideStatus(
                    rideId = ride ?: return null,
                    seq = seq ?: return null,
                    status = d.string("status") ?: return null,
                    previous = d.string("prev"),
                    atMs = d.long("at"),
                    stop = StopInfo.fromData(d.obj("stop")),
                    cancelledBy = d.string("cancelled_by"),
                    isReplay = isReplay,
                    hadCursor = hadCursor,
                )
                // Servers before the cancellation change sent a separate type.
                "ride.cancelled" -> RideStatus(
                    rideId = ride ?: return null,
                    seq = seq ?: return null,
                    status = RideStatuses.CANCELLED,
                    previous = null,
                    atMs = d.long("at"),
                    stop = null,
                    cancelledBy = d.string("by"),
                    isReplay = isReplay,
                    hadCursor = hadCursor,
                )
                "ride.driver_changed" -> DriverChanged(
                    ride ?: return null, seq ?: return null,
                    d.string("driver_id"), d.string("name"), isReplay, hadCursor,
                )
                "ride.notice" -> Notice(ride ?: return null, seq ?: return null, d.string("kind"), d, isReplay, hadCursor)
                "chat.message" -> Chat(
                    ride ?: return null, seq ?: return null,
                    ChatMessage.fromData(d) ?: return null, isReplay, hadCursor,
                )
                "driver.presence" -> DriverPresence(ride ?: return null, d.bool("online") ?: true, d.long("last_seen"))
                "resync.required" -> ResyncRequired(ride ?: return null, d.string("reason"))
                "notification" -> Notification(
                    rideId = ride,
                    id = d.string("id"),
                    title = d.string("title") ?: "1800LIMO",
                    body = d.string("body") ?: "",
                    kind = d.string("kind") ?: "info",
                    priority = d.string("priority"),
                    data = d.obj("data") ?: JsonObject(emptyMap()),
                )
                else -> Unknown(ride, env.type, seq, d)
            }
        }
    }
}
