package com.limo1800.realtime

import com.limo1800.realtime.api.HttpOutcome
import com.limo1800.realtime.api.RtApi
import com.limo1800.realtime.client.RealtimeClient
import com.limo1800.realtime.config.RealtimeConfig
import com.limo1800.realtime.config.RealtimeConfigRepository
import com.limo1800.realtime.outbox.OutboxProcessor
import com.limo1800.realtime.outbox.SendResult
import com.limo1800.realtime.outbox.StatusRequest
import com.limo1800.realtime.protocol.RideStatuses
import com.limo1800.realtime.protocol.RtEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Everything both apps do around the realtime client, in one place:
 *
 * - follows the `rt.provider` flag: connects to limo-rt when it says `python` and the user is
 *   signed in, disconnects otherwise (the app's Socket.IO path stays in charge then);
 * - on every `welcome`: drains the outbox and registers the FCM token (`POST /rt/v1/devices`);
 * - when a ride ends or is cancelled, drops status changes still queued for it;
 * - login / logout / FCM token refresh hooks.
 *
 * Each app builds one of these in its Hilt module and calls [start] from `Application.onCreate`.
 *
 * @param app `driver` or `rider`, for device registration.
 * @param isSignedIn whether a user token exists (the client also checks before connecting).
 * @param scheduleOutboxWorker asks WorkManager to finish the outbox later (needs a Context,
 *   so the app passes `{ OutboxWorker.schedule(context) }`).
 */
class RealtimeSession(
    val client: RealtimeClient,
    val api: RtApi,
    val outbox: OutboxProcessor,
    private val config: RealtimeConfigRepository,
    private val scope: CoroutineScope,
    private val app: String,
    private val appVersion: String?,
    private val locale: () -> String?,
    private val isSignedIn: () -> Boolean,
    private val scheduleOutboxWorker: () -> Unit,
    private val onStartHooks: List<() -> Unit> = emptyList(),
    private val log: (String) -> Unit = {},
) {
    val events: SharedFlow<RtEvent> get() = client.events
    val realtimeConfig: StateFlow<RealtimeConfig> get() = config.config

    /** True when this app should use limo-rt (REST, socket, outbox) rather than Node. */
    val usesLimoRt: Boolean get() = config.config.value.usesLimoRt

    @Volatile private var fcmToken: String? = null
    @Volatile private var registeredFcmToken: String? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        onStartHooks.forEach { it() }
        scope.launch {
            config.config.collect { applyConfig(it) }
        }
        scope.launch {
            client.events.collect { onEvent(it) }
        }
        refreshConfig(force = true)
    }

    /** Re-read the provider flag (cheap; rate-limited to once a minute unless [force]). */
    fun refreshConfig(force: Boolean = false) {
        scope.launch { config.refreshIfStale(force) }
    }

    // --- auth hooks ---

    fun onLogin() {
        refreshConfig(force = true)
        applyConfig(config.config.value)
        drainOutbox()
    }

    /** Call while the auth token is still stored: revoking the device token needs it. */
    suspend fun onLogout() {
        registeredFcmToken?.let { if (usesLimoRt) api.revokeDevice(it) }
        registeredFcmToken = null
        client.reset()
        outbox.clear()
    }

    /** From `FirebaseMessagingService.onNewToken` and from the token fetched at startup. */
    fun onFcmToken(token: String) {
        fcmToken = token
        scope.launch { registerDeviceIfNeeded() }
    }

    // --- writes ---

    suspend fun sendStatus(request: StatusRequest): SendResult =
        outbox.sendStatus(request).also { if (it is SendResult.Queued) scheduleOutboxWorker() }

    /** Returns the client_msg_id of the optimistic bubble. */
    suspend fun sendChat(rideId: Long, body: String): String {
        val id = outbox.sendChat(rideId, body)
        if (outbox.entries.value.any { it.key == id }) scheduleOutboxWorker()
        return id
    }

    fun drainOutbox() {
        scope.launch {
            val result = outbox.drain()
            if (result.pending > 0 && !result.authFailed) scheduleOutboxWorker()
        }
    }

    // --- internals ---

    private fun applyConfig(config: RealtimeConfig) {
        val url = config.socketUrl
        if (config.usesLimoRt && url != null && isSignedIn()) {
            client.connect(url)
        } else {
            client.disconnect()
        }
    }

    private suspend fun onEvent(event: RtEvent) {
        when (event) {
            is RtEvent.Welcome -> {
                drainOutbox()
                registerDeviceIfNeeded()
            }
            is RtEvent.RideStatus -> if (event.status in RideStatuses.TERMINAL) {
                outbox.dropRide(event.rideId)
            }
            else -> Unit
        }
    }

    private suspend fun registerDeviceIfNeeded() {
        val token = fcmToken ?: return
        if (!usesLimoRt || !isSignedIn() || token == registeredFcmToken) return
        when (val outcome = api.registerDevice(token, app, appVersion, locale())) {
            is HttpOutcome.Ok -> {
                registeredFcmToken = token
                log("device token registered")
            }
            else -> log("device token registration failed: $outcome")
        }
    }
}
