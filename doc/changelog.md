# Changelog

## v1.91 (2026-09-04)
- YouTube動画の音声を出せるようにした(v1.82以来の常時ミュートを解消)
  - 前提: リモコンの音量＋/−・消音ボタンはサイネージアプリ表示中でも**システム側で制御できる**ことをユーザーが実機確認済み(Firefoxでも音が出ることを確認済み)。したがって**アプリ側には音量調整・ミュート制御・端末ごとの音声設定を一切実装しない**方針とした(検討した「端末ごとにリモコンでON/OFF」案は不要と判断し不採用)
  - 実装: `youtube-player.html`は`__MUTED__`=trueで常にミュートロードのまま。`onStateChange`が実際にPLAYING(1)へ遷移した時点で`onYoutubePlaying()`をAndroidへ通知(1ロード1回)。`PlayerActivity`は`activeWebView`かつ`YOUTUBE_SOUND_ENABLED`(既定true)のときだけ`ytUnmute(100)`をJS経由で呼ぶ。「再生の成立を実測してから音を足す」ことで、いきなり音アリ自動再生してChromium/YouTubeの自動再生ポリシーに拒否され再生自体が止まるリスクを回避(v1.85の再生開始watchdog、v1.90の尺通知と同じ設計思想)
  - 自動復帰: アンミュート直後に`onStateChange`がPAUSED(2)へ落ちた場合(自動再生ポリシー拒否とみなす)、JS側が1回だけ`ytMute()`→`playVideo()`し直す(`unmuteReverted`で多重発火防止。無音でも映像は絶対に止めない)
  - `ytStop()`(降格・`onPause()`時に呼ばれる)に`ytMute()`を追加し、非アクティブWebViewから音が残る余地を無くした
  - `YOUTUBE_SOUND_ENABLED`定数(既定true)はキルスイッチ。実機で音声ONが再生の妨げになった場合は`false`にするだけでv1.90と同じ完全無音へ即座に戻せる
  - オーバーレイ3ページ目の`WebView:`直後に`ビルド: ${Build.DISPLAY}`を追加(下記のエラー5診断でファーム差の可視化が有効と分かったため、`-02`/`-03`の予防点検用)
  - リリース: versionCode 91 / versionName "1.91"
  - `ConfigManager.kt` / `AndroidManifest.xml` / `dispatchKeyEvent()` / `Models.kt` / `FlatScreen.kt` / `ServerClient.kt` / `SmbPdfManager.kt` / `SignageService.kt` は無改修。サーバー側(signage-server-windows)の変更も不要

