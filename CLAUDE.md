# signage-android

## Overview
Android版サイネージクライアント。サーバー(signage-server)からスケジュールを取得し、Web/PDFコンテンツをローテーション表示する専用端末向けアプリ。

## Build
```bash
./gradlew assembleDebug    # デバッグビルド
./gradlew assembleRelease  # リリースビルド
```

## Architecture
- **WebViewベース**: コンテンツ表示はWebView x2 (A/B交互フェード切替)
- **PDF表示**: assets内のpdf-viewer.html + PDF.js (CDN) でWebView内レンダリング
- **プロキシ**: OkHttp で社内プロキシ(210.175.128.100:8080)経由、ローカルIPはバイパス
- **キオスク**: 画面常時ON、フルスクリーン、Boot時自動起動

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
│   ├── SignageService.kt     # Foreground Service(ハートビート送信)
│   └── BootReceiver.kt      # BOOT_COMPLETED自動起動
└── ui/
    ├── SetupScreen.kt        # 初回設定画面(Compose)
    └── theme/                # テーマ
```

## Server API (Player endpoints)
- GET `/api/player/schedule?key={client_key}` - スケジュール取得
- POST `/api/player/heartbeat?key={client_key}` - ハートビート
- GET `/api/player/content/:id/file?key={client_key}` - PDFダウンロード

## Key Design Decisions
- minSdk = 24, targetSdk = 36
- Kotlin + Jetpack Compose (Setup画面のみ) + WebView (コンテンツ表示)
- OkHttp for HTTP (プロキシ設定が容易)
- Gson for JSON serialization
- Foreground Service for heartbeat (Android制約対応)
