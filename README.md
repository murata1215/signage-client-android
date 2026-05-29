# signage-client-android

Android版デジタルサイネージクライアント。サーバー (signage-server) からスケジュールを取得し、Web/PDFコンテンツをローテーション表示する専用端末向けアプリ。

Linux版 (Electron) の Android 移植版。

## 機能

- **セットアップ画面** - サーバーURL + Client Key 入力、接続テスト、設定変更・初期化対応
- **Webコンテンツ表示** - WebView でURL表示
- **PDFコンテンツ表示** - PDF.js (Base64注入方式) でWebView内レンダリング、2キャンバススワップでちらつき防止、devicePixelRatio対応高解像度レンダリング、CMapローカルバンドルで日本語CIDフォント対応
- **PDFデュアルページ表示** - A4縦PDFを自動検出し見開き2ページ表示 (allPages: 同一PDF内 / firstPageOnly: 異なるPDF同士)
- **SMB PDFフォルダ表示** - Windows共有フォルダからPDF自動取得・差分同期・ローテーション表示 (smbj SMB2/3)
- **3-WebViewクロスフェード** - WebView 3枚 (active+next+prev) で前後先読み + 800msフェード
- **サブプレイリスト先読み** - pdf_folderの子PDFもnextWebViewに先読み+WebViewスワップで即表示 (onPageChangedでレンダリング完了検知)
- **スケジュール更新** - SignageServiceが60秒間隔でスケジュール更新チェック、変更時はBroadcastでPlayerActivityに通知
- **再生時間外自動停止** - コンテンツ切替時に再生時間チェック、時間外ならstandby表示+60秒間隔で復帰チェック
- **スケジュールリトライ** - 取得失敗/コンテンツなし時は60秒間隔でリトライ
- **ハートビート送信** - Foreground Service で定期送信 (管理画面の稼働監視用)
- **リモコン操作** - DPAD左右で前後移動、決定で一時停止/再開
- **タッチジェスチャー** - スワイプで前後移動、ダブルタップで一時停止/再開
- **PDFキャッシュ** - 内部ストレージにダウンロード・キャッシュ管理
- **オフラインフォールバック** - サーバー到達不可時はキャッシュから再生継続
- **再生時間帯判定** - play_start_time / play_end_time による自動待機
- **キオスクモード** - フルスクリーン、画面常時ON、Boot時自動起動
- **プロキシ対応** - OkHttp で社内プロキシ経由、ローカルIPバイパス
- **ステータスバー** - コンテンツ名 + カウントダウン表示
- **一時停止モード** - WebView操作可能 (リモコンでリンク選択・クリック)、枠色で状態表示
- **自動アップデート** - サーバーからAPKを取得しIntent方式でOTA更新 (確認画面→完了後「開く」ボタン)
- **デバッグオーバーレイ** - 画面右半分×縦いっぱいにキー押下・アップデート・スケジュールログをリアルタイム表示 (KEYCODE 93でON/OFF)
- **リモコンキーログ** - F1-F4, VOL+/-, CH上下, 未知のキーをデバッグウインドウに記録

## 動作環境

- **ターゲット端末**: DS-ASTBX5 (Android STB) / 一般的なAndroid端末
- **minSdk**: 24 (Android 7.0)
- **targetSdk**: 36

## 技術スタック

| コンポーネント | 技術 |
|--------------|------|
| 言語 | Kotlin |
| UI (セットアップ) | Jetpack Compose + Material3 |
| UI (コンテンツ表示) | WebView x3 (active+next+prev交互切替) |
| 自動アップデート | ACTION_VIEW Intent方式 (Session APIはフォールバック用に残存) |
| PDF表示 | PDF.js 3.x (CDN) + Base64データ注入 + 2キャンバススワップ + CMapローカルバンドル |
| SMB接続 | smbj 0.13.0 (純Java SMB2/3クライアント) |
| 暗号化 | AES-256-CBC (SMBパスワード復号) |
| HTTP通信 | OkHttp 4.x (プロキシ対応) |
| JSON | Gson |
| 非同期処理 | Kotlin Coroutines |
| バックグラウンド | Foreground Service |
| 自動起動 | BOOT_COMPLETED BroadcastReceiver |

## ビルド

```bash
./gradlew assembleDebug    # デバッグビルド
./gradlew assembleRelease  # リリースビルド
```

## パッケージ構成

