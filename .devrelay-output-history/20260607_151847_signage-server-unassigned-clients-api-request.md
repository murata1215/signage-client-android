# signage-server 未割当クライアント一覧API 追加依頼

## 背景・目的

Android版サイネージクライアント (signage-android v1.71) の初期設定を簡略化しました。

**従来**: 端末でサーバーURLとClient Key (UUID) をリモコンで手入力 → 接続テスト → 保存
**新方式**:
1. サーバーURLはデフォルト値 `https://service.internal.atg.co.jp/tsinternal/signage-server-windows` をプリセット
2. 「未割当クライアントを取得」ボタンでサーバーから未割当クライアント一覧を取得
3. リストから1つ選択 → 自動で接続テスト → 成功したら設定保存して再生開始

UUIDのリモコン手入力が不要になります。このためにサーバー側へ以下のAPI追加をお願いします。

## 必要なAPI

### GET /api/player/unassigned-clients

**認証**: なし（key不要。初期設定時はまだkeyを持っていないため）

**レスポンス例:**
```json
{
  "clients": [
    { "id": 3, "name": "1F受付モニター", "client_key": "550e8400-e29b-41d4-a716-446655440000" },
    { "id": 5, "name": "3F会議室前", "client_key": "6ba7b810-9dad-11d1-80b4-00c04fd430c8" }
  ]
}
```

| フィールド | 型 | 説明 |
|-----------|-----|------|
| `clients` | array | 未割当クライアントの配列（0件なら空配列 `[]`） |
| `clients[].id` | number | クライアントID |
| `clients[].name` | string | クライアント名（端末の選択リストに表示される） |
| `clients[].client_key` | string | Client Key (UUID) |

**「未割当」の定義案:**
- `last_heartbeat IS NULL`（一度もハートビートを送っていないクライアント）
- 端末が接続してハートビートを送り始めると自動的にリストから消える
- DBスキーマ変更不要で実装できる想定です

## あわせてお願いしたいこと（任意）

### 管理画面に「割当解除」ボタン

端末の入れ替え・再セットアップ時に、該当クライアントを再び「未割当」に戻す手段が必要です。
管理画面のクライアント一覧に「割当解除」ボタンを追加し、`last_heartbeat` をクリア（NULL化）できるようにしてください。

※ これが無い場合でも、従来どおりClient Keyの手入力フォールバックで再セットアップは可能です。

## セキュリティに関する注記

このAPIは **client_key を無認証で返します**。以下の前提・緩和策を確認してください。

- **前提**: 社内ネットワーク限定での運用（外部公開しない）
- 返すのは「未割当」のクライアントのみ。割当済み（稼働中）端末のkeyは漏れない
- それでも気になる場合の強化案（Android側は対応可能なので必要なら言ってください）:
  - `GET /api/player/unassigned-clients?setup_token=xxxx` のような共有セットアップトークンを必須にする
  - 一覧APIでは `client_key` を返さず `id` のみ返し、`POST /api/player/claim` で id→key を1回だけ引き換える方式にする

## Android側の動作（実装済み: v1.71）

```
初期設定画面
  -> サーバーURL: デフォルト値プリセット（変更可能）
  -> [未割当クライアントを取得] ボタン
       -> GET {serverUrl}/api/player/unassigned-clients
       -> 一覧表示（0件なら「未割当のクライアントがありません」）
  -> クライアント選択
       -> GET /api/player/schedule?key={client_key} で接続テスト
       -> 成功: 設定保存 -> 再生開始（以後ハートビート送信 = 割当済みになる）
       -> 失敗: エラー表示（リスト選択し直し可能）
  -> フォールバック: 従来のClient Key手入力 + 接続テスト + 保存も残してあります
```

- サーバーAPI未実装の間は「取得に失敗しました（サーバー未対応…）」と表示され、手入力フローで運用できます（Android側を先行リリース済み）

## テスト方法

1. サーバー管理画面で新規クライアントを作成（ハートビート未受信の状態）
2. `curl "https://.../api/player/unassigned-clients"` で該当クライアントが返ることを確認
3. Android端末でアプリ初期設定 → 「未割当クライアントを取得」→ 作成したクライアントが表示される
4. 選択 → 再生開始 → ハートビート受信後、再度curlするとリストから消えていることを確認

## 参考: Android側の関連ファイル

| ファイル | 役割 |
|---------|------|
| `data/Models.kt` | `UnassignedClient`, `UnassignedClientsResponse` データクラス |
| `data/ServerClient.kt` | `fetchUnassignedClients()` (key無しGET) |
| `ui/SetupScreen.kt` | デフォルトURL + 一覧取得/選択UI + 手入力フォールバック |
