# Changelog

## v1.86 (2026-09-03)
- APK OTA更新の dev/prod チャネル分離に対応（サーバー側Phase 1と対）。`git push` は常にサーバーの `dev` チャネルへ反映され、手動の `本番反映.bat` 実行まで本番機には配布されない
- `UpdateInfo` に `channel`("dev"/"prod") を追加し、デバッグオーバーレイ(KEYCODE 93 → 3ページ目)に「更新チャネル:」として表示
- 更新ループ防御を追加: 同一version_codeへの更新試行が3回に達しても `currentVersion` が上がらない場合は以後スキップし、オーバーレイに「更新スキップ中:」を表示（サーバー・APKの不整合による無限DL+インストーラー起動ループを防止）
- `SignageService.kt` / `ServerClient.kt` は無改修
