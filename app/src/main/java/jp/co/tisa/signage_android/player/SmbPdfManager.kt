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
import java.time.LocalDate
import java.time.format.DateTimeFormatter
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
        val pageCount: Int,
        val isPortrait: Boolean = false  // 縦長PDF判定
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

                    val pdfInfo = analyzePdf(localFile)

                    newEntries.add(
                        SmbCacheEntry(
                            filename = rf.filename,
                            lastModified = rf.lastModified,
                            fileSize = rf.fileSize,
                            pageCount = pdfInfo.pageCount,
                            isPortrait = pdfInfo.isPortrait
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

    /**
     * SMB接続せずにローカルキャッシュからサブプレイリストを構築する。
     * 戻る操作などで「更新中」画面を出さずに即再生するために使用。
     * キャッシュが存在しない場合は空リストを返す。
     */
    fun buildPlaylistFromCache(folderItem: PlaylistItem): List<PlaylistItem> {
        val smbPath = folderItem.smbPath ?: return emptyList()
        val parsed = parseUncPath(smbPath) ?: return emptyList()
        val isFirstPageOnly = folderItem.firstPageOnly == true
        val durationSeconds = folderItem.durationSeconds
        val pdfPageDuration = folderItem.pdfPageDuration

        val shareHash = "${parsed.host}_${parsed.shareName}_${parsed.path}".hashCode()
            .let { if (it < 0) "n${-it}" else "$it" }
        val cacheDir = File(baseCacheDir, "share_$shareHash")
        val metadataFile = File(cacheDir, "_metadata.json")

        val cachedEntries = loadMetadata(metadataFile)
        if (cachedEntries.isEmpty()) return emptyList()

        return buildPlaylist(cachedEntries, cacheDir, folderItem, parsed, isFirstPageOnly, durationSeconds, pdfPageDuration)
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

    data class PdfInfo(val pageCount: Int, val isPortrait: Boolean)

    private fun analyzePdf(file: File): PdfInfo {
        return try {
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(fd)
            val count = renderer.pageCount
            val isPortrait = if (count > 0) {
                val page = renderer.openPage(0)
                val portrait = page.height > page.width
                page.close()
                portrait
            } else false
            renderer.close()
            fd.close()
            PdfInfo(count, isPortrait)
        } catch (e: Exception) {
            e.printStackTrace()
            PdfInfo(1, false)
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
    // Filename-based display control
    // =====================================================================

    /**
     * ファイル名規約からパースした表示制御設定。
     * 形式: {並び順}_{ページ制御}_{表示開始日}_{表示終了日}_{表示秒数}_{説明}.pdf
     * 例: 001_0_20260501_20260531_30_書簡.pdf
     */
    private data class FileNameConfig(
        val sortOrder: Int,          // 並び順（小さい順に表示）
        val firstPageOnly: Boolean,  // true=先頭ページのみ(0), false=全ページ(1)
        val startDate: LocalDate,    // 表示開始日
        val endDate: LocalDate,      // 表示終了日
        val durationSeconds: Int,    // 表示秒数（全ページ時は1ページあたり秒数）
        val description: String      // 説明テキスト
    )

    private val fileNamePattern = Regex(
        """^(\d+)_([01])_(\d{8})_(\d{8})_(\d+)_(.+)\.[pP][dD][fF]$"""
    )
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

    /**
     * ファイル名から表示制御設定をパースする。
     * 規約に合わないファイル名の場合は null を返す。
     */
    private fun parseFileNameConfig(filename: String): FileNameConfig? {
        val match = fileNamePattern.matchEntire(filename) ?: return null
        return try {
            FileNameConfig(
                sortOrder = match.groupValues[1].toInt(),
                firstPageOnly = match.groupValues[2] == "0",
                startDate = LocalDate.parse(match.groupValues[3], dateFormatter),
                endDate = LocalDate.parse(match.groupValues[4], dateFormatter),
                durationSeconds = match.groupValues[5].toInt(),
                description = match.groupValues[6]
            )
        } catch (e: Exception) {
            null  // 日付パース失敗等は規約外として扱う
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
        val today = LocalDate.now()

        // エントリをファイル名規約パース成功/失敗に分離
        data class ParsedEntry(val entry: SmbCacheEntry, val config: FileNameConfig)

        val configuredEntries = mutableListOf<ParsedEntry>()
        val defaultEntries = mutableListOf<SmbCacheEntry>()

        for (entry in entries) {
            val config = parseFileNameConfig(entry.filename)
            if (config != null) {
                // 日付範囲外のファイルはスキップ
                if (today < config.startDate || today > config.endDate) continue
                configuredEntries.add(ParsedEntry(entry, config))
            } else {
                defaultEntries.add(entry)
            }
        }

        // ソート: 規約ファイルはsortOrder順、規約外はファイル名アルファベット順
        val sortedConfigured = configuredEntries.sortedBy { it.config.sortOrder }
        val sortedDefault = defaultEntries.sortedBy { it.filename.lowercase() }

        var displayIndex = 0

        // 規約ファイル: 個別の表示制御設定を適用
        for (parsed_entry in sortedConfigured) {
            val entry = parsed_entry.entry
            val config = parsed_entry.config
            val contentId = generateContentId(parsed, entry.filename)
            val localFile = File(cacheDir, entry.filename)
            if (!localFile.exists()) continue

            localFileMap[contentId] = localFile

            // ページ制御=1(全ページ)の場合: durationSecondsをpdfPageDurationとして扱う
            val itemFirstPageOnly = config.firstPageOnly
            val itemPdfPageDuration = if (!itemFirstPageOnly) config.durationSeconds else null
            val itemDuration = if (!itemFirstPageOnly) {
                // allPagesモード: 安全弁用に大きめ設定
                (entry.pageCount + 1) * config.durationSeconds
            } else {
                config.durationSeconds
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
                    pdfPageDuration = itemPdfPageDuration,
                    durationSeconds = itemDuration,
                    displayOrder = displayIndex++,
                    useProxy = false,
                    proxyUrl = null,
                    smbPath = null,
                    smbUsername = null,
                    smbPassword = null,
                    firstPageOnly = itemFirstPageOnly,
                    isPortrait = entry.isPortrait
                )
            )
        }

        // 規約外ファイル: 親アイテムのデフォルト設定を使用（従来動作）
        for (entry in sortedDefault) {
            val contentId = generateContentId(parsed, entry.filename)
            val localFile = File(cacheDir, entry.filename)
            if (!localFile.exists()) continue

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
                    displayOrder = displayIndex++,
                    useProxy = false,
                    proxyUrl = null,
                    smbPath = null,
                    smbUsername = null,
                    smbPassword = null,
                    firstPageOnly = isFirstPageOnly,
                    isPortrait = entry.isPortrait
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
