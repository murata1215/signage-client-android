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
- **フラットスクリーンリスト**: スケジュール取得時にpdf_folderを展開し全コンテンツを1次元リストに格納。ナビゲーションはcurrentScreenIndexの増減のみ
- **先読み**: next/prev両方向を事前ロード、完了後にフェード切替(ちらつき防止)
- **SMB PDFフォルダ**: type=pdf_folder でWindows共有フォルダからPDFを自動取得・ローテーション表示
- **SMB URLテキスト**: SMBフォルダ内の.txtファイル(URL記載)をtype=webのPlaylistItemに展開してWeb画面表示。複数URLは1URLずつローテーション(1URL=1 FlatScreen)
- **再生時間外自動停止**: コンテンツ切替時にisWithinPlayTime()チェック、時間外ならstandby表示+60秒間隔で復帰チェック
- **スケジュール更新一元化**: SignageServiceが60秒間隔でAPK+スケジュール更新チェック、変更時はBroadcastでPlayerActivityに通知
- **自動アップデート**: ACTION_VIEW Intent方式でOTA更新 (1分間隔チェック、白い画面で確認→完了後「開く」ボタン)
- **プロキシ**: OkHttp で社内プロキシ(210.175.128.100:8080)経由、ローカルIPはバイパス
- **キオスク**: 画面常時ON、フルスクリーン、Boot時自動起動
- **操作**: リモコン(DPAD) + タッチジェスチャー(スワイプ/ダブルタップ)対応
- **SMB PDFフォルダ命名規約**: ファイル名で表示制御 ({順番}_{ページ制御}_{開始日}_{終了日}[_{秒}]_{説明}.pdf|.txt)、秒は省略可(省略時は親のデフォルト秒数)、規約外ファイルはデフォルト動作
- **初期設定簡略化**: サーバーURLデフォルトプリセット(https://service.internal.atg.co.jp/tsinternal/signage-server-windows) + 未割当クライアント一覧(GET /api/player/unassigned-clients、key不要)から選択→自動接続テスト→保存して開始。Client Key手入力はフォールバックとして残存
- **デバッグオーバーレイ**: 画面右半分×縦いっぱいに緑文字で4ページ表示 (KEYCODE 93でサイクル: 1.デバッグログ → 2.スケジュール情報 → 3.端末/ネットワーク情報 → 4.PDFフォルダ命名マニュアル → 消去)
- **WebViewクラッシュ復旧**: onRenderProcessGone()検知でクラッシュしたWebViewを破棄・再生成し再生継続(v1.78)
- **WebView用プロキシバイパス**: androidx.webkit ProxyControllerで社内ドメイン等をプロキシ経由せず直接接続(v1.79)
- **Web画面自動フィット拡大**: 固定幅レガシー画面(intramart等)を許可リストURLのみ自動でズーム拡大表示(v1.80)
- **YouTube動画再生**: type=youtube でPlaylistItemに登録されたYouTube URLを自動再生。assets内のyoutube-player.html + YouTube IFrame Player APIで、既存のPDFビューアと同じ「Android主導でJSを駆動する」方式(v1.82)
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
│   ├── FlatScreen.kt         # フラット化された1画面データクラス
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
- GET `/api/player/unassigned-clients` - 未割当クライアント一覧 (初期設定用・key不要、サーバー側実装依頼中)

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
- PDF allPagesモード: ページ送りはAndroid主導(PlayerActivity.startPdfPageRotation)。handler.postDelayedでpdfPageDuration秒ごとにJS androidAdvancePage()を呼び、'more'/'wait'/'done'で次ページ送り・読込待ち・完了(advanceToNext)を制御。Chromiumの非表示WebViewタイマースロットリングでJS主導setTimeoutが停止する問題を回避(v1.76)
- PDFは「先頭1枚(firstPageOnly)」か「全ページ(allPages)」の二択: FlatScreen.fromSubPdf/fromPdfで firstPageOnly = !isAllPages とし、allPages指定ファイルを親フォルダのfirstPageOnly既定で上書きしない(v1.77のページ送り停止バグ修正)
- PDF デュアルページ自動判定: 1ページ目のviewport height>widthで縦長検出、allPagesは同一PDF内見開き、firstPageOnlyは異なるPDFの1ページ目同士をloadDualFirstPages()で見開き
- WebView 3枚(active+next+prev)先読み+readyフラグでちらつき防止
- PdfJsInterface にWebView参照を持たせ、activeWebViewからの通知のみステータスバー更新(先読みWebViewの干渉防止)
- フラットスクリーンリスト: スケジュール取得時にpdf_folderをSMBキャッシュから展開し全コンテンツを1次元FlatScreenリストに格納。デュアルページは隣接する縦長PDFを自動ペアリング。ナビゲーションはcurrentScreenIndexの増減のみ（サブプレイリスト管理を廃止）
- スケジュール更新はSignageServiceに一元化 (PlayerActivity内のstartPolling廃止、advanceToNextからcheckForUpdate削除)
- SMBパスワードはAES-256-CBC暗号化(サーバー/クライアント共通鍵)
- スケジュール取得失敗/コンテンツなし時は60秒間隔リトライ
- ACTION_VIEW Intent方式でOTA更新(白い確認画面→完了後「開く」ボタン。Session APIはコード残存だが未使用)
- DS-STBRC03リモコンのキーマッピング: F1-F4はKEYCODE_F1-F4ではなく端末固有コード(F4=KEYCODE_TV_INPUT=178等)
- dispatchKeyEvent でリモコンキーをView階層より先に捕捉
- setInitialScale(100) + loadWithOverviewMode=false でズーム固定
- PDF.js Web Worker有効化 (CDNからpdf.worker.min.jsロード) + デュアルPDF読み込み/レンダリングをPromise.allで並列化
- フラットスクリーンナビゲーション: 進む=currentScreenIndex++, 戻る=currentScreenIndex--。WebView 3枚ローテーション(doAdvance/doPreviousSwap)でクロスフェード。先読みはpreloadBothDirections()でnext/prev両方向
- SMB PDFフォルダ命名規約: parseFileNameConfig()でファイル名パース、規約ファイルはsortOrder順+日付フィルタ+個別firstPageOnly/duration、規約外は親アイテムのデフォルト設定で後方配置
- デバッグオーバーレイ4ページ: debugPage(0-4)でサイクル管理、ページ2はコンテンツ切替時に自動更新、ページ3はハートビートBroadcast受信時に自動更新、ページ4は命名規約マニュアル(静的)
- SignageServiceからACTION_HEARTBEAT Broadcast送信、PlayerActivityで受信して最終HB時刻記録
- PDFレンダリングキャッシュ: PDF.jsレンダリング後にcanvas.toDataURL('image/jpeg', 0.85)でキャプチャ→onPageRendered()でAndroidに送信→JPEG保存。2回目以降はcached-pdf-viewer.htmlで<img>表示(PDF.jsパース+レンダリング完全スキップ)。キャッシュ検証はPDFファイルサイズ/更新日時+画面解像度
- SMB URLテキスト: parseUrlsFromTxt()でhttp/https行のみ抽出、addWebItems()で1URL=1のtype=web PlaylistItemに展開(専用タイマー不要、通常の画面自動送りでローテーション)。URLリストは_metadata.jsonに永続化しオフライン時もbuildPlaylistFromCache()で再生可能
- 命名規約の秒数フィールドは省略可: 正規表現 `(?:_(\d+))?` でオプショナル化、FileNameConfig.durationSecondsはInt?(null=親のデフォルト秒数)
- screenKey(): web画面は"web:{url}"でキー生成(fromWebはcontentId=0のため全web画面が同一キーになる問題の回避。SMB再同期時の位置保持・差分判定用)
- 初期設定の未割当クライアント選択: fetchUnassignedClients()はkey不要(初期設定時は未保有のため)。「未割当」=last_heartbeat IS NULL想定でHB送信開始により自動的にリストから消える。サーバーAPI未実装でも手入力フォールバックで運用可
- オフライン耐性は意図的設計: サーバー停止時もキャッシュスケジュール+キャッシュPDFで再生継続(HB/更新チェック失敗は無視)。止まるのはweb画面の表示先サーバー断と未キャッシュ新規コンテンツのみ
- WebViewクラッシュ復旧(v1.78): createWebView()のwebViewClientでonRenderProcessGone()をオーバーライドしtrueを返す(自前復旧)。handleWebViewCrash()でクラッシュしたWebViewをcontainerLayoutから除去してdestroy()、createWebView()で新規生成しwebViewA/B/C・active/next/prevの参照を差し替え。activeWebViewの場合は現在画面を再ロード+ページ送りタイマー再開+両方向先読み再開、next/prevの場合は該当方向のみ再プリロード。loadScreen()/preloadScreen()のWebViewClientにも同様のoverrideを追加(3箇所)
- WebView用プロキシバイパス(v1.79): WebViewはChromium自前のネットワークスタックを使うため、ServerClient.ktのOkHttp bypassPrefixesはWeb表示コンテンツには効かない。androidx.webkit.ProxyController.setProxyOverride()でWebView自体にプロキシ+バイパスルールを設定(setupWebViewProxy()、onCreate()でsetupViews()前に呼び出し)。WebViewFeature.PROXY_OVERRIDE非対応端末では機能スキップ。ServerClient.ktのbypassPrefixesにも"tisaweb.or.jp"を追加(OkHttp側は既存のまま個別対応)
- Web画面自動フィット拡大(v1.80): 固定幅レガシー画面(intramartの工程状況表等、viewport meta無し+px固定コンテンツ幅)が広いviewportに対して左上に小さく表示される問題への対応。オプトイン方式(WEB_AUTOFIT_URL_PATTERNS部分一致 + WEB_AUTOFIT_APPLY_TO_ALL=false既定)で許可リスト外のURLにはJSを一切注入せず既存ページを完全に無変更に保つ。scheduleAutoFit()がonPageFinished後に複数回遅延実行(ページ側initが未完了の可能性のため)、injectAutoFitScale()がTreeWalker(SHOW_TEXT)+img/svg/canvas/videoで実表示コンテンツの横幅を測定しcontentWidth<viewport×95%ならbody.style.zoomで拡大(上限3.0倍)。CSS zoomはvh/vw等ビューポート単位を伸縮しないため、zoom前にビューポート高さ相当(innerHeight×0.85〜1.15)の要素を検出しdata-sig-vh属性へ元の高さを退避、zoom後にheight=元高さ÷zで再設定して物理サイズを維持(下部が切れるのを防止)。loadScreen()/preloadScreen()両方のonPageFinishedにinjectWideViewport()の直後で呼び出し
- 自動フィット拡大の右端はみ出し修正(v1.81): v1.80は倍率計算がcontentWidth(=maxRight-minLeft)基準だったため、CSS zoomが文書原点(0,0)基準で拡大する性質と食い違い、minLeft×z分だけ右端が必ずはみ出すバグがあった。injectAutoFitScale()をreset()/measure()/apply(z)の3ヘルパーに再構成し、判定・倍率計算を「幅」ではなく「右端座標(maxRight、window.pageXOffsetで文書座標に補正)」基準に変更(z=avail×WEB_AUTOFIT_SAFETY/maxRight)。avail算出もwindow.innerWidth(スクロールバー幅を含む)からdocument.documentElement.clientWidthに変更。さらにzoom適用後にmeasure()で再測定しはみ出していればz=z×avail×SAFETY/rightで再計算・再適用する自己補正ループを最大WEB_AUTOFIT_VERIFY_PASSES(2)回実行(getBoundingClientRect()はzoom適用後の値を返すため再測定がそのまま比較可能。万一zoomを反映しない実装でも再測定値は変化せずループは即break、手順1の改善だけが効くため安全)。vh補正(下部切れ防止)の仕組みはapply()内にそのまま維持。デバッグログをzoom=1.44 right0=1330 fit=1900 avail=1920 left=8 pass=1形式に拡充しskip/nocontentも出力
- YouTube動画再生(v1.82): mediaPlaybackRequiresUserGestureが既定trueで自動再生がブロックされる問題への対応でcreateWebView()に`mediaPlaybackRequiresUserGesture=false`を追加。素の`loadUrl("youtube.com/embed/...")`ではなくassets/youtube-player.html(YouTube IFrame Player API)を挟む方式を採用した理由は、3枚WebViewローテーションの先読み(INVISIBLE)WebViewで裏再生され音だけ鳴る問題の回避のため: ytLoad(videoId, muted, autoplay)でautoplay=falseならロードのみ行い再生しない(先読み時)、autoplay=trueならロード後即playVideo()(アクティブロード時)。アクティブ昇格/降格時にAndroid側からytPlay()/ytStop()をJS経由で呼び分け(doAdvance()/doPreviousSwap()内、WebViewローテーション直後)。extractYoutubeId()でwatch?v=/youtu.be/embed/shorts/生ID全形式をクライアント側で正規化(サーバーはurlをそのまま格納・配信するだけでよい契約)。duration_seconds<=0の画面はplayToEnd=trueとなりscheduleAutoAdvance()が安全弁タイマー(YOUTUBE_MAX_DURATION_SEC=3600秒)のみ設定、実際の進行はJS onStateChange(ENDED)→SignageInterface.onYoutubeEnded()→advanceToNext()が担当。onYoutubeReady()は既存onPageChanged()と同じ「webView != activeWebViewならnextReady/prevReadyを立てる」パターンで先読み完了を判定。onYoutubeError(code)は削除済み・埋め込み禁止動画を検知して固まらず次へ進む。youtube-player.html側もIFrame API自体のロード失敗(プロキシ遮断等)に備え10秒フォールバックタイマーでonYoutubeError(-1)を発火。onPause()/onResume()をPlayerActivityに新規追加しアプリのバックグラウンド化時に音が鳴り続けないようytStop()。サーバー側(signage-server-windows)との契約: type="youtube"追加(新規カラム不要)、duration_seconds=0はtype=youtubeのときのみサーバー側の既定秒数フォールバックを抑止してそのまま配信(他typeの挙動は不変)、youtube_video_idはサーバー側で付与せずクライアントでURL正規化(二重実装回避・オフラインキャッシュ復元・OTA修正の速さのため)

## Release Checklist
コード変更をリリースする際は、以下を**必ず全て**実行すること：

1. **バージョン更新**: `app/build.gradle.kts` の `versionCode` と `versionName` をインクリメント
2. **ビルド**: `./gradlew assembleDebug`
3. **release/version.json 更新**: `version_code` と `version_name` を build.gradle.kts と同じ値に
4. **APKコピー**: `cp app/build/outputs/apk/debug/app-debug.apk release/signage-android-debug.apk`
5. **コミット**: コード変更 + release/ フォルダの両方を含めて `git add` → `git commit`
6. **プッシュ**: `git push`
