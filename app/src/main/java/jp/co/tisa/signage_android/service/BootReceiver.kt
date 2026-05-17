package jp.co.tisa.signage_android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import jp.co.tisa.signage_android.MainActivity
import jp.co.tisa.signage_android.data.ConfigManager

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val configManager = ConfigManager(context)
            if (configManager.isConfigured()) {
                // Launch the main activity which will start the player
                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("auto_start", true)
                }
                context.startActivity(launchIntent)
            }
        }
    }
}
