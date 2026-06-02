package jp.co.tisa.signage_android.player

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Base64
import jp.co.tisa.signage_android.data.ConfigManager
import jp.co.tisa.signage_android.data.PlaylistItem
import jp.co.tisa.signage_android.data.ServerClient
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.abs

class PlayerActivity : ComponentActivity() {

    private lateinit var configManager: ConfigManager
    private lateinit var serverClient: ServerClient
    private lateinit var scheduleManager: ScheduleManager
    private lateinit var pdfCacheManager: PdfCacheManager
    private lateinit var pdfRenderCacheManager: PdfRenderCacheManager
    private var smbPdfManager: SmbPdfManager? = null

    // フラットスクリーンリスト（メインPL + pdf_folderサブPLを展開した1次元リスト）
    private var flatScreens: List<FlatScreen> = emptyList()
    private var currentScreenIndex: Int = 0

    private lateinit var containerLayout: FrameLayout
    private lateinit var webViewA: WebView
    private lateinit var webViewB: WebView
    private lateinit var webViewC: WebView
    private lateinit var statusBar: TextView
    private lateinit var touchOverlay: View
    private lateinit var pauseBorder: View
    private lateinit var debugTextView: TextView

    // 画面一覧オーバーレイ（リモコン上下で表示・選択ジャンプ）
    private lateinit var screenListOverlay: FrameLayout
    private lateinit var screenListText: TextView
    private lateinit var previewImageView: ImageView
    private lateinit var previewLabel: TextView
    private var isScreenListMode = false
    private var selectedListIndex = 0
    private var thumbnailToken = 0  // 高速移動時のサムネイル取り違え防止
    private var screenListTimeout: Runnable? = null
    private val SCREEN_LIST_TIMEOUT_MS = 60_000L  // 無操作で選択確定するまでの時間

    // Webサムネイルキャッシュ
    private val WEB_THUMB_TTL_MS = 6 * 60 * 60 * 1000L  // 再キャプチャ間隔(6時間)
    private val WEB_CAPTURE_DELAY_MS = 1500L            // 表示後キャプチャまでの待ち
    private val WEB_THUMB_SCALE = 0.5f                  // サムネイル縮小率
    private var webCaptureRunnable: Runnable? = null

    private val debugLines = mutableListOf<String>()
    private var debugPage = 0  // 0=非表示, 1=デバッグログ, 2=スケジュール, 3=端末情報, 4=命名マニュアル
    private var lastHeartbeatTime: String? = null
    private var lastScheduleUpdateTime: String? = null

    // 3-WebView architecture: active + next (preloaded) + prev (preloaded)
    private var activeWebView: WebView? = null
    private var nextWebView: WebView? = null
    private var prevWebView: WebView? = null

    private val handler = Handler(Looper.getMainLooper())
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var contentTimer: Runnable? = null
    private var countdownTimer: Runnable? = null
    private var remainingSeconds: Int = 0
    private var currentPdfPage: Int = 0
    private var totalPdfPages: Int = 0
    private var isAllPagesMode: Boolean = false
    private var pdfPageDurationSec: Int = 10
    private var pollingJob: Job? = null
    private var isPlaying = false
    private var isPaused = false
    private var nextReady = false
    private var prevReady = false

    // Long-press (5 sec) to reset to setup screen
    private val LONG_PRESS_RESET_MS = 5000L
    private var longPressResetRunnable: Runnable? = null

    private lateinit var gestureDetector: GestureDetector

