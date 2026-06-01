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
- **PDF表示**: assets内のpdf-viewer.html + PDF.js 3.x (CDN + Web Worker有効) + Base64データ注入(CORS回避) + CMapローカルバンドル(日本語CIDフォント対応)
- **PDF 2キャンバススワップ**: ページ切替時のちらつき防止(canvas A/B交互表示)
- **PDFデュアルページ**: A4縦PDFを自動判定し見開き表示(allPages: 同一PDF内2ページ並べ / firstPageOnly: 異なるPDFの1ページ目同士を並べ)
- **PDF高解像度レンダリング**: devicePixelRatio対応でシャープ表示(canvas解像度=物理ピクセル、CSS表示=論理ピクセル)
- **PDFレンダリングキャッシュ**: 初回表示時にcanvasをJPEGキャプチャ→保存、2回目以降はcached-pdf-viewer.htmlで画像直接表示(PDF.jsスキップで爆速)
- **先読み**: next/prev両方向を事前ロード、完了後にフェード切替(ちらつき防止)
- **サブプレイリスト先読み**: pdf_folderの子PDFもnextWebViewに先読み+WebViewスワップで即表示(1件目は同期画面の裏で先読み)
- **pdf_folder先読み**: Webページ/サブPL表示中にバックグラウンドでSMB同期+1件目PDFレンダリング完了(Web→folder, folder→folder共に即切替)
- **SMB PDFフォルダ**: type=pdf_folder でWindows共有フォルダからPDFを自動取得・ローテーション表示
- **再生時間外自動停止**: コンテンツ切替時にisWithinPlayTime()チェック、時間外ならstandby表示+60秒間隔で復帰チェック
- **スケジュール更新一元化**: SignageServiceが60秒間隔でAPK+スケジュール更新チェック、変更時はBroadcastでPlayerActivityに通知
- **自動アップデート**: ACTION_VIEW Intent方式でOTA更新 (1分間隔チェック、白い画面で確認→完了後「開く」ボタン)
- **プロキシ**: OkHttp で社内プロキシ(210.175.128.100:8080)経由、ローカルIPはバイパス
- **キオスク**: 画面常時ON、フルスクリーン、Boot時自動起動
- **操作**: リモコン(DPAD) + タッチジェスチャー(スワイプ/ダブルタップ)対応
- **SMB PDFフォルダ命名規約**: ファイル名で表示制御 ({順番}_{ページ制御}_{開始日}_{終了日}_{秒}_{説明}.pdf)、規約外ファイルはデフォルト動作
- **デバッグオーバーレイ**: 画面右半分×縦いっぱいに緑文字で4ページ表示 (KEYCODE 93でサイクル: 1.デバッグログ → 2.スケジュール情報 → 3.端末/ネットワーク情報 → 4.PDFフォルダ命名マニュアル → 消去)
- **ターゲット端末**: DS-ASTBX5 (Android STB)

