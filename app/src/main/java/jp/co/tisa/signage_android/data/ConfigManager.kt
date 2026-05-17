package jp.co.tisa.signage_android.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson

class ConfigManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val PREFS_NAME = "signage_config"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_CLIENT_KEY = "client_key"
        private const val KEY_POLLING_INTERVAL = "polling_interval_sec"
        private const val KEY_CACHED_SCHEDULE = "cached_schedule"
        private const val KEY_CACHED_VERSION = "cached_version"
    }

    fun isConfigured(): Boolean {
        return getServerUrl() != null && getClientKey() != null
    }

    fun getServerUrl(): String? = prefs.getString(KEY_SERVER_URL, null)

    fun getClientKey(): String? = prefs.getString(KEY_CLIENT_KEY, null)

    fun getPollingInterval(): Int = prefs.getInt(KEY_POLLING_INTERVAL, 60)

    fun getConfig(): SignageConfig? {
        val serverUrl = getServerUrl() ?: return null
        val clientKey = getClientKey() ?: return null
        return SignageConfig(
            serverUrl = serverUrl,
            clientKey = clientKey,
            pollingIntervalSec = getPollingInterval()
        )
    }

    fun saveConfig(serverUrl: String, clientKey: String, pollingIntervalSec: Int = 60) {
        prefs.edit()
            .putString(KEY_SERVER_URL, serverUrl.trimEnd('/'))
            .putString(KEY_CLIENT_KEY, clientKey.trim())
            .putInt(KEY_POLLING_INTERVAL, pollingIntervalSec)
            .apply()
    }

    fun getCachedSchedule(): ScheduleResponse? {
        val json = prefs.getString(KEY_CACHED_SCHEDULE, null) ?: return null
        return try {
            gson.fromJson(json, ScheduleResponse::class.java)
        } catch (e: Exception) {
            null
        }
    }

    fun cacheSchedule(schedule: ScheduleResponse) {
        prefs.edit()
            .putString(KEY_CACHED_SCHEDULE, gson.toJson(schedule))
            .putInt(KEY_CACHED_VERSION, schedule.version)
            .apply()
    }

    fun getCachedVersion(): Int = prefs.getInt(KEY_CACHED_VERSION, -1)

    fun clearAll() {
        prefs.edit().clear().apply()
    }
}
