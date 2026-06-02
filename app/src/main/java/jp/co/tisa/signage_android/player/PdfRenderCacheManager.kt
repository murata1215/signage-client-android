package jp.co.tisa.signage_android.player

import android.content.Context
import com.google.gson.Gson
import java.io.File

/**
 * PDF.jsレンダリング済みキャンバスをJPEG画像としてキャッシュし、
 * 2回目以降はPDF.jsを経由せず画像表示で高速化する。
 *
 * キャッシュ構造:
 *   filesDir/pdf_render_cache/{contentId}/
 *     meta.json   - 検証用メタデータ
 *     s0.jpg      - スクリーン0
 *     s1.jpg      - スクリーン1 ...
 */
class PdfRenderCacheManager(context: Context) {

    private val cacheDir = File(context.filesDir, "pdf_render_cache").apply {
        if (!exists()) mkdirs()
    }

    private val gson = Gson()

    // =========================================================================
    // Cache key
    // =========================================================================

    /** 単一PDFのキャッシュディレクトリ */
    fun getCacheDir(contentId: Int): File = File(cacheDir, contentId.toString())

    /** デュアル初ページ（2つの異なるPDF）のキャッシュディレクトリ */
    fun getDualCacheDir(leftContentId: Int, rightContentId: Int): File =
        File(cacheDir, "dual_${leftContentId}_${rightContentId}")

    /** WebコンテンツのキャッシュキーをURLから生成（contentId=0のため URL ハッシュを使用） */
    fun webCacheKey(url: String): String {
        val h = url.hashCode()
        return if (h < 0) "web_n${-h.toLong()}" else "web_$h"
    }

    private fun getWebCacheDir(url: String): File = File(cacheDir, webCacheKey(url))

    // =========================================================================
    // Validation
    // =========================================================================

    /**
     * 有効なレンダリングキャッシュが存在するかチェック。
     * PDFファイルのサイズ・更新日時、画面解像度が一致し、
     * 全スクリーン画像が揃っている場合のみtrue。
     */
    fun hasCachedRender(
        contentId: Int,
        sourceFile: File,
        screenWidth: Int,
        screenHeight: Int,
        firstPageOnly: Boolean
    ): Boolean {
        return hasCachedRenderInDir(getCacheDir(contentId), sourceFile, screenWidth, screenHeight, firstPageOnly)
    }

    fun hasCachedDualRender(
        leftContentId: Int,
        rightContentId: Int,
        leftFile: File,
        rightFile: File,
        screenWidth: Int,
        screenHeight: Int
    ): Boolean {
        val dir = getDualCacheDir(leftContentId, rightContentId)
        val meta = loadMeta(dir) ?: return false

        // デュアルの場合、両方のソースファイルをチェック
        if (meta.sourceFileSize != leftFile.length() || meta.sourceFileSize2 != rightFile.length()) return false
        if (meta.sourceLastModified != leftFile.lastModified() || meta.sourceLastModified2 != rightFile.lastModified()) return false
        if (meta.screenWidth != screenWidth || meta.screenHeight != screenHeight) return false
        if (meta.totalScreens < 1) return false

        // 全画像ファイル存在チェック
        for (i in 0 until meta.totalScreens) {
            if (!getScreenFile(dir, i).exists()) return false
        }
        return true
    }

    private fun hasCachedRenderInDir(
        dir: File,
        sourceFile: File,
        screenWidth: Int,
        screenHeight: Int,
        firstPageOnly: Boolean
    ): Boolean {
        val meta = loadMeta(dir) ?: return false

        if (!sourceFile.exists()) return false
        if (meta.sourceFileSize != sourceFile.length()) return false
        if (meta.sourceLastModified != sourceFile.lastModified()) return false
        if (meta.screenWidth != screenWidth || meta.screenHeight != screenHeight) return false
        if (meta.firstPageOnly != firstPageOnly) return false
        if (meta.totalScreens < 1) return false

        // 全画像ファイル存在チェック
        for (i in 0 until meta.totalScreens) {
            if (!getScreenFile(dir, i).exists()) return false
        }
        return true
    }

    // =========================================================================
    // Read
    // =========================================================================

    /** キャッシュ済み画像パスのリストを返す。未完成ならnull */
    fun getCachedImagePaths(contentId: Int): List<File>? {
        return getCachedImagePathsInDir(getCacheDir(contentId))
    }

    fun getCachedDualImagePaths(leftContentId: Int, rightContentId: Int): List<File>? {
        return getCachedImagePathsInDir(getDualCacheDir(leftContentId, rightContentId))
    }

    private fun getCachedImagePathsInDir(dir: File): List<File>? {
        val meta = loadMeta(dir) ?: return null
        val files = (0 until meta.totalScreens).map { getScreenFile(dir, it) }
        return if (files.all { it.exists() }) files else null
    }

    // =========================================================================
    // Web thumbnail
    // =========================================================================

