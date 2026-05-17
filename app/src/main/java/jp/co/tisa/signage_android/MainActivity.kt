package jp.co.tisa.signage_android

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import jp.co.tisa.signage_android.data.ConfigManager
import jp.co.tisa.signage_android.player.PlayerActivity
import jp.co.tisa.signage_android.service.SignageService
import jp.co.tisa.signage_android.ui.SetupScreen
import jp.co.tisa.signage_android.ui.theme.SignageandroidTheme

class MainActivity : ComponentActivity() {

    private lateinit var configManager: ConfigManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        configManager = ConfigManager(this)

        // If already configured, go directly to player
        if (configManager.isConfigured()) {
            launchPlayer()
            return
        }

        // Show setup screen
        setContent {
            SignageandroidTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SetupScreen(
                        configManager = configManager,
                        onSetupComplete = {
                            launchPlayer()
                        }
                    )
                }
            }
        }
    }

    private fun launchPlayer() {
        // Start foreground service for heartbeat
        val serviceIntent = Intent(this, SignageService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // Launch player activity
        val playerIntent = Intent(this, PlayerActivity::class.java)
        startActivity(playerIntent)
        finish()
    }
}
