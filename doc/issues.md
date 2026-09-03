# Issues

## YouTube再生不能(エラー152-4)の原因特定 (v1.89)

- [~] **計測+対処を実装・ビルド成功（2026-09-04）。実機検証待ち**
  - 症状: `dt-astbx5-01`でYouTubeコンテンツが「この動画は再生できません / エラーコード: 152-4」で1フレームも再生できない
  - v1.83〜v1.85で潰した仮説(origin=153対策・UA単一トークン化・IFrame APIラッパー・直接embedフォールバック)は
    全てシロと判明: `fallbackToDirectEmbed()`(素のhttps直接embed、合成オリジン非経由)でも同じ152-4が再現するため
  - 最有力仮説: 企業プロキシ(210.175.128.100:8080)が`static.doubleclick.net`等の広告系ドメインを遮断しており、
    埋め込みプレイヤーの再生前広告ステータス問い合わせが失敗→再生拒否(onErrorは発火せずプレイヤー内部UIとして
    表示されるだけ、なのでv1.85のwatchdogでしか検知できなかった症状と一致)
  - 計測: `onReceivedError`/`onReceivedHttpError`でサブリソース失敗を`[YT-NET]`ログ化、疎通プローブに
    doubleclick等4項目追加、YouTube画面到達時に疎通プローブ再実行
  - 対処: `youtube-nocookie.com`切替え(B-1)、広告系ホストの`shouldInterceptRequest`空200化(B-2)、
    Desktop UA化(B-3)、watchdog猶予延長12→20秒+段階ログ(B-4)。いずれもフラグ1つで無効化可能な設計
  - リリース: versionCode 89 / versionName "1.89"
  - 次アクション: `dt-astbx5-01`で実機検証。オーバーレイ1ページ目の`[YT-NET]`ログと3ページ目の`YT疎通:`から
    原因を確定 → 再生成功ならそのままクローズ、`*.googlevideo.com`自体が塞がれていると判明した場合は
    ネットワーク管理者への許可依頼 or サーバー側にmp4を置く新typeの実装を検討

## APK OTA更新 dev/prod チャネル分離

- [x] **Phase 1（サーバー側）**: signage-server-windows で実装完了・実データ検証済み（2026-09-03）
  - `uploads/android/{dev,prod,backup}` 分離、`clients.is_dev` 追加、管理画面にチェックボックス、
    `promote-to-prod.ps1`/`.bat`（`y`確認あり）+デスクトップショートカット、`sync-android-apk.ps1` の同期先を `dev/` へ変更
  - 開発機: `dt-astbx5-01`(id=2) のみ `is_dev=1`
  - 依頼仕様書: `.devrelay-output/20260903_apk-dev-prod-channel-spec.md`
  - 検証済み: dev/prod ともv1.85、APK4経路すべてSHA256一致、check APIが `channel:"dev"/"prod"` を正しく出し分け
  - 検証用ゴミファイル `dl_dev.apk`/`dl_prod.apk` はサーバー側で削除済み
- [x] **Phase 2（Android v1.86）**: 実装完了
  - `data/Models.kt`: `UpdateInfo` に `channel: String? = null` 追加
  - `data/ConfigManager.kt`: `last_update_channel` / `update_attempt_version_code` / `update_attempt_count` のアクセサ追加
  - `service/AppUpdateManager.kt`: 更新ログに channel 表示 + **更新ループ防御**（同一version_codeへの試行が
    `MAX_INSTALL_ATTEMPTS(3)` に達したら以後スキップ。記録はDL/インストールの前に実施。成功検知で自動リセット）
  - `player/PlayerActivity.kt`: デバッグオーバーレイ3ページ目に「更新チャネル: dev/prod」「更新スキップ中: ...」表示
  - `service/SignageService.kt` / `data/ServerClient.kt` は無改修（設計上不要と判明）
  - リリース: versionCode 86 / versionName "1.86"、CLAUDE.md Release Checklist に手順7(本番反映)を追記
  - push後の残タスク: サーバー側でdev=86/prod=85の出し分け確認 → 実機(`dt-astbx5-01`)で自動更新確認 →
    本番4台が無風であることを確認 → 問題なければ `本番反映.bat` で本番展開
  - ✅ 2026-09-03 夜: サーバー検証OK(dev=86/prod=85出し分け正常) → `本番反映.bat`実行(`y`確認)で本番4台へv1.86反映完了。本件クローズ

