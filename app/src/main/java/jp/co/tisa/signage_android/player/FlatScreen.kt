package jp.co.tisa.signage_android.player

import jp.co.tisa.signage_android.data.PlaylistItem
import java.io.File

/**
 * フラット化された1画面分の表示データ。
 * スケジュール内の全コンテンツ（web, pdf, pdf_folder子PDF）を展開し、
 * 1つのリストとして管理するための単位。
 *
 * ナビゲーションは currentScreenIndex の増減のみで完結する。
 */
data class FlatScreen(
    /** 表示タイプ: "web", "pdf", "dual_pdf" */
    val type: String,
    /** ステータスバー表示名 */
    val displayName: String,
    /** 表示時間（秒） */
    val durationSeconds: Int,
    /** allPagesモード（PDF.js内ページ送り） */
    val isAllPages: Boolean = false,
    /** allPagesのページ間秒数 */
    val pdfPageDuration: Int? = null,

    // --- PDF用 ---
    /** PDFのcontentId */
    val contentId: Int = 0,
    /** ローカルPDFファイル */
    val sourceFile: File? = null,
    /** firstPageOnlyモード */
    val firstPageOnly: Boolean = true,
    /** 縦長PDF */
    val isPortrait: Boolean = false,

    // --- デュアルPDF用 ---
    /** 右側PDFのcontentId */
    val rightContentId: Int = 0,
    /** 右側のローカルPDFファイル */
    val rightSourceFile: File? = null,

    // --- WEB用 ---
    /** URL */
    val url: String? = null,

    // --- 元データ参照 ---
    /** 元のPlaylistItem（PdfJsInterfaceコールバック等で使用） */
    val item: PlaylistItem? = null,
    /** 元のメインプレイリストインデックス */
    val mainPlaylistIndex: Int = 0,
) {
    companion object {
        /** WebコンテンツからFlatScreenを作成 */
        fun fromWeb(item: PlaylistItem, mainIndex: Int): FlatScreen {
            return FlatScreen(
                type = "web",
                displayName = item.name,
                durationSeconds = item.durationSeconds,
                url = item.url,
                item = item,
                mainPlaylistIndex = mainIndex,
            )
        }

        /** サーバーPDFからFlatScreenを作成 */
        fun fromPdf(item: PlaylistItem, sourceFile: File, mainIndex: Int): FlatScreen {
            val isAllPages = item.pdfPageDuration != null
            return FlatScreen(
                type = "pdf",
                displayName = item.name,
                durationSeconds = item.durationSeconds,
                isAllPages = isAllPages,
                pdfPageDuration = item.pdfPageDuration,
                contentId = item.contentId,
                sourceFile = sourceFile,
                firstPageOnly = !isAllPages,
                isPortrait = item.isPortrait,
                item = item,
                mainPlaylistIndex = mainIndex,
            )
        }

        /** pdf_folder子PDFからFlatScreenを作成 */
        fun fromSubPdf(
            subItem: PlaylistItem,
            sourceFile: File,
            parentFolder: PlaylistItem,
            mainIndex: Int
        ): FlatScreen {
            val isFirstPageOnly = parentFolder.firstPageOnly == true || subItem.pdfPageDuration == null
            val isAllPages = subItem.pdfPageDuration != null
            return FlatScreen(
                type = "pdf",
                displayName = subItem.name,
                durationSeconds = subItem.durationSeconds,
                isAllPages = isAllPages,
                pdfPageDuration = subItem.pdfPageDuration,
                contentId = subItem.contentId,
                sourceFile = sourceFile,
                firstPageOnly = isFirstPageOnly,
                isPortrait = subItem.isPortrait,
                item = subItem,
                mainPlaylistIndex = mainIndex,
            )
        }

        /** デュアルPDF（2つのPDFの1ページ目を見開き）からFlatScreenを作成 */
        fun fromDualPdf(
            leftItem: PlaylistItem,
            leftFile: File,
            rightItem: PlaylistItem,
            rightFile: File,
            parentFolder: PlaylistItem,
            mainIndex: Int
        ): FlatScreen {
            return FlatScreen(
                type = "dual_pdf",
                displayName = "${leftItem.name} / ${rightItem.name}",
                durationSeconds = leftItem.durationSeconds,
                contentId = leftItem.contentId,
                sourceFile = leftFile,
                firstPageOnly = true,
                isPortrait = true,
                rightContentId = rightItem.contentId,
                rightSourceFile = rightFile,
                item = leftItem,
                mainPlaylistIndex = mainIndex,
            )
        }
    }
}