## Package Structure
```
jp.co.tisa.signage_android/
├── MainActivity.kt          # エントリポイント(Setup or Player起動)
├── data/
│   ├── Models.kt            # データクラス(ScheduleResponse, PlaylistItem等)
│   ├── ConfigManager.kt     # SharedPreferences設定管理
│   ├── ServerClient.kt      # OkHttp API通信(プロキシ対応)
│   └── CryptoUtils.kt       # AES-256-CBC復号(SMBパスワード用)
├── player/
│   ├── PlayerActivity.kt    # WebView再生画面(フルスクリーン)
│   ├── ScheduleManager.kt   # スケジュール取得・ポーリング・時間帯判定
│   ├── PdfCacheManager.kt   # PDFダウンロード・キャッシュ管理
│   ├── PdfRenderCacheManager.kt # PDFレンダリング済み画像キャッシュ(JPEG)
│   └── SmbPdfManager.kt     # SMB共有フォルダPDF取得・キャッシュ・サブプレイリスト生成
├── service/
│   ├── SignageService.kt     # Foreground Service(ハートビート + アップデート + スケジュール更新チェック)
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
- smbj 0.13.0 for SMB2/3 (純Java、Android互換、Windows共有フォルダ接続)
- Gson for JSON serialization
- Foreground Service for heartbeat (Android制約対応)
- PDF表示はBase64注入方式 (file://間のCORS制約回避) + CMapファイルはassets/pdfjs/cmaps/にローカルバンドル (CDNアクセス不可環境対応)
- PDF高解像度: devicePixelRatio倍でcanvasレンダリング、CSSサイズで表示 → シャープ表示
- allowFileAccessFromFileURLs=true でWebViewからローカルCMapファイル読み込み許可
- PDF 2キャンバススワップ方式 (canvas A/B交互表示でページ切替時のちらつき完全防止)
- PDF allPagesモード: ページ送りはpdf-viewer.html内setTimeoutチェーン管理、完了時onAllPagesCompleted()でAndroidに通知
- PDF デュアルページ自動判定: 1ページ目のviewport height>widthで縦長検出、allPagesは同一PDF内見開き、firstPageOnlyは異なるPDFの1ページ目同士をloadDualFirstPages()で見開き
- WebView 3枚(active+next+prev)先読み+readyフラグでちらつき防止
- PdfJsInterface にWebView参照を持たせ、activeWebViewからの通知のみステータスバー更新(先読みWebViewの干渉防止)
- type=pdf_folder はサブプレイリスト方式で統合 (メインプレイリスト内にpdf_folderアイテム → 同期画面 → 子PDFサブループ → メイン次アイテムへ)
- サブプレイリスト先読み: 現在のPDF表示中にnextWebViewへ次のPDFを先読み、onPageChangedコールバックでレンダリング完了を検知してからWebViewスワップ
- スケジュール更新はSignageServiceに一元化 (PlayerActivity内のstartPolling廃止、advanceToNextからcheckForUpdate削除)
- SMBパスワードはAES-256-CBC暗号化(サーバー/クライアント共通鍵)
- スケジュール取得失敗/コンテンツなし時は60秒間隔リトライ
- ACTION_VIEW Intent方式でOTA更新(白い確認画面→完了後「開く」ボタン。Session APIはコード残存だが未使用)
- DS-STBRC03リモコンのキーマッピング: F1-F4はKEYCODE_F1-F4ではなく端末固有コード(F4=KEYCODE_TV_INPUT=178等)
- dispatchKeyEvent でリモコンキーをView階層より先に捕捉
- setInitialScale(100) + loadWithOverviewMode=false でズーム固定
- PDF.js Web Worker有効化 (CDNからpdf.worker.min.jsロード) + デュアルPDF読み込み/レンダリングをPromise.allで並列化
- pdf_folder先読み: preloadPdfFolder()でWebページ表示中にSMB同期+PDF先読み完了、doAdvance()/advanceToNextMain()で即スワップ
- サブPL最終PDF時にpreloadNextSubPdf()から次メインpdf_folderを先読み(folder→folder即切替)
- advanceToNextSubPdf()で最終サブPDF検出時はWebViewスワップせず直接advanceToNextMain()へ(ダブルスワップ防止)
- SMB PDFフォルダ命名規約: parseFileNameConfig()でファイル名パース、規約ファイルはsortOrder順+日付フィルタ+個別firstPageOnly/duration、規約外は親アイテムのデフォルト設定で後方配置
- デバッグオーバーレイ4ページ: debugPage(0-4)でサイクル管理、ページ2はコンテンツ切替時に自動更新、ページ3はハートビートBroadcast受信時に自動更新、ページ4は命名規約マニュアル(静的)
- SignageServiceからACTION_HEARTBEAT Broadcast送信、PlayerActivityで受信して最終HB時刻記録
- PDFレンダリングキャッシュ: PDF.jsレンダリング後にcanvas.toDataURL('image/jpeg', 0.85)でキャプチャ→onPageRendered()でAndroidに送信→JPEG保存。2回目以降はcached-pdf-viewer.htmlで<img>表示(PDF.jsパース+レンダリング完全スキップ)。キャッシュ検証はPDFファイルサイズ/更新日時+画面解像度

## Release Checklist
コード変更をリリースする際は、以下を**必ず全て**実行すること：

1. **バージョン更新**: `app/build.gradle.kts` の `versionCode` と `versionName` をインクリメント
2. **ビルド**: `./gradlew assembleDebug`
3. **release/version.json 更新**: `version_code` と `version_name` を build.gradle.kts と同じ値に
4. **APKコピー**: `cp app/build/outputs/apk/debug/app-debug.apk release/signage-android-debug.apk`
5. **コミット**: コード変更 + release/ フォルダの両方を含めて `git add` → `git commit`
6. **プッシュ**: `git push`
