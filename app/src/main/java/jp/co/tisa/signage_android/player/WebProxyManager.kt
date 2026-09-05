package jp.co.tisa.signage_android.player

import android.os.Handler
import android.os.Looper
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import jp.co.tisa.signage_android.data.ProxySpec
import java.util.concurrent.Executor

/**
 * WebView用 androidx.webkit.ProxyController の状態を単一箇所で直列管理する(v1.92)。
 *
 * ProxyController#setProxyOverride()/clearProxyOverride() はアプリプロセス全体に効くAPIのため、
 * 3枚WebView(active+next+prev)クロスフェード構造から複数箇所で呼ばれると競合し得る。
 * ここを唯一の適用窓口にすることで「現在プロセスに適用されているプロキシ状態」を一元管理する。
 *
 * サーバー側で実装済みのコンテンツ単位 use_proxy/proxy_url を解釈する方式への移行に伴い、
 * 端末固定の社内プロキシ設定・ドメインバイパスリストは廃止した。
 */
object WebProxyManager {

    /** 現在プロセスに適用されているプロキシ状態(null = direct)。デバッグオーバーレイ表示にも使う */
    @Volatile
    var current: ProxySpec? = null
        private set

    /** 起動直後は「未適用」。最初のapply()呼び出しで必ず1回はAPIを通す */
    private var initialized = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { command -> mainHandler.post(command) }

    /**
     * targetの状態を適用する。target==current(null同士も等価)かつ初期適用済みならAPIを呼ばず
     * 即座にonReady()を呼ぶ。target!=nullならsetProxyOverride、target==nullならclearProxyOverride
     * を明示的に呼ぶ(前回設定がプロセスに残り続けるのを防ぐため)。
     * onReadyは必ずメインスレッドで呼ばれる。
     */
    fun apply(target: ProxySpec?, onReady: () -> Unit) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            // 未サポート端末は常にdirect扱い
            current = null
            initialized = true
            mainHandler.post(onReady)
            return
        }
        if (initialized && current == target) {
            mainHandler.post(onReady)
            return
        }
        try {
            val config = if (target != null) {
                ProxyConfig.Builder()
                    .addProxyRule(target.toRule())
                    .build()
            } else {
                // v1.95: clearProxyOverride()は「システムプロキシへ戻す」であってdirect強制ではない。
                // 端末のシステム/Wi-Fi設定にプロキシが入っていると use_proxy=0 のコンテンツまで
                // そのプロキシ経由になってしまう(実機で*.internal.tisaweb.or.jpが黒画面化する原因)。
                // use_proxy=0 を確実にdirectにするため、明示的なdirect上書きを設定する。
                ProxyConfig.Builder()
                    .addDirect()
                    .build()
            }
            ProxyController.getInstance().setProxyOverride(config, mainExecutor) {
                current = target
                initialized = true
                onReady()
            }
        } catch (e: Exception) {
            // 失敗してもアプリを止めず direct 扱いにフォールバックする
            current = null
            initialized = true
            mainHandler.post(onReady)
        }
    }
}
