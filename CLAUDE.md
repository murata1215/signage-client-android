# signage-android

## Overview
Android版サイネージクライアント。サーバー(signage-server)からスケジュールを取得し、Web/PDFコンテンツをローテーション表示する専用端末向けアプリ。

## Build
```bash
./gradlew assembleDebug    # デバッグビルド
./gradlew assembleRelease  # リリースビルド
```

## Architecture
- **WebViewベース**: コンテンツ表示はWebView x3 (active+next+prev 3枚ローテーション切替)
- **PDF表示**: assets内のpdf-viewer.html + PDF.js 3.x (CDN) + Base64データ注入(CORS回避)
- **先読み**: next/prev両方向を事前ロード、完了後にフェード切替(ちらつき防止)
- **自動アップデート**: PackageInstaller Session APIでOTA更新 (1分間隔チェック)
- **プロキシ**: OkHttp で社内プロキシ(210.175.128.100:8080)経由、ローカルIPはバイパス
- **キオスク**: 画面常時ON、フルスクリーン、Boot時自動起動
- **操作**: リモコン(DPAD) + タッチジェスチャー(スワイプ/ダブルタップ)対応
- **ターゲット端末**: DS-ASTBX5 (Android STB)

## Package Structure
```
jp.co.tisa.signage_android/
├── MainActivity.kt          # エントリポイント(Setup or Player起動)
├── data/
│   ├── Models.kt            # データクラス(ScheduleResponse, PlaylistItem等)
│   ├── ConfigManager.kt     # SharedPreferences設定管理
│   └── ServerClient.kt      # OkHttp API通信(プロキシ対応)
├── player/
│   ├── PlayerActivity.kt    # WebView再生画面(フルスクリーン)
│   ├── ScheduleManager.kt   # スケジュール取得・ポーリング・時間帯判定
│   └── PdfCacheManager.kt   # PDFダウンロード・キャッシュ管理
├── service/
│   ├── SignageService.kt     # Foreground Service(ハートビート + アップデートチェック)
│   ├── AppUpdateManager.kt   # OTA自動アップデート(PackageInstaller Session)
│   ├── InstallResultReceiver.kt # インストール結果受信
│   └── BootReceiver.kt      # BOOT_COMPLETED自動起動
└── ui/
    ├── SetupScreen.kt        # 初回設定画面(Compose)
    └── theme/                # テーマ
```

## Server API (Player endpoints)
- GET `/api/player/schedule?key={client_key}` - スケジュール取得
- POST `/api/player/heartbeat?key={client_key}` - ハートビート
- GET `/api/player/content/:id/file?key={client_key}` - PDFダウンロード
- GET `/api/player/update/check?key={client_key}` - アップデートチェック
- GET `/api/player/update/download?key={client_key}` - APKダウンロード

## Key Design Decisions
- minSdk = 24, targetSdk = 36
- Kotlin + Jetpack Compose (Setup画面のみ) + WebView (コンテンツ表示)
- OkHttp for HTTP (プロキシ設定が容易、response.use{}で確実にclose)
- Gson for JSON serialization
- Foreground Service for heartbeat (Android制約対応)
- PDF表示はBase64注入方式 (file://間のCORS制約回避)
- WebView 3枚(active+next+prev)先読み+readyフラグでちらつき防止
- PackageInstaller Session APIでサイレント自動アップデート(フォールバック: Intent方式)
- dispatchKeyEvent でリモコンキーをView階層より先に捕捉
- setInitialScale(100) + loadWithOverviewMode=false でズーム固定
