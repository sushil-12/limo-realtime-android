package com.limo1800.realtime.client

import com.limo1800.realtime.protocol.ActiveRideRef
import com.limo1800.realtime.protocol.DriverFix
import com.limo1800.realtime.protocol.RtEvent
import com.limo1800.realtime.protocol.Wire
import com.limo1800.realtime.protocol.bool
import com.limo1800.realtime.protocol.long
import com.limo1800.realtime.protocol.obj
import com.limo1800.realtime.protocol.string
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Who this client is, sent in `hello`. */
data class ClientIdentity(
    /** `driver` or `rider`. */
    val app: String,
    val appVersion: String?,
    val deviceId: String?,
    val platform: String = "android",
)

data class ClientTiming(
    val pingIntervalMs: Long = 20_000,
    /** No frame at all for this long means the socket is dead, whatever the OS thinks. */
    val deadAfterMs: Long = 30_000,
    /** From opening the socket to `welcome`. */
    val connectTimeoutMs: Long = 15_000,
    /** After `unauthorized`: give a token refresh or re-login time instead of hammering. */
    val unauthorizedBackoffMs: Long = 60_000,
    val tickMs: Long = 1_000,
)

sealed interface ConnectionState {
    /** Not wanted (logged out, or `rt.provider` isn't `python`), or no token. */
    data object Disconnected : ConnectionState
    data class Connecting(val attempt: Int) : ConnectionState
    data class Connected(val userId: String?) : ConnectionState
    data class WaitingToReconnect(val attempt: Int, val inMs: Long) : ConnectionState
}

/**
 * limo-rt's WebSocket (`/rt/v1/ws`), contract v1.
 *
 * Session: open → `hello` (Passport token) → `welcome` → `subscribe {last_seq}` per ride →
 * the server replays what we missed, sends the latest location as a snapshot, then
 * `subscribed`, then live frames. Rides are resubscribed after every reconnect.
 *
 * Durable events (`seq` set) are deduplicated against a per-ride cursor that survives
 * process death ([CursorStore]); each new one is acked, which tells the server not to push.
 * Location frames have no seq: latest wins, and nothing is queued while offline.
 *
 * All state lives in one coroutine (an actor fed by [inputs]); OkHttp callbacks and the
 * public methods only enqueue. Collect [events] from application scope at startup: a
 * SharedFlow without collectors drops what it's given.
 */
