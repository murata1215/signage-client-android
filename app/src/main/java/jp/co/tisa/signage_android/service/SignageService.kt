package jp.co.tisa.signage_android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import jp.co.tisa.signage_android.MainActivity
import jp.co.tisa.signage_android.R
import jp.co.tisa.signage_android.data.ConfigManager
import jp.co.tisa.signage_android.data.ServerClient
import kotlinx.coroutines.*

class SignageService : Service() {

    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var heartbeatJob: Job? = null
    private var updateCheckJob: Job? = null

    companion object {
        const val CHANNEL_ID = "signage_service_channel"
        const val NOTIFICATION_ID = 1
        private const val TAG = "SignageService"
        private const val UPDATE_CHECK_INTERVAL_MS = 3600_000L // 1 hour
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        startHeartbeat()
        startUpdateCheck()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "サイネージサービス",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "サイネージクライアントのバックグラウンドサービス"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("サイネージ稼働中")
            .setContentText("コンテンツを表示しています")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = coroutineScope.launch {
            val configManager = ConfigManager(this@SignageService)
            val config = configManager.getConfig() ?: return@launch
            val client = ServerClient(config)
            val intervalMs = (config.pollingIntervalSec * 1000).toLong()

            while (isActive) {
                try {
                    client.sendHeartbeat()
                } catch (e: Exception) {
                    // Ignore heartbeat failures
                }
                delay(intervalMs)
            }
        }
    }

    private fun startUpdateCheck() {
        updateCheckJob?.cancel()
        updateCheckJob = coroutineScope.launch {
            val configManager = ConfigManager(this@SignageService)
            val config = configManager.getConfig() ?: return@launch
            val client = ServerClient(config)
            val updateManager = AppUpdateManager(this@SignageService, client)

            // Initial check after 30 seconds
            delay(30_000)

            while (isActive) {
                try {
                    val updated = updateManager.checkAndUpdate()
                    if (updated) {
                        Log.i(TAG, "Update triggered, install dialog should appear")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Update check failed", e)
                }
                delay(UPDATE_CHECK_INTERVAL_MS)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        heartbeatJob?.cancel()
        updateCheckJob?.cancel()
        coroutineScope.cancel()
    }
}
