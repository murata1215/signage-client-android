package jp.co.tisa.signage_android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import jp.co.tisa.signage_android.data.ConfigManager
import jp.co.tisa.signage_android.data.ConnectionTestResult
import jp.co.tisa.signage_android.data.ServerClient
import jp.co.tisa.signage_android.data.SignageConfig
import kotlinx.coroutines.launch

@Composable
fun SetupScreen(
    configManager: ConfigManager,
    onSetupComplete: () -> Unit,
    onBack: (() -> Unit)? = null
) {
    // Load existing config values if available
    val existingConfig = configManager.getConfig()
    var serverUrl by remember { mutableStateOf(existingConfig?.serverUrl ?: "http://") }
    var clientKey by remember { mutableStateOf(existingConfig?.clientKey ?: "") }
    var testResult by remember { mutableStateOf<ConnectionTestResult?>(null) }
    var isTesting by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    val isExistingConfig = existingConfig != null
    val coroutineScope = rememberCoroutineScope()

    // Reset confirmation dialog
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("設定の初期化") },
            text = { Text("サーバーURL・Client Keyを初期化します。\nよろしいですか？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetDialog = false
                        configManager.clearAll()
                        serverUrl = "http://"
                        clientKey = ""
                        testResult = null
                    }
                ) {
                    Text("初期化する")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("キャンセル")
                }
            }
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = if (isExistingConfig) "サイネージクライアント 設定" else "サイネージクライアント 初期設定",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(48.dp))

            OutlinedTextField(
                value = serverUrl,
                onValueChange = {
                    serverUrl = it
                    testResult = null
                },
                label = { Text("サーバー URL") },
                placeholder = { Text("http://192.168.1.100:3000") },
                modifier = Modifier.fillMaxWidth(0.8f),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = clientKey,
                onValueChange = {
                    clientKey = it
                    testResult = null
                },
                label = { Text("Client Key") },
                placeholder = { Text("550e8400-e29b-41d4-a716-446655440000") },
                modifier = Modifier.fillMaxWidth(0.8f),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(32.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Back button (only when editing existing config)
                if (isExistingConfig && onBack != null) {
                    OutlinedButton(onClick = onBack) {
                        Text("戻る")
                    }
                }

                OutlinedButton(
                    onClick = {
                        coroutineScope.launch {
                            isTesting = true
                            testResult = null
                            val config = SignageConfig(
                                serverUrl = serverUrl.trimEnd('/'),
                                clientKey = clientKey.trim()
                            )
                            val client = ServerClient(config)
                            testResult = client.testConnection()
                            isTesting = false
                        }
                    },
                    enabled = serverUrl.length > 7 && clientKey.isNotBlank() && !isTesting
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text("接続テスト")
                }

                Button(
                    onClick = {
                        coroutineScope.launch {
                            isSaving = true
                            configManager.saveConfig(
                                serverUrl = serverUrl.trimEnd('/'),
                                clientKey = clientKey.trim()
                            )
                            isSaving = false
                            onSetupComplete()
                        }
                    },
                    enabled = testResult?.success == true && !isSaving
                ) {
                    Text("保存して開始")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            testResult?.let { result ->
                Card(
                    modifier = Modifier.fillMaxWidth(0.8f),
                    colors = CardDefaults.cardColors(
                        containerColor = if (result.success)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = result.message,
                        modifier = Modifier.padding(16.dp),
                        color = if (result.success)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            // Reset button (only when existing config)
            if (isExistingConfig) {
                Spacer(modifier = Modifier.height(32.dp))
                TextButton(
                    onClick = { showResetDialog = true },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("設定を初期化")
                }
            }
        }
    }
}
