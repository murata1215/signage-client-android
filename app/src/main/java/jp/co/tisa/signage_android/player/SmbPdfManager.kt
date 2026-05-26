package jp.co.tisa.signage_android.player

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import jp.co.tisa.signage_android.data.PlaylistItem
import java.io.File
import java.io.FileOutputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * SMB共有フォルダからPDFを取得・キャッシュ・プレイリスト生成するマネージャ。
 *
 * 各Shareの表示順番が来るたびに syncShare() を呼び出し、
 * 差分ダウンロード（変更検出: lastModified + fileSize）を行う。
 */
class SmbPdfManager(private val context: Context) {

    // =====================================================================
    // Data classes
    // =====================================================================

    data class SmbShareConfig(
        val host: String,
        val shareName: String,
        val path: String,
        val username: String,
        val password: String,
        val domain: String = "",
        val displayMode: String,   // "firstPageOnly" or "allPages"
        val durationSeconds: Int,
    )

    data class SmbCacheEntry(
        val filename: String,
        val lastModified: Long,
        val fileSize: Long,
        val pageCount: Int
    )

    // =====================================================================
    // Test configurations (hardcoded)
    // =====================================================================

    companion object {
        val TEST_CONFIGS = listOf(
            SmbShareConfig(
                host = "10.20.171.21",
                shareName = "05lf",
                path = "workspace/signage/030",
                username = "05_TAKATSUJI",
                password = "Lf6411",
                domain = "",
                displayMode = "firstPageOnly",
                durationSeconds = 10
            ),
            SmbShareConfig(
                host = "10.20.171.21",
                shareName = "05lf",
                path = "workspace/signage/031",
                username = "05_MEIKO",
                password = "Lf6411",
                domain = "",
                displayMode = "allPages",
                durationSeconds = 10
            ),
        )
    }

    // =====================================================================
    // State
    // =====================================================================

    private val gson = Gson()
    private val baseCacheDir = File(context.filesDir, "smb_pdf_cache").apply {
        if (!exists()) mkdirs()
    }

    // contentId → display mode mapping (populated by syncShare)
    private val displayModeMap = mutableMapOf<Int, String>()
    // contentId → local file mapping (populated by syncShare)
    private val localFileMap = mutableMapOf<Int, File>()

    fun getShareConfigs(): List<SmbShareConfig> = TEST_CONFIGS

    fun getDisplayMode(contentId: Int): String = displayModeMap[contentId] ?: "allPages"

    fun getLocalPdfFile(contentId: Int): File? = localFileMap[contentId]

    // =====================================================================
    // Sync logic
    // =====================================================================

