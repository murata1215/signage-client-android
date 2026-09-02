package jp.co.tisa.signage_android.data

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.TimeUnit

class ServerClient(private val config: SignageConfig) {

    private val gson = Gson()

    private val proxyHost = "210.175.128.100"
    private val proxyPort = 8080
    private val bypassPrefixes = listOf(
        "10.", "172.16.", "172.17.", "172.18.", "172.19.",
        "172.20.", "172.21.", "172.22.", "172.23.", "172.24.",
        "172.25.", "172.26.", "172.27.", "172.28.", "172.29.",
        "172.30.", "172.31.", "192.168.", "localhost", "127.0.0.1",
        "atg.co.jp", "tisaweb.or.jp"
    )

    private val proxyClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort)))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val directClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private fun getClient(url: String): OkHttpClient {
        val needsProxy = bypassPrefixes.none { url.contains(it) }
        return if (needsProxy) proxyClient else directClient
    }

    private fun buildPlayerUrl(endpoint: String): String {
        return "${config.serverUrl}$endpoint${if (endpoint.contains("?")) "&" else "?"}key=${config.clientKey}"
    }

    suspend fun fetchSchedule(): ScheduleResponse? = withContext(Dispatchers.IO) {
        try {
            val url = buildPlayerUrl("/api/player/schedule")
            val request = Request.Builder().url(url).get().build()
            val response = getClient(url).newCall(request).execute()
            response.use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@withContext null
                    gson.fromJson(body, ScheduleResponse::class.java)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun sendHeartbeat(): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = buildPlayerUrl("/api/player/heartbeat")
            val request = Request.Builder()
                .url(url)
                .post(ByteArray(0).toRequestBody(null))
                .build()
            val response = getClient(url).newCall(request).execute()
            response.use { it.isSuccessful }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun downloadPdf(contentId: Int, destFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = buildPlayerUrl("/api/player/content/$contentId/file")
            val request = Request.Builder().url(url).get().build()
            val response = getClient(url).newCall(request).execute()
            response.use { resp ->
                if (resp.isSuccessful) {
                    resp.body?.byteStream()?.use { input ->
                        FileOutputStream(destFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    true
                } else {
                    false
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun testConnection(): ConnectionTestResult = withContext(Dispatchers.IO) {
        try {
            val url = buildPlayerUrl("/api/player/schedule")
            val request = Request.Builder().url(url).get().build()
            val response = getClient(url).newCall(request).execute()
            response.use { resp ->
                when {
                    resp.isSuccessful -> ConnectionTestResult(true, "接続成功")
                    resp.code == 401 -> ConnectionTestResult(false, "Client Key が無効です")
                    else -> ConnectionTestResult(false, "サーバーエラー (${resp.code})")
                }
            }
        } catch (e: Exception) {
            ConnectionTestResult(false, "接続失敗: ${e.message}")
        }
    }

    // 未割当クライアント一覧取得 (key認証不要、初期設定画面用)
    suspend fun fetchUnassignedClients(): List<UnassignedClient>? = withContext(Dispatchers.IO) {
        try {
            val url = "${config.serverUrl}/api/player/unassigned-clients"
            val request = Request.Builder().url(url).get().build()
            val response = getClient(url).newCall(request).execute()
            response.use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@withContext null
                    gson.fromJson(body, UnassignedClientsResponse::class.java)?.clients
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val url = buildPlayerUrl("/api/player/update/check")
            val request = Request.Builder().url(url).get().build()
            val response = getClient(url).newCall(request).execute()
            response.use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@withContext null
                    gson.fromJson(body, UpdateInfo::class.java)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            // Update check failure is not critical
            null
        }
    }

    /**
     * YouTube関連ドメインへの疎通プローブ(v1.85)。
     * 152-4等のYouTube再生エラーが「プロキシがgooglevideo.com等の配信/認証系ドメインを
     * 塞いでいる」ことに起因していないかを実測で切り分けるための診断用メソッド。
     * 既存のプロキシ設定(getClient())をそのまま再利用する。
     * 戻り値はデバッグオーバーレイに1行で表示する簡易フォーマット。
     */
    suspend fun probeYoutubeConnectivity(): String = withContext(Dispatchers.IO) {
        val targets = listOf(
            "yt" to "https://www.youtube.com/favicon.ico",
            "google" to "https://www.google.com/generate_204",
            "ytimg" to "https://i.ytimg.com/favicon.ico",
            "gvideo" to "https://redirector.googlevideo.com/generate_204",
            "ncookie" to "https://www.youtube-nocookie.com/favicon.ico"
        )
        val results = targets.map { (label, url) ->
            val status = try {
                val request = Request.Builder().url(url).get().build()
                val response = getClient(url).newCall(request).execute()
                response.use { it.code.toString() }
            } catch (e: Exception) {
                "NG(${e.javaClass.simpleName})"
            }
            "$label=$status"
        }
        results.joinToString(" ")
    }

    suspend fun downloadApk(downloadPath: String, destFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = if (downloadPath.startsWith("http")) {
                // Absolute URL: use as-is
                downloadPath
            } else if (downloadPath.contains("key=")) {
                // Server returned full path with key already included
                // Use only host:port from serverUrl to avoid double path
                val uri = URI(config.serverUrl)
                val baseHost = "${uri.scheme}://${uri.host}${if (uri.port > 0) ":${uri.port}" else ""}"
                "$baseHost$downloadPath"
            } else {
                // Relative path: combine with serverUrl and add key
                "${config.serverUrl}$downloadPath${if (downloadPath.contains("?")) "&" else "?"}key=${config.clientKey}"
            }
            val request = Request.Builder().url(url).get().build()
            val response = getClient(url).newCall(request).execute()
            response.use { resp ->
                if (resp.isSuccessful) {
                    resp.body?.byteStream()?.use { input ->
                        FileOutputStream(destFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    true
                } else {
                    false
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}

data class ConnectionTestResult(
    val success: Boolean,
    val message: String
)
