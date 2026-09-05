package jp.co.tisa.signage_android.data

import java.net.InetSocketAddress
import java.net.Proxy

/**
 * コンテンツ単位のプロキシ指定(サーバー側で実装済みの use_proxy / proxy_url)をAndroid側で
 * 解釈するためのパーサ(v1.92)。
 *
 * サーバー移行(客先環境への展開)に伴い、端末に社内プロキシ(210.175.128.100:8080)や
 * ドメインバイパスリストをハードコードする従来方式を廃止し、管理画面で設定された
 * コンテンツごとの proxy_url 文字列をそのまま解釈してWebView/OkHttpに渡す方式に統一する。
 *
 * 受理仕様:
 * - "http://host:port" / "https://host:port" → そのままhost:port
 * - "host:port" (スキーム省略) → 同上
 * - "http://host" (ポート省略、スキームあり) → host:80 (デフォルトポート)
 * - 前後の空白・末尾スラッシュは無視する
 * - "" / null → null (direct扱い)
 * - "host" (スキームなし・ポートなしのホスト名のみ) → null
 *   (スキームが無いのにポートも無い入力は「プロキシ指定のつもりの入力ミス」の可能性が高く、
 *   意図せず direct のつもりが誤ってhttp:80を漁ることを避けるため、あえて不正扱いにする)
 * - ポートが範囲外(1-65535外)・非数値 → null
 *
 * parse() は例外を投げない。呼び出し側は null を direct へのフォールバックとして扱うこと。
 */
data class ProxySpec(val host: String, val port: Int) {

    /** androidx.webkit ProxyConfig.Builder#addProxyRule() 用の "host:port" 文字列 */
    fun toRule(): String = "$host:$port"

    /** OkHttpClient.Builder#proxy() 用の java.net.Proxy */
    fun toJavaProxy(): Proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port))

    companion object {
        private const val DEFAULT_PORT = 80
        private const val MIN_PORT = 1
        private const val MAX_PORT = 65535
        private val SCHEME_PREFIX = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")

        fun parse(raw: String?): ProxySpec? {
            if (raw == null) return null
            var s = raw.trim()
            if (s.isEmpty()) return null

            val hadScheme = SCHEME_PREFIX.containsMatchIn(s)
            s = s.replaceFirst(SCHEME_PREFIX, "")
            s = s.trimEnd('/')

            // host:port以降にパス等が残っていれば切り捨てる
            val slashIdx = s.indexOf('/')
            if (slashIdx >= 0) s = s.substring(0, slashIdx)
            if (s.isEmpty()) return null

            val colonIdx = s.lastIndexOf(':')
            val host: String
            val port: Int
            when {
                colonIdx <= 0 -> {
                    // ポート省略。スキームが明示されている場合のみデフォルトポート80を許容する。
                    if (!hadScheme) return null
                    host = s
                    port = DEFAULT_PORT
                }
                colonIdx == s.length - 1 -> {
                    // "host:" のような不正形式
                    return null
                }
                else -> {
                    host = s.substring(0, colonIdx)
                    val portStr = s.substring(colonIdx + 1)
                    val parsedPort = portStr.toIntOrNull() ?: return null
                    if (parsedPort < MIN_PORT || parsedPort > MAX_PORT) return null
                    port = parsedPort
                }
            }
            if (host.isBlank()) return null
            return ProxySpec(host, port)
        }
    }
}
