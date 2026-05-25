package jp.co.tisa.signage_android.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import jp.co.tisa.signage_android.data.ServerClient
import jp.co.tisa.signage_android.data.UpdateInfo
import java.io.File

class AppUpdateManager(
    private val context: Context,
    private val serverClient: ServerClient,
    private val onLog: (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "AppUpdateManager"
        private const val APK_FILENAME = "signage-update.apk"
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
        onLog("ダウンロード完了 (${sizeMb}MB) インストーラー起動")

        triggerInstall()
        return true
    }

    private fun triggerInstall() {
        try {
            val apkUri: Uri = FileProvider.getUriForFile(
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
            onLog("インストーラー起動完了")
        } catch (e: Exception) {
            onLog("インストール失敗: ${e.message}")
            Log.e(TAG, "Failed to trigger install", e)
        }
    }

    private fun cleanupApk() {
        if (apkFile.exists()) {
            apkFile.delete()
        }
    }
}
