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
        private const val KEY_LAST_UPDATE_CHANNEL = "last_update_channel"
        private const val KEY_UPDATE_ATTEMPT_VERSION_CODE = "update_attempt_version_code"
        private const val KEY_UPDATE_ATTEMPT_COUNT = "update_attempt_count"
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

    // ── APK更新チャネル/ループ防御(v1.86) ──

    fun saveLastUpdateChannel(channel: String?) {
        prefs.edit().putString(KEY_LAST_UPDATE_CHANNEL, channel).apply()
    }

    fun getLastUpdateChannel(): String? = prefs.getString(KEY_LAST_UPDATE_CHANNEL, null)

    /** Pair(attemptVersionCode, attemptCount). 未記録の場合は Pair(-1, 0) */
    fun getUpdateAttempt(): Pair<Int, Int> {
        val code = prefs.getInt(KEY_UPDATE_ATTEMPT_VERSION_CODE, -1)
        val count = prefs.getInt(KEY_UPDATE_ATTEMPT_COUNT, 0)
        return Pair(code, count)
    }

    /** 対象の version_code への試行を1回記録する。version_code が変わればカウントは1にリセット */
    fun recordUpdateAttempt(versionCode: Int) {
        val (prevCode, prevCount) = getUpdateAttempt()
        val newCount = if (prevCode == versionCode) prevCount + 1 else 1
        prefs.edit()
            .putInt(KEY_UPDATE_ATTEMPT_VERSION_CODE, versionCode)
            .putInt(KEY_UPDATE_ATTEMPT_COUNT, newCount)
            .apply()
    }

    fun resetUpdateAttempt() {
        prefs.edit()
            .remove(KEY_UPDATE_ATTEMPT_VERSION_CODE)
            .remove(KEY_UPDATE_ATTEMPT_COUNT)
            .apply()
    }
}
