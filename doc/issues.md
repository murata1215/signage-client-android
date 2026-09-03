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
