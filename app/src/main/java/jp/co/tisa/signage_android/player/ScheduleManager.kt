package jp.co.tisa.signage_android.player

import jp.co.tisa.signage_android.data.ConfigManager
import jp.co.tisa.signage_android.data.PlaylistItem
import jp.co.tisa.signage_android.data.ScheduleResponse
import jp.co.tisa.signage_android.data.ServerClient
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class ScheduleManager(
    private val configManager: ConfigManager,
    private val serverClient: ServerClient
) {
    private var currentSchedule: ScheduleResponse? = null
    private var currentIndex: Int = 0

    val playlist: List<PlaylistItem>
        get() = currentSchedule?.playlist ?: emptyList()

    val version: Int
        get() = currentSchedule?.version ?: -1

    suspend fun loadSchedule(): ScheduleResponse? {
        // Try server first
        val schedule = serverClient.fetchSchedule()
        if (schedule != null) {
            currentSchedule = schedule
            configManager.cacheSchedule(schedule)
            return schedule
        }

        // Fallback to cache
        val cached = configManager.getCachedSchedule()
        if (cached != null) {
            currentSchedule = cached
            return cached
        }

        return null
    }

    suspend fun checkForUpdate(): Boolean {
        val schedule = serverClient.fetchSchedule() ?: return false
        val cachedVersion = configManager.getCachedVersion()
        if (schedule.version != cachedVersion) {
            currentSchedule = schedule
            configManager.cacheSchedule(schedule)
            currentIndex = 0
            return true
        }
        return false
    }

    fun isWithinPlayTime(): Boolean {
        val schedule = currentSchedule ?: return false
        val now = LocalTime.now()
        val formatter = DateTimeFormatter.ofPattern("HH:mm")
        return try {
            val start = LocalTime.parse(schedule.playStartTime, formatter)
            val end = LocalTime.parse(schedule.playEndTime, formatter)
            if (start.isBefore(end)) {
                now.isAfter(start) && now.isBefore(end)
            } else {
                // Overnight schedule (e.g., 22:00 - 06:00)
                now.isAfter(start) || now.isBefore(end)
            }
        } catch (e: Exception) {
            true // Default to playing if time format is invalid
        }
    }

    fun getCurrentItem(): PlaylistItem? {
        val items = playlist
        if (items.isEmpty()) return null
        return items[currentIndex % items.size]
    }

    fun advanceToNext(): PlaylistItem? {
        val items = playlist
        if (items.isEmpty()) return null
        currentIndex = (currentIndex + 1) % items.size
        return items[currentIndex]
    }

    fun goToPrevious(): PlaylistItem? {
        val items = playlist
        if (items.isEmpty()) return null
        currentIndex = if (currentIndex - 1 < 0) items.size - 1 else currentIndex - 1
        return items[currentIndex]
    }

    fun getNextItem(): PlaylistItem? {
        val items = playlist
        if (items.isEmpty()) return null
        val nextIndex = (currentIndex + 1) % items.size
        return items[nextIndex]
    }

    fun reset() {
        currentIndex = 0
    }
}