    /** 指定URLのwebサムネイルがTTL内かつ存在するか */
    fun hasFreshWebThumbnail(url: String, ttlMillis: Long): Boolean {
        val dir = getWebCacheDir(url)
        val meta = loadMeta(dir) ?: return false
        if (!getScreenFile(dir, 0).exists()) return false
        return System.currentTimeMillis() - meta.createdAt < ttlMillis
    }

    /** webサムネイル画像ファイルを返す（存在時のみ） */
    fun getWebThumbnail(url: String): File? {
        return getScreenFile(getWebCacheDir(url), 0).takeIf { it.exists() }
    }

    /** webサムネイルJPEGを1枚保存（source検証は使わずTTLのみで管理） */
    fun saveWebThumbnail(url: String, jpegBytes: ByteArray, title: String, screenWidth: Int, screenHeight: Int) {
        try {
            val dir = getWebCacheDir(url)
            if (!dir.exists()) dir.mkdirs()
            getScreenFile(dir, 0).writeBytes(jpegBytes)
            val meta = RenderCacheMeta(
                sourceFileSize = 0,
                sourceLastModified = 0,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                totalScreens = 1,
                firstPageOnly = true,
                title = title,
                createdAt = System.currentTimeMillis()
            )
            saveMeta(dir, meta)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // =========================================================================
    // Write
    // =========================================================================

    /**
     * レンダリング済みスクリーンのJPEGバイトを保存。
     * 全スクリーンが揃った時点でメタデータも書き込む。
     */
    fun saveRenderedScreen(
        cacheKey: String,
        screenIndex: Int,
        totalScreens: Int,
        jpegBytes: ByteArray,
        sourceFile: File,
        screenWidth: Int,
        screenHeight: Int,
        firstPageOnly: Boolean,
        sourceFile2: File? = null,  // デュアル用
        title: String = ""          // 一覧表示用の整形済みタイトル
    ) {
        try {
            val dir = File(cacheDir, cacheKey)
            if (!dir.exists()) dir.mkdirs()

            // JPEG保存
            getScreenFile(dir, screenIndex).writeBytes(jpegBytes)

            // 全スクリーンが揃ったらメタデータ保存
            val allExist = (0 until totalScreens).all { getScreenFile(dir, it).exists() }
            if (allExist) {
                val meta = RenderCacheMeta(
                    sourceFileSize = sourceFile.length(),
                    sourceLastModified = sourceFile.lastModified(),
                    sourceFileSize2 = sourceFile2?.length() ?: 0,
                    sourceLastModified2 = sourceFile2?.lastModified() ?: 0,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight,
                    totalScreens = totalScreens,
                    firstPageOnly = firstPageOnly,
                    title = title,
                    createdAt = System.currentTimeMillis()
                )
                saveMeta(dir, meta)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // =========================================================================
    // Cleanup
    // =========================================================================

    /** アクティブでないcontentId・webキャッシュを削除 */
    fun cleanupUnused(activeContentIds: Set<Int>, activeWebKeys: Set<String> = emptySet()) {
        cacheDir.listFiles()?.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            // "web_xxx" ディレクトリはアクティブキー判定
            if (dir.name.startsWith("web_")) {
                if (dir.name !in activeWebKeys) {
                    dir.deleteRecursively()
                }
            } else if (dir.name.startsWith("dual_")) {
                // "dual_xxx_yyy" ディレクトリは個別判定
                val parts = dir.name.removePrefix("dual_").split("_")
                val ids = parts.mapNotNull { it.toIntOrNull() }
                if (ids.none { it in activeContentIds }) {
                    dir.deleteRecursively()
                }
            } else {
                val id = dir.name.toIntOrNull()
                if (id != null && id !in activeContentIds) {
                    dir.deleteRecursively()
                }
            }
        }
    }

    /** 指定contentIdのキャッシュを無効化 */
    fun invalidate(contentId: Int) {
        getCacheDir(contentId).let { if (it.exists()) it.deleteRecursively() }
    }

    // =========================================================================
    // Internal
    // =========================================================================

    private fun getScreenFile(dir: File, index: Int) = File(dir, "s$index.jpg")

    private fun loadMeta(dir: File): RenderCacheMeta? {
        val file = File(dir, "meta.json")
        if (!file.exists()) return null
        return try {
            gson.fromJson(file.readText(), RenderCacheMeta::class.java)
        } catch (e: Exception) {
            null
        }
    }

    private fun saveMeta(dir: File, meta: RenderCacheMeta) {
        File(dir, "meta.json").writeText(gson.toJson(meta))
    }

    data class RenderCacheMeta(
        val sourceFileSize: Long,
        val sourceLastModified: Long,
        val sourceFileSize2: Long = 0,      // デュアル初ページ用
        val sourceLastModified2: Long = 0,   // デュアル初ページ用
        val screenWidth: Int,
        val screenHeight: Int,
        val totalScreens: Int,
        val firstPageOnly: Boolean,
        val title: String = "",          // 一覧表示用の整形済みタイトル
        val createdAt: Long
    )
}
