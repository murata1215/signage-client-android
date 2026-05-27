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
    private var isDebugVisible = true
    private val DEBUG_HEADER = "***デバッグウインドウ表示中 この画面はリモコンの下ボタン(V)で消えます***"

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

        // Register broadcast receiver for update logs
        val filter = IntentFilter(jp.co.tisa.signage_android.service.SignageService.ACTION_UPDATE_LOG)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateLogReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(updateLogReceiver, filter)
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
        debugTextView.text = DEBUG_HEADER + "\n" + debugLines.joinToString("\n")
    }

    private fun toggleDebugOverlay() {
        isDebugVisible = !isDebugVisible
        debugTextView.visibility = if (isDebugVisible) View.VISIBLE else View.GONE
        if (isDebugVisible) {
            addDebugLog("[DEBUG] overlay ON")
        }
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

        // KEYCODE_BOOKMARK=93 (DS-STBRC03 remote) toggles debug overlay
        if (keyCode == 93) {
            toggleDebugOverlay()
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

        // Debug overlay: 1/4 screen, top-right
        val displayMetrics = resources.displayMetrics
        val quarterWidth = displayMetrics.widthPixels / 2
        val quarterHeight = displayMetrics.heightPixels / 2
        debugTextView = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                quarterWidth,
                quarterHeight
            ).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.END
            }
            setBackgroundColor(0xCC000000.toInt())
            setTextColor(0xFF00FF00.toInt()) // Green text
            textSize = 13f
            setPadding(16, 12, 16, 12)
            text = DEBUG_HEADER + "\n[UPDATE] 待機中..."
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
        startPolling()
    }

    private fun playCurrentContent() {
        val item = scheduleManager.getCurrentItem() ?: run {
            addDebugLog("[PLAY] getCurrentItem=null")
            return
        }

        addDebugLog("[PLAY] #${item.displayOrder} type=${item.type} name=${item.name}")

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
        statusBar.text = "SMB同期中: ${item.smbPath ?: ""}"

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
        playNextSubPdf()
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
    }

    // =========================================================================
    // Advance logic
    // =========================================================================

    private fun advanceToNext() {
        if (!isPlaying || isPaused) return

        // pdf_folderサブプレイリスト内の場合: 次の子PDFへ
        if (pdfFolderSubPlaylist != null) {
            addDebugLog("[PLAY] advance: sub ${pdfFolderSubIndex+1}/${pdfFolderSubPlaylist?.size}")
            pdfFolderSubIndex++
            contentTimer?.let { handler.removeCallbacks(it) }
            countdownTimer?.let { handler.removeCallbacks(it) }
            handler.post { playNextSubPdf() }
            return
        }

        // 通常のメインプレイリスト進行
        coroutineScope.launch {
            val updated = scheduleManager.checkForUpdate()
            if (updated) {
                val items = scheduleManager.playlist
                pdfCacheManager.downloadAll(items)
                val activeIds = items.filter { it.type == "pdf" }.map { it.contentId }.toSet()
                pdfCacheManager.cleanupUnused(activeIds)
            }
            handler.post { doAdvance() }
        }
    }

    /** メインプレイリストを強制的に次のアイテムへ進める（pdf_folder完了時等） */
    private fun advanceToNextMain() {
        contentTimer?.let { handler.removeCallbacks(it) }
        countdownTimer?.let { handler.removeCallbacks(it) }
        scheduleManager.advanceToNext()
        playCurrentContent()
    }

    private fun preloadBothDirections() {
        nextReady = false
        prevReady = false
        scheduleManager.getNextItem()?.let { nextItem ->
            if (nextItem.type != "pdf_folder") {
                preloadContent(nextWebView!!, nextItem, isPrevPreload = false)
            } else {
                nextReady = true // pdf_folderはプリロード不要
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

        // pdf_folder の場合は専用フローへ
        if (item.type == "pdf_folder") {
            startPdfFolderPlayback(item)
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
                nextReady = true
            }
        }
    }

    // =========================================================================
    // Content Loading
    // =========================================================================

    private fun loadContent(webView: WebView, item: PlaylistItem) {
        loadContentInternal(webView, item, preloadType = null)
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
                if (scheduleManager.isWithinPlayTime()) {
                    coroutineScope.launch {
                        playCurrentContent()
                        startPolling()
                    }
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

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = coroutineScope.launch {
            while (isActive) {
                delay((configManager.getPollingInterval() * 1000).toLong())
                val updated = scheduleManager.checkForUpdate()
                if (updated) {
                    val items = scheduleManager.playlist
                    pdfCacheManager.downloadAll(items)
                    val activeIds = items.filter { it.type == "pdf" }.map { it.contentId }.toSet()
                    pdfCacheManager.cleanupUnused(activeIds)
                    playCurrentContent()
                }
            }
        }
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