    // BroadcastReceiver for update debug logs
    private val updateLogReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val msg = intent?.getStringExtra(jp.co.tisa.signage_android.service.SignageService.EXTRA_LOG_MESSAGE) ?: return
            addDebugLog(msg)
        }
    }

    // BroadcastReceiver for heartbeat from SignageService
    private val heartbeatReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            lastHeartbeatTime = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date())
            if (debugPage == 3) updateDebugContent()
        }
    }

    // BroadcastReceiver for schedule updates from SignageService
    private val scheduleUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            lastScheduleUpdateTime = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date())
            addDebugLog("[SCHEDULE] Broadcast受信: スケジュール更新")
            coroutineScope.launch {
                val schedule = scheduleManager.loadSchedule()
                if (schedule != null && schedule.playlist.isNotEmpty()) {
                    val items = scheduleManager.playlist
                    pdfCacheManager.downloadAll(items)
                    val activeIds = items.filter { it.type == "pdf" }.map { it.contentId }.toSet()
                    val activeWebKeys = items.filter { it.type == "web" }
                        .mapNotNull { it.url?.let(pdfRenderCacheManager::webCacheKey) }.toSet()
                    pdfCacheManager.cleanupUnused(activeIds)
                    pdfRenderCacheManager.cleanupUnused(activeIds, activeWebKeys)
                    // フラットリスト再構築
                    val newScreens = withContext(Dispatchers.IO) { buildFlatScreens(items) }
                    withContext(Dispatchers.Main) {
                        flatScreens = newScreens
                        currentScreenIndex = 0
                        addDebugLog("[SCHEDULE] スケジュール反映完了: ${items.size}件 → ${flatScreens.size}画面")
                        if (isPlaying) {
                            // 再生中: 次の切替時に新スケジュールが反映される
                        } else {
                            if (scheduleManager.isWithinPlayTime()) {
                                addDebugLog("[SCHEDULE] 再生時間内 → 再生開始")
                                displayCurrentScreen()
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Full screen immersive mode
        setupFullScreen()

        // Setup gesture detector
        setupGestureDetector()

        // Setup views
        setupViews()

        // Initialize managers
        configManager = ConfigManager(this)

        // Register broadcast receivers
        val updateLogFilter = IntentFilter(jp.co.tisa.signage_android.service.SignageService.ACTION_UPDATE_LOG)
        val scheduleFilter = IntentFilter(jp.co.tisa.signage_android.service.SignageService.ACTION_SCHEDULE_UPDATED)
        val heartbeatFilter = IntentFilter(jp.co.tisa.signage_android.service.SignageService.ACTION_HEARTBEAT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateLogReceiver, updateLogFilter, Context.RECEIVER_EXPORTED)
            registerReceiver(scheduleUpdateReceiver, scheduleFilter, Context.RECEIVER_EXPORTED)
            registerReceiver(heartbeatReceiver, heartbeatFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(updateLogReceiver, updateLogFilter)
            registerReceiver(scheduleUpdateReceiver, scheduleFilter)
            registerReceiver(heartbeatReceiver, heartbeatFilter)
        }

        val config = configManager.getConfig() ?: run {
            addDebugLog("[INIT] config NULL → finish")
            finish()
            return
        }
        addDebugLog("[INIT] config OK: ${config.serverUrl}")
        serverClient = ServerClient(config)
        scheduleManager = ScheduleManager(configManager, serverClient)
        pdfCacheManager = PdfCacheManager(this, serverClient)
        pdfRenderCacheManager = PdfRenderCacheManager(this)
        smbPdfManager = SmbPdfManager(this)

        // Start playback
        addDebugLog("[INIT] startPlayback 開始")
        statusBar.text = "初期化中..."
        coroutineScope.launch {
            try {
                startPlayback()
            } catch (e: Exception) {
                addDebugLog("[ERROR] startPlayback例外: ${e.message}")
                statusBar.text = "エラー: ${e.message}"
                e.printStackTrace()
            }
        }
    }

    private fun addDebugLog(message: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        debugLines.add("[$time] $message")
        if (debugLines.size > 20) {
            debugLines.removeAt(0)
        }
        if (debugPage == 1) {
            updateDebugContent()
        }
    }

    private fun cycleDebugPage() {
        debugPage = (debugPage + 1) % 5  // 0→1→2→3→4→0
        if (debugPage == 0) {
            debugTextView.visibility = View.GONE
        } else {
            debugTextView.visibility = View.VISIBLE
            updateDebugContent()
        }
    }

    private fun updateDebugContent() {
        debugTextView.text = when (debugPage) {
            1 -> buildDebugLogText()
            2 -> buildScheduleInfoText()
            3 -> buildDeviceInfoText()
            4 -> buildNamingManualText()
            else -> ""
        }
    }

    private fun buildDebugLogText(): String {
        val header = "[1/4] デバッグログ (下ボタンで切替)"
        return header + "\n" + debugLines.joinToString("\n")
    }

    private fun buildScheduleInfoText(): String {
        val sb = StringBuilder("[2/4] スケジュール情報 (下ボタンで切替)\n")
        sb.append("バージョン: v${scheduleManager.version}\n")
        sb.append("再生時間: ${scheduleManager.playTimeRange}\n")
        sb.append("フラットスクリーン: ${flatScreens.size}画面\n")
        sb.append("現在: ${currentScreenIndex + 1}/${flatScreens.size}\n")
        sb.append("─".repeat(20)).append("\n")

        flatScreens.forEachIndexed { idx, screen ->
            val prefix = if (idx == currentScreenIndex) "▶ " else "  "
            val typeTag = when (screen.type) {
                "web" -> "web"
                "pdf" -> "pdf"
                "dual_pdf" -> "dual"
                else -> screen.type
            }
            val dur = "${screen.durationSeconds}秒"
            val allPages = if (screen.isAllPages) " [全頁]" else ""
            sb.append("${prefix}${idx + 1}. [$typeTag] ${screen.displayName} ($dur)$allPages\n")
        }

        return sb.toString().trimEnd()
    }

    @SuppressLint("SetTextI18n")
    private fun buildDeviceInfoText(): String {
        val sb = StringBuilder("[3/4] 端末情報 (下ボタンで切替)\n")

        val pInfo = try {
            packageManager.getPackageInfo(packageName, 0)
        } catch (_: Exception) { null }
        val versionName = pInfo?.versionName ?: "?"
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pInfo?.longVersionCode?.toString() ?: "?"
        } else {
            @Suppress("DEPRECATION")
            pInfo?.versionCode?.toString() ?: "?"
        }
        sb.append("アプリ: v$versionName (code: $versionCode)\n")
        sb.append("Android: API ${Build.VERSION.SDK_INT} (Android ${Build.VERSION.RELEASE})\n")
        sb.append("端末: ${Build.MANUFACTURER} / ${Build.MODEL}\n")

        // ── ネットワーク情報 ──
        sb.append("${"─".repeat(20)}\n")
        try {
            val netInfo = getNetworkInfo()
            sb.append("IP: ${netInfo.ip}\n")
            sb.append("サブネット: ${netInfo.subnet}\n")
            sb.append("ゲートウェイ: ${netInfo.gateway}\n")
            sb.append("MAC: ${netInfo.mac}\n")
        } catch (_: Exception) {
            sb.append("ネットワーク: 取得失敗\n")
        }
        sb.append("プロキシ: 210.175.128.100:8080\n")

        // ── サーバー・画面 ──
        sb.append("${"─".repeat(20)}\n")
        val config = configManager.getConfig()
        sb.append("サーバー: ${config?.serverUrl ?: "未設定"}\n")
        val key = config?.clientKey ?: "未設定"
        val keyDisplay = if (key.length > 16) key.take(16) + "..." else key
        sb.append("クライアントキー: $keyDisplay\n")

        val dm = resources.displayMetrics
        sb.append("画面: ${dm.widthPixels}x${dm.heightPixels} (dpr: ${dm.density})\n")

        val runtime = Runtime.getRuntime()
        val usedMB = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
        val totalMB = runtime.maxMemory() / 1024 / 1024
        sb.append("メモリ: ${usedMB}MB / ${totalMB}MB\n")

        // ストレージ
        try {
            val stat = android.os.StatFs(filesDir.absolutePath)
            val freeGB = stat.availableBytes / 1_073_741_824.0
            val totalGB = stat.totalBytes / 1_073_741_824.0
            val pct = if (totalGB > 0) (freeGB / totalGB * 100).toInt() else 0
            sb.append("ストレージ: ${"%.1f".format(freeGB)}GB空き / ${"%.1f".format(totalGB)}GB (${pct}%)\n")
        } catch (_: Exception) {
            sb.append("ストレージ: 取得失敗\n")
        }

        // SMBキャッシュ件数
        try {
            val cacheDir = File(filesDir, "smb_pdf_cache")
            val count = if (cacheDir.exists()) {
                cacheDir.listFiles()?.sumOf { shareDir ->
                    shareDir.listFiles()?.count { it.extension == "pdf" } ?: 0
                } ?: 0
            } else 0
            sb.append("SMBキャッシュ: ${count}件\n")
        } catch (_: Exception) {}

        // 稼働時間
        val uptimeMs = android.os.SystemClock.elapsedRealtime()
        val days = uptimeMs / 86_400_000
        val hours = (uptimeMs % 86_400_000) / 3_600_000
        val mins = (uptimeMs % 3_600_000) / 60_000
        val uptimeStr = if (days > 0) "${days}日 ${hours}時間 ${mins}分"
        else "${hours}時間 ${mins}分"
        sb.append("稼働時間: $uptimeStr\n")

        // 最終ハートビート・スケジュール更新
        sb.append("最終HB: ${lastHeartbeatTime ?: "未受信"}\n")
        sb.append("最終スケジュール更新: ${lastScheduleUpdateTime ?: "未受信"}\n")

        return sb.toString().trimEnd()
    }

    private fun buildNamingManualText(): String {
        val sb = StringBuilder("[4/4] PDFフォルダ命名規約 (下ボタンで切替)\n")
        sb.append("${"─".repeat(20)}\n")
        sb.append("■ 書式:\n")
        sb.append("  {順番}_{ページ}_{開始日}_{終了日}_{秒}_{説明}.pdf\n")
        sb.append("\n")
        sb.append("■ 例:\n")
        sb.append("  001_0_20260501_20260531_30_書簡.pdf\n")
        sb.append("  002_1_20260601_20261231_10_全頁資料.pdf\n")
        sb.append("\n")
        sb.append("■ フィールド:\n")
        sb.append("  順番   : 表示順 (数値・小さい順)\n")
        sb.append("  ページ : 0=先頭ページのみ / 1=全ページ\n")
        sb.append("  開始日 : yyyyMMdd (表示開始日)\n")
        sb.append("  終了日 : yyyyMMdd (表示終了日)\n")
        sb.append("  秒     : 表示秒数\n")
        sb.append("           (全ページ時は1頁あたりの秒数)\n")
        sb.append("  説明   : 任意テキスト\n")
        sb.append("\n")
        sb.append("■ 注意:\n")
        sb.append("  ・規約外ファイル名→デフォルト動作\n")
        sb.append("  ・日付範囲外のファイルは非表示\n")
        sb.append("  ・規約ファイルが先、規約外が後に表示")
        return sb.toString()
    }

    private data class NetworkInfo(
        val ip: String, val subnet: String, val gateway: String, val mac: String
    )

    private fun getNetworkInfo(): NetworkInfo {
        var ip = "不明"
        var subnet = "不明"
        var mac = "不明"
        var gateway = "不明"

        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            for (ni in interfaces) {
                if (ni.isLoopback || !ni.isUp) continue
                // MACアドレス
                ni.hardwareAddress?.let { hw ->
                    mac = hw.joinToString(":") { "%02X".format(it) }
                }
                for (addr in ni.interfaceAddresses) {
                    val inetAddr = addr.address
                    if (inetAddr is java.net.Inet4Address) {
                        ip = inetAddr.hostAddress ?: "不明"
                        val prefixLen = addr.networkPrefixLength.toInt()
                        val maskInt = if (prefixLen > 0) (-1 shl (32 - prefixLen)) else 0
                        subnet = "${(maskInt shr 24) and 0xFF}.${(maskInt shr 16) and 0xFF}.${(maskInt shr 8) and 0xFF}.${maskInt and 0xFF} (/$prefixLen)"
                    }
                }
                if (ip != "不明") break
            }
        } catch (_: Exception) {}

        // ゲートウェイ
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val network = cm.activeNetwork
            val lp = cm.getLinkProperties(network)
            lp?.routes?.firstOrNull { it.isDefaultRoute }?.let { route ->
                gateway = route.gateway?.hostAddress ?: "不明"
            }
        } catch (_: Exception) {}

        return NetworkInfo(ip, subnet, gateway, mac)
    }

    // =========================================================================
    // Gesture Detection (swipe left/right, double tap)
    // =========================================================================

    private fun setupGestureDetector() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            private val SWIPE_THRESHOLD = 100
            private val SWIPE_VELOCITY_THRESHOLD = 100

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                val diffX = (e2.x) - (e1?.x ?: 0f)
                val diffY = (e2.y) - (e1?.y ?: 0f)

                if (abs(diffX) > abs(diffY) &&
                    abs(diffX) > SWIPE_THRESHOLD &&
                    abs(velocityX) > SWIPE_VELOCITY_THRESHOLD
                ) {
                    if (diffX > 0) {
                        goToPrevious()
                    } else {
                        goToNext()
                    }
                    return true
                }
                return false
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                togglePause()
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                return false
            }
        })
    }

    // =========================================================================
    // Key Events (Remote Control)
    // =========================================================================

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event)
        }

        val keyCode = event.keyCode

        // KEYCODE_BOOKMARK=93 (DS-STBRC03 remote) cycles debug overlay pages
        if (keyCode == 93) {
            cycleDebugPage()
            return true
        }

        // 画面一覧オーバーレイ表示中は専用キー処理を最優先
        if (isScreenListMode) {
            return when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_CHANNEL_UP -> { moveSelection(-1); true }
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_CHANNEL_DOWN -> { moveSelection(1); true }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { confirmSelection(); true }
                KeyEvent.KEYCODE_BACK -> { closeScreenList(); true }
                else -> true  // オーバーレイ中は他キーを握りつぶす
            }
        }

        // When paused (interactive mode), only intercept BACK to resume
        if (isPaused) {
            return when (keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    resumePlayback()
                    true
                }
                else -> super.dispatchKeyEvent(event)
            }
        }

        // Normal mode: intercept navigation keys before they reach WebView
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                goToNext()
                true
            }
            KeyEvent.KEYCODE_CHANNEL_UP -> {
                addDebugLog("[KEY] CH▲ pressed")
                goToNext()
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                goToPrevious()
                true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                addDebugLog("[KEY] CH▼ pressed")
                goToPrevious()
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                togglePause()
                true
            }
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                openScreenList()
                true
            }
            // F1-F4 keys
            KeyEvent.KEYCODE_F1 -> { addDebugLog("[KEY] F1 pressed"); true }
            KeyEvent.KEYCODE_F2 -> { addDebugLog("[KEY] F2 pressed"); true }
            KeyEvent.KEYCODE_F3 -> { addDebugLog("[KEY] F3 pressed"); true }
            // Volume/Mute keys (may be consumed by OS)
            KeyEvent.KEYCODE_VOLUME_MUTE -> { addDebugLog("[KEY] MUTE pressed"); true }
            KeyEvent.KEYCODE_VOLUME_UP -> { addDebugLog("[KEY] VOL+ pressed"); true }
            KeyEvent.KEYCODE_VOLUME_DOWN -> { addDebugLog("[KEY] VOL- pressed"); true }
            else -> {
                addDebugLog("[KEY] unknown keyCode=$keyCode (${KeyEvent.keyCodeToString(keyCode)})")
                super.dispatchKeyEvent(event)
            }
        }
    }

    // =========================================================================
    // Playback Control (next / previous / pause / resume)
    // =========================================================================

    private fun goToNext() {
        if (!isPlaying) return
        if (flatScreens.isEmpty()) return
        contentTimer?.let { handler.removeCallbacks(it) }
        countdownTimer?.let { handler.removeCallbacks(it) }

        if (isPaused) {
            currentScreenIndex = (currentScreenIndex + 1) % flatScreens.size
            val screen = flatScreens[currentScreenIndex]
            loadScreen(activeWebView!!, screen)
            statusBar.text = formatStatusText(screen.displayTitle, "⏸ 一時停止中 (戻るで再開)")
        } else {
            advanceToNext()
        }
    }

    private fun goToPrevious() {
        if (!isPlaying) return
        if (flatScreens.isEmpty()) return
        contentTimer?.let { handler.removeCallbacks(it) }
        countdownTimer?.let { handler.removeCallbacks(it) }

        if (isPaused) {
            currentScreenIndex = (currentScreenIndex - 1 + flatScreens.size) % flatScreens.size
            val screen = flatScreens[currentScreenIndex]
            loadScreen(activeWebView!!, screen)
            statusBar.text = formatStatusText(screen.displayTitle, "⏸ 一時停止中 (戻るで再開)")
        } else {
            // If prev is ready, swap immediately; otherwise wait
            if (prevReady) {
                doPreviousSwap()
            } else {
                handler.post(object : Runnable {
                    override fun run() {
                        if (!prevReady) {
                            handler.postDelayed(this, 200)
                            return
                        }
                        doPreviousSwap()
                    }
                })
            }
        }
    }

    private fun doPreviousSwap() {
        if (flatScreens.isEmpty()) return
        currentScreenIndex = (currentScreenIndex - 1 + flatScreens.size) % flatScreens.size
        val screen = flatScreens[currentScreenIndex]

        // WebViewローテーション: prev→active, active→next, next→prev
        val oldActive = activeWebView
        val oldNext = nextWebView
        val oldPrev = prevWebView
        activeWebView = oldPrev
        nextWebView = oldActive
        prevWebView = oldNext

        // Crossfade
        activeWebView?.apply {
            alpha = 0f
            visibility = View.VISIBLE
            animate().alpha(1f).setDuration(800).start()
        }
        oldActive?.animate()?.alpha(0f)?.setDuration(800)?.withEndAction {
            oldActive.visibility = View.INVISIBLE
        }?.start()

        addDebugLog("[PLAY] 戻る → ${currentScreenIndex + 1}/${flatScreens.size}: ${screen.displayName}")
        if (debugPage == 2) updateDebugContent()
        updateScreenStatusBar(screen)

        // nextReady is true (old active already had content)
        nextReady = true

        // Schedule next auto-advance
        scheduleAutoAdvance(screen)

        // Preload previous into recycled WebView
        prevReady = false
        val prevIdx = (currentScreenIndex - 1 + flatScreens.size) % flatScreens.size
        preloadScreen(prevWebView!!, flatScreens[prevIdx], isPrevPreload = true)
    }

    private fun togglePause() {
        if (!isPlaying) return

        isPaused = true
        val screen = flatScreens.getOrNull(currentScreenIndex)

        contentTimer?.let { handler.removeCallbacks(it) }
        countdownTimer?.let { handler.removeCallbacks(it) }
        statusBar.text = formatStatusText(screen?.displayTitle ?: "", "⏸ 一時停止中 (戻るで再開)")

        enableWebViewInteraction()
    }

    private fun resumePlayback() {
        if (!isPlaying || !isPaused) return

        isPaused = false
        val screen = flatScreens.getOrNull(currentScreenIndex) ?: return

        disableWebViewInteraction()
        updateScreenStatusBar(screen)
        scheduleAutoAdvance(screen)
        preloadBothDirections()
    }

    private fun enableWebViewInteraction() {
        touchOverlay.visibility = View.GONE
        pauseBorder.visibility = View.VISIBLE
        activeWebView?.apply {
            isFocusable = true
            isFocusableInTouchMode = true
            requestFocus()
        }
    }

    private fun disableWebViewInteraction() {
        touchOverlay.visibility = View.VISIBLE
        pauseBorder.visibility = View.GONE
        activeWebView?.apply {
            isFocusable = false
            isFocusableInTouchMode = false
            clearFocus()
        }
    }

    // =========================================================================
    // Screen List Overlay (リモコン上下で一覧表示・選択ジャンプ)
    // =========================================================================

    /** 一覧オーバーレイを開く（自動送りを一時停止） */
    private fun openScreenList() {
        if (!isPlaying || flatScreens.isEmpty()) return
        if (isScreenListMode) return

        isScreenListMode = true
        selectedListIndex = currentScreenIndex

        // 自動送り・カウントダウンを停止（オーバーレイ操作中はページが進まない）
        contentTimer?.let { handler.removeCallbacks(it) }
        countdownTimer?.let { handler.removeCallbacks(it) }

        renderScreenList()
        updatePreviewThumbnail()
        screenListOverlay.visibility = View.VISIBLE
        scheduleScreenListTimeout()
        addDebugLog("[LIST] 一覧表示 (現在 ${currentScreenIndex + 1}/${flatScreens.size})")
    }

    /** 選択カーソルを移動（循環）。操作のたびに無操作タイマーをリセット */
    private fun moveSelection(delta: Int) {
        if (flatScreens.isEmpty()) return
        selectedListIndex = (selectedListIndex + delta + flatScreens.size) % flatScreens.size
        renderScreenList()
        updatePreviewThumbnail()
        scheduleScreenListTimeout()
    }

    /** 無操作タイマーを再セット。期限到達で現在の選択行を確定して再生に戻る */
    private fun scheduleScreenListTimeout() {
        screenListTimeout?.let { handler.removeCallbacks(it) }
        screenListTimeout = Runnable {
            if (isScreenListMode) {
                addDebugLog("[LIST] 60秒無操作 → 選択確定")
                confirmSelection()
            }
        }.also { handler.postDelayed(it, SCREEN_LIST_TIMEOUT_MS) }
    }

    /** 選択中の画面へジャンプして通常再生を継続 */
    private fun confirmSelection() {
        screenListTimeout?.let { handler.removeCallbacks(it) }
        isScreenListMode = false
        screenListOverlay.visibility = View.GONE
        previewImageView.setImageDrawable(null)

        currentScreenIndex = selectedListIndex
        addDebugLog("[LIST] 選択ジャンプ → ${currentScreenIndex + 1}/${flatScreens.size}")
        displayCurrentScreen()
    }

    /** 一覧をキャンセルして現在ページの再生を再開 */
    private fun closeScreenList() {
        screenListTimeout?.let { handler.removeCallbacks(it) }
        isScreenListMode = false
        screenListOverlay.visibility = View.GONE
        previewImageView.setImageDrawable(null)
        addDebugLog("[LIST] 一覧キャンセル")

        if (isPlaying && !isPaused) {
            flatScreens.getOrNull(currentScreenIndex)?.let { screen ->
                updateScreenStatusBar(screen)
                scheduleAutoAdvance(screen)
            }
        }
    }

    /** 選択行を常に中央に置く7行窓（上3 / 選択 / 下3）を循環描画 */
    private fun renderScreenList() {
        val size = flatScreens.size
        if (size == 0) return

        val sb = StringBuilder()
        sb.append("画面一覧  ▲▼:選択  中央:決定  戻る:取消\n")
        sb.append("${selectedListIndex + 1} / $size\n")
        sb.append("─".repeat(22)).append("\n")

        val span = 3  // 上下に表示する行数
        for (offset in -span..span) {
            val idx = (selectedListIndex + offset + size) % size
            val screen = flatScreens[idx]
            val cursor = if (offset == 0) "▶ " else "   "
            val playing = if (idx == currentScreenIndex) "●" else " "
            sb.append("$cursor$playing ${idx + 1}. ${screen.displayTitle}\n")
        }

        screenListText.text = sb.toString().trimEnd()
    }

    /** 選択中画面のキャッシュサムネイルを右側に表示（未キャッシュ/webはNo Preview） */
    private fun updatePreviewThumbnail() {
        val screen = flatScreens.getOrNull(selectedListIndex) ?: return
        previewLabel.text = "${selectedListIndex + 1}. ${screen.displayTitle}"

        val thumbFile = findThumbnailFile(screen)
        if (thumbFile == null) {
            previewImageView.setImageDrawable(null)
            previewImageView.background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(2, 0xFF444444.toInt())
            }
            previewLabel.text = "${selectedListIndex + 1}. ${screen.displayTitle}\n(No Preview)"
            return
        }

        val token = ++thumbnailToken
        coroutineScope.launch(Dispatchers.IO) {
            val bmp = try {
                decodeSampledBitmap(thumbFile, resources.displayMetrics.widthPixels / 2)
            } catch (_: Exception) { null }
            withContext(Dispatchers.Main) {
                // 選択が動いていなければ反映（高速移動時の取り違え防止）
                if (token == thumbnailToken && isScreenListMode) {
                    if (bmp != null) {
                        previewImageView.setImageBitmap(bmp)
                    } else {
                        previewImageView.setImageDrawable(null)
                        previewLabel.text = "${selectedListIndex + 1}. ${screen.displayTitle}\n(No Preview)"
                    }
                }
            }
        }
    }

    /** 画面に対応するサムネイルJPEG（キャッシュ済みPDF1ページ目）を探す。webや未キャッシュはnull */
    private fun findThumbnailFile(screen: FlatScreen): File? {
        return when (screen.type) {
            "pdf" -> pdfRenderCacheManager.getCachedImagePaths(screen.contentId)?.firstOrNull()
            "dual_pdf" -> pdfRenderCacheManager
                .getCachedDualImagePaths(screen.contentId, screen.rightContentId)?.firstOrNull()
            "web" -> screen.url?.let { pdfRenderCacheManager.getWebThumbnail(it) }
            else -> null
        }?.takeIf { it.exists() }
    }

    /** JPEGをreqWidth程度にダウンサンプルしてデコード */
    private fun decodeSampledBitmap(file: File, reqWidth: Int): android.graphics.Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        if (reqWidth > 0 && bounds.outWidth > reqWidth) {
            var w = bounds.outWidth
            while (w / 2 >= reqWidth) {
                sample *= 2
                w /= 2
            }
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    // =========================================================================
    // View Setup
    // =========================================================================

    private fun setupFullScreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.insetsController?.let { controller ->
            controller.hide(WindowInsets.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun setupViews() {
        containerLayout = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFF000000.toInt())
        }

        webViewA = createWebView()
        webViewB = createWebView()
        webViewC = createWebView()

        containerLayout.addView(webViewA)
        containerLayout.addView(webViewB)
        containerLayout.addView(webViewC)

        // Transparent touch overlay for gesture detection
        touchOverlay = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setOnTouchListener { _, event ->
                gestureDetector.onTouchEvent(event)
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        longPressResetRunnable?.let { handler.removeCallbacks(it) }
                        longPressResetRunnable = Runnable { resetToSetup() }
                        handler.postDelayed(longPressResetRunnable!!, LONG_PRESS_RESET_MS)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        longPressResetRunnable?.let { handler.removeCallbacks(it) }
                    }
                }
                true
            }
        }
        containerLayout.addView(touchOverlay)

        // Pause indicator: red border
        pauseBorder = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(8, Color.GRAY)
            }
            visibility = View.GONE
            isClickable = false
            isFocusable = false
        }
        containerLayout.addView(pauseBorder)

        // Status bar overlay
        statusBar = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.BOTTOM
            }
            setBackgroundColor(0xCC000000.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            setPadding(16, 8, 16, 8)
            visibility = View.VISIBLE
            setOnClickListener {
                if (isPaused) resumePlayback()
            }
        }
        containerLayout.addView(statusBar)

        // Debug overlay: half-width, full-height, top-right
        val displayMetrics = resources.displayMetrics
        val halfWidth = displayMetrics.widthPixels / 2
        debugTextView = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                halfWidth,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.END
            }
            setBackgroundColor(0xCC000000.toInt())
            setTextColor(0xFF00FF00.toInt()) // Green text
            textSize = 13f
            setPadding(16, 12, 16, 12)
            text = "[1/4] デバッグログ (下ボタンで切替)\n起動中..."
            isClickable = false
            isFocusable = false
            visibility = if (debugPage == 0) View.GONE else View.VISIBLE
        }
        containerLayout.addView(debugTextView)

        // 画面一覧オーバーレイ（最前面・初期非表示）
        screenListOverlay = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xEE000000.toInt())
            visibility = View.GONE
            isClickable = true  // 背面WebViewへのタッチ透過を防ぐ
        }

        // 左：リスト（緑文字・等幅・縦中央）
        screenListText = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                displayMetrics.widthPixels / 2,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            }
            typeface = Typeface.MONOSPACE
            setTextColor(0xFF00FF00.toInt())
            textSize = 16f
            setPadding(24, 24, 24, 24)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        screenListOverlay.addView(screenListText)

        // 右：サムネイルプレビュー
        previewImageView = ImageView(this).apply {
            val w = displayMetrics.widthPixels / 2
            layoutParams = FrameLayout.LayoutParams(
                w - 48,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
                setMargins(0, 48, 24, 96)
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(2, 0xFF444444.toInt())
            }
        }
        screenListOverlay.addView(previewImageView)

        // 右下：プレビューのラベル
        previewLabel = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                displayMetrics.widthPixels / 2,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.END or android.view.Gravity.BOTTOM
                setMargins(0, 0, 24, 32)
            }
            setTextColor(0xFFCCCCCC.toInt())
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setPadding(24, 8, 24, 8)
        }
        screenListOverlay.addView(previewLabel)

        containerLayout.addView(screenListOverlay)

        setContentView(containerLayout)

        activeWebView = webViewA
        nextWebView = webViewB
        prevWebView = webViewC
        webViewB.visibility = View.INVISIBLE
        webViewC.visibility = View.INVISIBLE
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val wv = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = true  // CMapファイル読み込み用
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                useWideViewPort = true
                loadWithOverviewMode = false
                builtInZoomControls = false
                displayZoomControls = false
                setSupportZoom(false)
                textZoom = 100
                userAgentString = settings.userAgentString.replace(
                    Regex("\\bwv\\b"), ""
                ) + " Chrome/120.0.0.0"
            }
            setInitialScale(100)
            isFocusable = false
            isFocusableInTouchMode = false
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            setBackgroundColor(0xFF000000.toInt())
        }
        wv.addJavascriptInterface(PdfJsInterface(wv), "SignageInterface")
        return wv
    }

    // =========================================================================
    // Playback Logic
    // =========================================================================

    private suspend fun startPlayback() {
        addDebugLog("[PLAY] スケジュール取得中...")
        withContext(Dispatchers.Main) { statusBar.text = "スケジュール取得中..." }
        val schedule = scheduleManager.loadSchedule()
        if (schedule == null || schedule.playlist.isEmpty()) {
            val reason = if (schedule == null) "取得失敗" else "コンテンツなし(0件)"
            addDebugLog("[PLAY] $reason → 60秒後にリトライ")
            withContext(Dispatchers.Main) { statusBar.text = "$reason (60秒後にリトライ)" }
            showStandby()
            startScheduleRetry()
            return
        }

        val types = schedule.playlist.groupBy { it.type }.mapValues { it.value.size }
        addDebugLog("[PLAY] スケジュール取得OK: ${schedule.playlist.size}件 $types")
        schedule.playlist.filter { it.type == "pdf_folder" }.forEach { pf ->
            addDebugLog("[PLAY] pdf_folder: name=${pf.name} smbPath=${pf.smbPath} firstPage=${pf.firstPageOnly}")
        }
        withContext(Dispatchers.Main) { statusBar.text = "スケジュール: ${schedule.playlist.size}件 PDFダウンロード中..." }

        pdfCacheManager.downloadAll(schedule.playlist)
        val activeIds = schedule.playlist.filter { it.type == "pdf" }.map { it.contentId }.toSet()
        val activeWebKeys = schedule.playlist.filter { it.type == "web" }
            .mapNotNull { it.url?.let(pdfRenderCacheManager::webCacheKey) }.toSet()
        pdfCacheManager.cleanupUnused(activeIds)
        pdfRenderCacheManager.cleanupUnused(activeIds, activeWebKeys)

        // フラットスクリーンリスト構築
        withContext(Dispatchers.Main) { statusBar.text = "フォルダ同期中..." }
        val screens = withContext(Dispatchers.IO) { buildFlatScreens(schedule.playlist) }
        withContext(Dispatchers.Main) {
            flatScreens = screens
            currentScreenIndex = 0
            addDebugLog("[PLAY] フラットリスト構築完了: ${screens.size}画面")
        }

        if (flatScreens.isEmpty()) {
            addDebugLog("[PLAY] 表示画面なし → 60秒後にリトライ")
            withContext(Dispatchers.Main) { statusBar.text = "表示コンテンツなし (60秒後にリトライ)" }
            showStandby()
            startScheduleRetry()
            return
        }

        if (!scheduleManager.isWithinPlayTime()) {
            addDebugLog("[PLAY] 再生時間外 (${schedule.playStartTime}-${schedule.playEndTime}) → standby")
            withContext(Dispatchers.Main) { statusBar.text = "再生時間外 (${schedule.playStartTime}-${schedule.playEndTime})" }
            showStandby()
            startTimeCheck()
            return
        }

        addDebugLog("[PLAY] 再生開始")
        displayCurrentScreen()
    }

    /**
     * フラットスクリーンリストを構築する。
     * メインプレイリストのpdf_folderをSMBキャッシュから展開し、
     * デュアルページのペアリングも行う。
     */
    private fun buildFlatScreens(playlist: List<PlaylistItem>): List<FlatScreen> {
        val screens = mutableListOf<FlatScreen>()

        playlist.forEachIndexed { mainIdx, item ->
            when (item.type) {
                "web" -> {
                    screens.add(FlatScreen.fromWeb(item, mainIdx))
                }
                "pdf" -> {
                    val sourceFile = pdfCacheManager.getCachedPdfPath(item.contentId)
                    screens.add(FlatScreen.fromPdf(item, sourceFile, mainIdx))
                }
                "pdf_folder" -> {
                    // SMBキャッシュからサブプレイリストを構築
                    val subItems = smbPdfManager?.buildPlaylistFromCache(item)
                    if (subItems.isNullOrEmpty()) {
                        // キャッシュなし → SMB同期を試行
                        try {
                            val manager = smbPdfManager ?: return@forEachIndexed
                            val (syncedItems, _) = kotlinx.coroutines.runBlocking {
                                manager.syncFolder(item) { /* progress ignored */ }
                            }
                            addFolderScreens(screens, syncedItems, item, mainIdx)
                        } catch (e: Exception) {
                            addDebugLog("[SMB] ${item.name} 同期失敗: ${e.message}")
                        }
                    } else {
                        addFolderScreens(screens, subItems, item, mainIdx)
                    }
                }
            }
        }
        return screens
    }

    /** pdf_folderの子PDFをFlatScreenリストに追加（デュアルペアリング含む） */
    private fun addFolderScreens(
        screens: MutableList<FlatScreen>,
        subItems: List<PlaylistItem>,
        parentFolder: PlaylistItem,
        mainIdx: Int
    ) {
        val isFirstPageOnly = parentFolder.firstPageOnly == true
        var i = 0
        while (i < subItems.size) {
            val subItem = subItems[i]
            val sourceFile = smbPdfManager?.getLocalPdfFile(subItem.contentId)
            if (sourceFile == null) {
                i++
                continue
            }

            // デュアルペアリング: firstPageOnly + 縦長 + 次も縦長
            if (isFirstPageOnly && subItem.isPortrait && i + 1 < subItems.size) {
                val nextSub = subItems[i + 1]
                if (nextSub.isPortrait) {
                    val rightFile = smbPdfManager?.getLocalPdfFile(nextSub.contentId)
                    if (rightFile != null) {
                        screens.add(FlatScreen.fromDualPdf(subItem, sourceFile, nextSub, rightFile, parentFolder, mainIdx))
                        i += 2
                        continue
                    }
                }
            }

            screens.add(FlatScreen.fromSubPdf(subItem, sourceFile, parentFolder, mainIdx))
            i++
        }
    }

    /** 現在のスクリーンを表示する */
    private fun displayCurrentScreen() {
        if (flatScreens.isEmpty()) return
        val screen = flatScreens[currentScreenIndex]

        addDebugLog("[PLAY] ${currentScreenIndex + 1}/${flatScreens.size}: [${screen.type}] ${screen.displayName}")
        if (debugPage == 2) updateDebugContent()

        isPlaying = true
        isPaused = false
        disableWebViewInteraction()

        // コンテンツロード
        loadScreen(activeWebView!!, screen)
        updateScreenStatusBar(screen)
        scheduleAutoAdvance(screen)
        preloadBothDirections()
    }

    /** スクリーンをWebViewにロードする */
    private fun loadScreen(webView: WebView, screen: FlatScreen) {
        webView.setInitialScale(100)
        when (screen.type) {
            "web" -> {
                webView.webViewClient = WebViewClient()
                webView.loadUrl(screen.url ?: return)
            }
            "pdf" -> {
                loadScreenPdf(webView, screen, preloadType = null)
            }
            "dual_pdf" -> {
                loadScreenDualPdf(webView, screen, preloadType = null)
            }
        }
    }

    /** PDF画面のロード（キャッシュチェック付き） */
    private fun loadScreenPdf(webView: WebView, screen: FlatScreen, preloadType: String?) {
        val sourceFile = screen.sourceFile ?: File("")
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels

        // レンダリングキャッシュチェック
        val cachedImages = pdfRenderCacheManager.getCachedImagePaths(screen.contentId)
        if (cachedImages != null && pdfRenderCacheManager.hasCachedRender(
                screen.contentId, sourceFile, screenW, screenH, screen.firstPageOnly)) {
            addDebugLog("[CACHE] キャッシュヒット: ${screen.displayName} (${cachedImages.size}画面)")
            loadCachedPdfViewer(webView, cachedImages, screen.pdfPageDuration ?: screen.durationSeconds, screen.firstPageOnly, preloadType)
            return
        }

        // 通常のPDF.jsフロー
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url?.contains("pdf-viewer.html") == true) {
                    loadPdfIntoViewer(webView, sourceFile, screen, preloadType)
                }
            }
        }
        webView.loadUrl("file:///android_asset/pdfjs/pdf-viewer.html")
    }

    /** デュアルPDF画面のロード（キャッシュチェック付き） */
    private fun loadScreenDualPdf(webView: WebView, screen: FlatScreen, preloadType: String?) {
        val leftFile = screen.sourceFile ?: File("")
        val rightFile = screen.rightSourceFile ?: File("")
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels

        // レンダリングキャッシュチェック
        val cachedImages = pdfRenderCacheManager.getCachedDualImagePaths(screen.contentId, screen.rightContentId)
        if (cachedImages != null && pdfRenderCacheManager.hasCachedDualRender(
                screen.contentId, screen.rightContentId, leftFile, rightFile, screenW, screenH)) {
            addDebugLog("[CACHE] デュアルPDF キャッシュヒット: ${screen.displayName}")
            loadCachedPdfViewer(webView, cachedImages, screen.durationSeconds, true, preloadType)
            return
        }

        // 通常のデュアルPDF.jsフロー
        webView.setInitialScale(100)
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url?.contains("pdf-viewer.html") == true) {
                    loadDualPdfIntoViewer(webView, screen)
                }
            }
        }
        webView.loadUrl("file:///android_asset/pdfjs/pdf-viewer.html")
    }

    /** デュアルPDFのBase64注入 */
    private fun loadDualPdfIntoViewer(webView: WebView, screen: FlatScreen) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val leftBytes = screen.sourceFile?.takeIf { it.exists() }?.readBytes()
                val rightBytes = screen.rightSourceFile?.takeIf { it.exists() }?.readBytes()

                if (leftBytes != null && rightBytes != null) {
                    val leftBase64 = Base64.encodeToString(leftBytes, Base64.NO_WRAP)
                    val rightBase64 = Base64.encodeToString(rightBytes, Base64.NO_WRAP)
                    withContext(Dispatchers.Main) {
                        webView.evaluateJavascript(
                            "setCaptureInfo(${screen.contentId}, true, ${screen.rightContentId});", null
                        )
                        webView.evaluateJavascript(
                            "loadDualFirstPages('$leftBase64', '$rightBase64');", null
                        )
                    }
                } else if (leftBytes != null) {
                    val base64 = Base64.encodeToString(leftBytes, Base64.NO_WRAP)
                    withContext(Dispatchers.Main) {
                        webView.evaluateJavascript("setCaptureInfo(${screen.contentId}, true);", null)
                        webView.evaluateJavascript("loadPdfBase64('$base64', 10, true);", null)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /** タイマーをスケジュールする */
    private fun scheduleAutoAdvance(screen: FlatScreen) {
        contentTimer?.let { handler.removeCallbacks(it) }
        if (screen.isAllPages) {
            // allPagesモード: onAllPagesCompletedで切替、安全弁タイマー
            val safetyDuration = ((screen.durationSeconds + 30) * 1000).toLong()
            contentTimer = Runnable {
                addDebugLog("[PDF] 安全弁タイマー発火")
                advanceToNext()
            }.also { handler.postDelayed(it, safetyDuration) }
        } else {
            val duration = (screen.durationSeconds * 1000).toLong()
            contentTimer = Runnable { advanceToNext() }
                .also { handler.postDelayed(it, duration) }
        }
    }

    // =========================================================================
    // Advance Logic (フラットスクリーン版)
    // =========================================================================

    private fun advanceToNext() {
        if (!isPlaying || isPaused) return
        if (isScreenListMode) return  // 一覧表示中はページを進めない
        if (flatScreens.isEmpty()) return

        // 再生時間外チェック
        if (!scheduleManager.isWithinPlayTime()) {
            addDebugLog("[PLAY] 再生時間外になった → standby")
            contentTimer?.let { handler.removeCallbacks(it) }
            countdownTimer?.let { handler.removeCallbacks(it) }
            isPlaying = false
            statusBar.text = "再生時間外 (${scheduleManager.playTimeRange})"
            showStandby()
            startTimeCheck()
            return
        }

        handler.post { doAdvance() }
    }

    private fun doAdvance() {
        if (!isPlaying || isPaused) return
        if (flatScreens.isEmpty()) return

        if (!nextReady) {
            handler.postDelayed({ doAdvance() }, 200)
            return
        }

        // インデックス進める
        currentScreenIndex = (currentScreenIndex + 1) % flatScreens.size
        val screen = flatScreens[currentScreenIndex]

        // WebViewローテーション: next→active, active→prev, prev→next
        val oldActive = activeWebView
        val oldNext = nextWebView
        val oldPrev = prevWebView
        activeWebView = oldNext
        prevWebView = oldActive
        nextWebView = oldPrev

        // Crossfade
        activeWebView?.apply {
            alpha = 0f
            visibility = View.VISIBLE
            animate().alpha(1f).setDuration(800).start()
        }
        oldActive?.animate()?.alpha(0f)?.setDuration(800)?.withEndAction {
            oldActive.visibility = View.INVISIBLE
        }?.start()

        addDebugLog("[PLAY] advance → ${currentScreenIndex + 1}/${flatScreens.size}: ${screen.displayName}")
        if (debugPage == 2) updateDebugContent()
        updateScreenStatusBar(screen)

        // prevReady is true (old active already had content)
        prevReady = true

        // Schedule next auto-advance
        scheduleAutoAdvance(screen)

        // Preload next into recycled WebView
        nextReady = false
        val nextIdx = (currentScreenIndex + 1) % flatScreens.size
        preloadScreen(nextWebView!!, flatScreens[nextIdx], isPrevPreload = false)
    }

    private fun preloadBothDirections() {
        if (flatScreens.isEmpty()) return
        nextReady = false
        prevReady = false
        val nextIdx = (currentScreenIndex + 1) % flatScreens.size
        val prevIdx = (currentScreenIndex - 1 + flatScreens.size) % flatScreens.size
        preloadScreen(nextWebView!!, flatScreens[nextIdx], isPrevPreload = false)
        preloadScreen(prevWebView!!, flatScreens[prevIdx], isPrevPreload = true)
    }

    /** スクリーンを先読みWebViewにロード */
    private fun preloadScreen(webView: WebView, screen: FlatScreen, isPrevPreload: Boolean) {
        val preloadType = if (isPrevPreload) "prev" else "next"
        webView.setInitialScale(100)
        when (screen.type) {
            "web" -> {
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        markPreloadReady(preloadType)
                    }
                }
                webView.loadUrl(screen.url ?: return)
            }
            "pdf" -> {
                loadScreenPdf(webView, screen, preloadType)
            }
            "dual_pdf" -> {
                loadScreenDualPdf(webView, screen, preloadType)
            }
        }
    }

    /** ステータスバーを更新 */
    private fun updateScreenStatusBar(screen: FlatScreen) {
        isAllPagesMode = screen.isAllPages
        pdfPageDurationSec = screen.pdfPageDuration ?: 10
        remainingSeconds = screen.durationSeconds
        startCountdown()
        maybeScheduleWebCapture(screen)
    }

    // =========================================================================
    // Web thumbnail capture
    // =========================================================================

    /**
     * webコンテンツ表示中、TTL切れのサムネイルを再キャプチャ予約する。
     * 表示直後はレンダリング未完了のため WEB_CAPTURE_DELAY_MS 待ってから
     * アクティブWebViewをソフトウェア描画してJPEG保存する。
     */
    private fun maybeScheduleWebCapture(screen: FlatScreen) {
        webCaptureRunnable?.let { handler.removeCallbacks(it) }
        webCaptureRunnable = null

        val url = screen.url
        if (screen.type != "web" || url.isNullOrEmpty()) return
        if (isScreenListMode) return
        if (pdfRenderCacheManager.hasFreshWebThumbnail(url, WEB_THUMB_TTL_MS)) return

        val targetIndex = currentScreenIndex
        val targetWebView = activeWebView ?: return
        val title = screen.displayTitle

        val runnable = Runnable {
            // 発火時に状態が変わっていないか再確認
            if (isScreenListMode) return@Runnable
            if (currentScreenIndex != targetIndex) return@Runnable
            if (activeWebView !== targetWebView) return@Runnable
            captureWebThumbnail(targetWebView, url, title)
        }
        webCaptureRunnable = runnable
        handler.postDelayed(runnable, WEB_CAPTURE_DELAY_MS)
    }

    /** アクティブWebViewを縮小ソフトウェア描画してJPEG保存 */
    private fun captureWebThumbnail(webView: WebView, url: String, title: String) {
        try {
            val w = webView.width
            val h = webView.height
            if (w <= 0 || h <= 0) return
            val tw = (w * WEB_THUMB_SCALE).toInt().coerceAtLeast(1)
            val th = (h * WEB_THUMB_SCALE).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(tw, th, Bitmap.Config.RGB_565)
            val canvas = Canvas(bitmap)
            canvas.scale(WEB_THUMB_SCALE, WEB_THUMB_SCALE)
            webView.draw(canvas)

            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val baos = java.io.ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
                    pdfRenderCacheManager.saveWebThumbnail(url, baos.toByteArray(), title, tw, th)
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    bitmap.recycle()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // =========================================================================
    // Content Loading
    // =========================================================================

    // --- Content Loading Helpers ---

    private fun loadCachedPdfViewer(
        webView: WebView,
        imagePaths: List<File>,
        duration: Int,
        firstPageOnly: Boolean,
        preloadType: String?
    ) {
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url?.contains("cached-pdf-viewer.html") == true) {
                    if (firstPageOnly || imagePaths.size == 1) {
                        webView.evaluateJavascript(
                            "loadCachedFirstPage('file://${imagePaths[0].absolutePath}');", null
                        )
                    } else {
                        val pathsJson = imagePaths.joinToString(",") { "\"${it.absolutePath}\"" }
                        webView.evaluateJavascript(
                            "loadCachedAllPages('[$pathsJson]', $duration, false, 1);", null
                        )
                    }
                    markPreloadReady(preloadType)
                }
            }
        }
        webView.loadUrl("file:///android_asset/pdfjs/cached-pdf-viewer.html")
    }

    private fun markPreloadReady(preloadType: String?) {
        when (preloadType) {
            "next" -> nextReady = true
            "prev" -> prevReady = true
        }
    }

    /** PDFのBase64注入 (FlatScreen版) */
    private fun loadPdfIntoViewer(webView: WebView, cachedFile: File, screen: FlatScreen, preloadType: String?) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val pdfBytes = if (cachedFile.exists()) {
                    cachedFile.readBytes()
                } else {
                    // サーバーPDFの場合ダウンロード試行
                    screen.item?.let { pdfCacheManager.downloadIfNeeded(it) }
                    if (cachedFile.exists()) cachedFile.readBytes() else null
                }

                if (pdfBytes != null) {
                    val base64 = Base64.encodeToString(pdfBytes, Base64.NO_WRAP)
                    val duration = screen.pdfPageDuration ?: 10
                    withContext(Dispatchers.Main) {
                        webView.evaluateJavascript(
                            "setCaptureInfo(${screen.contentId}, true);", null
                        )
                        webView.evaluateJavascript(
                            "loadPdfBase64('$base64', $duration, ${screen.firstPageOnly});", null
                        )
                        markPreloadReady(preloadType)
                    }
                } else {
                    withContext(Dispatchers.Main) { markPreloadReady(preloadType) }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) { markPreloadReady(preloadType) }
            }
        }
    }

    private fun showStandby() {
        activeWebView?.loadUrl("file:///android_asset/standby.html")
    }

    // --- Removed legacy sub-playlist functions ---
    // startPdfFolderPlayback, startSubPlaylist, waitAndSwapFirstSubPdf,
    // playNextSubPdf, calculateNextSubIndex, preloadNextSubPdf,
    // loadSubPdfPreload, loadPdfIntoViewerForSubPreload,
    // loadDualPdfContentForPreload, loadDualPdfIntoViewerForPreload,
    // advanceToNextSubPdf, playCurrentSubPdfAfterSwap,
    // advanceToNextMain, preloadPdfFolder,
    // loadContent, loadDualPdfContent, preloadContent, loadContentInternal,
    // loadCachedPdfViewerForSub, old loadPdfIntoViewer, old loadDualPdfIntoViewer
    // All replaced by flat screen architecture above.

    private fun startTimeCheck() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                // スケジュール更新はSignageServiceが管理、ここでは再生時間のみチェック
                if (scheduleManager.isWithinPlayTime()) {
                    addDebugLog("[TIME] 再生時間内になった → 再生開始")
                    displayCurrentScreen()
                } else {
                    handler.postDelayed(this, 60_000)
                }
            }
        }, 60_000)
    }

    /** スケジュール取得失敗/コンテンツなし時のリトライ（60秒間隔） */
    private fun startScheduleRetry() {
        val retryRunnable = object : Runnable {
            override fun run() {
                addDebugLog("[RETRY] スケジュール再取得中...")
                val self = this
                coroutineScope.launch {
                    try {
                        val schedule = scheduleManager.loadSchedule()
                        if (schedule != null && schedule.playlist.isNotEmpty()) {
                            addDebugLog("[RETRY] スケジュール取得成功: ${schedule.playlist.size}件 → 再生開始")
                            startPlayback()
                        } else {
                            val reason = if (schedule == null) "取得失敗" else "コンテンツなし"
                            addDebugLog("[RETRY] $reason → 60秒後にリトライ")
                            withContext(Dispatchers.Main) {
                                statusBar.text = "$reason (60秒後にリトライ)"
                            }
                            handler.postDelayed(self, 60_000)
                        }
                    } catch (e: Exception) {
                        addDebugLog("[RETRY] 例外: ${e.message}")
                        handler.postDelayed(self, 60_000)
                    }
                }
            }
        }
        handler.postDelayed(retryRunnable, 60_000)
    }

    // =========================================================================
    // Status Bar & Countdown
    // =========================================================================

    private fun formatStatusText(itemName: String, suffix: String): String {
        val pageInfo = if (totalPdfPages > 1) " ($currentPdfPage/$totalPdfPages)" else ""
        return "$itemName$pageInfo  |  $suffix"
    }

    private fun startCountdown() {
        countdownTimer?.let { handler.removeCallbacks(it) }
        countdownTimer = object : Runnable {
            override fun run() {
                remainingSeconds--
                if (remainingSeconds >= 0) {
                    val screen = flatScreens.getOrNull(currentScreenIndex)
                    val label = if (isAllPagesMode) "次のページまで" else "次の切替まで"
                    statusBar.text = formatStatusText(
                        screen?.displayTitle ?: "",
                        "$label: ${remainingSeconds}秒"
                    )
                    handler.postDelayed(this, 1000)
                }
            }
        }.also {
            handler.postDelayed(it, 1000)
        }
    }

    // =========================================================================
    // Reset to Setup
    // =========================================================================

    private fun resetToSetup() {
        isPlaying = false
        contentTimer?.let { handler.removeCallbacks(it) }
        countdownTimer?.let { handler.removeCallbacks(it) }
        pollingJob?.cancel()

        stopService(Intent(this, jp.co.tisa.signage_android.service.SignageService::class.java))

        val intent = Intent(this, jp.co.tisa.signage_android.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("show_settings", true)
        }
        startActivity(intent)
        finish()
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(updateLogReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(scheduleUpdateReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(heartbeatReceiver) } catch (_: Exception) {}
        isPlaying = false
        contentTimer?.let { handler.removeCallbacks(it) }
        countdownTimer?.let { handler.removeCallbacks(it) }
        longPressResetRunnable?.let { handler.removeCallbacks(it) }
        screenListTimeout?.let { handler.removeCallbacks(it) }
        pollingJob?.cancel()
        coroutineScope.cancel()
        webViewA.destroy()
        webViewB.destroy()
        webViewC.destroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            setupFullScreen()
        }
    }

    inner class PdfJsInterface(private val webView: WebView) {
        @JavascriptInterface
        fun onPageChanged(current: Int, total: Int) {
            handler.post {
                // 先読みWebViewからのレンダリング完了通知
                if (webView != activeWebView && !nextReady) {
                    nextReady = true
                    addDebugLog("[PRELOAD] 先読みレンダリング完了 (page $current/$total)")
                    return@post
                }
                if (webView != activeWebView && !prevReady) {
                    prevReady = true
                    return@post
                }
                if (webView != activeWebView) return@post

                currentPdfPage = current
                totalPdfPages = total

                if (isAllPagesMode) {
                    remainingSeconds = pdfPageDurationSec
                    countdownTimer?.let { handler.removeCallbacks(it) }
                    startCountdown()
                }

                val screen = flatScreens.getOrNull(currentScreenIndex)
                if (screen != null) {
                    val suffix = if (isPaused) {
                        "⏸ 一時停止中 (戻るで再開)"
                    } else {
                        val label = if (isAllPagesMode) "次のページまで" else "次の切替まで"
                        "$label: ${remainingSeconds}秒"
                    }
                    statusBar.text = formatStatusText(screen.displayTitle, suffix)
                }
            }
        }

        @JavascriptInterface
        fun onPdfViewerReady() {
            // PDF viewer is ready to receive data
        }

        /**
         * PDF.jsレンダリング完了後にcanvasキャプチャを受信して保存。
         * pdf-viewer.html の captureCurrentScreen() から呼ばれる。
         */
        @JavascriptInterface
        fun onPageRendered(contentId: Int, screenIndex: Int, totalScreens: Int, dataUrl: String, contentId2: Int) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val base64Data = dataUrl.substringAfter("base64,")
                    val jpegBytes = Base64.decode(base64Data, Base64.DEFAULT)

                    // ソースPDFファイルを特定（フラットスクリーンから）
                    val screen = flatScreens.getOrNull(currentScreenIndex)
                    val sourceFile = screen?.sourceFile
                        ?: smbPdfManager?.getLocalPdfFile(contentId)
                        ?: pdfCacheManager.getCachedPdfPath(contentId)

                    val firstPageOnly = screen?.firstPageOnly ?: false
                    val title = screen?.displayTitle ?: ""

                    val screenW = resources.displayMetrics.widthPixels
                    val screenH = resources.displayMetrics.heightPixels

                    // デュアル初ページの場合はcompositeキー
                    val cacheKey = if (contentId2 != 0) {
                        val sourceFile2 = smbPdfManager?.getLocalPdfFile(contentId2) ?: File("")
                        pdfRenderCacheManager.saveRenderedScreen(
                            "dual_${contentId}_${contentId2}",
                            screenIndex, totalScreens, jpegBytes,
                            sourceFile, screenW, screenH, true,
                            sourceFile2 = sourceFile2,
                            title = title
                        )
                        return@launch
                    } else {
                        contentId.toString()
                    }

                    pdfRenderCacheManager.saveRenderedScreen(
                        cacheKey, screenIndex, totalScreens, jpegBytes,
                        sourceFile, screenW, screenH, firstPageOnly,
                        title = title
                    )

                    withContext(Dispatchers.Main) {
                        if (screenIndex == totalScreens - 1) {
                            addDebugLog("[CACHE] レンダリングキャッシュ保存完了: id=$contentId (${totalScreens}画面)")
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        @JavascriptInterface
        fun onAllPagesCompleted() {
            handler.post {
                if (webView != activeWebView) return@post

                addDebugLog("[PDF] 全ページ表示完了 → 次のコンテンツへ")
                contentTimer?.let { handler.removeCallbacks(it) }
                countdownTimer?.let { handler.removeCallbacks(it) }
                advanceToNext()
            }
        }
    }
}
