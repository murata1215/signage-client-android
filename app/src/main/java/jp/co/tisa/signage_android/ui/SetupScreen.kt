package jp.co.tisa.signage_android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import jp.co.tisa.signage_android.data.UnassignedClient
import kotlinx.coroutines.launch

private const val DEFAULT_SERVER_URL = "https://service.internal.atg.co.jp/tsinternal/signage-server-windows"

@Composable
fun SetupScreen(
    configManager: ConfigManager,
    onSetupComplete: () -> Unit,
    onBack: (() -> Unit)? = null
) {
    // Load existing config values if available
    val existingConfig = configManager.getConfig()
    var serverUrl by remember { mutableStateOf(existingConfig?.serverUrl ?: DEFAULT_SERVER_URL) }
    var clientKey by remember { mutableStateOf(existingConfig?.clientKey ?: "") }
    var testResult by remember { mutableStateOf<ConnectionTestResult?>(null) }
    var isTesting by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    // 未割当クライアント選択用
    var unassignedClients by remember { mutableStateOf<List<UnassignedClient>?>(null) }
    var isFetchingClients by remember { mutableStateOf(false) }
    var fetchClientsError by remember { mutableStateOf<String?>(null) }
    var connectingClientId by remember { mutableStateOf<Int?>(null) }
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
                        serverUrl = DEFAULT_SERVER_URL
                        clientKey = ""
                        testResult = null
                        unassignedClients = null
                        fetchClientsError = null
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
                .verticalScroll(rememberScrollState())
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = if (isExistingConfig) "サイネージクライアント 設定" else "サイネージクライアント 初期設定",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(32.dp))

            OutlinedTextField(
                value = serverUrl,
                onValueChange = {
                    serverUrl = it
                    testResult = null
                    unassignedClients = null
                    fetchClientsError = null
                },
                label = { Text("サーバー URL") },
                placeholder = { Text(DEFAULT_SERVER_URL) },
                modifier = Modifier.fillMaxWidth(0.8f),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 未割当クライアント取得ボタン
            Button(
                onClick = {
                    coroutineScope.launch {
                        isFetchingClients = true
                        fetchClientsError = null
                        unassignedClients = null
                        testResult = null
                        val client = ServerClient(SignageConfig(serverUrl.trimEnd('/'), ""))
                        val clients = client.fetchUnassignedClients()
                        if (clients == null) {
                            fetchClientsError = "未割当クライアントの取得に失敗しました\n（サーバー未対応またはネットワークエラー。下の Client Key 手入力をご利用ください）"
                        } else {
                            unassignedClients = clients
                        }
                        isFetchingClients = false
                    }
                },
                enabled = serverUrl.length > 7 && !isFetchingClients && connectingClientId == null
            ) {
                if (isFetchingClients) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("未割当クライアントを取得")
            }

            // 取得エラー表示
            fetchClientsError?.let { error ->
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(0.8f),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = error,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            // 未割当クライアント一覧
            unassignedClients?.let { clients ->
                Spacer(modifier = Modifier.height(12.dp))
                if (clients.isEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(0.8f),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Text(
                            text = "未割当のクライアントがありません\n（サーバー管理画面でクライアントを追加してください）",
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Text(
                        text = "クライアントを選択すると接続して開始します",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Column(
                        modifier = Modifier.fillMaxWidth(0.8f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        clients.forEach { client ->
                            OutlinedButton(
                                onClick = {
                                    coroutineScope.launch {
                                        connectingClientId = client.id
                                        clientKey = client.clientKey
                                        testResult = null
                                        val config = SignageConfig(
                                            serverUrl = serverUrl.trimEnd('/'),
                                            clientKey = client.clientKey
                                        )
                                        val result = ServerClient(config).testConnection()
                                        testResult = result
                                        if (result.success) {
                                            configManager.saveConfig(
                                                serverUrl = serverUrl.trimEnd('/'),
                                                clientKey = client.clientKey
                                            )
                                            onSetupComplete()
                                        } else {
                                            connectingClientId = null
                                        }
                                    }
                                },
                                enabled = connectingClientId == null || connectingClientId == client.id,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (connectingClientId == client.id) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Text(client.name)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            HorizontalDivider(modifier = Modifier.fillMaxWidth(0.8f))

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = clientKey,
                onValueChange = {
                    clientKey = it
                    testResult = null
                },
                label = { Text("Client Key (手入力)") },
                placeholder = { Text("550e8400-e29b-41d4-a716-446655440000") },
                modifier = Modifier.fillMaxWidth(0.8f),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(24.dp))

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
