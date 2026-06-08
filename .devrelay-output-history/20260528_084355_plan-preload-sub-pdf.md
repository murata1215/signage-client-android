# 案B: サブプレイリストPDF先読み（プリロード）実装プラン

## 背景

pdf_folderのサブプレイリスト再生時、PDFの読み込み（ファイル読み取り→Base64変換→WebView描画）に約5秒かかり、10秒のカウントダウン中の前半が黒画面になっている。

メインプレイリストでは3-WebView方式（active/next/prev）で先読みしているが、サブプレイリスト再生中はnextWebView/prevWebViewが未使用のまま。これを活用して先読みを行う。

## 現在のフロー（問題あり）

```
playNextSubPdf()
  ├── activeWebViewにPDFをロード開始
  ├── タイマー10秒開始 ← ここからカウント
  ├── PDF読み込み... (約5秒)
  ├── 表示される (残り5秒)
  └── タイマー完了 → 次のPDFへ
```

## 目標フロー（先読みあり）

```
playNextSubPdf()
  ├── nextWebViewに次のPDFを先読み開始 (バックグラウンド)
  ├── activeWebViewで現在のPDFを表示 (既に先読み済み → 即表示)
  ├── タイマー10秒開始
  ├── 10秒間フル表示
  └── タイマー完了 → WebViewスワップ → 即表示 → 次の先読み開始
```

## 実装詳細

### 変更ファイル: `PlayerActivity.kt` のみ

### 1. 新フィールド追加

```kotlin
// サブプレイリスト先読み管理
private var subNextReady: Boolean = false  // nextWebViewの先読み完了フラグ
```

### 2. startSubPlaylist() の変更

```kotlin
private fun startSubPlaylist(subPlaylist: List<PlaylistItem>) {
    pdfFolderSubPlaylist = subPlaylist
    pdfFolderSubIndex = 0

    // 最初のPDFはactiveWebViewに直接ロード
    // 2番目のPDF（あれば）をnextWebViewに先読み
    loadSubPdfToWebView(activeWebView!!, 0) {
        // 最初のPDFロード完了 → 再生開始 + 次を先読み
        playCurrentSubPdf()
        preloadNextSubPdf()
    }
}
```

### 3. playCurrentSubPdf() — 表示+タイマー開始（先読み済みのものを表示）

先読み済みのWebViewが既に表示されている状態で、タイマーだけ開始する。

```kotlin
private fun playCurrentSubPdf() {
    val subList = pdfFolderSubPlaylist ?: return
    if (pdfFolderSubIndex >= subList.size) {
        // 全子PDF完了 → メインプレイリストの次へ
        pdfFolderSubPlaylist = null
        pdfFolderSubIndex = 0
        currentPdfFolderItem = null
        advanceToNextMain()
        return
    }

    val subItem = subList[pdfFolderSubIndex]
    isPlaying = true
    isPaused = false
    disableWebViewInteraction()
    updateStatusBar(subItem)

    // タイマー開始（ここではPDFは既に表示済み）
    contentTimer?.let { handler.removeCallbacks(it) }
    // ... 既存のタイマーロジック（firstPageOnly/allPages分岐）
}
```

### 4. preloadNextSubPdf() — 次のPDFをnextWebViewに先読み

```kotlin
private fun preloadNextSubPdf() {
    val subList = pdfFolderSubPlaylist ?: return
    subNextReady = false

    // 次に表示するインデックスを計算
    val nextIdx = calculateNextSubIndex()
    if (nextIdx >= subList.size) {
        subNextReady = true  // もう先読み不要
        return
    }

    val isFirstPageOnly = currentPdfFolderItem?.firstPageOnly == true

    // デュアル表示の場合: 次の2つのPDFを先読み
    if (isFirstPageOnly && subList[nextIdx].isPortrait
        && nextIdx + 1 < subList.size && subList[nextIdx + 1].isPortrait) {
        loadDualPdfContent(nextWebView!!, subList[nextIdx], subList[nextIdx + 1])
        // loadDualPdfContent完了時にsubNextReady = true
    } else {
        // シングル表示: 次の1つのPDFを先読み
        loadSubPdfToWebView(nextWebView!!, nextIdx) {
            subNextReady = true
        }
    }
}
```

### 5. advanceToNextSubPdf() — WebViewスワップ+次の先読み

