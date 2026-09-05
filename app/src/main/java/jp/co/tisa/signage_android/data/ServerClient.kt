package jp.co.tisa.signage_android.data

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * サーバー通信は常にdirect固定(v1.92)。
 * プロキシはコンテンツ単位(use_proxy/proxy_url、サーバー側で実装済み)で指定するため、
 * 端末固定の社内プロキシ設定・ドメインバイパスリストは廃止した。
 * WebView側のコンテンツ単位プロキシ適用は player.WebProxyManager が担当する。
 */
class ServerClient(private val config: SignageConfig) {

    private val gson = Gson()

    private val directClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .proxy(java.net.Proxy.NO_PROXY) // v1.95: 端末のシステムプロキシを拾わせない
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private fun buildPlayerUrl(endpoint: String): String {
        return "${config.serverUrl}$endpoint${if (endpoint.contains("?")) "&" else "?"}key=${config.clientKey}"
    }

    suspend fun fetchSchedule(): ScheduleResponse? = withContext(Dispatchers.IO) {
        try {
            val url = buildPlayerUrl("/api/player/schedule")
            val request = Request.Builder().url(url).get().build()
            val response = directClient.newCall(request).execute()
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
            val response = directClient.newCall(request).execute()
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
            val response = directClient.newCall(request).execute()
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
            val response = directClient.newCall(request).execute()
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
            val response = directClient.newCall(request).execute()
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
            val response = directClient.newCall(request).execute()
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
     * プロキシ指定ありでYouTube疎通プローブを行う際にだけ使う一時OkHttpClient。
     * 通常のサーバー通信(directClient)には影響しない(v1.92)。
     */
    private fun probeClientFor(proxy: ProxySpec?): OkHttpClient {
        if (proxy == null) return directClient
        return OkHttpClient.Builder()
            .proxy(proxy.toJavaProxy())
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * YouTube関連ドメインへの疎通プローブ(v1.85、v1.89でdoubleclick等の広告/認証系を追加)。
     * 152-4等のYouTube再生エラーが「プロキシがgooglevideo.com等の配信/認証系ドメインを
     * 塞いでいる」ことに起因していないかを実測で切り分けるための診断用メソッド。
     * 戻り値はデバッグオーバーレイに1行で表示する簡易フォーマット。
     * v1.89: 埋め込みプレイヤーが再生前に問い合わせる広告ステータス(static.doubleclick.net等)が
     * プロキシで遮断されエラー152の原因になっている疑いがあるため、対象ドメインを追加した。
     * v1.92: 端末固定プロキシの廃止に伴い、呼び出し側がプレイリスト中の use_proxy=true 項目の
     * proxy_url を渡した場合だけ、その一時OkHttpClientで疎通確認する(渡されなければdirect)。
     */
    suspend fun probeYoutubeConnectivity(proxy: ProxySpec? = null): String = withContext(Dispatchers.IO) {
        val targets = listOf(
            "yt" to "https://www.youtube.com/favicon.ico",
            "google" to "https://www.google.com/generate_204",
            "ytimg" to "https://i.ytimg.com/favicon.ico",
            "gvideo" to "https://redirector.googlevideo.com/generate_204",
            "ncookie" to "https://www.youtube-nocookie.com/favicon.ico",
            // v1.89: 埋め込みプレイヤーの広告ステータス問い合わせ/再生トークン発行系(152の主犯候補)
            "dclick" to "https://static.doubleclick.net/instream/ad_status.js",
            "gads" to "https://googleads.g.doubleclick.net/favicon.ico",
            "jnnpa" to "https://jnn-pa.googleapis.com/favicon.ico",
            "pglog" to "https://play.google.com/generate_204"
        )
        val client = probeClientFor(proxy)
        val results = targets.map { (label, url) ->
            val status = try {
                val request = Request.Builder().url(url).get().build()
                val response = client.newCall(request).execute()
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
            val response = directClient.newCall(request).execute()
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
