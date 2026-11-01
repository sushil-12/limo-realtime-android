package com.limo1800.realtime.api

import com.limo1800.realtime.protocol.ChatMessage
import com.limo1800.realtime.protocol.DriverFix
import com.limo1800.realtime.protocol.Wire
import com.limo1800.realtime.protocol.long
import com.limo1800.realtime.protocol.obj
import com.limo1800.realtime.protocol.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** Outcome of one REST call. */
sealed interface HttpOutcome {
    data class Ok(val code: Int, val json: JsonObject) : HttpOutcome
    data class Error(val code: Int, val json: JsonObject?) : HttpOutcome
    data class NetworkError(val cause: IOException) : HttpOutcome
    /** limo-rt isn't the active provider (no URL) or there's no token: nothing was sent. */
    data object NotConfigured : HttpOutcome

    /** Worth retrying later with the same idempotency key. */
    val isRetriable: Boolean
        get() = this is NetworkError || this is NotConfigured ||
            (this is Error && (code == 408 || code == 429 || code >= 500))

    /** The token was refused (Laravel's session-expired 440 included): park until re-login. */
    val isAuthFailure: Boolean get() = this is Error && (code == 401 || code == 440)
}

/**
 * limo-rt's REST endpoints (`/rt/v1/…`). Every call carries the user's Passport bearer;
 * nothing here uses a shared secret.
 *
 * @param restBase e.g. `https://rt.1800limo.com/rt/v1`, or null while limo-rt is not the
 *   active provider (see [restBaseFromSocketUrl]).
 */
class RtApi(
    private val http: OkHttpClient,
    private val restBase: () -> String?,
    private val tokenProvider: () -> String?,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json".toMediaType()

    // --- status ---

    suspend fun postStatus(rideId: Long, body: JsonObject, idempotencyKey: String): HttpOutcome =
        call("POST", "/driver/rides/$rideId/status", body, idempotencyKey)

    // --- chat ---

    suspend fun postChat(rideId: Long, body: String, clientMsgId: String): HttpOutcome =
        call("POST", "/rides/$rideId/messages", Wire.obj("body" to body, "client_msg_id" to clientMsgId))

    /** Oldest first. [afterId] > 0 fetches exactly what a reconnecting client missed. */
    suspend fun chatHistory(rideId: Long, afterId: Long = 0, limit: Int = 50): Result<List<ChatMessage>> =
        when (val r = call("GET", "/rides/$rideId/messages?after_id=$afterId&limit=${limit.coerceIn(1, 200)}")) {
            is HttpOutcome.Ok -> Result.success(
                (r.json["messages"] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonObject)?.let(ChatMessage::fromData) },
            )
            else -> Result.failure(RtApiException(r))
        }

    suspend fun markRead(rideId: Long, upToId: Long): HttpOutcome =
        call("POST", "/rides/$rideId/messages/read", Wire.obj("up_to_id" to upToId))

    // --- devices (FCM) ---

    suspend fun registerDevice(fcmToken: String, app: String, appVersion: String?, locale: String?): HttpOutcome =
        call(
            "POST", "/devices",
            Wire.obj(
                "token" to fcmToken, "app" to app, "platform" to "android",
                "app_version" to appVersion?.take(32), "locale" to locale?.take(16),
            ),
        )

    /** Call on logout, before the auth token is cleared: the request needs it. */
    suspend fun revokeDevice(fcmToken: String): HttpOutcome = call("POST", "/devices/revoke", Wire.obj("token" to fcmToken))

    // --- share / location ---

    /** A signed, ride-scoped tracking token (passenger only). */
    suspend fun createShareLink(rideId: Long): Result<ShareLink> =
        when (val r = call("POST", "/rides/$rideId/share")) {
            is HttpOutcome.Ok -> {
                val token = r.json.string("token")
                if (token == null) Result.failure(RtApiException(r))
                else Result.success(ShareLink(token, r.json.long("expires_at")))
            }
            else -> Result.failure(RtApiException(r))
        }

    /** REST fallback while the socket is down: the latest fix plus the ride's status. */
    suspend fun latestLocation(rideId: Long): Result<LatestLocation> =
        when (val r = call("GET", "/rides/$rideId/location")) {
            is HttpOutcome.Ok -> Result.success(
                LatestLocation(
                    fix = r.json.obj("location")?.let(DriverFix::fromData),
                    serverTsMs = r.json.long("ts"),
                    status = r.json.string("status"),
                    seq = r.json.long("seq"),
                ),
            )
            else -> Result.failure(RtApiException(r))
        }

    // --- plumbing ---

    internal suspend fun call(
        method: String,
        path: String,
        body: JsonObject? = null,
        idempotencyKey: String? = null,
    ): HttpOutcome {
        val base = restBase()?.trimEnd('/') ?: return HttpOutcome.NotConfigured
        val token = tokenProvider()?.takeIf { it.isNotBlank() } ?: return HttpOutcome.NotConfigured
        val builder = Request.Builder()
            .url(base + path)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $token")
        idempotencyKey?.let { builder.header("Idempotency-Key", it) }
        val requestBody = (body ?: if (method == "GET") null else JsonObject(emptyMap()))
            ?.toString()?.toRequestBody(jsonType)
        builder.method(method, requestBody)

        return withContext(Dispatchers.IO) {
            try {
                http.newCall(builder.build()).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    val parsed = if (text.isBlank()) JsonObject(emptyMap())
                    else runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
                    if (response.isSuccessful) HttpOutcome.Ok(response.code, parsed ?: JsonObject(emptyMap()))
                    else HttpOutcome.Error(response.code, parsed)
                }
            } catch (e: IOException) {
                HttpOutcome.NetworkError(e)
            }
        }
    }

    companion object {
        /** `wss://host/rt/v1/ws` → `https://host/rt/v1` (the REST base next to the socket). */
        fun restBaseFromSocketUrl(socketUrl: String?): String? {
            socketUrl ?: return null
            val uri = runCatching { java.net.URI(socketUrl) }.getOrNull() ?: return null
            val scheme = if (uri.scheme == "ws") "http" else "https"
            var path = uri.path.orEmpty()
            path = if (path.endsWith("/ws")) path.removeSuffix("/ws") else if (path.endsWith("/rt/v1")) path else "/rt/v1"
            val port = if (uri.port != -1) ":${uri.port}" else ""
            return "$scheme://${uri.host}$port$path"
        }
    }
}

data class ShareLink(val token: String, val expiresAtMs: Long?)

data class LatestLocation(val fix: DriverFix?, val serverTsMs: Long?, val status: String?, val seq: Long?)

class RtApiException(val outcome: HttpOutcome) : Exception(outcome.toString())
