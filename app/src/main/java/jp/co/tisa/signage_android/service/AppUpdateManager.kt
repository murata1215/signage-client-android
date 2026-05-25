package jp.co.tisa.signage_android.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import jp.co.tisa.signage_android.data.ServerClient
import java.io.File
import java.io.FileInputStream

class AppUpdateManager(
    private val context: Context,
    private val serverClient: ServerClient,
    private val onLog: (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "AppUpdateManager"
        private const val APK_FILENAME = "signage-update.apk"
        const val ACTION_INSTALL_RESULT = "jp.co.tisa.signage_android.INSTALL_RESULT"
    }

    private val apkDir: File = File(context.filesDir, "updates").apply {
        if (!exists()) mkdirs()
    }

    private val apkFile: File = File(apkDir, APK_FILENAME)

    fun getCurrentVersionCode(): Int {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get versionCode", e)
            0
        }
    }

    suspend fun checkAndUpdate(): Boolean {
        onLog("チェック開始...")

        val updateInfo = serverClient.checkForUpdate()
        if (updateInfo == null) {
            onLog("サーバー応答なし (APIが未実装またはエラー)")
            return false
        }

        val currentVersion = getCurrentVersionCode()
        onLog("現在v$currentVersion → サーバーv${updateInfo.versionCode} (${updateInfo.versionName})")

        if (updateInfo.versionCode <= currentVersion) {
            onLog("最新版です")
            cleanupApk()
            return false
        }

        onLog("更新あり! DL: ${updateInfo.url.takeLast(40)}")

        val success = serverClient.downloadApk(updateInfo.url, apkFile)
        if (!success || !apkFile.exists()) {
            onLog("ダウンロード失敗")
            return false
        }

        val sizeMb = apkFile.length() / (1024 * 1024)
        onLog("ダウンロード完了 (${sizeMb}MB)")

        triggerInstallViaSession()
        return true
    }

    /**
     * Install APK via PackageInstaller Session API.
     * On Android 12+, self-update (same package name, same signer) can be silent.
     */
    private fun triggerInstallViaSession() {
        try {
            val packageInstaller = context.packageManager.packageInstaller

            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            ).apply {
                setAppPackageName(context.packageName)
            }

            val sessionId = packageInstaller.createSession(params)
            onLog("Session作成: $sessionId")

            val session = packageInstaller.openSession(sessionId)

            // Write APK data to session
            session.openWrite("signage-update", 0, apkFile.length()).use { outputStream ->
                FileInputStream(apkFile).use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
                session.fsync(outputStream)
            }

            onLog("APK書込完了 → commit中...")

            // Create intent for install result
            val intent = Intent(ACTION_INSTALL_RESULT).apply {
                setPackage(context.packageName)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )

            // Commit the session (triggers install)
            session.commit(pendingIntent.intentSender)
            onLog("commit完了 → インストール中...")

        } catch (e: Exception) {
            onLog("Session失敗: ${e.message}")
            Log.e(TAG, "PackageInstaller session failed", e)
            // Fallback to traditional intent install
            onLog("フォールバック: Intent方式で再試行")
            triggerInstallViaIntent()
        }
    }

    /**
     * Fallback: traditional ACTION_VIEW intent install (shows confirmation dialog)
     */
    private fun triggerInstallViaIntent() {
        try {
            val apkUri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
            onLog("Intent方式: インストーラー起動")
        } catch (e: Exception) {
            onLog("Intent方式も失敗: ${e.message}")
            Log.e(TAG, "Fallback install also failed", e)
        }
    }

    private fun cleanupApk() {
        if (apkFile.exists()) {
            apkFile.delete()
        }
    }
}