```
jp.co.tisa.signage_android/
├── MainActivity.kt          # エントリポイント (Setup or Player起動)
├── data/
│   ├── Models.kt            # データクラス (ScheduleResponse, PlaylistItem等)
│   ├── ConfigManager.kt     # SharedPreferences 設定管理
│   ├── ServerClient.kt      # OkHttp API通信 (プロキシ対応)
│   └── CryptoUtils.kt       # AES-256-CBC復号 (SMBパスワード用)
├── player/
│   ├── PlayerActivity.kt    # WebView再生画面 (フルスクリーン)
│   ├── ScheduleManager.kt   # スケジュール取得・ポーリング・時間帯判定
│   ├── PdfCacheManager.kt   # PDFダウンロード・キャッシュ管理
│   └── SmbPdfManager.kt     # SMB共有フォルダPDF取得・キャッシュ管理
├── service/
│   ├── SignageService.kt     # Foreground Service (ハートビート + アップデート + スケジュール更新チェック)
│   ├── AppUpdateManager.kt   # OTA自動アップデート (PackageInstaller Session)
│   ├── InstallResultReceiver.kt # インストール結果受信
│   └── BootReceiver.kt      # BOOT_COMPLETED 自動起動
└── ui/
    ├── SetupScreen.kt        # 設定画面 (Compose、初回設定/設定変更/初期化)
    └── theme/                # テーマ
```

## 操作方法

### リモコン (DS-ASTBX5等)

| ボタン | 動作 |
|--------|------|
| 右 / CH+ | 次のコンテンツ |
| 左 / CH- | 前のコンテンツ |
| 決定 / ENTER | 一時停止/再開 |
| 上 / 下 | 無効 (WebView操作を防止) |
| KEYCODE 93 | デバッグオーバーレイ ON/OFF |
| F1-F4, VOL+/- | デバッグログに記録 |

### タッチ操作 (スマホ等)

| 操作 | 動作 |
|------|------|
| 左スワイプ | 次のコンテンツ |
| 右スワイプ | 前のコンテンツ |
| ダブルタップ | 一時停止/再開 |
| 5秒長押し | 設定画面を開く |

## サーバー連携 API

| メソッド | エンドポイント | 用途 |
|---------|-------------|------|
| GET | `/api/player/schedule?key={client_key}` | スケジュール取得 |
| POST | `/api/player/heartbeat?key={client_key}` | ハートビート送信 |
| GET | `/api/player/content/:id/file?key={client_key}` | PDFダウンロード |
| GET | `/api/player/update/check?key={client_key}` | アップデートチェック |
| GET | `/api/player/update/download?key={client_key}` | APKダウンロード |

## 起動フロー

```
アプリ起動
  ├── config なし → セットアップ画面 → URL+Key入力 → 保存
  └── config あり → Foreground Service 開始 → PlayerActivity 起動
        ├── スケジュール取得 (サーバー → キャッシュ → フォールバック)
        │   └── 取得失敗/コンテンツなし → 60秒間隔リトライ
        ├── PDF ダウンロード・キャッシュ
        ├── 時間帯判定
        │   ├── 時間帯内 → ローテーション再生開始
        │   └── 時間帯外 → 待機画面
        ├── ハートビート送信ループ
        ├── SignageService がスケジュール+APK更新チェック (60秒間隔)
        │   └── 更新あり → Broadcast → PlayerActivityがスケジュール反映
        └── type=pdf_folder 再生時
            ├── SMB同期画面表示 + 1件目PDFを裏で先読み
            ├── フォルダスキャン → 差分ダウンロード
            ├── 子PDFサブプレイリスト再生 (WebViewスワップ方式で即表示)
            └── 全子PDF完了 → メインプレイリスト次アイテムへ

再生中の設定変更
  5秒長押し → 設定画面 (現在の値が入った状態)
    ├── URL/Key変更 → 接続テスト → 保存して開始 → 新設定でPlayer再開
    ├── 戻る → 変更せずPlayerに戻る
    └── 設定を初期化 → 確認ダイアログ → OK → フィールド空に
```

## 自動アップデート

```
SignageService (1分間隔)
  ├── APKアップデートチェック
  │   -> GET /api/player/update/check?key=...
  │   -> サーバー versionCode > アプリ versionCode ?
  │     -> YES: APKダウンロード -> Intent方式でインストール (確認画面→完了後「開く」)
  │     -> NO: 何もしない
  └── スケジュール更新チェック
      -> GET /api/player/schedule?key=...
      -> version が変更されていれば Broadcast で PlayerActivity に通知

release/
├── signage-android-debug.apk    # 最新APK
└── version.json                  # バージョン情報 (サーバーに配置)
```

## プロキシ設定

| 項目 | 値 |
|------|-----|
| プロキシサーバー | 210.175.128.100:8080 |
| バイパス | 10.x, 172.16-31.x, 192.168.x, localhost, atg.co.jp |

## ライセンス

Private
