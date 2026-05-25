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
    private val serverClient: ServerClient
) {
    companion object {
        private const val TAG = "AppUpdateManager"
        private const val APK_FILENAME = "signage-update.apk"
    }

    private val apkDir: File = File(context.filesDir, "updates").apply {
        if (!exists()) mkdirs()
    }

    private val apkFile: File = File(apkDir, APK_FILENAME)

    /**
     * Get the current app's versionCode
     */
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

    /**
     * Check for updates and install if available.
     * Returns true if an update was found and install was triggered.
     */
    suspend fun checkAndUpdate(): Boolean {
        Log.i(TAG, "Checking for updates...")

        val updateInfo = serverClient.checkForUpdate()
        if (updateInfo == null) {
            Log.i(TAG, "No update info from server (API may not be implemented yet)")
            return false
        }

        val currentVersion = getCurrentVersionCode()
        Log.i(TAG, "Current versionCode: $currentVersion, Server versionCode: ${updateInfo.versionCode}")

        if (updateInfo.versionCode <= currentVersion) {
            Log.i(TAG, "Already up to date")
            cleanupApk()
            return false
        }

        Log.i(TAG, "Update available: ${updateInfo.versionName} (${updateInfo.versionCode})")

        // Download APK
        val success = serverClient.downloadApk(updateInfo.url, apkFile)
        if (!success || !apkFile.exists()) {
            Log.e(TAG, "Failed to download APK")
            return false
        }

        Log.i(TAG, "APK downloaded: ${apkFile.length()} bytes")

        // Trigger install
        triggerInstall()
        return true
    }

    /**
     * Trigger APK installation via Intent.
     * On non-Device Owner devices, this will show a confirmation dialog.
     */
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
            Log.i(TAG, "Install intent launched")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trigger install", e)
        }
    }

    /**
     * Clean up downloaded APK file
     */
    private fun cleanupApk() {
        if (apkFile.exists()) {
            apkFile.delete()
        }
    }
}
