package com.limo1800.realtime.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * limo-rt wire format v1. Every frame, both ways, is one JSON envelope:
 *
 * `{"v":1,"type":"…","id":"…","ts":<server ms>,"ride_id":"2327"|null,"seq":<int>|null,"data":{…}}`
 *
 * Durable ride events carry a per-ride `seq` and can be replayed; location frames have none.
 * Unknown types and fields are ignored, never errors.
 */
data class Envelope(
    val type: String,
    val rideId: Long?,
    val seq: Long?,
    val ts: Long?,
    val data: JsonObject,
)

object Wire {
    const val VERSION = 1

    private val json = Json { ignoreUnknownKeys = true }

    /** Null for anything that isn't a v1 envelope; the caller drops it. */
    fun decode(text: String): Envelope? {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val type = root.string("type") ?: return null
        val version = root.long("v") ?: VERSION.toLong()
        if (version != VERSION.toLong()) return null
        return Envelope(
            type = type,
            rideId = root.long("ride_id"),
            seq = root.long("seq"),
            ts = root.long("ts"),
            data = root["data"] as? JsonObject ?: JsonObject(emptyMap()),
        )
    }

    fun encode(
        type: String,
        rideId: Long? = null,
        seq: Long? = null,
        data: JsonObject? = null,
    ): String = buildJsonObject {
        put("v", JsonPrimitive(VERSION))
        put("type", JsonPrimitive(type))
        // limo-rt accepts numeric strings; strings keep 64-bit ids exact on every client.
        if (rideId != null) put("ride_id", JsonPrimitive(rideId.toString()))
        if (seq != null) put("seq", JsonPrimitive(seq))
        if (data != null) put("data", data)
    }.toString()

    fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(
        pairs.filter { it.second != null }.associate { (k, v) -> k to toElement(v) },
    )

    private fun toElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to toElement(v) })
        else -> JsonPrimitive(value.toString())
    }
}

// --- Tolerant field readers: limo-rt sends ids as strings, numbers may arrive either way. ---

internal fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

internal fun JsonObject.long(key: String): Long? {
    val p = this[key] as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return p.longOrNull ?: p.content.trim().toLongOrNull() ?: p.doubleOrNull?.toLong()
}

internal fun JsonObject.double(key: String): Double? {
    val p = this[key] as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return p.doubleOrNull ?: p.content.trim().toDoubleOrNull()
}

internal fun JsonObject.bool(key: String): Boolean? {
    val p = this[key] as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return p.booleanOrNull
}

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
