# サイネージ SMB PDFフォルダ表示機能 仕様書

## 1. 概要

管理画面のスケジュール編集で、既存の「WEBページ」「PDFファイル」に加え、**「PDFフォルダ（SMB共有）」** をプレイリストアイテムとして追加する。

指定したWindowsネットワーク共有フォルダ内のPDFファイルを自動取得し、サイネージ端末でローテーション表示する。

## 2. 再生イメージ

```
プレイリスト例:
  1. [web]        社内ポータル (30秒)
  2. [pdf_folder] \\server\share\報告書 (各PDF 1ページ目のみ, 10秒/件)
  3. [web]        お知らせページ (20秒)
  4. [pdf_folder] \\server\share\品質データ (各PDF 全ページ, 10秒/ページ)
  5. [pdf]        固定PDF (30秒)
```

PDFフォルダの番が来ると:
1. STBがSMB共有に接続 → フォルダスキャン → 差分ダウンロード
2. 同期中は「更新中」画面を表示（ダウンロードありの場合は完了後10秒間完了画面）
3. フォルダ内の全PDFをファイル名順にローテーション表示
4. 全PDF表示完了後、プレイリストの次のアイテムへ

## 3. サーバー側変更

### 3.1 管理画面 — プレイリストアイテム追加

プレイリスト編集画面に、コンテンツタイプ `pdf_folder` を追加。

**入力項目（1フォルダにつき）:**

| 項目 | フィールド名 | 型 | 必須 | 説明 | 入力例 |
|------|------------|-----|------|------|--------|
| 名前 | name | string | Yes | 表示用名称 | "高岡工場 品質レポート" |
| SMBパス | smb_path | string | Yes | UNCパス形式 | `\\10.20.171.21\05lf\workspace\signage\030` |
| ユーザー名 | smb_username | string | Yes | SMB認証ユーザー | "05_TAKATSUJI" |
| パスワード | smb_password | string | Yes | SMB認証パスワード（DB保存時は暗号化） | "Lf6411" |
| 1ページ目のみ | first_page_only | boolean | Yes | true=各PDFの1ページ目のみ, false=全ページ表示 | true |
| 表示秒数 | duration_seconds | int | Yes | first_page_only=true: 1PDFあたりの秒数, first_page_only=false: 1ページあたりの秒数 | 10 |

**バリデーション:**
- `smb_path`: `\\` で始まること（UNCパス形式）
- `duration_seconds`: 1〜999 の整数
- `smb_username`: 空でないこと
- `smb_password`: 空でないこと

### 3.2 DB保存

`smb_password` はDBに平文保存しない。以下のいずれかの簡易暗号化を適用:
- **推奨**: AES-256 (アプリ共通鍵) で暗号化して保存
- アプリ共通鍵はサーバーの環境変数 or 設定ファイルで管理
- API配信時も暗号化されたまま配信し、Android側で同じ鍵で復号する

**暗号化仕様（サーバー・クライアント共通）:**
- アルゴリズム: AES-256-CBC
- 鍵: サーバー/クライアント共通の固定鍵（環境変数 `SIGNAGE_ENCRYPTION_KEY` で管理）
- IV: 暗号文の先頭16バイトに付与
- 出力形式: Base64エンコード文字列

### 3.3 スケジュールAPI変更

```
GET /api/player/schedule?key={client_key}
```

**レスポンス — `playlist` 配列内の新アイテムタイプ:**

```json
{
  "version": 2,
  "play_start_time": "08:00",
  "play_end_time": "20:00",
  "playlist": [
    {
      "id": 101,
      "scope": "schedule_1",
      "content_id": 101,
      "name": "高岡工場 品質レポート",
      "type": "pdf_folder",
      "url": null,
      "file_url": null,
      "pdf_page_duration": null,
      "duration_seconds": 10,
      "display_order": 2,
      "use_proxy": false,
      "proxy_url": null,
      "smb_path": "\\\\10.20.171.21\\05lf\\workspace\\signage\\030",
      "smb_username": "05_TAKATSUJI",
      "smb_password": "BASE64_AES_ENCRYPTED_STRING_HERE",
      "first_page_only": true
    },
    {
      "id": 102,
      "scope": "schedule_1",
      "content_id": 102,
      "name": "品質データ 全ページ",
      "type": "pdf_folder",
      "url": null,
      "file_url": null,
      "pdf_page_duration": null,
      "duration_seconds": 10,
      "display_order": 4,
      "use_proxy": false,
      "proxy_url": null,
      "smb_path": "\\\\10.20.171.21\\05lf\\workspace\\signage\\031",
      "smb_username": "05_MEIKO",
      "smb_password": "BASE64_AES_ENCRYPTED_STRING_HERE",
      "first_page_only": false
    }
  ]
}
```

