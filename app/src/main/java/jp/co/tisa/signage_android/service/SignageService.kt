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
        private const val UPDATE_CHECK_INTERVAL_MS = 60_000L // 1 minute
        const val ACTION_UPDATE_LOG = "jp.co.tisa.signage_android.UPDATE_LOG"
        const val ACTION_SCHEDULE_UPDATED = "jp.co.tisa.signage_android.SCHEDULE_UPDATED"
        const val ACTION_HEARTBEAT = "jp.co.tisa.signage_android.HEARTBEAT"
        const val EXTRA_LOG_MESSAGE = "log_message"
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
                    sendBroadcast(Intent(ACTION_HEARTBEAT))
                } catch (e: Exception) {
                    // Ignore heartbeat failures
                }
                delay(intervalMs)
            }
        }
    }

    private fun sendUpdateLog(message: String) {
        Log.i(TAG, "[Update] $message")
        val intent = Intent(ACTION_UPDATE_LOG).apply {
            putExtra(EXTRA_LOG_MESSAGE, message)
        }
        sendBroadcast(intent)
    }

    private fun startUpdateCheck() {
        updateCheckJob?.cancel()
        updateCheckJob = coroutineScope.launch {
            val configManager = ConfigManager(this@SignageService)
            val config = configManager.getConfig() ?: return@launch
            val client = ServerClient(config)
            val updateManager = AppUpdateManager(this@SignageService, client) { msg ->
                sendUpdateLog(msg)
            }

            sendUpdateLog("アップデート監視開始 (${UPDATE_CHECK_INTERVAL_MS / 1000}秒間隔)")

            // Initial check after 10 seconds
            delay(10_000)

            while (isActive) {
                try {
                    updateManager.checkAndUpdate()
                } catch (e: Exception) {
                    sendUpdateLog("チェック失敗: ${e.message}")
                }

                // スケジュール更新チェック
                try {
                    sendUpdateLog("[SCHEDULE] チェック開始")
                    val schedule = client.fetchSchedule()
                    if (schedule != null) {
                        val cachedVersion = configManager.getCachedVersion()
                        if (schedule.version != cachedVersion) {
                            sendUpdateLog("[SCHEDULE] 更新あり → 更新実施中 (v${cachedVersion} → v${schedule.version})")
                            configManager.cacheSchedule(schedule)
                            sendBroadcast(Intent(ACTION_SCHEDULE_UPDATED))
                            sendUpdateLog("[SCHEDULE] 更新完了")
                        } else {
                            sendUpdateLog("[SCHEDULE] 更新なし (v${schedule.version})")
                        }
                    }
                } catch (e: Exception) {
                    sendUpdateLog("[SCHEDULE] チェック失敗: ${e.message}")
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
