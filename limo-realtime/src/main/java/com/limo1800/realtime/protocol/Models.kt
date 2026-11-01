package com.limo1800.realtime.protocol

import kotlinx.serialization.json.JsonObject

/** Booking statuses as limo-rt and Laravel spell them. */
object RideStatuses {
    const val PENDING = "pending"
    const val ASSIGNED = "assigned"
    const val EN_ROUTE_PU = "en_route_pu"
    const val ON_LOCATION = "on_location"
    const val START_RIDE = "start_ride"
    const val PASSENGER_DROP = "passenger_drop"
    const val PASSENGER_PICK = "passenger_pick"
    const val EN_ROUTE_DO = "en_route_do"
    const val ENDED = "ended"
    const val FINALIZED = "finalized"
    const val CANCELLED = "cancelled"
    const val REJECTED = "rejected"

    val TERMINAL = setOf(ENDED, FINALIZED, CANCELLED, REJECTED)

    /** The passenger is aboard (or being served at a stop): no more cancelling. */
    val STARTED = setOf(START_RIDE, PASSENGER_DROP, PASSENGER_PICK, EN_ROUTE_DO)

    /** Cancel is offered only before pickup, and the server enforces the same rule. */
    fun isCancellable(status: String?): Boolean =
        status != null && status !in STARTED && status !in TERMINAL
}

/**
 * One driver GPS fix exactly as the contract carries it (`loc.update` out, `loc` in).
 *
 * @property epoch new UUID per app launch; with [seq] it orders fixes across relaunches.
 * @property src `raw` (the device fix) or `nav_snapped` (Navigation SDK road-snapped).
 */
data class DriverFix(
    val lat: Double,
    val lng: Double,
    val accM: Double?,
    val speedMps: Double?,
    val heading: Double?,
    val gpsTs: Long,
    val epoch: String,
    val seq: Long,
    val src: String = SRC_RAW,
) {
    fun toData(): JsonObject = Wire.obj(
        "lat" to lat,
        "lng" to lng,
        "acc_m" to accM?.coerceIn(0.0, 10_000.0),
        // The contract uses -1 for "unknown" speed and heading.
        "speed_mps" to (speedMps?.let { if (it < 0) -1.0 else it.coerceAtMost(120.0) }),
        "heading" to (heading?.let { if (it < 0) -1.0 else it.coerceAtMost(360.0) }),
        "gps_ts" to gpsTs,
        "epoch" to epoch,
        "seq" to seq,
        "src" to src,
    )

    /** Orders two fixes from the same driver: same epoch by seq, else by GPS time. */
    fun isNewerThan(other: DriverFix): Boolean =
        if (epoch == other.epoch) seq > other.seq else gpsTs > other.gpsTs

    companion object {
        const val SRC_RAW = "raw"
        const val SRC_NAV_SNAPPED = "nav_snapped"

        fun fromData(data: JsonObject): DriverFix? {
            val lat = data.double("lat") ?: return null
            val lng = data.double("lng") ?: return null
            if (lat == 0.0 && lng == 0.0) return null
            return DriverFix(
                lat = lat,
                lng = lng,
                accM = data.double("acc_m"),
                speedMps = data.double("speed_mps")?.takeIf { it >= 0 },
                heading = data.double("heading")?.takeIf { it >= 0 },
                gpsTs = data.long("gps_ts") ?: return null,
                epoch = data.string("epoch") ?: "",
                seq = data.long("seq") ?: 0,
                src = data.string("src") ?: SRC_RAW,
            )
        }
    }
}

data class StopInfo(
    val index: Int,
    val number: Int?,
    val total: Int?,
    val address: String?,
    val lat: Double?,
    val lng: Double?,
) {
    companion object {
        fun fromData(data: JsonObject?): StopInfo? {
            data ?: return null
            return StopInfo(
                index = data.long("index")?.toInt() ?: return null,
                number = data.long("number")?.toInt(),
                total = data.long("total")?.toInt(),
                address = data.string("address"),
                lat = data.double("lat"),
                lng = data.double("lng"),
            )
        }
    }
}

/** limo-rt's chat row (`chat.message` frames and `GET …/messages`). */
data class ChatMessage(
    val id: Long,
    val senderRole: String,
    val senderId: String,
    val receiverId: String?,
    val body: String,
    val clientMsgId: String?,
    val atMs: Long,
    val readAtMs: Long?,
) {
    companion object {
        fun fromData(data: JsonObject): ChatMessage? {
            return ChatMessage(
                id = data.long("id") ?: return null,
                senderRole = data.string("from") ?: "rider",
                senderId = data.string("sender_id") ?: return null,
                receiverId = data.string("receiver_id"),
                body = data.string("body") ?: return null,
                clientMsgId = data.string("client_msg_id"),
                atMs = data.long("at") ?: 0,
                readAtMs = data.long("read_at"),
            )
        }
    }
}

data class ActiveRideRef(val rideId: Long, val status: String)
