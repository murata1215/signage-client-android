package jp.co.tisa.signage_android.player

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
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
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
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
import java.io.ByteArrayInputStream
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
    /** 日付フィルタでスキップされたファイルの直近ログ内容(重複ログ抑止用・v1.87) */
    private var lastSkipSignature: String? = null

    // 3-WebView architecture: active + next (preloaded) + prev (preloaded)
    private var activeWebView: WebView? = null
    private var nextWebView: WebView? = null
    private var prevWebView: WebView? = null

    /** createWebView()で決定した端末既定UA。YouTube画面から復元する際に使う(v1.85) */
    private var defaultUserAgent: String = ""
    /** YouTube疎通プローブの結果文字列(デバッグオーバーレイ表示用)(v1.85) */
    private var youtubeProbeResult: String = "未実施"
    /** WebView実装のパッケージ名・バージョン文字列(デバッグオーバーレイ表示用)(v1.85) */
    private var webViewImplInfo: String = "取得中"
    /** YouTube画面に初めて到達した時に疎通プローブを再実行したか(v1.89、起動直後はネットワーク未確立のことがあるため) */
    private var youtubeProbeRerunDone = false
    /** YouTube画面のネットワークエラー(サブリソース含む)の重複排除用セット(host+detail単位)(v1.89) */
    private val ytNetErrorHosts = LinkedHashSet<String>()
    /** ytNetErrorHostsに記録する上限件数。ローリングログ(20行)が埋まるのを防ぐ(v1.89) */
    private val YT_NET_LOG_MAX = 8

    private val handler = Handler(Looper.getMainLooper())
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var contentTimer: Runnable? = null
    private var countdownTimer: Runnable? = null
    private var pdfPageTimer: Runnable? = null  // allPagesのページ送り(Android主導)
    private var remainingSeconds: Int = 0
    private var currentPdfPage: Int = 0
    private var totalPdfPages: Int = 0
    private var isAllPagesMode: Boolean = false
    private var pdfPageDurationSec: Int = 10
    private var pollingJob: Job? = null
    private var smbSyncJob: Job? = null
    private val SMB_SYNC_INTERVAL_MS = 3 * 60 * 1000L  // SMBフォルダ再同期間隔(3分)
    private val SMB_SYNC_INITIAL_DELAY_MS = 5_000L     // 起動後 初回同期までの待ち(5秒)
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

        // Setup WebView proxy (before creating WebViews)
        setupWebViewProxy()

        // Setup views
        setupViews()

        // Initialize managers
        configManager = ConfigManager(this)

        // 未捕捉例外のクラッシュ記録+自動復帰(v1.88)。他の初期化より前に設置する
        installCrashHandler()

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

        // WebView実装バージョンを取得(デバッグオーバーレイ表示用)(v1.85)
        try {
            val pkg = WebViewCompat.getCurrentWebViewPackage(this)
            webViewImplInfo = if (pkg != null) "${pkg.packageName} ${pkg.versionName}" else "取得失敗"
        } catch (e: Exception) {
            webViewImplInfo = "取得失敗(${e.message})"
        }

        // YouTube関連ドメインへの疎通プローブ(起動時に1回)(v1.85)
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val result = serverClient.probeYoutubeConnectivity()
                withContext(Dispatchers.Main) {
                    youtubeProbeResult = result
                    addDebugLog("[YT] 疎通 $result")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    youtubeProbeResult = "失敗(${e.message})"
                }
            }
        }

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

    /**
     * 未捕捉例外(UIスレッドの例外含む)を検知してクラッシュ情報を記録し、
     * AlarmManagerで再起動を予約してからプロセスを終了する(v1.88)。
     * 一度落ちたら端末再起動まで止まったままだった問題への対処。
     * 60秒以内に連続でクラッシュした場合は再起動間隔を延ばし暴走ループを防ぐ。
     */
    private fun installCrashHandler() {
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            try {
                val frame = e.stackTrace.firstOrNull { it.className.startsWith("jp.co.tisa") }
                    ?: e.stackTrace.firstOrNull()
                val location = frame?.let { "${it.fileName}:${it.lineNumber}" } ?: "unknown"
                val info = "${e.javaClass.simpleName}: ${e.message} @ $location"
                val count = configManager.recordCrash(info)

                // 連続クラッシュ(60秒以内)が5回以上に達したら再起動間隔を延ばす(暴走ループ防止)
                val delayMs = if (count >= 5) 5 * 60_000L else 2_000L

                val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val restartIntent = Intent(this, jp.co.tisa.signage_android.MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                val pendingIntent = PendingIntent.getActivity(
                    this, 0, restartIntent, PendingIntent.FLAG_IMMUTABLE
                )
                // SCHEDULE_EXACT_ALARM権限が不要な非正確アラームで十分(数秒〜数分の遅延は許容)
                alarmManager.set(AlarmManager.RTC, System.currentTimeMillis() + delayMs, pendingIntent)
            } catch (_: Exception) {
                // クラッシュハンドラ自体が失敗しても、以降のkillProcessだけは必ず実行する
            }
            android.os.Process.killProcess(android.os.Process.myPid())
            kotlin.system.exitProcess(10)
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

    /** 日付フィルタで非表示になったファイルをログに出す（内容が変わった時のみ・Mainスレッドから呼ぶこと・v1.87） */
    private fun logDateSkippedIfChanged() {
        val skipped = smbPdfManager?.getDateSkipped().orEmpty()
        val sig = skipped.joinToString("|") { it.filename }
        if (sig == lastSkipSignature) return
        lastSkipSignature = sig
        if (skipped.isEmpty()) return
        val names = skipped.take(3).joinToString(", ") { it.filename.take(20) }
        val more = if (skipped.size > 3) " ほか${skipped.size - 3}件" else ""
        addDebugLog("[SMB] 期限外スキップ: ${skipped.size}件 ($names$more)")
    }

    /**
     * YouTube画面のネットワークエラー(サブリソース失敗含む)をログに出す(v1.89)。
     * onReceivedError/onReceivedHttpError/shouldInterceptRequestはワーカースレッドから
     * 呼ばれる可能性があるため、必ずhandler.post()でMainスレッド経由にする
     * (addDebugLog()はdebugPage==1のときTextViewを直接触るため。v1.87と同じ制約)。
     * host+詳細の組み合わせで重複除去し、最大YT_NET_LOG_MAX件までに絞ってログの氾濫を防ぐ。
     */
    private fun logYtNetError(detail: String) {
        handler.post {
            if (ytNetErrorHosts.size >= YT_NET_LOG_MAX && !ytNetErrorHosts.contains(detail)) return@post
            if (ytNetErrorHosts.add(detail)) {
                addDebugLog("[YT-NET] $detail")
            }
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

        val skipped = smbPdfManager?.getDateSkipped().orEmpty()
        if (skipped.isNotEmpty()) {
            val dateFmt = java.time.format.DateTimeFormatter.ofPattern("yyyy/M/d")
            sb.append("─".repeat(20)).append("\n")
            sb.append("非表示(期限切れ): ${skipped.size}件\n")
            skipped.take(8).forEach { s ->
                val name = if (s.filename.length > 30) s.filename.take(30) + "…" else s.filename
                val reason = if (s.isBeforeStart) "開始前" else "期限切れ"
                sb.append("  $name (${s.startDate.format(dateFmt)}〜${s.endDate.format(dateFmt)} $reason)\n")
            }
            if (skipped.size > 8) {
                sb.append("  ...ほか${skipped.size - 8}件\n")
            }
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

        // ── WebView / YouTube診断(v1.85) ──
        sb.append("${"─".repeat(20)}\n")
        sb.append("WebView: $webViewImplInfo\n")
        val uaDisplay = defaultUserAgent.takeLast(70)
        sb.append("UA: ...$uaDisplay\n")
        // 疎通対象が9項目(v1.89でdoubleclick等4件追加)になり1行に収まらないため、4項目ごとに改行する
        sb.append("YT疎通:\n")
        val probeItems = youtubeProbeResult.split(" ").filter { it.isNotBlank() }
        probeItems.chunked(4).forEach { chunk ->
            sb.append("  ${chunk.joinToString(" ")}\n")
        }

        // ── サーバー・画面 ──
        sb.append("${"─".repeat(20)}\n")
        val config = configManager.getConfig()
        sb.append("サーバー: ${config?.serverUrl ?: "未設定"}\n")
        val key = config?.clientKey ?: "未設定"
        val keyDisplay = if (key.length > 16) key.take(16) + "..." else key
        sb.append("クライアントキー: $keyDisplay\n")
        sb.append("更新チャネル: ${configManager.getLastUpdateChannel() ?: "未取得"}\n")
        val (attemptCode, attemptCount) = configManager.getUpdateAttempt()
        if (attemptCode >= 0 && attemptCount >= jp.co.tisa.signage_android.service.AppUpdateManager.MAX_INSTALL_ATTEMPTS) {
            sb.append("更新スキップ中: v$attemptCode (${attemptCount}回失敗)\n")
        }

        // ── 直近クラッシュ(v1.88): 未記録なら非表示 ──
        val (crashInfo, crashTime) = configManager.getLastCrash()
        if (crashInfo != null && crashTime > 0) {
            val crashTimeStr = java.text.SimpleDateFormat("MM/dd HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date(crashTime))
            sb.append("直近クラッシュ: $crashTimeStr ${crashInfo.take(60)}\n")
            sb.append("                (連続${configManager.getCrashCount()}回)\n")
        }

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

        // When paused (interactive mode), center button (or BACK) resumes
        if (isPaused) {
            return when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    resumePlayback()
                    true
                }
                // 戻るも再開として維持（キオスクで finish() に流れて終了させない）
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
        pdfPageTimer?.let { handler.removeCallbacks(it) }

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
        pdfPageTimer?.let { handler.removeCallbacks(it) }

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
        val oldScreen = flatScreens.getOrNull(currentScreenIndex)
        currentScreenIndex = (currentScreenIndex - 1 + flatScreens.size) % flatScreens.size
        val screen = flatScreens[currentScreenIndex]

        // WebViewローテーション: prev→active, active→next, next→prev
        val oldActive = activeWebView
        val oldNext = nextWebView
        val oldPrev = prevWebView
        activeWebView = oldPrev
        nextWebView = oldActive
        prevWebView = oldNext

        // YouTube: 旧activeが再生中なら停止(裏で音が鳴るのを防止)、新activeがyoutubeなら再生開始
        if (oldScreen?.type == "youtube") ytStop(oldActive)
        if (screen.type == "youtube") ytPlay(activeWebView)

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
        startPdfPageRotation(screen)

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
        pdfPageTimer?.let { handler.removeCallbacks(it) }
        statusBar.text = formatStatusText(screen?.displayTitle ?: "", "⏸ 一時停止中 (中央ボタンで再開)")

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
        pdfPageTimer?.let { handler.removeCallbacks(it) }

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

    /** 選択行を常に中央に置く7行窓（上3 / 選択 / 下3）を循環描画。画面数が7以下なら全件を1回ずつ表示 */
    private fun renderScreenList() {
        val size = flatScreens.size
        if (size == 0) return

        val sb = StringBuilder()
        sb.append("画面一覧  ▲▼:選択  中央:決定  戻る:取消\n")
        sb.append("${selectedListIndex + 1} / $size\n")
        sb.append("─".repeat(22)).append("\n")

        val span = 3                        // 選択行の上下に表示する行数
        val windowRows = span * 2 + 1       // 窓の最大行数(7)
        // size <= windowRows のときは循環せず全件を1回ずつ（同じ画面が重複表示されるのを防ぐ）。
        // size > windowRows のときのみ7行窓を循環させる。floorModで負インデックスを防止(v1.88)。
        val indices = if (size <= windowRows) {
            (0 until size).toList()
        } else {
            (-span..span).map { Math.floorMod(selectedListIndex + it, size) }
        }

        for (idx in indices) {
            val screen = flatScreens[idx]
            val cursor = if (idx == selectedListIndex) "▶ " else "   "
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

    /**
     * WebViewのプロキシ設定。社内プロキシを経由しつつ、
     * ローカルIPや社内ドメインはバイパスする。
     */
    private fun setupWebViewProxy() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            addDebugLog("[PROXY] ProxyController not supported on this device")
            return
        }
        try {
            val proxyConfig = ProxyConfig.Builder()
                .addProxyRule("210.175.128.100:8080")
                // ローカルIPレンジをバイパス
                .addBypassRule("10.*")
                .addBypassRule("172.16.*")
                .addBypassRule("172.17.*")
                .addBypassRule("172.18.*")
                .addBypassRule("172.19.*")
                .addBypassRule("172.20.*")
                .addBypassRule("172.21.*")
                .addBypassRule("172.22.*")
                .addBypassRule("172.23.*")
                .addBypassRule("172.24.*")
                .addBypassRule("172.25.*")
                .addBypassRule("172.26.*")
                .addBypassRule("172.27.*")
                .addBypassRule("172.28.*")
                .addBypassRule("172.29.*")
                .addBypassRule("172.30.*")
                .addBypassRule("172.31.*")
                .addBypassRule("192.168.*")
                .addBypassRule("localhost")
                .addBypassRule("127.0.0.1")
                // 社内ドメインをバイパス
                .addBypassRule("*.atg.co.jp")
                .addBypassRule("*.tisaweb.or.jp")
                .addBypassRule("*.internal.*")
                .build()
            ProxyController.getInstance().setProxyOverride(
                proxyConfig,
                { it.run() },  // executor: run on caller thread
                { addDebugLog("[PROXY] WebView proxy configured") }
            )
        } catch (e: Exception) {
            addDebugLog("[PROXY] Failed to set WebView proxy: ${e.message}")
        }
    }

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
                setStroke(24, Color.GRAY)
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
            // density非依存の物理px指定。sp指定だと高密度STB(density2.0)で約2倍に
            // 大きくなるため、画面高さ基準でLinux相当の絶対サイズに揃える。
            setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_PX,
                resources.displayMetrics.heightPixels * 0.015f
            )
            setPadding(16, 6, 16, 6)
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
                val computedUa = settings.userAgentString.replace(
                    Regex("\\bwv\\b"), ""
                ) + " Chrome/120.0.0.0"
                userAgentString = computedUa
                // YouTube画面から復元する際に使う既定UA(v1.85)。web/PDF画面の挙動は変えないため
                // 既存の計算式(壊れているが影響範囲不明のため温存)をそのまま保持する。
                defaultUserAgent = computedUa
                mediaPlaybackRequiresUserGesture = false  // YouTube等の自動再生を許可(v1.82)
            }
            setInitialScale(100)
            isFocusable = false
            isFocusableInTouchMode = false
            webViewClient = object : WebViewClient() {
                override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                    val crashed = detail?.didCrash() == true
                    addDebugLog("[WEBVIEW] Render process gone (crashed=$crashed) - recovering...")
                    handleWebViewCrash(view)
                    return true
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    // ERRORレベルのみ拾う(低ノイズ)。YouTube以外の画面のJSエラー切り分けにも有用(v1.84)
                    if (consoleMessage?.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                        addDebugLog(
                            "[JS-ERR] ${consoleMessage.message()} " +
                                "(${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})"
                        )
                    }
                    return true
                }
            }
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
            logDateSkippedIfChanged()
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
        configManager.resetCrashCount()  // 再生に到達 = 正常復帰とみなす(v1.88)
        displayCurrentScreen()
        startSmbFolderSync()
    }

    /**
     * フラットスクリーンリストを構築する。
     * メインプレイリストのpdf_folderをSMBキャッシュから展開し、
     * デュアルページのペアリングも行う。
     */
    private fun buildFlatScreens(playlist: List<PlaylistItem>): List<FlatScreen> {
        val screens = mutableListOf<FlatScreen>()
        smbPdfManager?.resetDateSkipped()

        playlist.forEachIndexed { mainIdx, item ->
            when (item.type) {
                "web" -> {
                    screens.add(FlatScreen.fromWeb(item, mainIdx))
                }
                "pdf" -> {
                    val sourceFile = pdfCacheManager.getCachedPdfPath(item.contentId)
                    screens.add(FlatScreen.fromPdf(item, sourceFile, mainIdx))
                }
                "youtube" -> {
                    val vid = extractYoutubeId(item.url ?: "")
                    if (vid == null) {
                        addDebugLog("[YT] URL解析失敗: ${item.name} url=${item.url} → スキップ")
                    } else {
                        screens.add(FlatScreen.fromYoutube(item, vid, mainIdx))
                    }
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

            // .txt由来のweb画面（URL表示）はそのまま追加（デュアルペアリング対象外）
            if (subItem.type == "web") {
                screens.add(FlatScreen.fromWeb(subItem, mainIdx))
                i++
                continue
            }

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

    // =========================================================================
    // SMB folder periodic re-sync
    // =========================================================================

    /**
     * 一定間隔でSMB共有フォルダを再同期し、新規/削除されたPDFや
     * 命名規約の日付フィルタ変化をフラットリストへ反映する。
     * 再生中の画面は維持し、next/prevのみ先読みし直すのでちらつかない。
     */
    private fun startSmbFolderSync() {
        smbSyncJob?.cancel()
        val manager = smbPdfManager ?: return
        smbSyncJob = coroutineScope.launch {
            delay(SMB_SYNC_INITIAL_DELAY_MS)  // 起動直後の初回同期(表示の落ち着き待ち程度)
            while (isActive) {
                if (isPlaying && !isPaused && !isScreenListMode) {
                    val items = scheduleManager.playlist
                    val folders = items.filter { it.type == "pdf_folder" }
                    if (folders.isNotEmpty()) {
                        try {
                            val newScreens = withContext(Dispatchers.IO) {
                                for (f in folders) {
                                    try { manager.syncFolder(f) { } } catch (_: Exception) {}
                                }
                                buildFlatScreens(items)
                            }
                            applyRefreshedScreens(newScreens)
                            logDateSkippedIfChanged()
                        } catch (e: Exception) {
                            addDebugLog("[SMB] 定期同期エラー: ${e.message}")
                        }
                    }
                }
                delay(SMB_SYNC_INTERVAL_MS)
            }
        }
    }

    /** 再同期結果を再生中断なしで反映する（Mainスレッド） */
    private fun applyRefreshedScreens(newScreens: List<FlatScreen>) {
        if (newScreens.isEmpty()) return
        if (!isPlaying || isPaused || isScreenListMode) return
        if (sameScreens(flatScreens, newScreens)) return  // 変更なし: 先読み無駄打ち防止

        val current = flatScreens.getOrNull(currentScreenIndex)
        flatScreens = newScreens
        // 現在表示中の画面を新リスト内で同定して位置を維持
        val newIdx = if (current != null) newScreens.indexOfFirst { sameScreen(it, current) } else -1
        currentScreenIndex = if (newIdx >= 0) newIdx
            else currentScreenIndex.coerceIn(0, newScreens.size - 1)

        addDebugLog("[SMB] フォルダ更新反映: ${newScreens.size}画面 (index=${currentScreenIndex + 1})")
        if (debugPage == 2) updateDebugContent()

        // activeはそのまま、隣接のみ新リストで先読みし直す
        preloadBothDirections()
    }

    private fun screenKey(s: FlatScreen): String =
        if (s.type == "web") "web:${s.url}" else "${s.type}:${s.contentId}:${s.rightContentId}"
    private fun sameScreen(a: FlatScreen, b: FlatScreen): Boolean = screenKey(a) == screenKey(b)
    private fun sameScreens(a: List<FlatScreen>, b: List<FlatScreen>): Boolean =
        a.size == b.size && a.indices.all { screenKey(a[it]) == screenKey(b[it]) }

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
        startPdfPageRotation(screen)
        preloadBothDirections()
    }

    /**
     * web画面を物理解像度幅でレイアウトさせるための初期スケール。
     * Android WebViewは device-width = 物理px ÷ density となり、高密度STBでは
     * ページが「狭い画面」とみなして文字が大きくなる。density打ち消し(100/density)で
     * 物理解像度に1:1レイアウトさせ、Linux版同様に横いっぱい表示する。
     */
    private val webInitialScale: Int
        get() = (100f / resources.displayMetrics.density).toInt().coerceAtLeast(1)

    /**
     * web画面のビューポートを物理解像度幅に強制し、densityに依存せずデスクトップ幅で
     * レイアウトさせる。onPageFinishedで注入。
     */
    private fun injectWideViewport(view: WebView?) {
        val w = resources.displayMetrics.widthPixels
        view?.evaluateJavascript(
            "(function(){" +
                "var m=document.querySelector('meta[name=\"viewport\"]');" +
                "if(!m){m=document.createElement('meta');m.setAttribute('name','viewport');" +
                "(document.head||document.documentElement).appendChild(m);}" +
                "m.setAttribute('content','width=$w');" +
                "})();",
            null
        )
    }

    // =========================================================================
    // Web auto-fit scale (v1.80)
    // 固定幅レガシー画面(intramart等)が左上に小さく表示される問題への対応。
    // オプトイン方式: 許可リストに載ったURLのみJSを注入し、他ページは完全に無変更。
    // =========================================================================

    /** 自動フィット対象URL（部分一致）。空 + APPLY_TO_ALL=false なら機能OFF */
    private val WEB_AUTOFIT_URL_PATTERNS = listOf("dev-lafit20.internal.tisaweb.or.jp")
    /** true にすると全web画面に適用（既定はfalse＝許可リストのみ） */
    private val WEB_AUTOFIT_APPLY_TO_ALL = false
    /** コンテンツ右端がビューポートのこの割合未満なら拡大 */
    private val WEB_AUTOFIT_THRESHOLD = 0.95f
    /** 拡大の上限倍率 */
    private val WEB_AUTOFIT_MAX_ZOOM = 3.0f
    /** 右端に残す安全マージン（1.0=ぴったり）。丸め誤差・スクロールバー対策 */
    private val WEB_AUTOFIT_SAFETY = 0.99f
    /** zoom適用後の検証・自己補正の最大回数 */
    private val WEB_AUTOFIT_VERIFY_PASSES = 2
    /** 測定タイミング(ms)。ページ側 init() の実行待ちのため複数回試行 */
    private val WEB_AUTOFIT_DELAYS_MS = longArrayOf(500L, 2000L)

    /**
     * 許可リストに一致するURLのみ、遅延測定で自動フィット拡大を実行する。
     * 一致しなければ何もしない（既存ページへの影響ゼロを保証）。
     */
    private fun scheduleAutoFit(view: WebView?, url: String?) {
        if (view == null) return
        val target = url ?: return
        val allowed = WEB_AUTOFIT_APPLY_TO_ALL ||
            WEB_AUTOFIT_URL_PATTERNS.any { target.contains(it) }
        if (!allowed) return
        for (delay in WEB_AUTOFIT_DELAYS_MS) {
            handler.postDelayed({ injectAutoFitScale(view) }, delay)
        }
    }

    /**
     * 実表示コンテンツの右端座標を測定し、ビューポート幅に対して小さければ
     * body.style.zoom で拡大する。CSS zoomは文書原点(0,0)基準で拡大するため、
     * 判定・倍率計算は「幅」ではなく「右端座標(maxRight)」を基準にする
     * (幅基準だとminLeft分だけ右にはみ出すバグがあったため v1.81 で修正)。
     * さらにzoom適用後に再測定し、はみ出していれば自己補正する(最大
     * WEB_AUTOFIT_VERIFY_PASSES回)。vh/vw等ビューポート単位はzoomの影響を
     * 受けないため、zoom前にビューポート高さ相当の要素を検出して
     * 物理高さを維持するよう個別に補正する(下部が切れるのを防止)。
     */
    private fun injectAutoFitScale(view: WebView?) {
        view?.evaluateJavascript(
            "(function(){" +
                "try{" +
                "function reset(){" +
                "  document.body.style.zoom='';" +
                "  var prev=document.querySelectorAll('[data-sig-vh]');" +
                "  for(var i=0;i<prev.length;i++){" +
                "    prev[i].style.height=prev[i].getAttribute('data-sig-vh');" +
                "    prev[i].removeAttribute('data-sig-vh');" +
                "  }" +
                "}" +
                // 実表示コンテンツの左右端(文書座標)を測定
                "function measure(){" +
                "  var minLeft=Infinity,maxRight=-Infinity;" +
                "  function consider(r){" +
                "    if(r.width>0&&r.height>0){" +
                "      var l=r.left+window.pageXOffset;" +
                "      var rr=r.right+window.pageXOffset;" +
                "      if(l<minLeft)minLeft=l;" +
                "      if(rr>maxRight)maxRight=rr;" +
                "    }" +
                "  }" +
                "  var tw=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,null,false);" +
                "  var n;" +
                "  while(n=tw.nextNode()){" +
                "    if(n.nodeValue&&n.nodeValue.trim().length>0&&n.parentElement){" +
                "      consider(n.parentElement.getBoundingClientRect());" +
                "    }" +
                "  }" +
                "  var media=document.querySelectorAll('img,svg,canvas,video');" +
                "  for(var j=0;j<media.length;j++){consider(media[j].getBoundingClientRect());}" +
                "  if(!isFinite(minLeft)||!isFinite(maxRight))return null;" +
                "  return {left:minLeft,right:maxRight};" +
                "}" +
                // zoom適用 + vh相当要素の物理高さ補正(下部切れ防止)
                "function apply(z){" +
                "  reset();" +
                "  var vh=window.innerHeight;" +
                "  var all=document.body.querySelectorAll('*');" +
                "  var targets=[];" +
                "  for(var k=0;k<all.length;k++){" +
                "    var el=all[k];" +
                "    var h=el.getBoundingClientRect().height;" +
                "    if(h>=vh*0.85&&h<=vh*1.15){targets.push([el,h]);}" +
                "  }" +
                "  document.body.style.zoom=z;" +
                "  for(var m=0;m<targets.length;m++){" +
                "    var el2=targets[m][0];var h2=targets[m][1];" +
                "    el2.setAttribute('data-sig-vh',el2.style.height||'');" +
                "    el2.style.height=(h2/z)+'px';" +
                "  }" +
                "}" +
                "reset();" +
                "var avail=document.documentElement.clientWidth||window.innerWidth;" +
                "var m0=measure();" +
                "if(!m0){return 'nocontent';}" +
                "if(m0.right>=avail*$WEB_AUTOFIT_THRESHOLD){" +
                "  return 'skip right0='+Math.round(m0.right)+' avail='+avail;" +
                "}" +
                "var z=Math.min(avail*$WEB_AUTOFIT_SAFETY/m0.right,$WEB_AUTOFIT_MAX_ZOOM);" +
                "apply(z);" +
                "var pass=0;" +
                "for(;pass<$WEB_AUTOFIT_VERIFY_PASSES;pass++){" +
                "  var mv=measure();" +
                "  if(!mv)break;" +
                "  if(mv.right<=avail*$WEB_AUTOFIT_SAFETY)break;" +
                "  z=z*(avail*$WEB_AUTOFIT_SAFETY)/mv.right;" +
                "  apply(z);" +
                "}" +
                "var mf=measure();" +
                "return 'zoom='+z.toFixed(2)+' right0='+Math.round(m0.right)+" +
                "' fit='+(mf?Math.round(mf.right):-1)+' avail='+avail+" +
                "' left='+Math.round(m0.left)+' pass='+pass;" +
                "}catch(e){return 'error:'+e.message;}" +
                "})();",
            { result ->
                val cleaned = result?.trim('"') ?: "null"
                addDebugLog("[WEBFIT] $cleaned")
            }
        )
    }

    // =========================================================================
    // YouTube playback (v1.82)
    // assets/pdf-viewer.html + PdfJsInterface と同じ「AndroidがJSを駆動する」方式。
    // 先読み時はautoplay=falseでロードのみ行い(裏で音が鳴るのを防止)、
    // アクティブ昇格時にytPlay()を呼んで再生開始する。
    // =========================================================================

    /** YouTube: 既定でミュート再生（サイネージ用途。音声不要かつ自動再生が確実） */
    private val YOUTUBE_MUTED = true
    /** YouTube: duration_seconds<=0 のとき動画の最後まで再生して次へ進む */
    private val YOUTUBE_ADVANCE_ON_END = true
    /**
     * YouTube: 最後まで再生モードの安全弁(秒)。onYoutubeEnded()が来なくてもこれを超えたら強制的に次へ。
     * ライブ配信(ENDEDが来ない)や再生時間不明のコンテンツが無限に居座らないための上限。
     * 通常運用ではライブは管理画面でduration_secondsを明示指定する想定だが、
     * 未指定/取得漏れ時のフェイルセーフとしてこの値を用いる。
     */
    private val YOUTUBE_MAX_DURATION_SEC = 3600
    /** YouTube: 先読み中に onYoutubeReady() が来ない場合に強制readyにするまでの時間(ms)(v1.84) */
    private val YOUTUBE_PRELOAD_READY_TIMEOUT_MS = 15000L

    /**
     * YouTube IFrame Player APIのorigin検証用ベースURL(エラー153対策、v1.83)。
     * v1.89: v1.85のIFrame APIラッパー・直接embed双方で152-4が解消しなかったため、
     * 広告関連リソース(doubleclick.net等)を読みに行かない youtube-nocookie.com への
     * 切替えを試す(YOUTUBE_USE_NOCOOKIE)。この定数自体はnocookie=false時のフォールバック値
     * として残す。
     */
    private val YOUTUBE_PLAYER_BASE_URL = "https://www.youtube.com"

    /**
     * YouTube: プライバシー強化ドメイン(youtube-nocookie.com)を使うか(v1.89)。
     * 152-4対策の第一候補。falseに戻せば従来のwww.youtube.comに即座に戻る。
     * loadDataWithBaseURLのbaseUrl・IFrame APIのorigin/host・直接embedフォールバックの
     * embedUrlは全てyoutubeEffectiveBaseUrl経由で参照するため、ここ1箇所の切替えで連動する。
     */
    private val YOUTUBE_USE_NOCOOKIE = true

    /** YOUTUBE_USE_NOCOOKIEに応じて実際に使うベースURL(v1.89) */
    private val youtubeEffectiveBaseUrl: String
        get() = if (YOUTUBE_USE_NOCOOKIE) "https://www.youtube-nocookie.com" else YOUTUBE_PLAYER_BASE_URL

    /**
     * YouTube画面専用のUser-Agent(v1.85→v1.89でDesktop UAに変更)。
     * createWebView()の既定UAは `settings.userAgentString.replace(Regex("wv"),"") + " Chrome/120.0.0.0"`
     * という式のため、Chrome/バージョン+Mobile Safari/バージョンの後ろにさらに
     * Chrome/120.0.0.0が連結され「Chrome/トークンが2個ある不正なUA」になってしまっている
     * (先頭に出るのは端末実機の古いChromiumバージョン)。v1.85ではこれを単一トークンの
     * 正規Mobile UAに直しただけだったが、152-x は「モバイルChromeでは埋め込み再生できない
     * のでYouTubeアプリで見て」という意味のエラーコードであるため、Mobile Safariトークンを
     * 含むUA自体が拒否の一因になっている可能性がある。端末は1920x1080のSTBでモバイル用途では
     * ないため、v1.89でDesktop UA(Mobileトークン無し)に変更する。
     * YouTube画面ではこのUAに一時的に差し替え、それ以外の画面
     * (intramart等の自動フィット拡大が前提の既存Web画面)には一切影響させない。
     */
    private val YOUTUBE_USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36"

    /** YouTube: 再生開始watchdogがstalledを検知した際、直接embedページで代替再生する際の表示秒数(v1.85) */
    private val YOUTUBE_FALLBACK_DURATION_SEC = 60

    /** YouTube: 直接embedフォールバック実施済みを示すWebView.tagの値(v1.85) */
    private val YT_FALLBACK_TAG = "yt_fallback"

    /**
     * YouTube画面で握り潰す広告/計測系ホスト(v1.89)。
     * 企業プロキシがこれらのドメインを遮断していると、埋め込みプレイヤーが再生前に行う
     * 広告ステータス問い合わせ(例: static.doubleclick.net/instream/ad_status.js)が
     * ハードなネットワークエラーになり、YouTube側が再生そのものを拒否する(エラー152)
     * ケースが確認されている。ここに列挙したホストへのリクエストはWebView側で
     * 空のHTTP 200に差し替え、「広告なし」として扱わせることで再生拒否を回避する。
     */
    private val YT_AD_BLOCK_HOSTS = listOf(
        "static.doubleclick.net",
        "googleads.g.doubleclick.net",
        "pagead2.googlesyndication.com",
        "www.googletagservices.com"
    )
    /** YT_AD_BLOCK_HOSTSの握り潰しを有効にするか(v1.89)。効果が無ければfalseに戻すだけで無効化できる */
    private val YOUTUBE_STUB_AD_REQUESTS = true

    /**
     * 画面種別に応じてUAを切り替える(v1.85)。
     * YouTube画面のみYOUTUBE_USER_AGENTを使い、それ以外は端末既定UA(defaultUserAgent)に
     * 復元する。web画面の自動フィット拡大(v1.80)等、既存挙動への影響をゼロにするため。
     */
    private fun applyUserAgentForScreen(webView: WebView, screen: FlatScreen) {
        val target = if (screen.type == "youtube") YOUTUBE_USER_AGENT else defaultUserAgent
        if (target.isNotEmpty() && webView.settings.userAgentString != target) {
            webView.settings.userAgentString = target
        }
    }

    /**
     * 各種YouTube URL形式から動画ID(11文字)を抽出。取れなければnull。
     * 対応: watch?v=ID / youtu.be/ID / embed/ID / shorts/ID / 生の11文字ID
     */
    private fun extractYoutubeId(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        if (Regex("^[A-Za-z0-9_-]{11}$").matches(trimmed)) return trimmed
        val patterns = listOf(
            Regex("""[?&]v=([A-Za-z0-9_-]{11})"""),
            Regex("""youtu\.be/([A-Za-z0-9_-]{11})"""),
            Regex("""/embed/([A-Za-z0-9_-]{11})"""),
            Regex("""/shorts/([A-Za-z0-9_-]{11})"""),
        )
        for (p in patterns) {
            p.find(trimmed)?.let { return it.groupValues[1] }
        }
        return null
    }

    /** アクティブ昇格したYouTube WebViewの再生を開始する */
    private fun ytPlay(view: WebView?) {
        view?.evaluateJavascript("if(window.ytPlay)ytPlay();", null)
    }

    /** 非アクティブに降格したYouTube WebViewの再生を停止する（裏で音が鳴るのを防止） */
    private fun ytStop(view: WebView?) {
        view?.evaluateJavascript("if(window.ytStop)ytStop();", null)
    }

    /** スクリーンをWebViewにロードする */
    private fun loadScreen(webView: WebView, screen: FlatScreen) {
        webView.setInitialScale(100)
        applyUserAgentForScreen(webView, screen)
        when (screen.type) {
            "web" -> {
                webView.setInitialScale(webInitialScale)
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        injectWideViewport(view)
                        scheduleAutoFit(view, url)
                    }
                    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                        val crashed = detail?.didCrash() == true
                        addDebugLog("[WEBVIEW] Render process gone in web view (crashed=$crashed)")
                        handleWebViewCrash(view)
                        return true
                    }
                }
                webView.loadUrl(screen.url ?: return)
            }
            "pdf" -> {
                loadScreenPdf(webView, screen, preloadType = null)
            }
            "dual_pdf" -> {
                loadScreenDualPdf(webView, screen, preloadType = null)
            }
            "youtube" -> {
                loadScreenYoutube(webView, screen, preloadType = null)
            }
        }
    }

    /** YouTube: プレイヤーページのHTMLテンプレート(assets)。初回読み込み時にキャッシュする(v1.83) */
    private var youtubePlayerHtmlTemplateCache: String? = null

    /**
     * assets/youtube-player.html のテンプレートをテキストとして読み込む(キャッシュ付き)。
     * file:///android_asset/ からのloadUrl()ではWebViewのoriginがfile://になり、
     * YouTube IFrame Player APIのorigin検証に失敗してエラー153になるため、
     * loadDataWithBaseURL()でhttpsオリジンを付与して読み込む(v1.83)。
     */
    private fun loadYoutubePlayerHtmlTemplate(): String {
        youtubePlayerHtmlTemplateCache?.let { return it }
        val html = assets.open("youtube-player.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
        youtubePlayerHtmlTemplateCache = html
        return html
    }

    /**
     * 動画ID等をテンプレートに埋め込んだ完成HTMLを返す(v1.84)。
     * 以前は onPageFinished 後に evaluateJavascript() で値を渡していたが、
     * loadDataWithBaseURL() のURL正規化でbaseUrlに末尾スラッシュが付与されるため
     * onPageFinished側のURL比較が常にfalseになり ytLoad() が一度も呼ばれないバグがあった。
     * HTMLに値を先に埋め込む方式にしてタイミング依存を根絶する。
     * v1.89: __ORIGIN__ を追加し、IFrame APIのorigin/hostをyoutubeEffectiveBaseUrl(nocookie切替対応)
     * と自動的に一致させる(不一致はエラー153の原因になるため)。
     */
    private fun buildYoutubeHtml(videoId: String, muted: Boolean, autoplay: Boolean): String {
        return loadYoutubePlayerHtmlTemplate()
            .replace("__VIDEO_ID__", videoId)
            .replace("__MUTED__", muted.toString())
            .replace("__AUTOPLAY__", autoplay.toString())
            .replace("__ORIGIN__", youtubeEffectiveBaseUrl)
    }

    /**
     * YouTube画面用WebViewClientを生成する共通ヘルパー(v1.89)。
     * loadScreenYoutube()(IFrame APIラッパー経由)とfallbackToDirectEmbed()(直接embed)の
     * 両方で使う。onPageFinished/onRenderProcessGoneのログ文言は呼び出し元ごとに変えたいため
     * 引数化し、ネットワーク診断(onReceivedError/onReceivedHttpError)と広告リクエストの
     * 握り潰し(shouldInterceptRequest)は共通化する。
     *
     * @param pageFinishedTag onPageFinished時のログに使うタグ(例: "onPageFinished" / "フォールバックonPageFinished")
     * @param renderGoneTag   onRenderProcessGone時のログに使うタグ(例: "youtube view" / "youtube fallback view")
     */
    private fun createYoutubeWebViewClient(pageFinishedTag: String, renderGoneTag: String): WebViewClient {
        return object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                addDebugLog("[YT] $pageFinishedTag url=$url")
            }
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                val crashed = detail?.didCrash() == true
                addDebugLog("[WEBVIEW] Render process gone in $renderGoneTag (crashed=$crashed)")
                handleWebViewCrash(view)
                return true
            }

            /**
             * メインフレーム以外(広告ステータス問い合わせ等のサブリソース)を含む全リクエストの
             * ハードなネットワークエラーを記録する(v1.89)。エラー152はonErrorを発火させず
             * プレイヤー内部UIとして表示されるだけのことがあり、原因の特定にはこの計測が必須。
             * ワーカースレッドから呼ばれるためlogYtNetError内でhandler.post()する。
             */
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                val host = request?.url?.host ?: "?"
                val path = request?.url?.path ?: ""
                val main = request?.isForMainFrame == true
                logYtNetError("$host$path err=${error?.errorCode} ${error?.description} main=$main")
            }

            /** HTTPエラー応答(404/403等)も同様に記録する(v1.89) */
            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                super.onReceivedHttpError(view, request, errorResponse)
                val host = request?.url?.host ?: "?"
                val path = request?.url?.path ?: ""
                val main = request?.isForMainFrame == true
                logYtNetError("$host$path http=${errorResponse?.statusCode} main=$main")
            }

            /**
             * YT_AD_BLOCK_HOSTS宛のリクエストを空のHTTP 200に差し替える(v1.89、B-2)。
             * プロキシの接続拒否(ERR_CONNECTION_REFUSED等)は再生拒否の主因候補のため、
             * 「広告なし」として振る舞わせることで再生拒否を回避できないか試す。
             */
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                if (YOUTUBE_STUB_AD_REQUESTS) {
                    val host = request?.url?.host
                    if (host != null && YT_AD_BLOCK_HOSTS.any { host == it || host.endsWith(".$it") }) {
                        logYtNetError("stub $host (ad-block)")
                        return WebResourceResponse(
                            "application/javascript", "utf-8", ByteArrayInputStream(ByteArray(0))
                        )
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }
        }
    }

    /**
     * YouTube関連ドメインへの疎通プローブをYouTube画面到達時にもう一度実行する(v1.89、A-4)。
     * onCreate()での1回だけでは起動直後でネットワークが未確立の可能性があるため、
     * 実際にYouTube画面を表示するタイミングで再測定する。アプリ起動中1回のみ実施。
     */
    private fun rerunYoutubeProbeIfNeeded() {
        if (youtubeProbeRerunDone) return
        youtubeProbeRerunDone = true
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val result = serverClient.probeYoutubeConnectivity()
                withContext(Dispatchers.Main) {
                    youtubeProbeResult = result
                    addDebugLog("[YT] 疎通(再) $result")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    addDebugLog("[YT] 疎通(再)失敗: ${e.message}")
                }
            }
        }
    }

    /** YouTube画面のロード（アクティブ=autoplay、先読み=無音ロードのみ） */
    private fun loadScreenYoutube(webView: WebView, screen: FlatScreen, preloadType: String?) {
        val autoplay = preloadType == null
        // このWebViewでの直接embedフォールバック実施フラグをリセット(v1.85)
        webView.tag = null
        webView.webViewClient = createYoutubeWebViewClient(
            pageFinishedTag = "onPageFinished",
            renderGoneTag = "youtube view"
        )
        // YouTube画面に初めて到達したタイミングで疎通プローブを再測定する(v1.89、A-4)
        rerunYoutubeProbeIfNeeded()
        addDebugLog(
            "[YT] stage1(wrapper) load videoId=${screen.youtubeId} muted=$YOUTUBE_MUTED " +
                "autoplay=$autoplay preload=$preloadType base=$youtubeEffectiveBaseUrl ua=...${YOUTUBE_USER_AGENT.takeLast(40)}"
        )
        // https オリジンを付与して読み込む(エラー153対策、v1.83)。file:///android_asset/直読みは行わない。
        // v1.89: youtubeEffectiveBaseUrl経由でnocookieドメインに切替え可能にした。
        webView.loadDataWithBaseURL(
            youtubeEffectiveBaseUrl,
            buildYoutubeHtml(screen.youtubeId ?: "", YOUTUBE_MUTED, autoplay),
            "text/html", "utf-8", null
        )
        // 先読み時、何らかの理由でonYoutubeReady()が来なくてもdoAdvance()の無限リトライに
        // ならないよう、一定時間後に強制的にready扱いにする(v1.84)。markPreloadReadyは冪等。
        if (preloadType != null) {
            handler.postDelayed({
                if (webView == nextWebView || webView == prevWebView) {
                    markPreloadReady(preloadType)
                }
            }, YOUTUBE_PRELOAD_READY_TIMEOUT_MS)
        }
    }

    /**
     * YouTube: 再生開始watchdogがstalled(152等でonReady後も再生が始まらない)を検知した際、
     * IFrame APIラッパー(loadDataWithBaseURL経由の合成オリジン)を介さず、
     * YouTubeの/embed/ページへ直接loadUrl()するフォールバック(v1.85)。
     * このページはSignageInterfaceを参照しないため、onYoutubeEnded()/onError()等のJS通知は
     * 一切来なくなる。そのためENDEDではなくYOUTUBE_FALLBACK_DURATION_SEC秒の尺タイマーで
     * 強制的に次の画面へ進める。1画面につき1回だけ実施(webView.tagで判定)。
     */
    private fun fallbackToDirectEmbed(webView: WebView, screen: FlatScreen) {
        if (webView.tag == YT_FALLBACK_TAG) return
        webView.tag = YT_FALLBACK_TAG
        val id = screen.youtubeId
        if (id.isNullOrEmpty()) {
            addDebugLog("[YT] フォールバック不可(videoId無し) → 次のコンテンツへ")
            advanceToNext()
            return
        }
        // v1.89: youtubeEffectiveBaseUrl経由でnocookieドメインに切替え可能にした。
        val embedUrl = "$youtubeEffectiveBaseUrl/embed/$id" +
            "?autoplay=1&mute=1&controls=0&playsinline=1&rel=0&modestbranding=1&iv_load_policy=3&fs=0&disablekb=1"
        addDebugLog("[YT] stage2(直接embed)へフォールバック: $embedUrl")
        webView.webViewClient = createYoutubeWebViewClient(
            pageFinishedTag = "フォールバックonPageFinished",
            renderGoneTag = "youtube fallback view"
        )
        webView.loadUrl(embedUrl)
        // フォールバック中はJSからのENDED通知が来ないため、尺タイマーのみで進行を担保する
        contentTimer?.let { handler.removeCallbacks(it) }
        val fallbackDuration = (YOUTUBE_FALLBACK_DURATION_SEC * 1000).toLong()
        contentTimer = Runnable {
            addDebugLog("[YT] フォールバック尺タイマー発火(${YOUTUBE_FALLBACK_DURATION_SEC}秒) → 次のコンテンツへ")
            advanceToNext()
        }.also { handler.postDelayed(it, fallbackDuration) }
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

    /**
     * 全頁モードのページ送りをAndroid主導で開始する。
     * WebView内のsetTimeoutは非表示/合成中にChromiumがスロットリングするため、
     * Androidのhandlerで pdfPageDuration 秒ごとに androidAdvancePage() を呼ぶ。
     * 戻り値が"more"なら次を予約、"done"なら次コンテンツへ進む。
     */
    private fun startPdfPageRotation(screen: FlatScreen) {
        pdfPageTimer?.let { handler.removeCallbacks(it) }
        pdfPageTimer = null
        if (!screen.isAllPages) return
        val periodMs = ((screen.pdfPageDuration ?: 10) * 1000).toLong()
        addDebugLog("[PDF] ページ送り開始(Android主導) ${periodMs / 1000}秒間隔")
        lateinit var tick: Runnable
        tick = Runnable {
            val wv = activeWebView ?: return@Runnable
            // 画面が切り替わっていれば残留タイマーは無視
            if (pdfPageTimer !== tick) return@Runnable
            wv.evaluateJavascript("(window.androidAdvancePage?androidAdvancePage():'done')") { result ->
                if (pdfPageTimer !== tick) return@evaluateJavascript
                when (result?.trim('"')) {
                    "more" -> handler.postDelayed(tick, periodMs)
                    "wait" -> handler.postDelayed(tick, 1000L)  // PDF読込中、1秒後に再試行(最終的には安全弁が救済)
                    else -> {
                        addDebugLog("[PDF] 全ページ表示完了 → 次のコンテンツへ")
                        advanceToNext()
                    }
                }
            }
        }
        pdfPageTimer = tick
        handler.postDelayed(tick, periodMs)
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
        } else if (screen.type == "youtube" && screen.playToEnd) {
            // 最後まで再生モード: 進行はonYoutubeEnded()が担当。ここでは安全弁タイマーのみ。
            val safetyDuration = (YOUTUBE_MAX_DURATION_SEC * 1000).toLong()
            contentTimer = Runnable {
                addDebugLog("[YT] 安全弁タイマー発火(${YOUTUBE_MAX_DURATION_SEC}秒) → 次のコンテンツへ")
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
            pdfPageTimer?.let { handler.removeCallbacks(it) }
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

        val oldScreen = flatScreens.getOrNull(currentScreenIndex)

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

        // YouTube: 旧activeが再生中なら停止(裏で音が鳴るのを防止)、新activeがyoutubeなら再生開始
        if (oldScreen?.type == "youtube") ytStop(oldActive)
        if (screen.type == "youtube") ytPlay(activeWebView)

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
        startPdfPageRotation(screen)

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
        applyUserAgentForScreen(webView, screen)
        when (screen.type) {
            "web" -> {
                webView.setInitialScale(webInitialScale)
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        injectWideViewport(view)
                        scheduleAutoFit(view, url)
                        markPreloadReady(preloadType)
                    }
                    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                        val crashed = detail?.didCrash() == true
                        addDebugLog("[WEBVIEW] Render process gone in preload view (crashed=$crashed)")
                        handleWebViewCrash(view)
                        return true
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
            "youtube" -> {
                loadScreenYoutube(webView, screen, preloadType)
                // markPreloadReadyは通常onYoutubeReady()から呼ばれる(準備完了を待つ)。
                // タイムアウト時はloadScreenYoutube内のフォールバックが強制的に呼ぶ(v1.84)
            }
        }
    }

    /** ステータスバーを更新 */
    private fun updateScreenStatusBar(screen: FlatScreen) {
        isAllPagesMode = screen.isAllPages
        pdfPageDurationSec = screen.pdfPageDuration ?: 10
        // 前画面のページ表示(例: "(1/2)")の残留を消す。実値はonPageChangedで更新。
        currentPdfPage = 1
        totalPdfPages = 1
        // 全頁モードのカウントダウンはper-page秒を起点に（durationSecondsは安全弁の総時間のため不適）
        remainingSeconds = if (screen.isAllPages) pdfPageDurationSec else screen.durationSeconds
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
                        // ページ送りはactive昇格時にstartPdfPageRotation()がAndroid主導で駆動する。
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
                    // ページ送りはactive昇格時にstartPdfPageRotation()がAndroid主導で駆動する。
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
        pdfPageTimer?.let { handler.removeCallbacks(it) }
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
    // WebView Crash Recovery
    // =========================================================================

    /**
     * WebViewのrender processがクラッシュした場合のリカバリ。
     * クラッシュしたWebViewを破棄→再作成→再生を自動再開する。
     */
    private fun handleWebViewCrash(crashedView: WebView?) {
        if (crashedView == null) return

        val isActive = crashedView == activeWebView
        val isNext = crashedView == nextWebView
        val isPrev = crashedView == prevWebView

        addDebugLog("[WEBVIEW] Recovering ${if (isActive) "active" else if (isNext) "next" else "prev"} WebView")

        try {
            (crashedView.parent as? ViewGroup)?.removeView(crashedView)
            crashedView.destroy()
        } catch (e: Exception) {
            addDebugLog("[WEBVIEW] WebView destroy error: ${e.message}")
        }

        // 新しいWebViewを作成してコンテナに追加(touchOverlayより下に)
        val newWebView = createWebView()
        containerLayout.addView(newWebView, 0)

        // 変数参照を更新
        when {
            crashedView === webViewA -> webViewA = newWebView
            crashedView === webViewB -> webViewB = newWebView
            crashedView === webViewC -> webViewC = newWebView
        }

        if (isActive) {
            activeWebView = newWebView
            newWebView.visibility = View.VISIBLE
            // 現在のスクリーンを再ロード
            val screen = flatScreens.getOrNull(currentScreenIndex)
            if (screen != null) {
                loadScreen(newWebView, screen)
                updateScreenStatusBar(screen)
                startPdfPageRotation(screen)
                scheduleAutoAdvance(screen)
            }
            // 先読みも再実行
            preloadBothDirections()
        } else {
            if (isNext) nextWebView = newWebView
            if (isPrev) prevWebView = newWebView
            newWebView.visibility = View.INVISIBLE
            // クラッシュした先読みWebViewの再ロード
            if (flatScreens.isNotEmpty()) {
                if (isNext) {
                    nextReady = false
                    val nextIdx = (currentScreenIndex + 1) % flatScreens.size
                    preloadScreen(newWebView, flatScreens[nextIdx], isPrevPreload = false)
                }
                if (isPrev) {
                    prevReady = false
                    val prevIdx = (currentScreenIndex - 1 + flatScreens.size) % flatScreens.size
                    preloadScreen(newWebView, flatScreens[prevIdx], isPrevPreload = true)
                }
            }
        }

        addDebugLog("[WEBVIEW] Recovery complete")
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    override fun onPause() {
        super.onPause()
        // アプリがバックグラウンドに回ったとき、YouTube動画の音が鳴り続けないよう停止
        ytStop(activeWebView)
    }

    override fun onResume() {
        super.onResume()
        if (flatScreens.getOrNull(currentScreenIndex)?.type == "youtube") {
            ytPlay(activeWebView)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(updateLogReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(scheduleUpdateReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(heartbeatReceiver) } catch (_: Exception) {}
        isPlaying = false
        contentTimer?.let { handler.removeCallbacks(it) }
        countdownTimer?.let { handler.removeCallbacks(it) }
        pdfPageTimer?.let { handler.removeCallbacks(it) }
        longPressResetRunnable?.let { handler.removeCallbacks(it) }
        screenListTimeout?.let { handler.removeCallbacks(it) }
        pollingJob?.cancel()
        smbSyncJob?.cancel()
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
                pdfPageTimer?.let { handler.removeCallbacks(it) }
                advanceToNext()
            }
        }

        /** YouTube: プレイヤー準備完了通知。先読みWebViewなら nextReady/prevReady を立てる */
        @JavascriptInterface
        fun onYoutubeReady() {
            handler.post {
                if (webView != activeWebView && !nextReady) {
                    nextReady = true
                    addDebugLog("[YT] 先読み準備完了(next)")
                    return@post
                }
                if (webView != activeWebView && !prevReady) {
                    prevReady = true
                    addDebugLog("[YT] 先読み準備完了(prev)")
                    return@post
                }
                // activeWebViewの場合は既にloadScreen()で再生開始指示済みのため何もしない
            }
        }

        /** YouTube: 再生終了通知。playToEndモードのときのみ次のコンテンツへ進む */
        @JavascriptInterface
        fun onYoutubeEnded() {
            handler.post {
                if (webView != activeWebView) return@post
                val screen = flatScreens.getOrNull(currentScreenIndex)
                if (screen?.type == "youtube" && screen.playToEnd && YOUTUBE_ADVANCE_ON_END) {
                    addDebugLog("[YT] 再生終了 → 次のコンテンツへ")
                    contentTimer?.let { handler.removeCallbacks(it) }
                    advanceToNext()
                }
            }
        }

        /** YouTube: エラー通知（削除済み・埋め込み禁止・読込失敗・onReadyタイムアウト等）。固まらず次のコンテンツへ */
        @JavascriptInterface
        fun onYoutubeError(code: Int) {
            handler.post {
                if (webView != activeWebView) return@post
                addDebugLog("[YT] エラー(code=$code) → 次のコンテンツへ")
                contentTimer?.let { handler.removeCallbacks(it) }
                advanceToNext()
            }
        }

        /**
         * YouTube: 再生開始watchdog発火通知(v1.85)。
         * onReadyまで到達しYT.Playerは生成されたが、152等でYouTube側が再生を拒否し
         * かつonErrorも発火しない(プレイヤー内部UIとして表示されるだけの)ケースを検知した通知。
         * IFrame APIラッパーを介さない直接embedページへのフォールバックに切り替える。
         */
        @JavascriptInterface
        fun onYoutubeStalled() {
            handler.post {
                if (webView != activeWebView) return@post
                val screen = flatScreens.getOrNull(currentScreenIndex)
                if (screen?.type != "youtube") return@post
                addDebugLog("[YT] stage1(wrapper)で再生開始せず(watchdog) → stage2(直接embed)へ")
                fallbackToDirectEmbed(webView, screen)
            }
        }

        /** YouTube: JS側の各段階をデバッグオーバーレイへ転送(v1.84) */
        @JavascriptInterface
        fun onYoutubeLog(msg: String) {
            handler.post {
                val tag = if (webView == activeWebView) "active" else "preload"
                addDebugLog("[YT-JS:$tag] $msg")
            }
        }
    }
}
