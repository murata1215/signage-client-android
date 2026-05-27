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
import jp.co.tisa.signage_android.data.CryptoUtils
import jp.co.tisa.signage_android.data.PlaylistItem
import java.io.File
import java.io.FileOutputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * SMB共有フォルダからPDFを取得・キャッシュ・プレイリスト生成するマネージャ。
 *
 * サーバーAPIから受信した type="pdf_folder" のPlaylistItemを受け取り、
 * SMB共有フォルダを同期して子PDFのPlaylistItemリストを返す。
 */
class SmbPdfManager(private val context: Context) {

    // =====================================================================
    // Data classes
    // =====================================================================

    private data class SmbCacheEntry(
        val filename: String,
        val lastModified: Long,
        val fileSize: Long,
        val pageCount: Int
    )

    private data class ParsedUncPath(
        val host: String,
        val shareName: String,
        val path: String
    )

    // =====================================================================
    // State
    // =====================================================================

    private val gson = Gson()
    private val baseCacheDir = File(context.filesDir, "smb_pdf_cache").apply {
        if (!exists()) mkdirs()
    }

    // contentId → local file mapping (populated by syncFolder)
    private val localFileMap = mutableMapOf<Int, File>()

    fun getLocalPdfFile(contentId: Int): File? = localFileMap[contentId]

    // =====================================================================
    // UNC Path parsing
    // =====================================================================

    /**
     * UNCパスを host / shareName / path に分解する。
     * 例: "\\\\10.20.171.21\\05lf\\workspace\\signage\\030"
     *   → host="10.20.171.21", shareName="05lf", path="workspace/signage/030"
     */
    private fun parseUncPath(uncPath: String): ParsedUncPath? {
        // Normalize: remove leading \\ and split by \ or /
        val cleaned = uncPath.trimStart('\\').trimStart('/')
        val segments = cleaned.split('\\', '/').filter { it.isNotEmpty() }
        if (segments.size < 2) return null
        return ParsedUncPath(
            host = segments[0],
            shareName = segments[1],
            path = segments.drop(2).joinToString("/")
        )
    }

    // =====================================================================
    // Sync logic
    // =====================================================================

    /**
     * サーバーから受信した pdf_folder PlaylistItem を元にSMBフォルダを同期し、
     * 子PDFのPlaylistItemリストとダウンロード件数を返す。
     *
     * @param folderItem type="pdf_folder" のPlaylistItem
     * @param onProgress 進捗メッセージのコールバック
     * @return Pair(子PDFプレイリスト, ダウンロード件数)
     */
    suspend fun syncFolder(
        folderItem: PlaylistItem,
        onProgress: suspend (String) -> Unit
    ): Pair<List<PlaylistItem>, Int> {
        val smbPath = folderItem.smbPath ?: run {
            onProgress("エラー: SMBパスが未設定")
            return Pair(emptyList(), 0)
        }
        val parsed = parseUncPath(smbPath) ?: run {
            onProgress("エラー: SMBパスの形式が不正: $smbPath")
            return Pair(emptyList(), 0)
        }
        val username = folderItem.smbUsername ?: ""
        val password = try {
            CryptoUtils.decryptAes256Cbc(folderItem.smbPassword ?: "")
        } catch (e: Exception) {
            folderItem.smbPassword ?: ""
        }
        val isFirstPageOnly = folderItem.firstPageOnly == true
        val durationSeconds = folderItem.durationSeconds
        val pdfPageDuration = folderItem.pdfPageDuration

        val shareHash = "${parsed.host}_${parsed.shareName}_${parsed.path}".hashCode()
            .let { if (it < 0) "n${-it}" else "$it" }
        val cacheDir = File(baseCacheDir, "share_$shareHash").apply {
            if (!exists()) mkdirs()
        }

        // Load existing metadata
        val metadataFile = File(cacheDir, "_metadata.json")
        val existingEntries = loadMetadata(metadataFile)
        val existingMap = existingEntries.associateBy { it.filename }.toMutableMap()

        // Connect to SMB and list files
        try {
            onProgress("${parsed.path} に接続中...")

            val smbConfig = SmbConfig.builder()
                .withTimeout(15, TimeUnit.SECONDS)
                .withSoTimeout(15, TimeUnit.SECONDS)
                .build()
            val client = SMBClient(smbConfig)
            val connection = client.connect(parsed.host)
            val authContext = AuthenticationContext(
                username, password.toCharArray(), ""
            )
            val session = connection.authenticate(authContext)
            val share = session.connectShare(parsed.shareName) as DiskShare

            onProgress("${parsed.path} ファイル一覧取得中...")

            val remoteFiles = listPdfFiles(share, parsed.path)

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

                    downloadFile(share, parsed.path, rf.filename, localFile)

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

            return Pair(
                buildPlaylist(newEntries, cacheDir, folderItem, parsed, isFirstPageOnly, durationSeconds, pdfPageDuration),
                downloadCount
            )

        } catch (e: Exception) {
            e.printStackTrace()
            onProgress("接続エラー: ${e.message}")

            // Fallback: use cached files if available
            val cachedEntries = loadMetadata(metadataFile)
            if (cachedEntries.isNotEmpty()) {
                onProgress("キャッシュを使用: ${cachedEntries.size}件")
                return Pair(
                    buildPlaylist(cachedEntries, cacheDir, folderItem, parsed, isFirstPageOnly, durationSeconds, pdfPageDuration),
                    0
                )
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

            val attrs = entry.fileAttributes
            if (attrs != 0L && (attrs and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L) continue

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
            1
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
        parentItem: PlaylistItem,
        parsed: ParsedUncPath,
        isFirstPageOnly: Boolean,
        durationSeconds: Int,
        pdfPageDuration: Int?
    ): List<PlaylistItem> {
        val items = mutableListOf<PlaylistItem>()

        for ((index, entry) in entries.sortedBy { it.filename.lowercase() }.withIndex()) {
            val contentId = generateContentId(parsed, entry.filename)
            val localFile = File(cacheDir, entry.filename)
            if (!localFile.exists()) continue

            // Store mapping
            localFileMap[contentId] = localFile

            // allPagesモードでは安全弁用にdurationSecondsを大きめに設定
            val itemDuration = if (!isFirstPageOnly && pdfPageDuration != null) {
                (entry.pageCount + 1) * pdfPageDuration
            } else {
                durationSeconds
            }

            items.add(
                PlaylistItem(
                    id = contentId,
                    scope = "smb_${parentItem.id}",
                    contentId = contentId,
                    name = entry.filename,
                    type = "pdf",
                    url = null,
                    fileUrl = null,
                    pdfPageDuration = pdfPageDuration,
                    durationSeconds = itemDuration,
                    displayOrder = index,
                    useProxy = false,
                    proxyUrl = null,
                    smbPath = null,
                    smbUsername = null,
                    smbPassword = null,
                    firstPageOnly = isFirstPageOnly
                )
            )
        }

        return items
    }

    private fun generateContentId(parsed: ParsedUncPath, filename: String): Int {
        val key = "${parsed.host}/${parsed.shareName}/${parsed.path}/$filename"
        val hash = key.hashCode()
        return if (hash > 0) -hash else if (hash == 0) -1 else hash
    }
}
