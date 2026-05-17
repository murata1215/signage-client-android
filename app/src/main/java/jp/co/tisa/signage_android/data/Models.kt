package jp.co.tisa.signage_android.data

import com.google.gson.annotations.SerializedName

data class SignageConfig(
    val serverUrl: String,
    val clientKey: String,
    val pollingIntervalSec: Int = 60
)

data class ScheduleResponse(
    @SerializedName("version") val version: Int,
    @SerializedName("play_start_time") val playStartTime: String,
    @SerializedName("play_end_time") val playEndTime: String,
    @SerializedName("playlist") val playlist: List<PlaylistItem>
)

data class PlaylistItem(
    @SerializedName("id") val id: Int,
    @SerializedName("scope") val scope: String,
    @SerializedName("content_id") val contentId: Int,
    @SerializedName("name") val name: String,
    @SerializedName("type") val type: String,
    @SerializedName("url") val url: String? = null,
    @SerializedName("file_url") val fileUrl: String? = null,
    @SerializedName("pdf_page_duration") val pdfPageDuration: Int? = null,
    @SerializedName("duration_seconds") val durationSeconds: Int,
    @SerializedName("display_order") val displayOrder: Int,
    @SerializedName("use_proxy") val useProxy: Boolean = false,
    @SerializedName("proxy_url") val proxyUrl: String? = null
)