    /**
     * 指定したShareを同期し、PlaylistItemリストとダウンロード件数のPairを返す。
     * @param config Share設定
     * @param onProgress 進捗メッセージのコールバック（UIスレッドで呼ばれる想定）
     * @return Pair(プレイリスト, ダウンロード件数)
     */
    suspend fun syncShare(
        config: SmbShareConfig,
        onProgress: suspend (String) -> Unit
    ): Pair<List<PlaylistItem>, Int> {
        val shareHash = "${config.host}_${config.shareName}_${config.path}".hashCode()
            .let { if (it < 0) "n${-it}" else "$it" }
        val cacheDir = File(baseCacheDir, "share_$shareHash").apply {
            if (!exists()) mkdirs()
        }

        // Load existing metadata
        val metadataFile = File(cacheDir, "_metadata.json")
        val existingEntries = loadMetadata(metadataFile)
        val existingMap = existingEntries.associateBy { it.filename }.toMutableMap()

        // Connect to SMB and list files
        val remoteFiles: List<SmbRemoteFile>
        try {
            onProgress("${config.path} に接続中...")

            val smbConfig = SmbConfig.builder()
                .withTimeout(15, TimeUnit.SECONDS)
                .withSoTimeout(15, TimeUnit.SECONDS)
                .build()
            val client = SMBClient(smbConfig)
            val connection = client.connect(config.host)
            val authContext = AuthenticationContext(
                config.username, config.password.toCharArray(), config.domain
            )
            val session = connection.authenticate(authContext)
            val share = session.connectShare(config.shareName) as DiskShare

            onProgress("${config.path} ファイル一覧取得中...")

            remoteFiles = listPdfFiles(share, config.path)

            // Determine which files need downloading
            val remoteFilenames = remoteFiles.map { it.filename }.toSet()
            val newEntries = mutableListOf<SmbCacheEntry>()
            var downloadCount = 0

            for (rf in remoteFiles) {
                val existing = existingMap[rf.filename]
                val localFile = File(cacheDir, rf.filename)

                val needsDownload = existing == null
                        || existing.lastModified != rf.lastModified
                        || existing.fileSize != rf.fileSize
                        || !localFile.exists()

                if (needsDownload) {
                    downloadCount++
                    onProgress("${rf.filename} 取得中... ($downloadCount/${remoteFiles.size})")

                    // Download the file
                    downloadFile(share, config.path, rf.filename, localFile)

                    // Count pages
                    val pageCount = countPdfPages(localFile)

                    newEntries.add(
                        SmbCacheEntry(
                            filename = rf.filename,
                            lastModified = rf.lastModified,
                            fileSize = rf.fileSize,
                            pageCount = pageCount
                        )
                    )
                } else {
                    // Use cached entry
                    newEntries.add(existing!!)
                }
            }

            // Delete local files no longer on the share
            cacheDir.listFiles()?.forEach { file ->
                if (file.name != "_metadata.json" && file.name !in remoteFilenames) {
                    file.delete()
                }
            }

            // Save updated metadata
            saveMetadata(metadataFile, newEntries)

            // Close SMB resources
            try { share.close() } catch (_: Exception) {}
            try { session.close() } catch (_: Exception) {}
            try { connection.close() } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}

            if (downloadCount > 0) {
                onProgress("同期完了: ${downloadCount}件ダウンロード (全${remoteFiles.size}件)")
            } else {
                onProgress("同期完了: ${remoteFiles.size}件 (更新なし)")
            }

            // Build playlist
            return Pair(buildPlaylist(newEntries, cacheDir, config), downloadCount)

        } catch (e: Exception) {
            e.printStackTrace()
            onProgress("接続エラー: ${e.message}")

            // Fallback: use cached files if available
            val cachedEntries = loadMetadata(metadataFile)
            if (cachedEntries.isNotEmpty()) {
                onProgress("キャッシュを使用: ${cachedEntries.size}件")
                return Pair(buildPlaylist(cachedEntries, cacheDir, config), 0)
            }
            return Pair(emptyList(), 0)
        }
    }

    // =====================================================================
    // SMB operations
    // =====================================================================

    private data class SmbRemoteFile(
        val filename: String,
        val lastModified: Long,
        val fileSize: Long
    )

    private fun listPdfFiles(share: DiskShare, path: String): List<SmbRemoteFile> {
        val result = mutableListOf<SmbRemoteFile>()
        val dirEntries: List<FileIdBothDirectoryInformation> = share.list(path)

        for (entry in dirEntries) {
            val name = entry.fileName
            if (name == "." || name == "..") continue

            // Check if it's a directory
            val attrs = entry.fileAttributes
            if (attrs != 0L && (attrs and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L) continue

            // Only PDF files (case-insensitive)
            if (!name.lowercase().endsWith(".pdf")) continue

            result.add(
                SmbRemoteFile(
                    filename = name,
                    lastModified = entry.lastWriteTime.toEpochMillis(),
                    fileSize = entry.endOfFile
                )
            )
        }

        return result.sortedBy { it.filename.lowercase() }
    }

    private fun downloadFile(share: DiskShare, dirPath: String, filename: String, destFile: File) {
        val remotePath = "$dirPath/$filename"
        val remoteFile = share.openFile(
            remotePath,
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
            SMB2CreateDisposition.FILE_OPEN,
            null
        )

        remoteFile.use { rf ->
            val inputStream = rf.inputStream
            FileOutputStream(destFile).use { output ->
                inputStream.copyTo(output, bufferSize = 8192)
            }
        }
    }

    // =====================================================================
    // PDF page counting
    // =====================================================================

    private fun countPdfPages(file: File): Int {
        return try {
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(fd)
            val count = renderer.pageCount
            renderer.close()
            fd.close()
            count
        } catch (e: Exception) {
            e.printStackTrace()
            1 // Default to 1 page if can't read
        }
    }

    // =====================================================================
    // Metadata persistence
    // =====================================================================

    private fun loadMetadata(file: File): List<SmbCacheEntry> {
        if (!file.exists()) return emptyList()
        return try {
            val json = file.readText()
            val type = object : TypeToken<List<SmbCacheEntry>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveMetadata(file: File, entries: List<SmbCacheEntry>) {
        try {
            file.writeText(gson.toJson(entries))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // =====================================================================
    // Playlist building
    // =====================================================================

    private fun buildPlaylist(
        entries: List<SmbCacheEntry>,
        cacheDir: File,
        config: SmbShareConfig
    ): List<PlaylistItem> {
        val items = mutableListOf<PlaylistItem>()

        for ((index, entry) in entries.sortedBy { it.filename.lowercase() }.withIndex()) {
            val contentId = generateContentId(config, entry.filename)
            val localFile = File(cacheDir, entry.filename)
            if (!localFile.exists()) continue

            // Store mappings
            displayModeMap[contentId] = config.displayMode
            localFileMap[contentId] = localFile

            val durationSeconds = if (config.displayMode == "allPages") {
                entry.pageCount * config.durationSeconds
            } else {
                config.durationSeconds
            }

            val pdfPageDuration = if (config.displayMode == "allPages") {
                config.durationSeconds
            } else {
                null
            }

            items.add(
                PlaylistItem(
                    id = contentId,
                    scope = "smb_${config.path}",
                    contentId = contentId,
                    name = entry.filename,
                    type = "pdf",
                    url = null,
                    fileUrl = null,
                    pdfPageDuration = pdfPageDuration,
                    durationSeconds = durationSeconds,
                    displayOrder = index,
                    useProxy = false,
                    proxyUrl = null
                )
            )
        }

        return items
    }

    /**
     * Generate a stable negative contentId from share config + filename.
     * Negative to avoid collision with server-assigned positive IDs.
     */
    private fun generateContentId(config: SmbShareConfig, filename: String): Int {
        val key = "${config.host}/${config.shareName}/${config.path}/$filename"
        val hash = key.hashCode()
        return if (hash > 0) -hash else if (hash == 0) -1 else hash
    }
}
