package jp.co.tisa.signage_android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

class InstallResultReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "InstallResultReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""

        // Send log to PlayerActivity debug overlay
        val logIntent = Intent(SignageService.ACTION_UPDATE_LOG)

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // User confirmation needed (non-Device Owner)
                val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirmIntent)
                    logIntent.putExtra(SignageService.EXTRA_LOG_MESSAGE, "ユーザー確認が必要です")
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "Install succeeded")
                logIntent.putExtra(SignageService.EXTRA_LOG_MESSAGE, "インストール成功! アプリ再起動...")
            }
            PackageInstaller.STATUS_FAILURE,
            PackageInstaller.STATUS_FAILURE_ABORTED,
            PackageInstaller.STATUS_FAILURE_BLOCKED,
            PackageInstaller.STATUS_FAILURE_CONFLICT,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
            PackageInstaller.STATUS_FAILURE_INVALID,
            PackageInstaller.STATUS_FAILURE_STORAGE -> {
                Log.e(TAG, "Install failed: status=$status msg=$message")
                logIntent.putExtra(SignageService.EXTRA_LOG_MESSAGE, "インストール失敗: $message (status=$status)")
            }
            else -> {
                Log.w(TAG, "Unknown install status: $status msg=$message")
                logIntent.putExtra(SignageService.EXTRA_LOG_MESSAGE, "不明なステータス: $status")
            }
        }

        context.sendBroadcast(logIntent)
    }
}
