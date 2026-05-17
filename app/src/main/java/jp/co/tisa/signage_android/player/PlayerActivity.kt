package jp.co.tisa.signage_android.player

import android.annotation.SuppressLint
import android.content.Intent
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
    private lateinit var statusBar: TextView

    private var activeWebView: WebView? = null
    private var standbyWebView: WebView? = null

    private val handler = Handler(Looper.getMainLooper())
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var contentTimer: Runnable? = null
    private var countdownTimer: Runnable? = null
    private var remainingSeconds: Int = 0
    private var pollingJob: Job? = null
    private var isPlaying = false
    private var isPaused = false
    private var standbyReady = false

    // Long-press (5 sec) to reset to setup screen
    private val LONG_PRESS_RESET_MS = 5000L
    private var longPressResetRunnable: Runnable? = null

    private lateinit var gestureDetector: GestureDetector

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

        // Start playback
        coroutineScope.launch {
            startPlayback()
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
                        // Swipe right -> previous
                        goToPrevious()
                    } else {
                        // Swipe left -> next
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
                // Single tap does nothing (avoids conflict with double tap)
                return false
            }
        })
    }

    // =========================================================================
    // Key Events (Remote Control)
    // =========================================================================

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_CHANNEL_UP -> {
                goToNext()
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                goToPrevious()
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                togglePause()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    // =========================================================================
    // Playback Control (next / previous / pause)
    // =========================================================================

    private fun goToNext() {
        if (!isPlaying) return
        if (isPaused) {
            // If paused, just advance without resuming auto-rotation
            scheduleManager.advanceToNext()
            val item = scheduleManager.getCurrentItem() ?: return
            loadContent(activeWebView!!, item)
            statusBar.text = "${item.name}  |  ⏸ 一時停止中"
        } else {
            advanceToNext()
        }
    }

    private fun goToPrevious() {
        if (!isPlaying) return
        if (isPaused) {
            // If paused, just go back without resuming auto-rotation
            scheduleManager.goToPrevious()
            val item = scheduleManager.getCurrentItem() ?: return
            loadContent(activeWebView!!, item)
            statusBar.text = "${item.name}  |  ⏸ 一時停止中"
        } else {
            // Cancel current timer, go to previous, restart timer
            contentTimer?.let { handler.removeCallbacks(it) }
            countdownTimer?.let { handler.removeCallbacks(it) }

            scheduleManager.goToPrevious()
            val item = scheduleManager.getCurrentItem() ?: return
            loadContent(activeWebView!!, item)
            updateStatusBar(item)

            // Schedule next auto-advance
            val duration = (item.durationSeconds * 1000).toLong()
            contentTimer = Runnable { advanceToNext() }.also {
                handler.postDelayed(it, duration)
            }
        }
    }

    private fun togglePause() {
        if (!isPlaying) return

        isPaused = !isPaused
        val item = scheduleManager.getCurrentItem()

        if (isPaused) {
            // Stop auto-rotation
            contentTimer?.let { handler.removeCallbacks(it) }
            countdownTimer?.let { handler.removeCallbacks(it) }
            statusBar.text = "${item?.name ?: ""}  |  ⏸ 一時停止中"
        } else {
            // Resume auto-rotation
            if (item != null) {
                updateStatusBar(item)
                val duration = (item.durationSeconds * 1000).toLong()
                contentTimer = Runnable { advanceToNext() }.also {
                    handler.postDelayed(it, duration)
                }
            }
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

        containerLayout.addView(webViewA)
        containerLayout.addView(webViewB)

        // Transparent touch overlay for gesture detection (above WebViews)
        val touchOverlay = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setOnTouchListener { _, event ->
                gestureDetector.onTouchEvent(event)
                // 5-second long press to reset to setup screen
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        longPressResetRunnable?.let { handler.removeCallbacks(it) }
                        longPressResetRunnable = Runnable { resetToSetup() }
                        handler.postDelayed(longPressResetRunnable!!, LONG_PRESS_RESET_MS)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        longPressResetRunnable?.let { handler.removeCallbacks(it) }
                    }
                    MotionEvent.ACTION_MOVE -> {
                        // Cancel if finger moves too much (not a hold)
                    }
                }
                true // Always consume to receive full gesture sequence
            }
        }
        containerLayout.addView(touchOverlay)

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
        }
        containerLayout.addView(statusBar)

        setContentView(containerLayout)

        activeWebView = webViewA
        standbyWebView = webViewB
        webViewB.visibility = View.INVISIBLE
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
                // Set Chrome-like User-Agent to avoid bot detection
                userAgentString = settings.userAgentString.replace(
                    Regex("\\bwv\\b"), ""
                ) + " Chrome/120.0.0.0"
            }
            setInitialScale(100)
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
        // Load schedule
        val schedule = scheduleManager.loadSchedule()
        if (schedule == null) {
            showStandby()
            return
        }

        // Download PDFs
        pdfCacheManager.downloadAll(schedule.playlist)

        // Cleanup unused cache
        val activeIds = schedule.playlist.filter { it.type == "pdf" }.map { it.contentId }.toSet()
        pdfCacheManager.cleanupUnused(activeIds)

        // Check play time
        if (!scheduleManager.isWithinPlayTime()) {
            showStandby()
            startTimeCheck()
            return
        }

        // Start content rotation
        playCurrentContent()

        // Start background polling
        startPolling()
    }

    private fun playCurrentContent() {
        val item = scheduleManager.getCurrentItem() ?: return
        isPlaying = true
        isPaused = false
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

        // Preload next content into standby WebView
        standbyReady = false
        scheduleManager.getNextItem()?.let { nextItem ->
            preloadContent(standbyWebView!!, nextItem)
        }
    }

    private fun advanceToNext() {
        if (!isPlaying || isPaused) return

        // Check for schedule update at content switch time
        coroutineScope.launch {
            val updated = scheduleManager.checkForUpdate()
            if (updated) {
                // Download new PDFs if schedule changed
                val items = scheduleManager.playlist
                pdfCacheManager.downloadAll(items)
                val activeIds = items.filter { it.type == "pdf" }.map { it.contentId }.toSet()
                pdfCacheManager.cleanupUnused(activeIds)
            }
            // Perform the actual content switch on main thread
            handler.post { doAdvance() }
        }
    }

    private fun doAdvance() {
        if (!isPlaying || isPaused) return

        // If standby is not ready yet, wait a bit and retry
        if (!standbyReady) {
            handler.postDelayed({ doAdvance() }, 200)
            return
        }

        // Swap WebViews with crossfade (standby already has preloaded content)
        val temp = activeWebView
        activeWebView = standbyWebView
        standbyWebView = temp

        activeWebView?.apply {
            alpha = 0f
            visibility = View.VISIBLE
            animate().alpha(1f).setDuration(800).start()
        }
        standbyWebView?.animate()?.alpha(0f)?.setDuration(800)?.withEndAction {
            standbyWebView?.visibility = View.INVISIBLE
        }?.start()

        // Advance schedule (standby had the next item preloaded)
        scheduleManager.advanceToNext()
        val item = scheduleManager.getCurrentItem() ?: return
        updateStatusBar(item)

        // Schedule next auto-advance
        contentTimer?.let { handler.removeCallbacks(it) }
        val duration = (item.durationSeconds * 1000).toLong()
        contentTimer = Runnable {
            advanceToNext()
        }.also {
            handler.postDelayed(it, duration)
        }

        // Preload the NEXT content into new standby WebView
        standbyReady = false
        scheduleManager.getNextItem()?.let { nextItem ->
            preloadContent(standbyWebView!!, nextItem)
        }
    }

    private fun loadContent(webView: WebView, item: PlaylistItem) {
        loadContentInternal(webView, item, isPreload = false)
    }

    private fun preloadContent(webView: WebView, item: PlaylistItem) {
        loadContentInternal(webView, item, isPreload = true)
    }

    private fun loadContentInternal(webView: WebView, item: PlaylistItem, isPreload: Boolean) {
        // Reset zoom to prevent scale leaking between different contents
        webView.setInitialScale(100)
        when (item.type) {
            "pdf" -> {
                val cachedFile = pdfCacheManager.getCachedPdfPath(item.contentId)
                val duration = item.pdfPageDuration ?: 10
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (url?.contains("pdf-viewer.html") == true) {
                            loadPdfIntoViewer(webView, cachedFile, item, duration, isPreload)
                        }
                    }
                }
                webView.loadUrl("file:///android_asset/pdfjs/pdf-viewer.html")
            }
            "web" -> {
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (isPreload) {
                            standbyReady = true
                        }
                    }
                }
                val url = item.url ?: return
                webView.loadUrl(url)
            }
        }
    }

    private fun loadPdfIntoViewer(webView: WebView, cachedFile: File, item: PlaylistItem, duration: Int, isPreload: Boolean) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val pdfBytes = if (cachedFile.exists()) {
                    cachedFile.readBytes()
                } else {
                    // Try to download on the fly
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
                        if (isPreload) {
                            standbyReady = true
                        }
                    }
                } else if (isPreload) {
                    withContext(Dispatchers.Main) {
                        standbyReady = true // Don't block rotation even if PDF failed
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                if (isPreload) {
                    withContext(Dispatchers.Main) {
                        standbyReady = true
                    }
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
                    handler.postDelayed(this, 60_000) // Check every minute
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
                    // Re-download PDFs for new schedule
                    val items = scheduleManager.playlist
                    pdfCacheManager.downloadAll(items)
                    val activeIds = items.filter { it.type == "pdf" }.map { it.contentId }.toSet()
                    pdfCacheManager.cleanupUnused(activeIds)
                    // Restart playback
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
        // Stop playback
        isPlaying = false
        contentTimer?.let { handler.removeCallbacks(it) }
        countdownTimer?.let { handler.removeCallbacks(it) }
        pollingJob?.cancel()

        // Stop foreground service
        stopService(Intent(this, jp.co.tisa.signage_android.service.SignageService::class.java))

        // Clear config so setup screen appears
        configManager.clearAll()

        // Launch MainActivity (will show setup screen)
        val intent = Intent(this, jp.co.tisa.signage_android.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
        finish()
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    override fun onDestroy() {
        super.onDestroy()
        isPlaying = false
        contentTimer?.let { handler.removeCallbacks(it) }
        countdownTimer?.let { handler.removeCallbacks(it) }
        longPressResetRunnable?.let { handler.removeCallbacks(it) }
        pollingJob?.cancel()
        coroutineScope.cancel()
        webViewA.destroy()
        webViewB.destroy()
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
                        statusBar.text = "${item.name} ($current/$total)  |  ⏸ 一時停止中"
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
