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

    // 3-WebView architecture: active + next (preloaded) + prev (preloaded)
    private var activeWebView: WebView? = null
    private var nextWebView: WebView? = null
    private var prevWebView: WebView? = null

    private val handler = Handler(Looper.getMainLooper())
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var contentTimer: Runnable? = null
    private var countdownTimer: Runnable? = null
    private var remainingSeconds: Int = 0
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
        val config = configManager.getConfig() ?: run {
            finish()
            return
        }
        serverClient = ServerClient(config)
        scheduleManager = ScheduleManager(configManager, serverClient)
        pdfCacheManager = PdfCacheManager(this, serverClient)

        // Register broadcast receiver for update logs
        val filter = IntentFilter(jp.co.tisa.signage_android.service.SignageService.ACTION_UPDATE_LOG)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateLogReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(updateLogReceiver, filter)
        }

        // Start playback
        coroutineScope.launch {
            startPlayback()
        }
    }

    private fun addDebugLog(message: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        debugLines.add("[$time] $message")
        if (debugLines.size > 10) {
            debugLines.removeAt(0)
        }
        debugTextView.text = debugLines.joinToString("\n")
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

        // F4 key toggles debug overlay (works in any mode)
        if (keyCode == KeyEvent.KEYCODE_F4) {
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
            // Note: F4 is handled above (debug overlay toggle)
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
            statusBar.text = "${item.name}  |  ⏸ 一時停止中 (戻るで再開)"
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
            statusBar.text = "${item.name}  |  ⏸ 一時停止中 (戻るで再開)"
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
        nextWebView = oldActive  // Current content becomes "next" (one step forward)
        prevWebView = oldNext    // Old next becomes prev (will be reloaded)

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
        statusBar.text = "${item?.name ?: ""}  |  ⏸ 一時停止中 (戻るで再開)"

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

        // Debug overlay for update logs (top-right)
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
            text = "[UPDATE] 待機中..."
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
        return WebView(this).apply {
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
            addJavascriptInterface(PdfJsInterface(), "SignageInterface")
            setBackgroundColor(0xFF000000.toInt())
        }
    }

    // =========================================================================
    // Playback Logic
    // =========================================================================

    private suspend fun startPlayback() {
        val schedule = scheduleManager.loadSchedule()
        if (schedule == null) {
            showStandby()
            return
        }

        pdfCacheManager.downloadAll(schedule.playlist)
        val activeIds = schedule.playlist.filter { it.type == "pdf" }.map { it.contentId }.toSet()
        pdfCacheManager.cleanupUnused(activeIds)

        if (!scheduleManager.isWithinPlayTime()) {
            showStandby()
            startTimeCheck()
            return
        }

        playCurrentContent()
        startPolling()
    }

    private fun playCurrentContent() {
        val item = scheduleManager.getCurrentItem() ?: return
        isPlaying = true
        isPaused = false
        disableWebViewInteraction()
        loadContent(activeWebView!!, item)
        updateStatusBar(item)

        // Schedule next content
        contentTimer?.let { handler.removeCallbacks(it) }
        val duration = (item.durationSeconds * 1000).toLong()
        contentTimer = Runnable {
            advanceToNext()
        }.also {
            handler.postDelayed(it, duration)
        }

        // Preload both next and previous
        preloadBothDirections()
    }

    private fun preloadBothDirections() {
        nextReady = false
        prevReady = false
        scheduleManager.getNextItem()?.let { nextItem ->
            preloadContent(nextWebView!!, nextItem, isPrevPreload = false)
        }
        scheduleManager.getPreviousItem()?.let { prevItem ->
            preloadContent(prevWebView!!, prevItem, isPrevPreload = true)
        }
    }

    private fun advanceToNext() {
        if (!isPlaying || isPaused) return

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
        prevWebView = oldActive  // Current content becomes "prev" (one step back)
        nextWebView = oldPrev    // Old prev becomes next (will be reloaded)

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
            preloadContent(nextWebView!!, nextItem, isPrevPreload = false)
        }
    }

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
                val cachedFile = pdfCacheManager.getCachedPdfPath(item.contentId)
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
                } else {
                    pdfCacheManager.downloadIfNeeded(item)
                    if (cachedFile.exists()) cachedFile.readBytes() else null
                }

                if (pdfBytes != null) {
                    val base64 = Base64.encodeToString(pdfBytes, Base64.NO_WRAP)
                    withContext(Dispatchers.Main) {
                        webView.evaluateJavascript(
                            "loadPdfBase64('$base64', $duration);",
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
        remainingSeconds = item.durationSeconds
        statusBar.text = "${item.name}  |  次の切替まで: ${remainingSeconds}秒"
        startCountdown()
    }

    private fun startCountdown() {
        countdownTimer?.let { handler.removeCallbacks(it) }
        countdownTimer = object : Runnable {
            override fun run() {
                remainingSeconds--
                if (remainingSeconds >= 0) {
                    val item = scheduleManager.getCurrentItem()
                    statusBar.text = "${item?.name ?: ""}  |  次の切替まで: ${remainingSeconds}秒"
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

    inner class PdfJsInterface {
        @JavascriptInterface
        fun onPageChanged(current: Int, total: Int) {
            handler.post {
                val item = scheduleManager.getCurrentItem()
                if (item != null) {
                    if (isPaused) {
                        statusBar.text = "${item.name} ($current/$total)  |  ⏸ 一時停止中 (戻るで再開)"
                    } else {
                        statusBar.text = "${item.name} ($current/$total)  |  次の切替まで: ${remainingSeconds}秒"
                    }
                }
            }
        }

        @JavascriptInterface
        fun onPdfViewerReady() {
            // PDF viewer is ready to receive data
        }
    }
}