## SMB PDFフォルダ 日付フィルタのスキップ可視化 (v1.87)

- [x] **実装完了・ビルド成功（2026-09-03）**
  - 背景: `dt-astbx5-01`で共有フォルダ`\\10.20.171.189\temp\signage`の中身が丸ごと表示されない事象を調査した結果、
    バグではなく命名規約の表示終了日`20260630`切れが原因と判明。ただし「SMB接続失敗で0件」と「全ファイル期限切れで0件」が
    現場のオーバーレイから区別できない問題が判明したため、診断表示のみを追加する（日付フィルタの挙動自体は変更しない）
  - `SmbPdfManager.kt`: `SkippedByDate`データクラス + `dateSkippedMap`(親pdf_folder.id単位)を追加。
    `buildPlaylist()`の日付フィルタ`continue`直前で記録。`syncFolder()`/`buildPlaylistFromCache()`両経路が通る
    唯一の合流点のため経路を問わず捕捉できる
  - `PlayerActivity.kt`: `buildFlatScreens()`冒頭で`resetDateSkipped()`、`logDateSkippedIfChanged()`で
    内容変化時のみデバッグログへ`[SMB] 期限外スキップ: N件`出力（3分毎の定期再同期での連投を抑止）。
    デバッグオーバーレイ2ページ目`buildScheduleInfoText()`に「非表示(期限切れ): N件」ブロックを追加(0件時は非表示)
  - リリース: versionCode 87 / versionName "1.87"
  - 対処（コード変更なし）: 共有フォルダ側でファイル名の終了日をリネームで延長すれば即座に表示再開（反映は最大3分、
    即時なら端末再起動）

## 画面一覧オーバーレイのクラッシュ修正 + 未捕捉例外の自動復帰 (v1.88)

- [x] **実装完了・ビルド成功（2026-09-03）**
  - 症状: 投影コンテンツが1件・2件の場合、リモコンの「↓」を押しているとアプリが落ちる
  - 原因: `PlayerActivity.renderScreenList()` の7行窓インデックス計算 `(selectedListIndex + offset + size) % size`
    (offset=-3..3) がKotlinの `%`(floorModではなく剰余)により負値になり、`flatScreens[-1]` で
    `ArrayIndexOutOfBoundsException`。`size==2`かつ`selectedListIndex==0`のとき`(0-3+2)=-1`→`-1%2=-1`で確実に発生。
    他の剰余箇所(L695/720/758/835/2061/2099/2107/2468/2473)は全て`-1+size`止まりで安全、欠陥はここ1箇所のみ
  - `PlayerActivity.kt`: `renderScreenList()`を`Math.floorMod`化 + 画面数が7(窓の最大行数)以下のときは
    循環させず全件を1回ずつ表示するよう修正(クラッシュ回避 + 「2画面なのに同じ2件が7行並ぶ」表示も解消)。
    カーソル判定を`offset==0`から`idx==selectedListIndex`に変更
  - あわせて、アプリに未捕捉例外ハンドラが無く一度落ちると端末再起動まで止まったままだった問題に対処:
    `onCreate()`冒頭で`installCrashHandler()`により`Thread.setDefaultUncaughtExceptionHandler`を設置。
    例外情報を`ConfigManager.recordCrash()`で記録(`commit()`で同期書き込み)、`AlarmManager.set(RTC,...)`で
    `MainActivity`への復帰を予約してから`Process.killProcess()`。非正確アラームのため`SCHEDULE_EXACT_ALARM`
    権限不要・`AndroidManifest.xml`無改修。60秒以内の連続クラッシュが5回に達したら再起動間隔を2秒→5分に延長
    (暴走ループ防止)。`startPlayback()`が「[PLAY] 再生開始」に到達した時点で`resetCrashCount()`(成功検知)
  - `ConfigManager.kt`: `recordCrash()`/`getLastCrash()`/`getCrashCount()`/`resetCrashCount()`追加
  - デバッグオーバーレイ3ページ目に「直近クラッシュ: MM/dd HH:mm:ss ExceptionClass: message @ File:line (連続N回)」
    を追加(未記録時は行ごと非表示)
  - リリース: versionCode 88 / versionName "1.88"
  - `AndroidManifest.xml` / `SmbPdfManager.kt` / `SignageService.kt` / `ServerClient.kt` は無改修
