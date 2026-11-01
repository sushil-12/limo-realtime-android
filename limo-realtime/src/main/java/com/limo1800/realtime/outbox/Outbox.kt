package com.limo1800.realtime.outbox

import com.limo1800.realtime.api.HttpOutcome
import com.limo1800.realtime.api.RtApi
import com.limo1800.realtime.protocol.ChatMessage
import com.limo1800.realtime.protocol.Wire
import com.limo1800.realtime.protocol.obj
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import java.util.UUID

enum class OutboxKind { STATUS, CHAT }

enum class OutboxState {
    /** Waiting to be sent, or being retried. */
    PENDING,
    /** Gave up: the server refused it for good (shown as "failed" for chat; retry allowed). */
    FAILED,
}

data class OutboxEntry(
    val id: Long,
    val rideId: Long,
    val kind: OutboxKind,
    /** STATUS: the request body. CHAT: `{"body": "…"}`. */
    val payload: JsonObject,
    /** STATUS: the Idempotency-Key. CHAT: the client_msg_id. Unique. */
    val key: String,
    val createdAtMs: Long,
    val attempts: Int,
    val state: OutboxState,
    val lastError: String?,
) {
    /** For STATUS entries, the status being sent. */
    val status: String? get() = (payload["status"] as? kotlinx.serialization.json.JsonPrimitive)?.content
    /** For CHAT entries, the text. */
    val chatBody: String? get() = (payload["body"] as? kotlinx.serialization.json.JsonPrimitive)?.content
}

/** Durable storage behind the outbox. Implementations must keep insertion order by [OutboxEntry.id]. */
interface OutboxStore {
    /** Returns the entry's id; an existing [key] is not inserted twice. */
    fun insert(rideId: Long, kind: OutboxKind, payload: JsonObject, key: String, createdAtMs: Long): Long
    fun all(): List<OutboxEntry>
    fun delete(id: Long)
    fun update(id: Long, attempts: Int, state: OutboxState, lastError: String?)
    fun deleteRide(rideId: Long, kind: OutboxKind? = null)
    fun clear()
}

/** What happened when a status was handed to the outbox. */
sealed interface SendResult {
    data object Sent : SendResult
    /** Stored; it will be retried (offline, server busy, token being refreshed). */
    data object Queued : SendResult
    /** The server refused it for good (e.g. 409/422): don't retry, show [message]. */
    data class Rejected(val code: Int, val message: String?) : SendResult
}

/**
 * Status changes and chat messages, written to disk before they are sent and retried until
 * the server takes them.
 *
 * - **Order:** each ride's queue is sent strictly in order; a retriable failure stops that
 *   ride's queue so `start_ride` can never overtake `on_location`. Rides are independent.
 * - **Idempotency:** every retry reuses the original key (status `Idempotency-Key`, chat
 *   `client_msg_id`), so a request that landed but whose answer was lost is never applied twice.
 * - **Auth:** 401/440 stops the whole drain and keeps everything; call [drain] again after the
 *   token is refreshed or the user logs in again. Unlike iOS today, nothing is dropped.
 * - **Permanent errors** (other 4xx): a status is dropped (the server said no, e.g. a stale
 *   transition); a chat message is kept as FAILED so the UI can offer "tap to retry".
 *
 * [drain] is safe to call from anywhere and is serialised by a mutex; the app calls it after
 * every enqueue, on socket `welcome`, on connectivity, and from [OutboxWorker].
 */
