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
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import android.graphics.Color
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
    private var smbPdfManager: SmbPdfManager? = null

    // pdf_folder サブプレイリスト管理
    private var pdfFolderSubPlaylist: List<PlaylistItem>? = null
    private var pdfFolderSubIndex: Int = 0
    private var currentPdfFolderItem: PlaylistItem? = null

    private lateinit var containerLayout: FrameLayout
    private lateinit var webViewA: WebView
    private lateinit var webViewB: WebView
    private lateinit var webViewC: WebView
    private lateinit var statusBar: TextView
    private lateinit var touchOverlay: View
    private lateinit var pauseBorder: View
    private lateinit var debugTextView: TextView
    private val debugLines = mutableListOf<String>()
    private var debugPage = 1  // 0=非表示, 1=デバッグログ, 2=スケジュール, 3=端末情報

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
    private var subNextReady = false  // サブプレイリスト先読み完了フラグ

    // pdf_folder先読み（Webページ表示中にSMB同期+1件目PDFレンダリング）
    private var preloadedPdfFolderSubPlaylist: List<PlaylistItem>? = null
    private var preloadedPdfFolderItem: PlaylistItem? = null

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

    // BroadcastReceiver for schedule updates from SignageService
    private val scheduleUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            addDebugLog("[SCHEDULE] Broadcast受信: スケジュール更新")
            coroutineScope.launch {
                val schedule = scheduleManager.loadSchedule()
                if (schedule != null && schedule.playlist.isNotEmpty()) {
                    val items = scheduleManager.playlist
                    pdfCacheManager.downloadAll(items)
                    val activeIds = items.filter { it.type == "pdf" }.map { it.contentId }.toSet()
                    pdfCacheManager.cleanupUnused(activeIds)
                    handler.post {
                        addDebugLog("[SCHEDULE] スケジュール反映完了: ${items.size}件")
                        if (isPlaying) {
                            // 再生中: 次の切替時に新スケジュールが反映される
                        } else {
                            // standby中: 再生時間内なら再生開始
                            if (scheduleManager.isWithinPlayTime()) {
                                addDebugLog("[SCHEDULE] 再生時間内 → 再生開始")
                                playCurrentContent()
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateLogReceiver, updateLogFilter, Context.RECEIVER_EXPORTED)
            registerReceiver(scheduleUpdateReceiver, scheduleFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(updateLogReceiver, updateLogFilter)
            registerReceiver(scheduleUpdateReceiver, scheduleFilter)
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
        debugPage = (debugPage + 1) % 4  // 0→1→2→3→0
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
            else -> ""
        }
    }

    private fun buildDebugLogText(): String {
        val header = "[1/3] デバッグログ (下ボタンで切替)"
        return header + "\n" + debugLines.joinToString("\n")
    }

    private fun buildScheduleInfoText(): String {
        val sb = StringBuilder("[2/3] スケジュール情報 (下ボタンで切替)\n")
        sb.append("バージョン: v${scheduleManager.version}\n")
        sb.append("再生時間: ${scheduleManager.playTimeRange}\n")

        val playlist = scheduleManager.playlist
        sb.append("プレイリスト: ${playlist.size}件\n")

        val currentItem = scheduleManager.getCurrentItem()
        val subList = pdfFolderSubPlaylist
        val subIdx = pdfFolderSubIndex

        if (subList != null) {
            sb.append("状態: サブPL再生中 (${subIdx + 1}/${subList.size})\n")
        }

        sb.append("─".repeat(20)).append("\n")

        playlist.forEachIndexed { _, item ->
            val prefix = if (item == currentItem) "▶ " else "  "
            val typeTag = when (item.type) {
                "web" -> "web"
                "pdf" -> "pdf"
                "pdf_folder" -> "folder"
                else -> item.type
            }
            val dur = "${item.durationSeconds}秒"
            sb.append("${prefix}#${item.displayOrder} [$typeTag] ${item.name} ($dur)\n")
        }

        if (subList != null && subList.isNotEmpty()) {
            sb.append("\n[サブPL: ${currentPdfFolderItem?.name}]\n")
            subList.forEachIndexed { idx, sub ->
                val prefix = if (idx == subIdx) "▶ " else "  "
                sb.append("${prefix}${idx + 1}. ${sub.name}\n")
            }
        }

        return sb.toString().trimEnd()
    }

    @SuppressLint("SetTextI18n")
    private fun buildDeviceInfoText(): String {
        val sb = StringBuilder("[3/3] 端末情報 (下ボタンで切替)\n")

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

        sb.append("WebView active: ${activeWebView?.url ?: "null"}\n")

        return sb.toString().trimEnd()
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
                true // Consume to prevent WebView from scrolling/focusing
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
        if (isPaused) {
            scheduleManager.advanceToNext()
            val item = scheduleManager.getCurrentItem() ?: return
            loadContent(activeWebView!!, item)
            statusBar.text = formatStatusText(item.name, "⏸ 一時停止中 (戻るで再開)")
        } else {
            advanceToNext()
        }
    }

    private fun goToPrevious() {
        if (!isPlaying) return
        if (isPaused) {
            scheduleManager.goToPrevious()
            val item = scheduleManager.getCurrentItem() ?: return
            loadContent(activeWebView!!, item)
            statusBar.text = formatStatusText(item.name, "⏸ 一時停止中 (戻るで再開)")
        } else {
            // Cancel current timers
            contentTimer?.let { handler.removeCallbacks(it) }
            countdownTimer?.let { handler.removeCallbacks(it) }

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
        // Rotate: active→next, prev→active, next→prev
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

        // Move schedule index
        scheduleManager.goToPrevious()
        val item = scheduleManager.getCurrentItem() ?: return
        updateStatusBar(item)

        // nextReady is true (old active already had content)
        nextReady = true

        // Schedule next auto-advance
        val duration = (item.durationSeconds * 1000).toLong()
        contentTimer = Runnable { advanceToNext() }.also {
            handler.postDelayed(it, duration)
        }

        // Preload previous into the recycled WebView
        prevReady = false
        scheduleManager.getPreviousItem()?.let { prevItem ->
            preloadContent(prevWebView!!, prevItem, isPrevPreload = true)
        }
    }

    private fun togglePause() {
        if (!isPlaying) return

        isPaused = true
        val item = scheduleManager.getCurrentItem()

        contentTimer?.let { handler.removeCallbacks(it) }
        countdownTimer?.let { handler.removeCallbacks(it) }
        statusBar.text = formatStatusText(item?.name ?: "", "⏸ 一時停止中 (戻るで再開)")

        enableWebViewInteraction()
    }

    private fun resumePlayback() {
        if (!isPlaying || !isPaused) return

        isPaused = false
        val item = scheduleManager.getCurrentItem()

        disableWebViewInteraction()

        if (item != null) {
            updateStatusBar(item)
            val duration = (item.durationSeconds * 1000).toLong()
            contentTimer = Runnable { advanceToNext() }.also {
                handler.postDelayed(it, duration)
            }
        }

        // Re-preload next and prev after pause (content may have changed)
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
            text = "[1/3] デバッグログ (下ボタンで切替)\n起動中..."
            isClickable = false
            isFocusable = false
            visibility = View.VISIBLE
        }
        containerLayout.addView(debugTextView)

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
        // pdf_folderアイテムのフィールドをダンプ（デバッグ用）
        schedule.playlist.filter { it.type == "pdf_folder" }.forEach { pf ->
            addDebugLog("[PLAY] pdf_folder: name=${pf.name} smbPath=${pf.smbPath} user=${pf.smbUsername} pw=${if (pf.smbPassword != null) "set" else "null"} firstPage=${pf.firstPageOnly}")
        }
        withContext(Dispatchers.Main) { statusBar.text = "スケジュール: ${schedule.playlist.size}件 PDFダウンロード中..." }

        pdfCacheManager.downloadAll(schedule.playlist)
        val activeIds = schedule.playlist.filter { it.type == "pdf" }.map { it.contentId }.toSet()
        pdfCacheManager.cleanupUnused(activeIds)

        if (!scheduleManager.isWithinPlayTime()) {
            addDebugLog("[PLAY] 再生時間外 (${schedule.playStartTime}-${schedule.playEndTime}) → standby")
            withContext(Dispatchers.Main) { statusBar.text = "再生時間外 (${schedule.playStartTime}-${schedule.playEndTime})" }
            showStandby()
            startTimeCheck()
            return
        }

        addDebugLog("[PLAY] 再生開始")
        playCurrentContent()
    }

    private fun playCurrentContent() {
        val item = scheduleManager.getCurrentItem() ?: run {
            addDebugLog("[PLAY] getCurrentItem=null")
            return
        }

        addDebugLog("[PLAY] #${item.displayOrder} type=${item.type} name=${item.name}")
        if (debugPage == 2) updateDebugContent()

        // pdf_folder タイプの場合は専用フローへ
        if (item.type == "pdf_folder") {
            startPdfFolderPlayback(item)
            return
        }

        isPlaying = true
        isPaused = false
        disableWebViewInteraction()
        loadContent(activeWebView!!, item)
        updateStatusBar(item)

        // Schedule next content
        contentTimer?.let { handler.removeCallbacks(it) }

        // allPagesモード (pdfPageDuration != null かつ pdf_folderの子PDF) の場合:
        // ページ送りはpdf-viewer.htmlのsetTimeoutチェーンが管理し、
        // 全ページ完了時にonAllPagesCompleted()で通知される。
        val isSubAllPages = pdfFolderSubPlaylist != null && item.pdfPageDuration != null
        if (isSubAllPages) {
            val safetyDuration = ((item.durationSeconds + 30) * 1000).toLong()
            contentTimer = Runnable {
                addDebugLog("[PDF] 安全弁タイマー発火")
                advanceToNext()
            }.also {
                handler.postDelayed(it, safetyDuration)
            }
        } else {
            val duration = (item.durationSeconds * 1000).toLong()
            contentTimer = Runnable {
                advanceToNext()
            }.also {
                handler.postDelayed(it, duration)
            }
        }

        // Preload both next and previous (only for main playlist items)
        if (pdfFolderSubPlaylist == null) {
            preloadBothDirections()
        }
    }

    // =========================================================================
    // pdf_folder: SMB同期 → 子PDFサブプレイリスト再生
    // =========================================================================

    private fun startPdfFolderPlayback(item: PlaylistItem) {
        val manager = smbPdfManager ?: run {
            addDebugLog("[SMB] SmbPdfManager未初期化")
            advanceToNextMain()
            return
        }

        // Stop any running timers
        contentTimer?.let { handler.removeCallbacks(it) }
        countdownTimer?.let { handler.removeCallbacks(it) }
        currentPdfFolderItem = item

        addDebugLog("[SMB] フォルダ同期開始: ${item.name}")
        addDebugLog("[SMB] smbPath=${item.smbPath} user=${item.smbUsername} pw=${if (item.smbPassword != null) "***" else "null"} firstPage=${item.firstPageOnly}")
        statusBar.text = "SMB同期中: ${item.smbPath ?: "(パス未設定)"}"

        // 1. Show sync status screen
        activeWebView?.loadUrl("file:///android_asset/sync-status.html")

        // 2. Wait for sync-status.html to load, then start sync
        activeWebView?.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url?.contains("sync-status.html") == true) {
                    coroutineScope.launch {
                        val (subPlaylist, downloadCount) = withContext(Dispatchers.IO) {
                            manager.syncFolder(item) { progressMsg ->
                                withContext(Dispatchers.Main) {
                                    val escaped = progressMsg.replace("'", "\\'")
                                    activeWebView?.evaluateJavascript(
                                        "updateSyncStatus('$escaped');", null
                                    )
                                    addDebugLog("[SMB] $progressMsg")
                                }
                            }
                        }

                        withContext(Dispatchers.Main) {
                            if (subPlaylist.isEmpty()) {
                                addDebugLog("[SMB] ${item.name} - ファイルなし")
                                activeWebView?.evaluateJavascript(
                                    "showComplete('ファイルが見つかりませんでした');", null
                                )
                                handler.postDelayed({
                                    currentPdfFolderItem = null
                                    advanceToNextMain()
                                }, 10_000L)
                                return@withContext
                            }

                            if (downloadCount > 0) {
                                val completeMsg = "同期完了: ${downloadCount}件ダウンロード (全${subPlaylist.size}件)"
                                activeWebView?.evaluateJavascript(
                                    "showComplete('$completeMsg');", null
                                )
                                addDebugLog("[SMB] 同期完了: ${item.name} - ${downloadCount}件DL / 全${subPlaylist.size}件")

                                handler.postDelayed({
                                    startSubPlaylist(subPlaylist)
                                }, 10_000L)
                            } else {
                                addDebugLog("[SMB] ${item.name} - ${subPlaylist.size}件 (更新なし)")
                                startSubPlaylist(subPlaylist)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun startSubPlaylist(subPlaylist: List<PlaylistItem>) {
        pdfFolderSubPlaylist = subPlaylist
        pdfFolderSubIndex = 0
        subNextReady = false

        val subItem = subPlaylist[0]
        val isFirstPageOnly = currentPdfFolderItem?.firstPageOnly == true

        // 1件目をnextWebViewに先読み（activeWebViewはsync画面を表示中）
        if (isFirstPageOnly && subItem.isPortrait
            && subPlaylist.size > 1 && subPlaylist[1].isPortrait) {
            loadDualPdfContentForPreload(nextWebView!!, subItem, subPlaylist[1])
        } else {
            loadSubPdfPreload(nextWebView!!, subItem)
        }

        addDebugLog("[SMB] 1件目PDF先読み開始")
        // 先読み完了を待ってWebViewスワップ → 即表示
        waitAndSwapFirstSubPdf()
    }

    /** 1件目のサブPDF先読み完了を待ち、WebViewスワップで即表示 */
    private fun waitAndSwapFirstSubPdf() {
        if (!subNextReady) {
            handler.postDelayed({ waitAndSwapFirstSubPdf() }, 200)
            return
        }

        addDebugLog("[SMB] 1件目PDF先読み完了 → スワップ表示")

        // WebViewスワップ（sync画面 → 先読み済みPDF）
        val oldActive = activeWebView
        activeWebView = nextWebView
        nextWebView = oldActive

        // クロスフェード
        activeWebView?.apply {
            alpha = 0f
            visibility = View.VISIBLE
            animate().alpha(1f).setDuration(800).start()
        }
        oldActive?.animate()?.alpha(0f)?.setDuration(800)?.withEndAction {
            oldActive.visibility = View.INVISIBLE
        }?.start()

        // タイマー開始 + 2件目先読み
        playCurrentSubPdfAfterSwap()
    }

    private fun playNextSubPdf() {
        val subList = pdfFolderSubPlaylist ?: return
        if (pdfFolderSubIndex >= subList.size) {
            // 全子PDF完了 → メインプレイリストの次へ
            addDebugLog("[SMB] フォルダ内全PDF表示完了 → 次のメインアイテムへ")
            pdfFolderSubPlaylist = null
            pdfFolderSubIndex = 0
            currentPdfFolderItem = null
            advanceToNextMain()
            return
        }

        val subItem = subList[pdfFolderSubIndex]
        if (debugPage == 2) updateDebugContent()
        val isFirstPageOnly = currentPdfFolderItem?.firstPageOnly == true

        // firstPageOnly + 縦長PDF + 次のPDFがある場合: 2つのPDFの1ページ目を見開き表示
        if (isFirstPageOnly && subItem.isPortrait && pdfFolderSubIndex + 1 < subList.size) {
            val nextSubItem = subList[pdfFolderSubIndex + 1]
            if (nextSubItem.isPortrait) {
                // デュアル表示: 2つのPDFを同時ロード
                isPlaying = true
                isPaused = false
                disableWebViewInteraction()
                loadDualPdfContent(activeWebView!!, subItem, nextSubItem)
                updateStatusBar(subItem) // 左側のPDF名をステータスバーに表示

                contentTimer?.let { handler.removeCallbacks(it) }
                val duration = (subItem.durationSeconds * 1000).toLong()
                contentTimer = Runnable {
                    contentTimer?.let { handler.removeCallbacks(it) }
                    countdownTimer?.let { handler.removeCallbacks(it) }
                    handler.post { advanceToNextSubPdf() }
                }.also {
                    handler.postDelayed(it, duration)
                }
                preloadNextSubPdf()
                return
            }
        }

        isPlaying = true
        isPaused = false
        disableWebViewInteraction()
        loadContent(activeWebView!!, subItem)
        updateStatusBar(subItem)

        // Schedule next sub-PDF
        contentTimer?.let { handler.removeCallbacks(it) }
        val isSubAllPages = subItem.pdfPageDuration != null
        if (isSubAllPages) {
            // allPages: onAllPagesCompletedで切替、安全弁タイマー
            val safetyDuration = ((subItem.durationSeconds + 30) * 1000).toLong()
            contentTimer = Runnable {
                addDebugLog("[PDF] 安全弁タイマー発火")
                advanceToNext()
            }.also {
                handler.postDelayed(it, safetyDuration)
            }
        } else {
            // firstPageOnly: duration_seconds秒後に次へ
            val duration = (subItem.durationSeconds * 1000).toLong()
            contentTimer = Runnable {
                advanceToNext()
            }.also {
                handler.postDelayed(it, duration)
            }
        }
        preloadNextSubPdf()
    }

    /** 次のサブPDFインデックスを計算（デュアル表示の場合は+2） */
    private fun calculateNextSubIndex(): Int {
        val subList = pdfFolderSubPlaylist ?: return 0
        val isFirstPageOnly = currentPdfFolderItem?.firstPageOnly == true
        val current = pdfFolderSubIndex
        val currentItem = subList.getOrNull(current) ?: return current + 1

        if (isFirstPageOnly && currentItem.isPortrait
            && current + 1 < subList.size && subList[current + 1].isPortrait) {
            return current + 2
        }
        return current + 1
    }

    /** 次のサブPDFをnextWebViewに先読み */
    private fun preloadNextSubPdf() {
        val subList = pdfFolderSubPlaylist ?: return
        subNextReady = false

        val nextIdx = calculateNextSubIndex()
        if (nextIdx >= subList.size) {
            subNextReady = true  // 最後のPDF → サブPDF先読み不要
            // 次のメインアイテムがpdf_folderなら先読み開始（nextWebViewは空いている）
            scheduleManager.getNextItem()?.let { nextMainItem ->
                if (nextMainItem.type == "pdf_folder") {
                    addDebugLog("[SMB] サブPL最終 → 次のpdf_folder先読み開始: ${nextMainItem.name}")
                    preloadPdfFolder(nextMainItem)
                }
            }
            return
        }

        val isFirstPageOnly = currentPdfFolderItem?.firstPageOnly == true
        val nextItem = subList[nextIdx]

        // デュアル表示判定
        if (isFirstPageOnly && nextItem.isPortrait
            && nextIdx + 1 < subList.size && subList[nextIdx + 1].isPortrait) {
            loadDualPdfContentForPreload(nextWebView!!, nextItem, subList[nextIdx + 1])
        } else {
            // シングル表示
            loadSubPdfPreload(nextWebView!!, nextItem)
        }
    }

    /** サブPDF先読み用: nextWebViewにPDFをロード */
    private fun loadSubPdfPreload(webView: WebView, item: PlaylistItem) {
        webView.setInitialScale(100)
        val cachedFile = smbPdfManager?.getLocalPdfFile(item.contentId) ?: File("")
        val duration = item.pdfPageDuration ?: 10
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url?.contains("pdf-viewer.html") == true) {
                    loadPdfIntoViewerForSubPreload(webView, cachedFile, item, duration)
                }
            }
        }
        webView.loadUrl("file:///android_asset/pdfjs/pdf-viewer.html")
    }

    /** サブPDF先読み用: Base64注入（subNextReadyはPDF.jsレンダリング完了時にonPageChangedでセット） */
    private fun loadPdfIntoViewerForSubPreload(webView: WebView, cachedFile: File, item: PlaylistItem, duration: Int) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val pdfBytes = if (cachedFile.exists()) cachedFile.readBytes() else null
                if (pdfBytes != null) {
                    val base64 = Base64.encodeToString(pdfBytes, Base64.NO_WRAP)
                    val firstPageOnly = currentPdfFolderItem?.firstPageOnly == true
                    withContext(Dispatchers.Main) {
                        webView.evaluateJavascript(
                            "loadPdfBase64('$base64', $duration, $firstPageOnly);", null
                        )
                        // subNextReadyはセットしない → PDF.jsのonPageChangedコールバックで完了通知
                        addDebugLog("[SMB] サブPDF Base64注入完了: ${item.name}")
                    }
                } else {
                    withContext(Dispatchers.Main) { subNextReady = true }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) { subNextReady = true }
            }
        }
    }

    /** デュアルPDF先読み用: 2つのPDFの1ページ目を左右に並べてnextWebViewにロード */
    private fun loadDualPdfContentForPreload(webView: WebView, leftItem: PlaylistItem, rightItem: PlaylistItem) {
        webView.setInitialScale(100)
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url?.contains("pdf-viewer.html") == true) {
                    loadDualPdfIntoViewerForPreload(webView, leftItem, rightItem)
                }
            }
        }
        webView.loadUrl("file:///android_asset/pdfjs/pdf-viewer.html")
    }

    /** デュアルPDF先読み用: Base64注入（subNextReadyはPDF.jsレンダリング完了時にonPageChangedでセット） */
    private fun loadDualPdfIntoViewerForPreload(webView: WebView, leftItem: PlaylistItem, rightItem: PlaylistItem) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val leftFile = smbPdfManager?.getLocalPdfFile(leftItem.contentId)
                val rightFile = smbPdfManager?.getLocalPdfFile(rightItem.contentId)
                val leftBytes = leftFile?.takeIf { it.exists() }?.readBytes()
                val rightBytes = rightFile?.takeIf { it.exists() }?.readBytes()

                // Base64エンコードをIOスレッドで実行（Mainスレッド負荷軽減）
                val leftBase64 = leftBytes?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
                val rightBase64 = rightBytes?.let { Base64.encodeToString(it, Base64.NO_WRAP) }

                withContext(Dispatchers.Main) {
                    if (leftBase64 != null && rightBase64 != null) {
                        webView.evaluateJavascript(
                            "loadDualFirstPages('$leftBase64', '$rightBase64');", null
                        )
                    } else if (leftBase64 != null) {
                        webView.evaluateJavascript(
                            "loadPdfBase64('$leftBase64', 10, true);", null
                        )
                    } else {
                        // ファイルなし → フォールバック
                        subNextReady = true
                    }
                    // subNextReadyはセットしない → PDF.jsのonPageChangedコールバックで完了通知
                    addDebugLog("[SMB] デュアルPDF Base64注入完了: ${leftItem.name}")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                handler.post { subNextReady = true }
            }
        }
    }

    /** サブプレイリスト: WebViewスワップで次のPDFへ遷移 */
    private fun advanceToNextSubPdf() {
        if (!subNextReady) {
            // 先読みがまだ完了していない場合は200msごとにリトライ
            handler.postDelayed({ advanceToNextSubPdf() }, 200)
            return
        }

        // 次のインデックスを計算
        val nextIdx = calculateNextSubIndex()

        // WebViewスワップ（2枚版）
        val oldActive = activeWebView
        activeWebView = nextWebView
        nextWebView = oldActive

        // クロスフェード
        activeWebView?.apply {
            alpha = 0f
            visibility = View.VISIBLE
            animate().alpha(1f).setDuration(800).start()
        }
        oldActive?.animate()?.alpha(0f)?.setDuration(800)?.withEndAction {
            oldActive.visibility = View.INVISIBLE
        }?.start()

        // インデックスを進める
        pdfFolderSubIndex = nextIdx

        // 先読み済みWebViewが表示されている → ロードせずタイマーだけ開始
        playCurrentSubPdfAfterSwap()
    }

    /** スワップ後のサブPDF再生: ロードは行わず、タイマー+ステータスバー+先読みのみ */
    private fun playCurrentSubPdfAfterSwap() {
        val subList = pdfFolderSubPlaylist ?: return
        if (pdfFolderSubIndex >= subList.size) {
            // 全子PDF完了 → メインプレイリストの次へ
            addDebugLog("[SMB] フォルダ内全PDF表示完了 → 次のメインアイテムへ")
            pdfFolderSubPlaylist = null
            pdfFolderSubIndex = 0
            currentPdfFolderItem = null
            advanceToNextMain()
            return
        }

        val subItem = subList[pdfFolderSubIndex]
        if (debugPage == 2) updateDebugContent()
        isPlaying = true
        isPaused = false
        disableWebViewInteraction()
        // ★ loadContent()は呼ばない（先読み済みのWebViewがそのまま表示されている）
        updateStatusBar(subItem)

        // タイマー設定
        contentTimer?.let { handler.removeCallbacks(it) }
        val isSubAllPages = subItem.pdfPageDuration != null
        if (isSubAllPages) {
            // allPages: onAllPagesCompletedで切替、安全弁タイマー
            val safetyDuration = ((subItem.durationSeconds + 30) * 1000).toLong()
            contentTimer = Runnable {
                addDebugLog("[PDF] 安全弁タイマー発火")
                advanceToNext()
            }.also { handler.postDelayed(it, safetyDuration) }
        } else {
            // firstPageOnly: duration_seconds秒後に次へ
            val duration = (subItem.durationSeconds * 1000).toLong()
            contentTimer = Runnable { advanceToNext() }
                .also { handler.postDelayed(it, duration) }
        }
        preloadNextSubPdf()
    }

    // =========================================================================
    // Advance logic
    // =========================================================================

    private fun advanceToNext() {
        if (!isPlaying || isPaused) return

        // 再生時間外チェック
        if (!scheduleManager.isWithinPlayTime()) {
            addDebugLog("[PLAY] 再生時間外になった → standby")
            contentTimer?.let { handler.removeCallbacks(it) }
            countdownTimer?.let { handler.removeCallbacks(it) }
            isPlaying = false
            pdfFolderSubPlaylist = null
            pdfFolderSubIndex = 0
            currentPdfFolderItem = null
            statusBar.text = "再生時間外 (${scheduleManager.playTimeRange})"
            showStandby()
            startTimeCheck()
            return
        }

        // pdf_folderサブプレイリスト内の場合: WebViewスワップ方式で次の子PDFへ
        if (pdfFolderSubPlaylist != null) {
            addDebugLog("[PLAY] advance: sub ${pdfFolderSubIndex+1}/${pdfFolderSubPlaylist?.size}")
            contentTimer?.let { handler.removeCallbacks(it) }
            countdownTimer?.let { handler.removeCallbacks(it) }
            handler.post { advanceToNextSubPdf() }
            return
        }

        // 通常のメインプレイリスト進行（スケジュール更新はSignageServiceが管理）
        handler.post { doAdvance() }
    }

    /** メインプレイリストを強制的に次のアイテムへ進める（pdf_folder完了時等） */
    private fun advanceToNextMain() {
        contentTimer?.let { handler.removeCallbacks(it) }
        countdownTimer?.let { handler.removeCallbacks(it) }
        scheduleManager.advanceToNext()
        val item = scheduleManager.getCurrentItem()

        // 先読み済みpdf_folderなら即スワップ表示
        if (item != null && item.type == "pdf_folder") {
            val preloadedSub = preloadedPdfFolderSubPlaylist
            if (preloadedSub != null && preloadedSub.isNotEmpty() && nextReady) {
                addDebugLog("[SMB] pdf_folder先読み済み → 即表示 (${preloadedSub.size}件)")

                // nextWebViewに先読みPDFが入っているのでスワップ
                val oldActive = activeWebView
                activeWebView = nextWebView
                nextWebView = oldActive

                // クロスフェード
                activeWebView?.apply {
                    alpha = 0f
                    visibility = View.VISIBLE
                    animate().alpha(1f).setDuration(800).start()
                }
                oldActive?.animate()?.alpha(0f)?.setDuration(800)?.withEndAction {
                    oldActive.visibility = View.INVISIBLE
                }?.start()

                currentPdfFolderItem = preloadedPdfFolderItem ?: item
                pdfFolderSubPlaylist = preloadedSub
                pdfFolderSubIndex = 0
                preloadedPdfFolderSubPlaylist = null
                preloadedPdfFolderItem = null
                nextReady = false
                playCurrentSubPdfAfterSwap()
                return
            }
        }

        playCurrentContent()  // 従来フロー（先読み未完了 or 非pdf_folder）
    }

    private fun preloadBothDirections() {
        nextReady = false
        prevReady = false
        scheduleManager.getNextItem()?.let { nextItem ->
            if (nextItem.type != "pdf_folder") {
                preloadContent(nextWebView!!, nextItem, isPrevPreload = false)
            } else {
                preloadPdfFolder(nextItem) // Webページ表示中にSMB同期+PDF先読み
            }
        }
        scheduleManager.getPreviousItem()?.let { prevItem ->
            if (prevItem.type != "pdf_folder") {
                preloadContent(prevWebView!!, prevItem, isPrevPreload = true)
            } else {
                prevReady = true
            }
        }
    }

    /** pdf_folder先読み: Webページ表示中にSMB同期+1件目PDFをnextWebViewにレンダリング */
    private fun preloadPdfFolder(item: PlaylistItem) {
        val manager = smbPdfManager ?: run {
            nextReady = true
            return
        }
        // nextReady = false のまま（先読み完了時にセット）
        preloadedPdfFolderSubPlaylist = null
        preloadedPdfFolderItem = item

        addDebugLog("[SMB] pdf_folder先読み開始: ${item.name}")

        coroutineScope.launch {
            val (subPlaylist, _) = withContext(Dispatchers.IO) {
                manager.syncFolder(item) { progressMsg ->
                    withContext(Dispatchers.Main) {
                        addDebugLog("[SMB] (先読み) $progressMsg")
                    }
                }
            }

            withContext(Dispatchers.Main) {
                if (subPlaylist.isEmpty()) {
                    addDebugLog("[SMB] pdf_folder先読み: ファイルなし")
                    preloadedPdfFolderSubPlaylist = emptyList()
                    nextReady = true
                    return@withContext
                }

                // サブプレイリストを保存
                preloadedPdfFolderSubPlaylist = subPlaylist
                addDebugLog("[SMB] pdf_folder先読み: ${subPlaylist.size}件 → 1件目PDF先読み開始")

                // 1件目PDFをnextWebViewに先読み
                val subItem = subPlaylist[0]
                val isFirstPageOnly = item.firstPageOnly == true

                if (isFirstPageOnly && subItem.isPortrait
                    && subPlaylist.size > 1 && subPlaylist[1].isPortrait) {
                    loadDualPdfContentForPreload(nextWebView!!, subItem, subPlaylist[1])
                } else {
                    loadSubPdfPreload(nextWebView!!, subItem)
                }
                // レンダリング完了はonPageChangedコールバックでnextReady=trueにセット
            }
        }
    }

    private fun doAdvance() {
        if (!isPlaying || isPaused) return

        if (!nextReady) {
            handler.postDelayed({ doAdvance() }, 200)
            return
        }

        // Rotate: active→prev, next→active, prev→next
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

        // Advance schedule
        scheduleManager.advanceToNext()
        val item = scheduleManager.getCurrentItem() ?: return

        // pdf_folder の場合: 先読み済みなら即サブPL開始、未完了なら従来フロー
        if (item.type == "pdf_folder") {
            val preloadedSub = preloadedPdfFolderSubPlaylist
            if (preloadedSub != null && preloadedSub.isNotEmpty()) {
                // 先読み完了済み → sync画面不要、即サブPL開始
                addDebugLog("[SMB] pdf_folder先読み済み → 即表示 (${preloadedSub.size}件)")
                currentPdfFolderItem = preloadedPdfFolderItem ?: item
                pdfFolderSubPlaylist = preloadedSub
                pdfFolderSubIndex = 0
                preloadedPdfFolderSubPlaylist = null
                preloadedPdfFolderItem = null
                // activeWebViewには先読み済みPDFが表示されている（doAdvanceのスワップ済み）
                playCurrentSubPdfAfterSwap()
            } else {
                // 先読み未完了 → 従来フロー
                startPdfFolderPlayback(item)
            }
            return
        }

        updateStatusBar(item)

        // prevReady is true (old active already had content)
        prevReady = true

        // Schedule next auto-advance
        contentTimer?.let { handler.removeCallbacks(it) }
        val duration = (item.durationSeconds * 1000).toLong()
        contentTimer = Runnable {
            advanceToNext()
        }.also {
            handler.postDelayed(it, duration)
        }

        // Preload next into recycled WebView
        nextReady = false
        scheduleManager.getNextItem()?.let { nextItem ->
            if (nextItem.type != "pdf_folder") {
                preloadContent(nextWebView!!, nextItem, isPrevPreload = false)
            } else {
                preloadPdfFolder(nextItem) // Webページ表示中にSMB同期+PDF先読み
            }
        }
    }

    // =========================================================================
    // Content Loading
    // =========================================================================

    private fun loadContent(webView: WebView, item: PlaylistItem) {
        loadContentInternal(webView, item, preloadType = null)
    }

    /**
     * 2つのPDFの1ページ目を左右に並べて表示する（firstPageOnly + 縦長PDF見開き表示）。
     */
    private fun loadDualPdfContent(webView: WebView, leftItem: PlaylistItem, rightItem: PlaylistItem) {
        webView.setInitialScale(100)
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url?.contains("pdf-viewer.html") == true) {
                    loadDualPdfIntoViewer(webView, leftItem, rightItem)
                }
            }
        }
        webView.loadUrl("file:///android_asset/pdfjs/pdf-viewer.html")
    }

    private fun loadDualPdfIntoViewer(webView: WebView, leftItem: PlaylistItem, rightItem: PlaylistItem) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val leftFile = smbPdfManager?.getLocalPdfFile(leftItem.contentId)
                val rightFile = smbPdfManager?.getLocalPdfFile(rightItem.contentId)

                val leftBytes = leftFile?.takeIf { it.exists() }?.readBytes()
                val rightBytes = rightFile?.takeIf { it.exists() }?.readBytes()

                if (leftBytes != null && rightBytes != null) {
                    val leftBase64 = Base64.encodeToString(leftBytes, Base64.NO_WRAP)
                    val rightBase64 = Base64.encodeToString(rightBytes, Base64.NO_WRAP)
                    withContext(Dispatchers.Main) {
                        webView.evaluateJavascript(
                            "loadDualFirstPages('$leftBase64', '$rightBase64');",
                            null
                        )
                    }
                } else if (leftBytes != null) {
                    // 右側がない場合はシングル表示にフォールバック
                    val base64 = Base64.encodeToString(leftBytes, Base64.NO_WRAP)
                    withContext(Dispatchers.Main) {
                        webView.evaluateJavascript(
                            "loadPdfBase64('$base64', 10, true);",
                            null
                        )
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun preloadContent(webView: WebView, item: PlaylistItem, isPrevPreload: Boolean) {
        loadContentInternal(webView, item, preloadType = if (isPrevPreload) "prev" else "next")
    }

    private fun loadContentInternal(webView: WebView, item: PlaylistItem, preloadType: String?) {
        webView.setInitialScale(100)
        when (item.type) {
            "pdf" -> {
                // pdf_folderの子PDFの場合はSmbPdfManagerから取得
                val cachedFile = if (pdfFolderSubPlaylist != null) {
                    smbPdfManager?.getLocalPdfFile(item.contentId) ?: File("")
                } else {
                    pdfCacheManager.getCachedPdfPath(item.contentId)
                }
                val duration = item.pdfPageDuration ?: 10
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (url?.contains("pdf-viewer.html") == true) {
                            loadPdfIntoViewer(webView, cachedFile, item, duration, preloadType)
                        }
                    }
                }
                webView.loadUrl("file:///android_asset/pdfjs/pdf-viewer.html")
            }
            "web" -> {
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        markPreloadReady(preloadType)
                    }
                }
                val url = item.url ?: return
                webView.loadUrl(url)
            }
        }
    }

    private fun markPreloadReady(preloadType: String?) {
        when (preloadType) {
            "next" -> nextReady = true
            "prev" -> prevReady = true
        }
    }

    private fun loadPdfIntoViewer(webView: WebView, cachedFile: File, item: PlaylistItem, duration: Int, preloadType: String?) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val pdfBytes = if (cachedFile.exists()) {
                    cachedFile.readBytes()
                } else if (pdfFolderSubPlaylist == null) {
                    // 通常PDF: サーバーからダウンロード試行
                    pdfCacheManager.downloadIfNeeded(item)
                    if (cachedFile.exists()) cachedFile.readBytes() else null
                } else {
                    null
                }

                if (pdfBytes != null) {
                    val base64 = Base64.encodeToString(pdfBytes, Base64.NO_WRAP)
                    val firstPageOnly = if (pdfFolderSubPlaylist != null) {
                        currentPdfFolderItem?.firstPageOnly == true
                    } else false
                    withContext(Dispatchers.Main) {
                        webView.evaluateJavascript(
                            "loadPdfBase64('$base64', $duration, $firstPageOnly);",
                            null
                        )
                        markPreloadReady(preloadType)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        markPreloadReady(preloadType)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    markPreloadReady(preloadType)
                }
            }
        }
    }

    private fun showStandby() {
        activeWebView?.loadUrl("file:///android_asset/standby.html")
    }

    private fun startTimeCheck() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                // スケジュール更新はSignageServiceが管理、ここでは再生時間のみチェック
                if (scheduleManager.isWithinPlayTime()) {
                    addDebugLog("[TIME] 再生時間内になった → 再生開始")
                    playCurrentContent()
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

    private fun updateStatusBar(item: PlaylistItem) {
        currentPdfPage = 0
        totalPdfPages = 0
        isAllPagesMode = pdfFolderSubPlaylist != null && item.pdfPageDuration != null
        pdfPageDurationSec = item.pdfPageDuration ?: 10

        if (isAllPagesMode) {
            remainingSeconds = pdfPageDurationSec
            statusBar.text = "${item.name}  |  次のページまで: ${remainingSeconds}秒"
        } else {
            remainingSeconds = item.durationSeconds
            statusBar.text = "${item.name}  |  次の切替まで: ${remainingSeconds}秒"
        }
        startCountdown()
    }

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
                    val item = if (pdfFolderSubPlaylist != null && pdfFolderSubIndex < (pdfFolderSubPlaylist?.size ?: 0)) {
                        pdfFolderSubPlaylist!![pdfFolderSubIndex]
                    } else {
                        scheduleManager.getCurrentItem()
                    }
                    val label = if (isAllPagesMode) "次のページまで" else "次の切替まで"
                    statusBar.text = formatStatusText(
                        item?.name ?: "",
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
        isPlaying = false
        contentTimer?.let { handler.removeCallbacks(it) }
        countdownTimer?.let { handler.removeCallbacks(it) }
        longPressResetRunnable?.let { handler.removeCallbacks(it) }
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
                // pdf_folder先読み（preloadPdfFolder）: nextWebViewでのレンダリング完了
                if (webView != activeWebView && preloadedPdfFolderSubPlaylist != null && !nextReady) {
                    nextReady = true
                    addDebugLog("[SMB] pdf_folder先読みレンダリング完了 (page $current/$total)")
                    return@post
                }

                // サブPDF先読みWebViewからのレンダリング完了通知
                if (webView != activeWebView && pdfFolderSubPlaylist != null && !subNextReady) {
                    subNextReady = true
                    addDebugLog("[SMB] サブPDF先読みレンダリング完了 (page $current/$total)")
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

                val item = if (pdfFolderSubPlaylist != null && pdfFolderSubIndex < (pdfFolderSubPlaylist?.size ?: 0)) {
                    pdfFolderSubPlaylist!![pdfFolderSubIndex]
                } else {
                    scheduleManager.getCurrentItem()
                }
                if (item != null) {
                    val suffix = if (isPaused) {
                        "⏸ 一時停止中 (戻るで再開)"
                    } else {
                        val label = if (isAllPagesMode) "次のページまで" else "次の切替まで"
                        "$label: ${remainingSeconds}秒"
                    }
                    statusBar.text = formatStatusText(item.name, suffix)
                }
            }
        }

        @JavascriptInterface
        fun onPdfViewerReady() {
            // PDF viewer is ready to receive data
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
