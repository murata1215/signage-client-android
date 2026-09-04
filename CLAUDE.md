<!-- DevRelay Agreement v6 -->
See `rules/devrelay.md` for DevRelay rules.
<!-- /DevRelay Agreement -->

---

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
- **YouTube動画再生**: type=youtube でPlaylistItemに登録されたYouTube URLを自動再生。assets内のyoutube-player.html + YouTube IFrame Player APIで、既存のPDFビューアと同じ「Android主導でJSを駆動する」方式(v1.82)。動画IDはevaluateJavascript()での事後注入ではなくHTMLテンプレートに事前埋め込み(v1.84)。エラーコード152等onErrorを発火せずプレイヤー内部UIとして表示されるだけの再生拒否は、再生開始watchdog(onReady後に再生状態をポーリング)で検知し、IFrame APIラッパーを介さない直接embedページへ自動フォールバックする(v1.85)
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
- YouTube origin検証エラー153の修正(v1.83): assets/からのloadUrl("file:///android_asset/...")ではWebViewのoriginがfile://になり、YouTube IFrame Player APIのplayerVars.origin検証に失敗してエラー153(invalid embed)になっていた。loadDataWithBaseURL("https://www.youtube.com", html, ...)でhttpsオリジンを付与して読み込む方式に変更(YOUTUBE_PLAYER_BASE_URL定数)
- YouTube黒画面フリーズの修正(v1.84): v1.83のloadDataWithBaseURL化で新たに発生したバグ。loadDataWithBaseURLに渡すbaseUrlがホスト名のみ("https://www.youtube.com")だと、ChromiumのURL正規化でonPageFinished()に渡るurlに末尾スラッシュが付与される("https://www.youtube.com/")。旧実装はonPageFinished内で`url == YOUTUBE_PLAYER_BASE_URL`という完全一致比較でytLoad()をevaluateJavascript注入していたため、比較が常にfalseとなりytLoad()が一度も呼ばれず動画が黒画面のまま固まっていた(かつduration_seconds=0のplayToEndモードでは進行役のonYoutubeEnded()も来ないため最大3600秒フリーズし、doAdvance()の!nextReadyリトライループでYouTube画面に出入りもできなくなる複合不具合だった)。修正方針は「Androidが事後にevaluateJavascriptで値を渡す」タイミング依存をやめ、buildYoutubeHtml(videoId,muted,autoplay)でHTMLテンプレート内の__VIDEO_ID__/__MUTED__/__AUTOPLAY__プレースホルダを事前置換してからloadDataWithBaseURLする方式に変更(PDFビューアがBase64を先に注入するのと同じ考え方)。onYouTubeIframeAPIReadyはCONFIGオブジェクトを使い自動でdoLoad()を呼ぶ。あわせて診断性のため: JS側の各段階(api ready/player created/onReady/stateChange/onError)をSignageInterface.onYoutubeLog()経由で`[YT-JS:active]`/`[YT-JS:preload]`としてデバッグオーバーレイに出力、WebChromeClient.onConsoleMessage()でERRORレベルのJSコンソールを`[JS-ERR]`として捕捉、doLoad()にプレイヤー生成watchdog(15秒でonReadyが来なければonYoutubeError(-2))、loadScreenYoutube()の先読み時にYOUTUBE_PRELOAD_READY_TIMEOUT_MS(15秒)で強制markPreloadReady()する保険(doAdvance()の無限リトライ防止)を追加。AndroidManifest.xmlに`android:hardwareAccelerated="true"`を明示(WebView内YouTube黒画面の既知の対処法、保険として追加)
- YouTube再生開始watchdog+直接embed自動フォールバック(v1.85): v1.84でonReady/YT.Player生成までは成功するようになったが、実機でエラーコード152(YouTube側の再生拒否)がプレイヤー内部UIとして表示されるだけでonErrorが発火しないケースが判明。「プレイヤーは生成されたが1フレームも再生されない」状態はJSのイベント通知だけでは検知できないため、youtube-player.htmlにstartPlayWatchdog()を追加(onReady後にautoplay時のみ開始、setInterval 1秒毎にgetPlayerState()/getCurrentTime()をポーリングし`log('watch state=N t=X.XX')`をSignageInterface.onYoutubeLog()経由でオーバーレイへ出力、PLAY_WATCHDOG_SEC(12秒)以内にstate===1(PLAYING)へ遷移しなければonYoutubeStalled()を通知、PLAYINGまたはENDEDでタイマーはclearInterval)。Android側はonYoutubeStalled()を受けてfallbackToDirectEmbed()を呼び、loadDataWithBaseURL経由のIFrame APIラッパーHTMLを介さず`https://www.youtube.com/embed/{id}?autoplay=1&mute=1&controls=0...`へwebView.loadUrl()で直接トップレベル遷移する(1画面につき1回のみ、webView.tag==YT_FALLBACK_TAGで判定・loadScreenYoutube()冒頭でリセット)。このフォールバックページはSignageInterfaceを参照しないためJS通知が一切来なくなり、ENDEDでの進行判断ができない。そのためcontentTimerをYOUTUBE_FALLBACK_DURATION_SEC(60秒)の尺タイマーに差し替えて強制的にadvanceToNext()する設計にした(152の原因が何であれ、サイネージとして絶対に止まらないことを優先)。あわせて原因切り分け用の計測も同時投入: (1)環境ログ — youtube-player.html冒頭でlocation.href/document.referrer/navigator.userAgentをonYoutubeLog()送出、(2)YouTube専用UA — createWebView()の既定UAは`settings.userAgentString.replace(Regex("wv"),"") + " Chrome/120.0.0.0"`という式のためChrome/トークンが2個ある不正なUAになっているバグを発見(初回コミットから存在)。web/PDF画面(intramart等の自動フィット拡大が前提)への影響を避けるため既定UAはdefaultUserAgentとして温存しつつ、applyUserAgentForScreen()でtype=youtubeの画面のみYOUTUBE_USER_AGENT(単一Chromeトークンの正規UA)に一時差し替え、loadScreen()/preloadScreen()冒頭で毎回呼び出して画面種別に応じて自動復元、(3)WebView実装バージョン表示 — WebViewCompat.getCurrentWebViewPackage()でパッケージ名/バージョンをwebViewImplInfoに保持しデバッグオーバーレイ3ページ目に表示、(4)YouTube疎通プローブ — ServerClient.probeYoutubeConnectivity()が既存のプロキシ設定(getClient())を再利用してyoutube.com/google.com(generate_204)/i.ytimg.com/redirector.googlevideo.com/youtube-nocookie.comへGETしHTTPステータスを収集、onCreate()でIO coroutineとして1回実行しyoutubeProbeResultに保持、オーバーレイ3ページ目に`YT疎通: yt=200 google=204 ...`形式で表示。実機で「youtubeトップ」(type=web, loadUrl("https://www.youtube.com/"))は正常表示される一方、type=youtubeの埋め込みプレイヤーだけが152になることを確認済みのため、原因はUA/疎通よりもloadDataWithBaseURLの合成オリジンでの埋め込み(Referer/origin検証)である可能性が高いと推定し、直接embedフォールバックを最優先の対策として実装した
- **APK OTA dev/prod チャネル分離 + 更新ループ防御(v1.86)**: `git push`後60秒〜5分でapk-syncされ即座に全本番機へ配布される従来方式は、未検証コードを本番に流すリスクが高いため、signage-server-windows側でdev/prodチャネルを分離(`uploads/android/{dev,prod}` + `clients.is_dev`カラム + 管理画面チェックボックスで端末ごとに割当、pushは常にdevへ、`本番反映.bat`(`y`確認あり)を手動実行した時だけprodへ反映)。**Android側はサーバーの`/api/player/update/check`レスポンスに追加された`channel`("dev"/"prod")を受け取って表示するだけで、チャネル分岐ロジック自体は一切持たない**(サーバーがclient_keyごとに解決済みのURLを返す設計のため無改修で成立)。`UpdateInfo.channel`はGsonの未知フィールド無視により旧サーバー/旧APKとも後方互換。ConfigManagerに`last_update_channel`を保存しデバッグオーバーレイ3ページ目に表示(現場でリモコンだけでdev/prod判別可能)。あわせて更新ループ防御を追加: サーバーが「新バージョンあり」と告げながら実際には同一APKしか配布できていない不整合(例: check/downloadで異なるチャネルのファイルを参照してしまう等)が起きると、`versionCode <= currentVersion`が永久にfalseのままとなり60秒間隔で16MBのDL+インストーラー起動を無限に繰り返す事故になる。`ConfigManager`に`update_attempt_version_code`/`update_attempt_count`を持たせ、同一version_codeへの試行が`MAX_INSTALL_ATTEMPTS(3)`に達したら以降スキップしログとオーバーレイに警告表示。試行回数の記録は「インストール起動で自プロセスが強制終了され得る」ため必ずDL/インストールの**前**に行う(後に置くとカウントが進まず防御が機能しない)。現在バージョンが記録済みattempt versionCode以上に上がっていれば自動的にカウンタをリセットする成功検知も実装。`SignageService.kt`/`ServerClient.kt`は無改修(前者はConfigManager経由で表示するためBroadcast Extra不要、後者は`downloadApk()`の既存分岐が新しいURL形式をそのまま処理できるため)
- **SMB PDFフォルダ 日付フィルタのスキップ可視化(v1.87)**: `dt-astbx5-01`で共有フォルダ`\\10.20.171.189\temp\signage`の中身が丸ごと表示されない事象の調査で、命名規約の表示終了日(`endDate`)切れが原因と判明したが、従来は「SMB接続失敗で0件」も「全ファイル期限切れで0件」もどちらも無言でpdf_folderが消えるだけで、現場のオーバーレイからは区別できなかった(`[SMB] …同期失敗:`ログは接続失敗時にしか出ない)。`SmbPdfManager.buildPlaylist()`の日付フィルタ(`today < startDate || today > endDate`で`continue`)は一切変更せず、除外したファイルを`SkippedByDate`(folderName/filename/startDate/endDate/isBeforeStart)として記録するだけの診断機能を追加。`syncFolder()`(オンライン)と`buildPlaylistFromCache()`(オフライン)の両方が通る唯一の合流点である`buildPlaylist()`に仕込むことで経路を問わず捕捉。記録は`dateSkippedMap: ConcurrentHashMap<Int, List<SkippedByDate>>`(親pdf_folder.id単位、`buildFlatScreens()`冒頭で`resetDateSkipped()`して毎回作り直すことでスケジュールから消えたフォルダの残骸を防止)。表示は2箇所: (1)デバッグオーバーレイ2ページ目`buildScheduleInfoText()`にflatScreens一覧の後ろへ`非表示(期限切れ): N件`ブロックを追加(0件時は区切り線ごと非表示、最大8件+ファイル名30文字打ち切り+`yyyy/M/d`日付表示)、(2)デバッグログに`[SMB] 期限外スキップ: N件 (...)`を追加。3分間隔の定期再同期(`SMB_SYNC_INTERVAL_MS`)のたびに毎回ログへ出すと20行しかないローリングログが埋まるため、`lastSkipSignature`(ファイル名の連結文字列)で前回と比較し**内容が変化した時だけ**ログ出力する`logDateSkippedIfChanged()`を新設。`addDebugLog()`が`debugPage==1`のときTextViewを直接触るため、呼び出しは必ずMainスレッドから行う設計にした: 初回スケジュール読込時は`startPlayback()`の`withContext(Dispatchers.Main){...}`ブロック内、定期再同期時は`startSmbFolderSync()`の`coroutineScope`(Dispatchers.Main)内で`applyRefreshedScreens()`呼び出し直後(`applyRefreshedScreens`自体は`sameScreens`一致で早期returnするため、その外側に置く必要がある)。既存の`[SMB] …同期失敗:`ログが`withContext(Dispatchers.IO)`配下でTextViewを触っている潜在的なスレッド不整合(実害報告なし)は今回のスコープ外として変更していない
- **画面一覧オーバーレイのクラッシュ修正 + 未捕捉例外の自動復帰(v1.88)**: 投影コンテンツが1〜2件のとき、リモコンの↓(画面一覧オーバーレイ)で確実にアプリが落ちる不具合を修正。原因は`renderScreenList()`の7行窓計算`(selectedListIndex + offset + size) % size`(offset=-3..3)。Kotlinの`%`はfloorModではなく剰余(符号は被除数に従う)のため、`size==2`かつ`selectedListIndex==0`のとき`(0-3+2)=-1`→`-1%2=-1`→`flatScreens[-1]`で`ArrayIndexOutOfBoundsException`。`+size`補正は`offset>=-size`のときしか効かず、`span=3`固定のこの式は暗黙に`size>=3`を前提にしていた欠陥(他の剰余箇所L695/720/758/835/2061/2099/2107/2468/2473はいずれも`-1+size`止まりで安全、欠陥はここ1箇所)。修正は`Math.floorMod`化に加え、`size<=7`(窓の最大行数)のときは循環させず全件を1回ずつ表示するよう変更(クラッシュ回避と同時に「2画面なのに同じ2件が7行並ぶ」表示も解消)。カーソル判定を`offset==0`から`idx==selectedListIndex`に変更(`size>7`の循環経路では従来と等価)。あわせて、本アプリには未捕捉例外ハンドラも自動復帰も無く一度落ちると端末再起動まで止まったままだった問題への対処として、v1.78/v1.85/v1.87と同じ「診断可視化+絶対に止まらない」設計思想でクラッシュハンドラを追加: `onCreate()`冒頭(`configManager`初期化直後)で`installCrashHandler()`により`Thread.setDefaultUncaughtExceptionHandler`を設置。例外発生時は例外クラス名/メッセージ/`jp.co.tisa`パッケージ内の最初のスタックフレーム(ファイル:行)を`ConfigManager.recordCrash()`で記録(直後にプロセスを殺すため`apply()`ではなく`commit()`で同期書き込み)、`AlarmManager.set(RTC, ...)`(非正確アラームのため`SCHEDULE_EXACT_ALARM`権限不要・`AndroidManifest.xml`無改修)で`MainActivity`への復帰を予約してから`Process.killProcess()`。前回クラッシュから60秒以内の連続クラッシュが5回に達したら再起動間隔を2秒→5分に延ばす暴走ループ防御を`ConfigManager.recordCrash()`内に実装(v1.86の`update_attempt_count`と同じ考え方)。成功検知は`startPlayback()`が「[PLAY] 再生開始」に到達した時点で`resetCrashCount()`。デバッグオーバーレイ3ページ目に「直近クラッシュ: MM/dd HH:mm:ss ExceptionClass: message @ File:line (連続N回)」を追加(未記録時は行ごと非表示)
- **YouTube再生不能(エラー152-4)の原因特定計測+対処(v1.89)**: v1.83〜v1.85の対策(origin=153対策・UA単一トークン化・直接embedフォールバック)後もYouTubeが「エラーコード: 152-4」で1フレームも再生できない状態が継続。`fallbackToDirectEmbed()`(合成オリジンを介さない素のhttps直接embed)でも同じ152-4が再現することから、原因はorigin/Referer/UA単体の問題ではないと判明。WebViewベースYouTube再生の類似事例で「広告ステータス問い合わせ(static.doubleclick.net/instream/ad_status.js)がプロキシ等でERR_CONNECTION_REFUSEDになり再生自体が拒否される(onErrorは発火せずプレイヤー内部UIとして表示されるだけ)」報告があり、これがv1.85のwatchdogでしか検知できなかった症状と一致するため最有力仮説とした(**実機検証の結果この仮説は誤りと判明**。後述)。【計測】YouTube用`WebViewClient`に`onReceivedError`/`onReceivedHttpError`を追加しサブリソース失敗を`[YT-NET] host/path err=... main=...`としてログ出力(host+詳細単位で重複除去、最大8件、ワーカースレッド呼び出しのため`handler.post()`経由)。`ServerClient.probeYoutubeConnectivity()`に`static.doubleclick.net`/`googleads.g.doubleclick.net`/`jnn-pa.googleapis.com`/`play.google.com`を追加(9項目化、オーバーレイ3ページ目`YT疎通:`は4項目ごと改行)。YouTube画面到達時に疎通プローブを再実行(`rerunYoutubeProbeIfNeeded()`、起動直後のネットワーク未確立を考慮)。【対処】(B-1)埋め込みホストを`youtube-nocookie.com`に切替え(`YOUTUBE_USE_NOCOOKIE`フラグ、`youtubeEffectiveBaseUrl`経由でloadDataWithBaseURLのbaseUrl・IFrame APIのorigin/host(HTMLへ`__ORIGIN__`プレースホルダで注入)・直接embedのembedUrlを一括連動)。(B-2)`YT_AD_BLOCK_HOSTS`宛リクエストを`shouldInterceptRequest`で空のHTTP 200に差し替え、プロキシの接続拒否を「広告なし」として扱わせる(`YOUTUBE_STUB_AD_REQUESTS`フラグ)。(B-3)YouTube画面のUAをv1.85のMobile UAからDesktop UAに変更(152系は「モバイルChromeでは埋め込み再生不可→アプリで見て」の意味のため、Mobileトークンが逆効果の可能性を考慮。適用は`type=="youtube"`のみ)。(B-4)`PLAY_WATCHDOG_SEC`を12→20秒に延長し、ログを`stage1(wrapper)`/`stage2(直接embed)`表記に統一。`loadScreenYoutube()`/`fallbackToDirectEmbed()`の`WebViewClient`生成は`createYoutubeWebViewClient()`に共通化。全対処はフラグ1つで無効化可能な設計。`AndroidManifest.xml`/`ConfigManager.kt`/`SmbPdfManager.kt`/`SignageService.kt`は無改修。**【実機検証結果(2026-09-04 08:51 `dt-astbx5-01`)】YouTube再生に成功**(`[YT-JS:active] stateChange=1`→`watch cleared(playing)`、`stage2(直接embed)`へのフォールバックは発生せずstage1(wrapper)のまま成功)。一方で**事前の最有力仮説(企業プロキシによるdoubleclick遮断)は実測でシロと判明**: `YT疎通:`実測値`dclick=200 gads=200`(遮断なし)、`[YT-NET]`ログ0件、B-2(広告ホストの空200スタブ)も一度も発動せず不発。`ytimg=404`/`jnnpa=404`は到達済みだが該当パスにfaviconが無いだけで異常ではない。効いたと推定されるのはB-1(`youtube-nocookie.com`切替)とB-3(Desktop UA化。152-x系は「モバイルChromeでは埋め込み再生不可」の意味のコードのため)のいずれか、または両方(152の実際の原因はdoubleclick遮断ではなく、nocookieドメインまたはDesktop UAが関係する別の検証/権限ロジックだったと推測される。詳細な切り分けは「再生できるようになった直後に意図的に壊す」リスクに見合わないため未実施)。全フラグ(`YOUTUBE_USE_NOCOOKIE`/`YOUTUBE_STUB_AD_REQUESTS`/Desktop UA)は現状(有効)のまま維持。残検証: 動画終了→次画面自動送り(`onYoutubeEnded`経路、検証時flatScreensが1画面のみだったため未確認)、web/PDF/SMB画面のデグレ確認
- **YouTubeライブ配信で次画面へ進まない+ステータスバー固着の修正(v1.90)**: v1.89でYouTube再生自体には成功したが、実機で3画面中1画面(ANN NEWS24、表示秒数0=デフォルト)から次へ進まず、ステータスバーが「フォルダ同期中...」で固まる不具合が発覚。原因は独立した2つの欠陥: (1)`FlatScreen.kt`の`playToEnd = item.durationSeconds <= 0`により表示秒数0のYouTubeは「動画終了(ENDED)まで再生」モードになり、進行はJSの`onStateChange(ENDED)`→`onYoutubeEnded()`のみが担当、`scheduleAutoAdvance()`のタイマーは`YOUTUBE_MAX_DURATION_SEC`(3600秒)の安全弁のみだった。ANN NEWS24は24時間ライブ配信のためENDEDが永久に来ず、最大1時間「固まった」ように見えていた。(2)`updateScreenStatusBar()`が自分ではstatusBarに書かず`startCountdown()`の初回tickに表示を委ねていたが、`remainingSeconds = screen.durationSeconds`が0だと`remainingSeconds--`で-1になり`if (remainingSeconds >= 0)`が偽のまま一度もテキストを書かずに停止、直前の`startPlayback()`が書いた「フォルダ同期中...」等の文言がそのまま残留していた(表示秒数0のYouTube画面で必ず発生)。修正1: `updateScreenStatusBar()`で`startCountdown()`を呼ぶ前に必ず1回`statusBar.text`を設定(残り秒数>0なら通常表示、`playToEnd`時は「再生中(動画の終わりまで)」)。修正2: v1.85の再生開始watchdogと同じ「JSのイベント通知に頼らず実測でポーリングする」設計思想で、`youtube-player.html`の`onStateChange`PLAYING(state=1)遷移時に`player.getDuration()`を読み`onYoutubeDuration(dur)`をAndroidへ通知(1ロード1回、`durationNotified`で抑止。PLAYINGはautoplay=trueのアクティブWebViewでしか起きないため先読み側からの誤通知なし)。`PlayerActivity`の`onYoutubeDuration()`は`playToEnd`の画面のときだけ`contentTimer`を締め直す: 尺>0(通常動画)なら`尺+YOUTUBE_END_GRACE_SEC(15秒)`に短縮(ENDED取りこぼしの保険、通常はENDEDが先に発火するため実際には使われない)、尺<=0(ライブ配信/尺不明)なら`YOUTUBE_LIVE_DURATION_SEC(180秒)`で強制的に次へ進む。あわせて`remainingSeconds`を入れ直し`startCountdown()`を呼び直すことでステータスバーに残り秒数が表示されるようになる。表示秒数を明示指定した画面(`playToEnd=false`)には一切干渉しない。`YOUTUBE_MAX_DURATION_SEC`(3600秒)は尺通知が来ない場合の最後の砦としてそのまま残す。`FlatScreen.kt`/`ServerClient.kt`/`ScheduleManager.kt`/`SmbPdfManager.kt`/`SignageService.kt`/`AndroidManifest.xml`は無改修、サーバー側の変更も不要(`playToEnd`の契約自体は変えないため)
- **`dt-astbx5-04`のYouTubeエラー5はWebView版数差が原因と判明・ファーム更新で解消(2026-09-04、コード変更なし)**: `-04`のみYouTubeが「タイトル表示→再生開始直後にエラーでスキップ」される不具合を調査。バージョン差(既に同一v1.90)・解像度差(仮説A、両機とも1920x1080 dpr2.0で同一のため棄却)・ネットワーク/プロキシ差(同一/24・同一ゲートウェイ・同一プロキシで棄却)を順に除外し、`[YT-JS:active] stateChange=1(PLAYING)→stateChange=3(BUFFERING)→onError code=5`のログから**WebViewのメディアパイプライン層の問題**と特定。オーバーレイ3ページ目の`WebView:`表示で`-01`が146.0.7680.119、`-04`が140.0.7298.0と判明(`-01`/`-04`とも`com.android.webview`=AOSP実装のためPlayストア経由の更新は不可)。I-O DATA公式のファームウェア更新手順を`-04`へ適用したところWebViewが更新されYouTube再生成功。アプリ側のコード変更は無し(診断のみ)。予防のため`-02`/`-03`もオーバーレイ3ページ目でWebView版数を確認し140系ならファーム更新を推奨
- **YouTube音声ON(v1.91)**: v1.82以来ハードコードされていた常時ミュート(`YOUTUBE_MUTED=true`のみで`unMute()`呼び出しが皆無)を解消し、音声を出せるようにした。音量調整・消音操作は**リモコンからシステム側で制御できることを確認済み**のため、アプリ側には音量/ミュートのUIや端末ごとの設定を一切持たせず、「YouTubeプレイヤーのミュートを外す」ことだけを実装。設計は「ミュートでロード→実際に`onStateChange`がPLAYING(1)へ遷移したことを確認→`onYoutubePlaying()`をAndroidへ通知→`activeWebView`のときだけ`ytUnmute(100)`を呼ぶ」というv1.85の再生開始watchdog・v1.90の尺通知と同じ「JSのイベントを信じず実測してから動く」流儀。いきなり音アリで自動再生すると自動再生ポリシーに拒否され再生自体が止まるリスクがあるため、**再生の成立を確認してから音を足す**ことで回避。万一アンミュート直後に`onStateChange`がPAUSED(2)へ落ちた場合はJS側(`youtube-player.html`)が1回だけ`ytMute()`に戻し`playVideo()`し直す自動復帰(`unmuteReverted`で多重発火防止、無音でも映像は絶対に止めない)。降格・`onPause()`時に呼ばれる`ytStop()`は`pauseVideo()`に加え`ytMute()`も呼ぶよう変更し、非アクティブWebViewから音が残る余地を無くした。`YOUTUBE_SOUND_ENABLED`定数(既定true)は問題発生時に`false`へ変更するだけでv1.90と同じ完全無音へ即座に切り戻せるキルスイッチとして残す。`ConfigManager.kt`/`AndroidManifest.xml`/`dispatchKeyEvent()`/サーバー側は無改修(音量・消音はアプリの管轄外という結論のため)

## Release Checklist
コード変更をリリースする際は、以下を**必ず全て**実行すること：

1. **バージョン更新**: `app/build.gradle.kts` の `versionCode` と `versionName` をインクリメント
2. **ビルド**: `./gradlew assembleDebug`
3. **release/version.json 更新**: `version_code` と `version_name` を build.gradle.kts と同じ値に
4. **APKコピー**: `cp app/build/outputs/apk/debug/app-debug.apk release/signage-android-debug.apk`
5. **コミット**: コード変更 + release/ フォルダの両方を含めて `git add` → `git commit`
6. **プッシュ**: `git push`
7. **本番反映**: push だけでは signage-server-windows の `dev` チャネルが更新されるのみで、本番機には配布されない(v1.86でdev/prod分離)。動作確認後、サーバーのデスクトップ `本番反映.bat` を実行(`y`入力で確定)して本番4台へ反映する