class OutboxProcessor(
    private val store: OutboxStore,
    private val api: RtApi,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val maxChatAttempts: Int = 20,
    private val log: (String) -> Unit = {},
) {
    private val mutex = Mutex()
    private val _entries = MutableStateFlow(store.all())
    /** Everything not yet delivered, for "sending…/failed" UI. */
    val entries: StateFlow<List<OutboxEntry>> = _entries.asStateFlow()

    private val _delivered = MutableStateFlow<ChatMessage?>(null)
    /** The server's copy of the last chat message delivered from the outbox. */
    val lastDeliveredChat: StateFlow<ChatMessage?> = _delivered.asStateFlow()

    data class DrainResult(val sent: Int, val pending: Int, val authFailed: Boolean)

    // --- enqueue ---

    /** Stores a status change and tries to send it at once. */
    suspend fun sendStatus(request: StatusRequest): SendResult {
        val id = store.insert(request.rideId, OutboxKind.STATUS, request.toBody(), request.idempotencyKey, nowMs())
        refresh()
        drain()
        val still = store.all().firstOrNull { it.id == id }
        return when {
            still == null -> rejectedOrSent(id)
            else -> SendResult.Queued
        }
    }

    /**
     * Stores a chat message (shown at once as "sending") and tries to send it. Returns the
     * client_msg_id that ties the optimistic bubble to the server's echo.
     */
    suspend fun sendChat(rideId: Long, body: String, clientMsgId: String = UUID.randomUUID().toString()): String {
        store.insert(rideId, OutboxKind.CHAT, Wire.obj("body" to body.trim()), clientMsgId, nowMs())
        refresh()
        drain()
        return clientMsgId
    }

    /** "Tap to retry" on a failed chat bubble. */
    suspend fun retry(id: Long) {
        store.all().firstOrNull { it.id == id }?.let { store.update(it.id, 0, OutboxState.PENDING, null) }
        refresh()
        drain()
    }

    /** The ride is over (ended/cancelled): queued status changes for it are meaningless now. */
    fun dropRide(rideId: Long, kind: OutboxKind? = OutboxKind.STATUS) {
        store.deleteRide(rideId, kind)
        refresh()
    }

    /** Logout: everything queued belongs to the previous user. */
    fun clear() {
        store.clear()
        refresh()
    }

    // --- drain ---

    suspend fun drain(): DrainResult = mutex.withLock {
        var sent = 0
        var authFailed = false
        val blockedRides = mutableSetOf<Long>()
        for (entry in store.all()) {
            if (entry.state != OutboxState.PENDING || entry.rideId in blockedRides) continue
            val outcome = deliver(entry)
            when {
                outcome is HttpOutcome.Ok -> {
                    store.delete(entry.id)
                    sent++
                }
                outcome.isAuthFailure -> {
                    store.update(entry.id, entry.attempts + 1, OutboxState.PENDING, "auth")
                    authFailed = true
                    break
                }
                outcome.isRetriable -> {
                    store.update(entry.id, entry.attempts + 1, OutboxState.PENDING, outcome.describe())
                    blockedRides += entry.rideId
                }
                else -> {
                    if (entry.kind == OutboxKind.STATUS) {
                        rejections[entry.id] = outcome
                        log("status ${entry.status} for ride ${entry.rideId} refused: ${outcome.describe()}")
                        store.delete(entry.id)
                    } else {
                        store.update(entry.id, entry.attempts + 1, OutboxState.FAILED, outcome.describe())
                    }
                }
            }
            // A chat message that keeps failing for transient reasons is eventually shown as failed.
            if (entry.kind == OutboxKind.CHAT && outcome.isRetriable && entry.attempts + 1 >= maxChatAttempts) {
                store.update(entry.id, entry.attempts + 1, OutboxState.FAILED, outcome.describe())
            }
        }
        refresh()
        DrainResult(sent, store.all().count { it.state == OutboxState.PENDING }, authFailed)
    }

    /** Permanent refusals of status entries, read once by [sendStatus]. */
    private val rejections = mutableMapOf<Long, HttpOutcome>()

    private fun rejectedOrSent(id: Long): SendResult = when (val o = rejections.remove(id)) {
        is HttpOutcome.Error -> SendResult.Rejected(o.code, o.json?.let { (it["message"] as? kotlinx.serialization.json.JsonPrimitive)?.content })
        else -> SendResult.Sent
    }

    private suspend fun deliver(entry: OutboxEntry): HttpOutcome = when (entry.kind) {
        OutboxKind.STATUS -> api.postStatus(entry.rideId, entry.payload, entry.key)
        OutboxKind.CHAT -> api.postChat(entry.rideId, entry.chatBody.orEmpty(), entry.key).also { o ->
            if (o is HttpOutcome.Ok) o.json.obj("message")?.let(ChatMessage::fromData)?.let { _delivered.value = it }
        }
    }

    private fun refresh() {
        _entries.value = store.all()
    }

    private fun HttpOutcome.describe(): String = when (this) {
        is HttpOutcome.Ok -> "ok $code"
        is HttpOutcome.Error -> "http $code"
        is HttpOutcome.NetworkError -> "network: ${cause.message}"
        HttpOutcome.NotConfigured -> "not configured"
    }
}

/**
 * One driver status change, in the shape `POST /rt/v1/driver/rides/{id}/status` takes.
 * The key is fixed when the driver taps (ride, status, stop, event time), so every retry
 * and replay of this one transition is recognised by the server.
 */
data class StatusRequest(
    val rideId: Long,
    val status: String,
    /** When the driver acted (epoch ms), not when it was finally delivered. */
    val eventAtMs: Long,
    val stop: StopRef? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val metadata: Map<String, Any?> = emptyMap(),
    val idempotencyKey: String = buildString {
        append(rideId).append('-').append(status)
        stop?.let { append("-stop").append(it.index) }
        append('-').append(eventAtMs)
    },
) {
    data class StopRef(
        val index: Int,
        val stopNumber: Int?,
        val totalStops: Int?,
        val address: String?,
        val latitude: Double?,
        val longitude: Double?,
    )

    fun toBody(): JsonObject = Wire.obj(
        "status" to status,
        "timestamp" to eventAtMs,
        "stop_info" to stop?.let {
            mapOf(
                "index" to it.index, "stop_number" to it.stopNumber, "total_stops" to it.totalStops,
                "address" to it.address, "latitude" to it.latitude, "longitude" to it.longitude,
            ).filterValues { v -> v != null }
        },
        // Absent rather than 0/0 when there is no fix: 0/0 would poison the last-known position.
        "location" to if (latitude != null && longitude != null && !(latitude == 0.0 && longitude == 0.0)) {
            mapOf("latitude" to latitude, "longitude" to longitude)
        } else null,
        "metadata" to (metadata + ("source" to "android")).filterValues { it != null },
        "idempotency_key" to idempotencyKey,
    )

}
