package com.limo1800.realtime.config

import android.content.Context
import android.content.SharedPreferences
import com.limo1800.realtime.protocol.obj
import com.limo1800.realtime.protocol.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Which realtime service the app talks to.
 * - `node`: limoRTService (Socket.IO), the current path, kept for rollback.
 * - `python`: limo-rt (WebSocket `/rt/v1/ws` + REST `/rt/v1/…`).
 */
enum class RealtimeProvider { NODE, PYTHON }

data class RealtimeConfig(val provider: RealtimeProvider, val socketUrl: String?) {
    /** True when every realtime call should go to limo-rt. */
    val usesLimoRt: Boolean get() = provider == RealtimeProvider.PYTHON && !socketUrl.isNullOrBlank()

    companion object {
        val NODE = RealtimeConfig(RealtimeProvider.NODE, null)
    }
}

/** Where the cached answer lives; SharedPreferences in the app, a map in tests. */
interface ConfigCache {
    fun load(): RealtimeConfig?
    fun save(config: RealtimeConfig)
}

class SharedPrefsConfigCache(context: Context) : ConfigCache {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("limo_rt_config_v1", Context.MODE_PRIVATE)

    override fun load(): RealtimeConfig? {
        val provider = prefs.getString("provider", null) ?: return null
        return RealtimeConfig(
            if (provider == "python") RealtimeProvider.PYTHON else RealtimeProvider.NODE,
            prefs.getString("url", null),
        )
    }

    override fun save(config: RealtimeConfig) {
        prefs.edit()
            .putString("provider", if (config.provider == RealtimeProvider.PYTHON) "python" else "node")
            .putString("url", config.socketUrl)
            .apply()
    }
}

/**
 * Reads `GET {laravel}/api/mobile/v1/config` → `data.realtime {provider, url}`.
 *
 * Laravel applies the pilot allowlist per user, so the bearer token is sent when there is
 * one. The last answer is cached: a launch without network stays on the provider the user
 * was on. A config without a `realtime` block means a Laravel from before the migration,
 * i.e. Node. Never blocks a connect: callers use [config] now and react when it changes.
 */
class RealtimeConfigRepository(
    private val http: OkHttpClient,
    private val laravelBaseUrl: String,
    private val tokenProvider: () -> String?,
    private val cache: ConfigCache,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val refreshIntervalMs: Long = 60_000,
) {
    private val _config = MutableStateFlow(cache.load() ?: RealtimeConfig.NODE)
    val config: StateFlow<RealtimeConfig> = _config.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var lastRefreshAt = Long.MIN_VALUE / 2

    /** Re-reads the config if the last read is older than a minute (or [force]). */
    suspend fun refreshIfStale(force: Boolean = false): RealtimeConfig = mutex.withLock {
        if (!force && nowMs() - lastRefreshAt < refreshIntervalMs) return _config.value
        val fetched = fetch() ?: return _config.value // unreachable: keep the cached provider
        lastRefreshAt = nowMs()
        if (fetched != _config.value) {
            cache.save(fetched)
            _config.value = fetched
        }
        fetched
    }

    private suspend fun fetch(): RealtimeConfig? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(laravelBaseUrl.trimEnd('/') + "/api/mobile/v1/config")
            .header("Accept", "application/json")
            .apply { tokenProvider()?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") } }
            .build()
        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val root = json.parseToJsonElement(response.body?.string().orEmpty()) as? JsonObject
                val data = root?.obj("data") ?: return@withContext null
                parse(data)
            }
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    companion object {
        internal fun parse(data: JsonObject): RealtimeConfig {
            val realtime = data.obj("realtime") ?: return RealtimeConfig.NODE
            return if (realtime.string("provider") == "python") {
                RealtimeConfig(RealtimeProvider.PYTHON, realtime.string("url"))
            } else {
                RealtimeConfig.NODE
            }
        }
    }
}
