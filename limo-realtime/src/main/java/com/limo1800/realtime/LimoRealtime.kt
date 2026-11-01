package com.limo1800.realtime

import android.content.Context
import com.limo1800.realtime.android.RealtimeLifecycle
import com.limo1800.realtime.api.RtApi
import com.limo1800.realtime.client.ClientIdentity
import com.limo1800.realtime.client.OkHttpTransportFactory
import com.limo1800.realtime.client.RealtimeClient
import com.limo1800.realtime.client.SharedPrefsCursorStore
import com.limo1800.realtime.config.RealtimeConfigRepository
import com.limo1800.realtime.config.SharedPrefsConfigCache
import com.limo1800.realtime.outbox.OutboxProcessor
import com.limo1800.realtime.outbox.OutboxWorker
import com.limo1800.realtime.outbox.SqliteOutboxStore
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Builds the whole realtime graph for one app. Call once (a Hilt `@Singleton` provider is the
 * natural place), then [RealtimeSession.start] from `Application.onCreate`.
 *
 * @param app `driver` or `rider`.
 * @param laravelBaseUrl e.g. `https://api.1800limo.com` (for `mobile/v1/config`).
 * @param tokenProvider the Passport access token, or null when signed out.
 * @param scope an application-lifetime scope (e.g. `SupervisorJob() + Dispatchers.Default`).
 * @param http optional base client; the library adds its own timeouts and no interceptors that
 *   would retry or show loading UI. Don't pass a client whose interceptors do either.
 */
object LimoRealtime {

    fun create(
        context: Context,
        app: String,
        appVersion: String?,
        deviceId: String?,
        laravelBaseUrl: String,
        tokenProvider: () -> String?,
        scope: CoroutineScope,
        http: OkHttpClient = OkHttpClient(),
        log: (String) -> Unit = {},
    ): RealtimeSession {
        val appContext = context.applicationContext
        val rtHttp = http.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()

        val config = RealtimeConfigRepository(rtHttp, laravelBaseUrl, tokenProvider, SharedPrefsConfigCache(appContext))
        val client = RealtimeClient(
            identity = ClientIdentity(app = app, appVersion = appVersion, deviceId = deviceId),
            tokenProvider = tokenProvider,
            transports = OkHttpTransportFactory(rtHttp),
            cursors = SharedPrefsCursorStore(appContext),
            scope = scope,
            log = { log("client: $it") },
        )
        val api = RtApi(
            http = rtHttp,
            restBase = { config.config.value.takeIf { it.usesLimoRt }?.let { RtApi.restBaseFromSocketUrl(it.socketUrl) } },
            tokenProvider = tokenProvider,
        )
        val outbox = OutboxProcessor(SqliteOutboxStore(appContext), api, log = { log("outbox: $it") })
        OutboxWorker.install(outbox)

        lateinit var session: RealtimeSession
        val lifecycle = RealtimeLifecycle(
            context = appContext,
            client = client,
            onNetworkAvailable = { session.drainOutbox() },
            onForeground = {
                session.refreshConfig()
                session.drainOutbox()
            },
        )
        session = RealtimeSession(
            client = client,
            api = api,
            outbox = outbox,
            config = config,
            scope = scope,
            app = app,
            appVersion = appVersion,
            locale = { Locale.getDefault().toLanguageTag() },
            isSignedIn = { !tokenProvider().isNullOrBlank() },
            scheduleOutboxWorker = { OutboxWorker.schedule(appContext) },
            onStartHooks = listOf { lifecycle.start() },
            log = log,
        )
        return session
    }
}