class RealtimeClient(
    private val identity: ClientIdentity,
    private val tokenProvider: () -> String?,
    private val transports: TransportFactory,
    private val cursors: CursorStore,
    private val scope: CoroutineScope,
    private val backoff: Backoff = Backoff(),
    private val timing: ClientTiming = ClientTiming(),
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<RtEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<RtEvent> = _events.asSharedFlow()

    /** server clock − device clock, from `welcome` and each `pong`. Null until known. */
    private val _serverOffsetMs = MutableStateFlow<Long?>(null)
    val serverOffsetMs: StateFlow<Long?> = _serverOffsetMs.asStateFlow()

    /** Reconnects since this object was created (telemetry: < 1 per ride-hour). */
    private val _reconnects = MutableStateFlow(0)
    val reconnects: StateFlow<Int> = _reconnects.asStateFlow()

    private sealed interface Input {
        data class Connect(val url: String) : Input
        data object Disconnect : Input
        data object Reset : Input
        data class ReconnectNow(val reason: String) : Input
        data class Subscribe(val rideId: Long) : Input
        data class Unsubscribe(val rideId: Long) : Input
        data class AppState(val foreground: Boolean) : Input
        data class Opened(val gen: Int) : Input
        data class Message(val gen: Int, val text: String) : Input
        data class Closed(val gen: Int, val code: Int, val reason: String) : Input
        data class Failed(val gen: Int, val error: Throwable) : Input
        data class Tick(val gen: Int) : Input
        data class ReconnectDue(val token: Int) : Input
    }

    private val inputs = Channel<Input>(Channel.UNLIMITED)

    // --- actor state (touched only inside the actor) ---
    private var url: String? = null
    private var wanted = false
    private var attempt = 0
    private var gen = 0
    private var reconnectToken = 0
    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var connectStartedAt = 0L
    private var lastReceivedAt = 0L
    private var pingIntervalMs = timing.pingIntervalMs
    private var unauthorizedUntil = 0L
    private var lastPingAt = 0L
    private var foreground = true
    private val rides = linkedSetOf<Long>()
    private val subscribedThisSession = mutableSetOf<Long>()
    private val awaitingSubscribed = mutableSetOf<Long>()
    private val hadCursorAtSubscribe = mutableMapOf<Long, Boolean>()

    // Read from other threads by sendLocation().
    @Volatile private var transport: Transport? = null
    @Volatile private var connected = false
    @Volatile private var lastSentAt = 0L

    init {
        scope.launch { for (input in inputs) handle(input) }
    }

    // --- public API: all asynchronous, safe from any thread ---

    fun connect(url: String) { inputs.trySend(Input.Connect(url)) }

    /** Close and stop reconnecting. Subscriptions are kept for the next [connect]. */
    fun disconnect() { inputs.trySend(Input.Disconnect) }

    /** Logout: disconnect and forget subscriptions and cursors (they belong to that user). */
    fun reset() { inputs.trySend(Input.Reset) }

    /** Skip any backoff and reconnect now (network change, foreground, manual retry). */
    fun reconnectNow(reason: String) { inputs.trySend(Input.ReconnectNow(reason)) }

    fun subscribe(rideId: Long) { inputs.trySend(Input.Subscribe(rideId)) }

    fun unsubscribe(rideId: Long) { inputs.trySend(Input.Unsubscribe(rideId)) }

    fun onForeground() { inputs.trySend(Input.AppState(foreground = true)) }

    fun onBackground() { inputs.trySend(Input.AppState(foreground = false)) }

    /**
     * One driver fix. Returns false (and drops it) while disconnected: by the time the socket
     * is back a newer fix exists, and the passenger only wants the latest.
     */
    fun sendLocation(rideId: Long, fix: DriverFix): Boolean {
        val t = transport ?: return false
        if (!connected) return false
        lastSentAt = nowMs()
        return t.send(Wire.encode("loc.update", rideId = rideId, data = fix.toData()))
    }

    // --- actor ---

    private suspend fun handle(input: Input) {
        when (input) {
            is Input.Connect -> {
                wanted = true
                if (url != input.url) {
                    val changed = url != null
                    url = input.url
                    if (changed && transport != null) {
                        log("socket URL changed, reconnecting")
                        teardown()
                    }
                }
                if (transport == null && reconnectJob == null) open()
            }
            Input.Disconnect -> {
                wanted = false
                cancelReconnect()
                teardown()
            }
            Input.Reset -> {
                wanted = false
                cancelReconnect()
                teardown()
                rides.clear()
                hadCursorAtSubscribe.clear()
                cursors.clear()
            }
            is Input.ReconnectNow -> {
                if (!wanted) return
                log("reconnect now: ${input.reason}")
                attempt = 0
                unauthorizedUntil = 0
                cancelReconnect()
                teardown()
                open()
            }
            is Input.Subscribe -> {
                rides.add(input.rideId)
                sendSubscribe(input.rideId)
            }
            is Input.Unsubscribe -> {
                rides.remove(input.rideId)
                awaitingSubscribed.remove(input.rideId)
                if (subscribedThisSession.remove(input.rideId)) {
                    send(Wire.encode("unsubscribe", rideId = input.rideId))
                }
            }
            is Input.AppState -> {
                foreground = input.foreground
                if (connected) {
                    sendAppState()
                } else if (input.foreground && wanted) {
                    // Android may have frozen the socket; don't wait out the backoff.
                    handle(Input.ReconnectNow("foreground"))
                }
            }
            is Input.Opened -> if (input.gen == gen) sendHello()
            is Input.Message -> if (input.gen == gen) onMessage(input.text)
            is Input.Closed -> if (input.gen == gen) {
                if (input.code == CLOSE_UNAUTHORIZED) unauthorizedUntil = nowMs() + timing.unauthorizedBackoffMs
                connectionLost("closed ${input.code} ${input.reason}")
            }
            is Input.Failed -> if (input.gen == gen) connectionLost("failure: ${input.error.message}")
            is Input.Tick -> if (input.gen == gen) heartbeat()
            is Input.ReconnectDue -> if (input.token == reconnectToken) {
                reconnectJob = null
                open()
            }
        }
    }

    private fun open() {
        val target = url ?: return
        if (!wanted) return
        if (tokenProvider().isNullOrBlank()) {
            log("no auth token, not connecting")
            _state.value = ConnectionState.Disconnected
            return
        }
        if (attempt > 0) _reconnects.value += 1
        val myGen = ++gen
        _state.value = ConnectionState.Connecting(attempt)
        connectStartedAt = nowMs()
        lastReceivedAt = connectStartedAt
        transport = transports.open(target, object : TransportListener {
            override fun onOpen() { inputs.trySend(Input.Opened(myGen)) }
            override fun onMessage(text: String) { inputs.trySend(Input.Message(myGen, text)) }
            override fun onClosed(code: Int, reason: String) { inputs.trySend(Input.Closed(myGen, code, reason)) }
            override fun onFailure(error: Throwable) { inputs.trySend(Input.Failed(myGen, error)) }
        })
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(timing.tickMs)
                inputs.send(Input.Tick(myGen))
            }
        }
    }

    private fun teardown() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        transport?.close(1000, "bye")
        transport = null
        connected = false
        gen++
        subscribedThisSession.clear()
        awaitingSubscribed.clear()
        _state.value = ConnectionState.Disconnected
    }

    private fun connectionLost(reason: String) {
        if (transport == null) return
        log("connection lost ($reason)")
        teardown()
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (!wanted || reconnectJob != null) return
        attempt += 1
        var delayMs = backoff.delayMs(attempt, duringRide = rides.isNotEmpty())
        val untilAuth = unauthorizedUntil - nowMs()
        if (untilAuth > delayMs) delayMs = untilAuth
        _state.value = ConnectionState.WaitingToReconnect(attempt, delayMs)
        log("reconnecting in ${delayMs}ms (attempt $attempt)")
        val token = ++reconnectToken
        reconnectJob = scope.launch {
            delay(delayMs)
            inputs.send(Input.ReconnectDue(token))
        }
    }

    private fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectToken++
    }

    private fun heartbeat() {
        val now = nowMs()
        if (!connected) {
            if (transport != null && now - connectStartedAt > timing.connectTimeoutMs) {
                connectionLost("no welcome within ${timing.connectTimeoutMs}ms")
            }
            return
        }
        if (now - lastReceivedAt > timing.deadAfterMs) {
            connectionLost("silent for ${now - lastReceivedAt}ms")
            return
        }
        // Ping when idle, and also when we're busy sending but hearing nothing back: a driver
        // streams a fix every second yet receives almost nothing, and only the pong proves
        // the link still works in both directions.
        val quietOut = now - lastSentAt >= pingIntervalMs
        val quietIn = now - lastReceivedAt >= pingIntervalMs && now - lastPingAt >= pingIntervalMs
        if (quietOut || quietIn) {
            lastPingAt = now
            send(Wire.encode("ping"))
        }
    }

    // --- outbound ---

    private fun send(text: String) {
        val t = transport ?: return
        lastSentAt = nowMs()
        if (!t.send(text)) log("send failed")
    }

    private fun sendHello() {
        val token = tokenProvider()
        if (token.isNullOrBlank()) {
            connectionLost("auth token disappeared")
            return
        }
        send(
            Wire.encode(
                "hello",
                data = Wire.obj(
                    "token" to token,
                    "app" to identity.app,
                    "platform" to identity.platform,
                    "app_version" to identity.appVersion?.take(32),
                    "device_id" to identity.deviceId?.take(128),
                ),
            ),
        )
    }

    private fun sendAppState() {
        send(Wire.encode("app_state", data = Wire.obj("state" to if (foreground) "foreground" else "background")))
    }

    private fun sendSubscribe(rideId: Long) {
        if (!connected || rideId in subscribedThisSession) return
        subscribedThisSession.add(rideId)
        awaitingSubscribed.add(rideId)
        val cursor = cursors.get(rideId)
        hadCursorAtSubscribe[rideId] = cursor != null
        send(Wire.encode("subscribe", rideId = rideId, data = Wire.obj("last_seq" to (cursor ?: 0L))))
    }

    // --- inbound ---

    private suspend fun onMessage(text: String) {
        lastReceivedAt = nowMs()
        val env = Wire.decode(text) ?: return log("unreadable frame dropped")
        val rideId = env.rideId
        when (env.type) {
            "welcome" -> onWelcome(env.data)
            "pong" -> env.data.long("server_time")?.let { _serverOffsetMs.value = it - nowMs() }
            "subscribed" -> {
                rideId ?: return
                awaitingSubscribed.remove(rideId)
                // Our cursor ahead of the server's counter means its stream was reset;
                // keeping ours would make every new event look already seen.
                val serverSeq = env.data.long("seq")
                val ours = cursors.get(rideId)
                if (serverSeq != null && ours != null && ours > serverSeq) cursors.put(rideId, serverSeq)
                _events.emit(RtEvent.Subscribed(rideId, serverSeq ?: 0, env.data.string("status")))
            }
            "error" -> onError(env.data.string("code") ?: "unknown", env.data.bool("retry") == true, rideId)
            else -> {
                val seq = env.seq
                if (rideId != null && seq != null) {
                    if (seq <= (cursors.get(rideId) ?: 0L)) return
                    cursors.put(rideId, seq)
                    send(Wire.encode("ack", rideId = rideId, seq = seq))
                }
                val isReplay = rideId != null && rideId in awaitingSubscribed
                val hadCursor = rideId?.let { hadCursorAtSubscribe[it] } ?: true
                RtEvent.from(env, isReplay, hadCursor)?.let { _events.emit(it) }
            }
        }
    }

    private suspend fun onWelcome(data: kotlinx.serialization.json.JsonObject) {
        connected = true
        attempt = 0
        unauthorizedUntil = 0
        data.long("ping_interval_s")?.takeIf { it >= 5 }?.let {
            pingIntervalMs = minOf(it * 1000, timing.pingIntervalMs)
        }
        data.long("server_time")?.let { _serverOffsetMs.value = it - nowMs() }
        _state.value = ConnectionState.Connected(data.string("user_id"))
        if (!foreground) sendAppState()
        rides.toList().forEach { sendSubscribe(it) }
        val active = data.obj("active_ride")?.let { a ->
            val id = a.long("ride_id") ?: return@let null
            ActiveRideRef(id, a.string("status") ?: "")
        }
        _events.emit(RtEvent.Welcome(data.string("user_id"), data.string("role"), active, data.long("server_time")))
    }

    private suspend fun onError(code: String, retry: Boolean, rideId: Long?) {
        log("server error $code${rideId?.let { " for ride $it" } ?: ""}")
        when (code) {
            "unauthorized" -> unauthorizedUntil = nowMs() + timing.unauthorizedBackoffMs
            "forbidden_ride", "share_expired" -> if (rideId != null) {
                // Not (or no longer) this user's ride: stop resubscribing to it.
                rides.remove(rideId)
                subscribedThisSession.remove(rideId)
                awaitingSubscribed.remove(rideId)
            }
        }
        _events.emit(RtEvent.ServerError(rideId, code, retry))
    }

    companion object {
        const val CLOSE_UNAUTHORIZED = 4401
    }
}