```kotlin
private fun advanceToNextSubPdf() {
    if (!subNextReady) {
        // 先読みがまだ完了していない場合は待つ
        handler.postDelayed({ advanceToNextSubPdf() }, 200)
        return
    }

    // WebViewスワップ（メインプレイリストのdoAdvance()と同様）
    val oldActive = activeWebView
    activeWebView = nextWebView
    nextWebView = oldActive

    // クロスフェード
    activeWebView?.apply {
        alpha = 0f
        visibility = View.VISIBLE
        animate().alpha(1f).setDuration(800).start()
    }
    oldActive?.animate()?.alpha(0f)?.setDuration(800)?.withEndAction {
        oldActive.visibility = View.INVISIBLE
    }?.start()

    // インデックス進める
    pdfFolderSubIndex = calculateNextSubIndex()

    // 表示+タイマー開始
    playCurrentSubPdf()

    // 次の先読み開始
    preloadNextSubPdf()
}
```

### 6. calculateNextSubIndex() — 次のインデックス計算

```kotlin
private fun calculateNextSubIndex(): Int {
    val subList = pdfFolderSubPlaylist ?: return 0
    val isFirstPageOnly = currentPdfFolderItem?.firstPageOnly == true
    val current = pdfFolderSubIndex
    val currentItem = subList.getOrNull(current) ?: return current + 1

    // デュアル表示の場合は2つ進める
    if (isFirstPageOnly && currentItem.isPortrait
        && current + 1 < subList.size && subList[current + 1].isPortrait) {
        return current + 2
    }
    return current + 1
}
```

### 7. loadSubPdfToWebView() — 汎用PDF読み込み（コールバック付き）

```kotlin
private fun loadSubPdfToWebView(
    webView: WebView,
    subIndex: Int,
    onReady: (() -> Unit)? = null
) {
    val subList = pdfFolderSubPlaylist ?: return
    val subItem = subList.getOrNull(subIndex) ?: return

    webView.setInitialScale(100)
    webView.webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            if (url?.contains("pdf-viewer.html") == true) {
                // PDFをBase64で注入
                loadPdfIntoViewerWithCallback(webView, subItem) {
                    onReady?.invoke()
                }
            }
        }
    }
    webView.loadUrl("file:///android_asset/pdfjs/pdf-viewer.html")
}
```

### 8. advanceToNext() の修正

```kotlin
private fun advanceToNext() {
    if (!isPlaying || isPaused) return

    if (pdfFolderSubPlaylist != null) {
        // WebViewスワップ方式で次のサブPDFへ
        contentTimer?.let { handler.removeCallbacks(it) }
        countdownTimer?.let { handler.removeCallbacks(it) }
        handler.post { advanceToNextSubPdf() }
        return
    }
    // ... 既存のメインプレイリスト進行
}
```

## 注意点

### WebView管理

- サブプレイリスト再生中: `activeWebView` と `nextWebView` の2枚を使用（`prevWebView` は未使用）
- サブプレイリスト完了後: 3-WebView体制に戻す（メインプレイリストの先読みが必要）
- サブプレイリスト開始時: nextWebView/prevWebViewの状態をリセット

### 最初のPDFだけ遅延が残る

- サブプレイリストの**1件目のPDF**は先読みできない（同期直後にすぐ表示するため）
- 2件目以降は先読み済みのWebViewにスワップするため即表示
- 対策: 同期完了画面（10秒）の間にactiveWebViewに1件目を先読みすることも可能

### allPagesモード（全ページ表示）との統合

- allPagesモードでは `onAllPagesCompleted` コールバックで次のPDFへ進む
- 先読みのタイミング: allPagesモードでは最初のページ描画完了（onPageChanged）時に次のPDFの先読みを開始できる
- 先読み対象: 次のPDFファイル全体（Base64注入まで完了させる）

### メモリ考慮

- STBのメモリが限られている場合、3つのWebViewに同時にBase64 PDFを持つと問題になる可能性
- 実運用のPDFサイズ（数MB）であれば問題ないと想定
- 巨大PDFの場合は要注意

## 実装順序

1. `startSubPlaylist()` を先読み対応に書き換え
2. `playCurrentSubPdf()` を新規追加（タイマーのみ管理）
3. `preloadNextSubPdf()` を新規追加
4. `advanceToNextSubPdf()` を新規追加（WebViewスワップ）
5. `loadSubPdfToWebView()` を新規追加（コールバック付きPDF読み込み）
6. `calculateNextSubIndex()` を新規追加（デュアル対応のインデックス計算）
7. `advanceToNext()` を修正（サブプレイリスト時はWebViewスワップ方式へ）
8. 既存の `playNextSubPdf()` は `playCurrentSubPdf()` + `preloadNextSubPdf()` に分割

## 検証手順

1. ビルド成功確認
2. firstPageOnlyモード: PDF間の切り替えが即座に行われること（黒画面なし）
3. firstPageOnly+見開き: デュアル表示のPDF間も即座に切り替わること
4. allPagesモード: 全ページ表示完了後、次のPDFが即座に表示されること
5. サブプレイリスト完了後、メインプレイリストの次のアイテムへ正常遷移
6. メインプレイリスト側のweb/pdf先読みが退行していないこと