### 3.4 新規フィールド一覧

既存の `PlaylistItem` に以下を追加:

| フィールド | JSON key | 型 | 説明 |
|-----------|----------|-----|------|
| SMBパス | `smb_path` | string? | UNCパス (`\\host\share\path`) |
| SMBユーザー | `smb_username` | string? | SMB認証ユーザー名 |
| SMBパスワード | `smb_password` | string? | AES暗号化済みパスワード (Base64) |
| 1ページ目のみ | `first_page_only` | boolean? | true: 1ページ目のみ / false: 全ページ |

※ `type` が `pdf_folder` の場合のみ使用。`web` / `pdf` の場合はnull。

### 3.5 既存フィールドの扱い（type=pdf_folder時）

| フィールド | 値 | 説明 |
|-----------|-----|------|
| `type` | `"pdf_folder"` | 新タイプ |
| `duration_seconds` | ユーザー指定値 | first_page_only=true: 1PDFあたり秒数, false: 1ページあたり秒数 |
| `url` | null | 使用しない |
| `file_url` | null | 使用しない |
| `pdf_page_duration` | null | Android側で動的に設定するためサーバーでは不要 |
| `use_proxy` | false | SMBはローカルネットワーク接続のためプロキシ不要 |

## 4. Android側の動作

### 4.1 type=pdf_folder 受信時の処理フロー

```
1. スケジュールAPI受信 → type="pdf_folder" のアイテムを検出
2. そのアイテムの表示順番が来た時:
   a. smb_password を復号
   b. smb_path をパース (host / shareName / path に分解)
   c. SMB接続 → フォルダ内の .pdf ファイル一覧取得
   d. 前回からの差分のみダウンロード (lastModified + fileSize で変更検出)
   e. 同期完了後、フォルダ内の全PDFをファイル名順にローテーション表示
   f. 全PDF表示完了 → プレイリストの次のアイテムへ
```

### 4.2 UNCパスの解析ルール

```
\\10.20.171.21\05lf\workspace\signage\030
  ├─ host:      10.20.171.21
  ├─ shareName: 05lf
  └─ path:      workspace/signage/030
```

- `\\` 以降の最初のセグメント = host
- 2番目のセグメント = shareName
- 3番目以降 = path（`\` → `/` に変換）

### 4.3 Android側で必要な Models.kt 変更

```kotlin
data class PlaylistItem(
    // ... 既存フィールド ...
    @SerializedName("smb_path") val smbPath: String? = null,
    @SerializedName("smb_username") val smbUsername: String? = null,
    @SerializedName("smb_password") val smbPassword: String? = null,
    @SerializedName("first_page_only") val firstPageOnly: Boolean? = null
)
```

## 5. 暗号化鍵の共有方法

| 環境 | 設定場所 | 備考 |
|------|---------|------|
| サーバー | 環境変数 `SIGNAGE_ENCRYPTION_KEY` | 32文字（256bit） |
| Android | `BuildConfig` or ハードコード定数 | APKに埋め込み |

鍵の例（本番運用前に変更）:
```
SIGNAGE_ENCRYPTION_KEY=s1gn4g3_2024_AES_k3y_!@#$5678
```

## 6. 注意事項

- **ネットワーク**: SMBアクセスは社内LAN（10.x.x.x）で行うため、プロキシは不要
- **ポート**: SMBは TCP 445 を使用。ファイアウォールで許可されている前提
- **ファイル数上限**: 1フォルダあたりのPDFファイル数に上限は設けないが、実運用では100件程度を想定
- **ファイルサイズ**: 個別のPDFは数MB程度を想定。極端に大きなファイル（100MB超等）は非推奨
- **既存機能への影響**: `type=web` / `type=pdf` の既存アイテムには影響なし。追加フィールドはnullが入るのみ

## 7. 実装優先度

| 優先度 | 内容 |
|--------|------|
| P1 | 管理画面に `pdf_folder` タイプ追加 + DB保存 |
| P1 | スケジュールAPIに新フィールド追加 |
| P1 | パスワード暗号化/復号の実装 |
| P2 | 管理画面のバリデーション（UNCパス形式チェック等） |
| P2 | 管理画面のパスワード入力マスク |
