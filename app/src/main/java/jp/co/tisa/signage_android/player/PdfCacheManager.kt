package jp.co.tisa.signage_android.player

import android.content.Context
import jp.co.tisa.signage_android.data.PlaylistItem
import jp.co.tisa.signage_android.data.ServerClient
import java.io.File

class PdfCacheManager(
    context: Context,
    private val serverClient: ServerClient
) {
    private val cacheDir: File = File(context.filesDir, "pdf_cache").apply {
        if (!exists()) mkdirs()
    }

    fun getCachedPdfPath(contentId: Int): File {
        return File(cacheDir, "content_$contentId.pdf")
    }

    fun isCached(contentId: Int): Boolean {
        return getCachedPdfPath(contentId).exists()
    }

    suspend fun downloadIfNeeded(item: PlaylistItem): File? {
        if (item.type != "pdf") return null
        val file = getCachedPdfPath(item.contentId)
        if (file.exists()) return file

        val success = serverClient.downloadPdf(item.contentId, file)
        return if (success) file else null
    }

    suspend fun downloadAll(items: List<PlaylistItem>): Map<Int, File> {
        val result = mutableMapOf<Int, File>()
        items.filter { it.type == "pdf" }.forEach { item ->
            val file = downloadIfNeeded(item)
            if (file != null) {
                result[item.contentId] = file
            }
        }
        return result
    }

    fun cleanupUnused(activeContentIds: Set<Int>) {
        cacheDir.listFiles()?.forEach { file ->
            val match = Regex("content_(\\d+)\\.pdf").find(file.name)
            val id = match?.groupValues?.get(1)?.toIntOrNull()
            if (id != null && id !in activeContentIds) {
                file.delete()
            }
        }
    }

    fun clearAll() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }
}
