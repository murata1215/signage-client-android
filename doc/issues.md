# Issues

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
