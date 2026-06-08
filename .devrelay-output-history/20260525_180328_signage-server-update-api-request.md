# signage-server アップデートAPI追加依頼

## 概要

Android版サイネージクライアント (signage-client-android) に自動アップデート機能を実装しました。
サーバー側に以下の2つのAPIエンドポイントの追加をお願いします。

## 必要なAPI

### 1. アップデートチェック

```
GET /api/player/update/check?key={client_key}
```

**レスポンス例:**
```json
{
  "version_code": 2,
  "version_name": "1.1",
  "url": "/api/player/update/download",
  "force": false
}
```

| フィールド | 型 | 説明 |
|-----------|-----|------|
| `version_code` | number | APKのversionCode（整数、現在のアプリは1） |
| `version_name` | string | 表示用バージョン名（例: "1.1"） |
| `url` | string | APKダウンロードパス（相対パスまたは絶対URL） |
| `force` | boolean | 強制アップデートフラグ（将来用、現在未使用） |

**動作:**
- アップデートがない場合: 現在と同じ `version_code` を返す、または 404 を返す
- 認証: 既存のPlayer APIと同じく `?key={client_key}` で認証

### 2. APKダウンロード

```
GET /api/player/update/download?key={client_key}
```

**レスポンス:**
- Content-Type: `application/vnd.android.package-archive`
- Body: APKバイナリ

## サーバー側の実装案

### 案A: 管理画面からAPKアップロード

1. 管理画面に「アプリ更新」ページを追加
2. APKファイル + versionCode + versionName を入力して登録
3. DBに保存（uploads/ディレクトリにAPK、メタ情報はSQLiteに）

### 案B: ファイル配置 + 設定ファイル（シンプル版）

1. サーバーの特定ディレクトリにAPKを配置
   ```
   uploads/android/
   ├── signage-android.apk
   └── version.json
   ```

2. `version.json` の内容:
   ```json
   {
     "version_code": 2,
     "version_name": "1.1"
   }
   ```

3. APIは `version.json` を読んでレスポンス生成、ダウンロードはファイルを返すだけ

## Android側の動作（実装済み）

```
SignageService (起動30秒後 + 1時間ごと)
  -> GET /api/player/update/check?key=...
  -> サーバーの version_code > アプリの versionCode ?
    -> YES: APKダウンロード -> Androidインストーラー起動
    -> NO: 何もしない（ログ出力のみ）
```

- Device Owner設定済み端末: サイレントインストール
- 未設定端末: 「インストール」確認画面が表示される（タップで更新完了）

## テスト方法

1. サーバーに versionCode=2 のAPKを配置
2. Android端末でアプリ起動（現在 versionCode=1）
3. 30秒後または1時間後にアップデートチェックが走る
4. APKがダウンロードされ、インストール画面が表示される

## 参考: Android側の関連ファイル

| ファイル | 役割 |
|---------|------|
| `data/Models.kt` | `UpdateInfo` データクラス |
| `data/ServerClient.kt` | `checkForUpdate()`, `downloadApk()` |
| `service/AppUpdateManager.kt` | バージョン比較、ダウンロード、インストール起動 |
| `service/SignageService.kt` | 1時間ごとのアップデートチェック |