## `dt-astbx5-04` YouTubeエラー5の原因診断・解消 (2026-09-04、コード変更なし)
- 症状: `dt-astbx5-04`のみYouTubeがタイトル表示後すぐエラーになり次のコンテンツへスキップされる(`-01`等は正常再生)
- ユーザーの初期仮説「4K/FullHDの解像度差では?」を実測で否定: オーバーレイ3ページ目の`画面:`表示は両機とも`1920x1080 (dpr: 2.0)`で同一
- ネットワーク差も否定: `-01`(10.20.249.61)/`-04`(10.20.249.64)は同一/24、同一ゲートウェイ(10.20.249.251)、同一プロキシ(210.175.128.100:8080)
- ログ`[YT-JS:active] stateChange=1(PLAYING)→stateChange=3(BUFFERING)→onError code=5`から、失敗箇所はWebViewのメディアパイプライン層と特定
- オーバーレイ3ページ目の`WebView:`表示から`-01`=146.0.7680.119、`-04`=140.0.7298.0と判明。両機とも`com.android.webview`(AOSP実装)のためPlayストア経由の個別更新は不可、ファームウェア更新でのみ更新される
- I-O DATA公式のファームウェア更新手順(https://www.iodata.jp/lib/manual/ds-astbx5/002setup/008fw.html)を`-04`へ適用 → WebViewが更新されYouTube再生成功。**アプリのコード変更は無し(診断のみ)**
- 残タスク: `-02`/`-03`もオーバーレイ3ページ目でWebView版数を確認し、140系ならファーム更新を推奨(予防)

## v1.90 (2026-09-04)
- 【重要】YouTubeコンテンツの表示秒数が0(デフォルト)のとき、次の画面へ進まなくなる不具合を修正
  - 原因: 表示秒数0のYouTubeは「動画終了(ENDED)まで再生」する`playToEnd`モードになり、進行はJSの`onStateChange(ENDED)`通知のみが担当。安全弁タイマーは`YOUTUBE_MAX_DURATION_SEC`(3600秒/1時間)のみだった。ANN NEWS24等の**24時間ライブ配信はENDEDが永久に来ない**ため、最大1時間「固まった」ように見えていた
  - 修正: `youtube-player.html`が`onStateChange`のPLAYING遷移時に`player.getDuration()`を実測して`onYoutubeDuration()`でAndroidへ通知。`PlayerActivity`はこれを受けて`playToEnd`画面の安全弁タイマーを締め直す — 通常動画(尺>0)は「尺+15秒」、ライブ配信/尺不明(尺<=0)は**180秒**で強制的に次へ進むようにした
  - 表示秒数を明示指定した画面には一切干渉しない設計。`YOUTUBE_MAX_DURATION_SEC`(3600秒)は尺通知が来ない場合の最後の砦としてそのまま残す
- ステータスバーが「フォルダ同期中...」等の文言のまま固着する不具合を修正
  - 原因: `updateScreenStatusBar()`は自分ではステータスバーに書かず`startCountdown()`の初回tickに表示を委ねていたが、`remainingSeconds`が0だと初回tickで即座に停止し一度も書き込まないまま終了、直前の文言が残留していた(表示秒数0のYouTube画面で必ず発生)
  - 修正: `updateScreenStatusBar()`で`startCountdown()`を呼ぶ前に必ず1回`statusBar.text`を設定するよう変更。画面種別に依らず固着を構造的に解消
- リリース: versionCode 90 / versionName "1.90"
- `FlatScreen.kt` / `ServerClient.kt` / `ScheduleManager.kt` / `SmbPdfManager.kt` / `SignageService.kt` / `AndroidManifest.xml` は無改修。サーバー側(signage-server-windows)の変更も不要

## v1.89 (2026-09-04)
- YouTube再生不能(エラー152-4)の原因特定用の計測を追加 + 有力な対処を投入。v1.83〜v1.85で潰した仮説(origin/153対策・UA単一トークン化・直接embedフォールバック)は全てシロと判明(直接embedでも152-4が再現するため)。WebViewベースのYouTube再生で同様の152が「広告ステータス問い合わせ(static.doubleclick.net/instream/ad_status.js)がプロキシ等でERR_CONNECTION_REFUSEDになり再生自体が拒否される」事例と一致することが有力な仮説
- 【計測】YouTube用WebViewClientに`onReceivedError`/`onReceivedHttpError`を追加し、サブリソース含む全リクエストの失敗を`[YT-NET] host/path err=... main=...`としてログ出力(ホスト+詳細単位で重複除去、最大8件)。ワーカースレッドから呼ばれるため`handler.post()`経由でMainスレッドに渡す
- 【計測】疎通プローブ(`ServerClient.probeYoutubeConnectivity()`)に`static.doubleclick.net`/`googleads.g.doubleclick.net`/`jnn-pa.googleapis.com`/`play.google.com`を追加(9項目化)。デバッグオーバーレイ3ページ目の`YT疎通:`表示を4項目ごとに改行
- 【計測】YouTube画面に初めて到達したタイミングで疎通プローブを再実行(`rerunYoutubeProbeIfNeeded()`)。起動直後はネットワーク未確立の可能性があるため
- 【対処B-1】埋め込みホストを`youtube-nocookie.com`に切替え(`YOUTUBE_USE_NOCOOKIE = true`)。広告関連リソースを読みに行かないプライバシー強化ドメインのため、doubleclick遮断があっても再生できる可能性がある。`youtubeEffectiveBaseUrl`経由でloadDataWithBaseURLのbaseUrl・IFrame APIのorigin/host・直接embedのembedUrlを一括連動(1フラグで従来動作に復帰可能)
- 【対処B-2】`YT_AD_BLOCK_HOSTS`(doubleclick.net等4ホスト)宛のリクエストを`shouldInterceptRequest`で空のHTTP 200に差し替え、プロキシの接続拒否を「広告なし」として扱わせる(`YOUTUBE_STUB_AD_REQUESTS`フラグで無効化可能)
- 【対処B-3】YouTube画面のUser-Agentを、v1.85のMobile UAからDesktop UAに変更。エラー152系は「モバイルChromeでは埋め込み再生できないのでアプリで見て」という意味のコードのため、Mobileトークンが逆効果の可能性を考慮(適用範囲は`type=="youtube"`のみ、web/PDF画面は無影響)
- 【対処B-4】再生開始watchdogの猶予`PLAY_WATCHDOG_SEC`を12秒→20秒に延長。あわせてログを`stage1(wrapper)`/`stage2(直接embed)`の段階表記に統一し、どの段階まで進んだかをオーバーレイから判別しやすくした
- `loadScreenYoutube()`/`fallbackToDirectEmbed()`の`WebViewClient`生成を`createYoutubeWebViewClient()`に共通化(ネットワーク診断・広告握り潰しの重複実装を回避)
- リリース: versionCode 89 / versionName "1.89"
- `AndroidManifest.xml` / `ConfigManager.kt` / `SmbPdfManager.kt` / `SignageService.kt` は無改修。サーバー側(signage-server-windows)の変更も不要

### 実機検証結果（2026-09-04 08:51 `dt-astbx5-01`）
- **YouTube再生に成功**(`[YT-JS:active] stateChange=1`→`watch cleared(playing)`、stage1(wrapper)のまま。stage2直接embedへのフォールバックは発生せず)
- **事前の最有力仮説(企業プロキシによるdoubleclick遮断)はシロと判明**: 実測`YT疎通: dclick=200 gads=200`(遮断なし)、`[YT-NET]`ログ0件、B-2(広告ホストの空200スタブ)も0件で不発
- 効いたと推定されるのはB-1(`youtube-nocookie.com`切替)とB-3(Desktop UA化)のいずれか、または両方。切り分けは実施せず、フラグは全て現状維持
- 残検証: 動画終了→次画面自動送り(現状1画面のみのため未検証)、web/PDF/SMB画面のデグレ確認

## v1.88 (2026-09-03)
- 【重要】投影コンテンツが1〜2件のとき、リモコンの↓(画面一覧オーバーレイ)を押すと確実にアプリが落ちる不具合を修正
  - 原因: `renderScreenList()` の7行窓インデックス計算がKotlinの `%`(floorModではなく剰余)により負値になり、`flatScreens[-1]` で `ArrayIndexOutOfBoundsException`
  - 修正: `Math.floorMod` 化 + 画面数が7以下のときは循環させず全件を1回ずつ表示(表示の重複も解消)
- 未捕捉例外のクラッシュ記録+自動復帰を追加。これまでは一度落ちると端末再起動まで止まったままだった
  - `Thread.setDefaultUncaughtExceptionHandler` で例外情報を記録し、`AlarmManager` で再起動を予約してからプロセス終了(`SCHEDULE_EXACT_ALARM`権限不要、`AndroidManifest.xml`無改修)
  - 60秒以内の連続クラッシュが5回に達したら再起動間隔を2秒→5分に延長(暴走ループ防止)
  - デバッグオーバーレイ3ページ目に「直近クラッシュ: ...」を追加(未記録時は非表示)、再生開始成功時にカウントリセット

## v1.87 (2026-09-03)
- SMB PDFフォルダの命名規約日付フィルタで非表示になったファイルを可視化。デバッグオーバーレイ2ページ目に「非表示(期限切れ): N件」ブロックを追加し、対象ファイル名・表示期間・開始前/期限切れの別を最大8件表示
- 同内容が変化した時のみデバッグログに `[SMB] 期限外スキップ: N件 (...)` を出力（3分毎の定期再同期での連続出力を抑止）
- `dt-astbx5-01` で共有フォルダ `\\10.20.171.189\temp\signage` の中身が表示されない事象（表示終了日 `20260630` 切れが原因）の調査で判明した「接続失敗と全件期限切れが見分けられない」問題への対応
- 日付フィルタ自体の挙動（期限切れファイルを表示しない）は変更なし。診断表示の追加のみ

## v1.86 (2026-09-03)
- APK OTA更新の dev/prod チャネル分離に対応（サーバー側Phase 1と対）。`git push` は常にサーバーの `dev` チャネルへ反映され、手動の `本番反映.bat` 実行まで本番機には配布されない
- `UpdateInfo` に `channel`("dev"/"prod") を追加し、デバッグオーバーレイ(KEYCODE 93 → 3ページ目)に「更新チャネル:」として表示
- 更新ループ防御を追加: 同一version_codeへの更新試行が3回に達しても `currentVersion` が上がらない場合は以後スキップし、オーバーレイに「更新スキップ中:」を表示（サーバー・APKの不整合による無限DL+インストーラー起動ループを防止）
- `SignageService.kt` / `ServerClient.kt` は無改修
