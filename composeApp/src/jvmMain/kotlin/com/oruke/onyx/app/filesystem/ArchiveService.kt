package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileCapability
import com.oruke.onyx.core.model.VFileKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.sf.sevenzipjbinding.ExtractAskMode
import net.sf.sevenzipjbinding.ExtractOperationResult
import net.sf.sevenzipjbinding.IArchiveExtractCallback
import net.sf.sevenzipjbinding.IInArchive
import net.sf.sevenzipjbinding.ISequentialOutStream
import net.sf.sevenzipjbinding.PropID
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.Date

/**
 * 压缩文件服务 — 基于 7-Zip-JBinding 实现。
 *
 * 职责：
 * 1. 列出压缩包内条目（作为 VFile 列表）
 * 2. 解压到目标目录
 *
 * 条目路径格式：`archive://<archivePath>!/<entryPath>`
 */
class ArchiveService {

    companion object {
        private const val ARCHIVE_SCHEME = "archive://"
        private val ARCHIVE_EXTENSIONS = setOf(
            "zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz",
            "lzma", "cab", "iso", "arj", "lzh", "z", "cpio",
        )

        fun isArchive(fileName: String): Boolean {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            return ext in ARCHIVE_EXTENSIONS
        }

        fun archiveLocation(archivePath: String, entryPath: String = ""): String {
            return "$ARCHIVE_SCHEME$archivePath!/$entryPath"
        }

        fun parseArchiveLocation(location: String): Pair<String, String>? {
            if (!location.startsWith(ARCHIVE_SCHEME)) return null
            val rest = location.removePrefix(ARCHIVE_SCHEME)
            val bangSlash = rest.indexOf("!/")
            if (bangSlash < 0) return null
            return rest.substring(0, bangSlash) to rest.substring(bangSlash + 2)
        }
    }

    /**
     * 列出压缩包内指定路径下的直接子条目。
     */
    suspend fun list(archivePath: String, innerPath: String = ""): Result<List<VFile>> =
        withContext(Dispatchers.IO) {
            runCatching {
                openArchive(archivePath).use { archive ->
                    val numItems = archive.numberOfItems
                    val prefix = if (innerPath.isBlank()) "" else innerPath.trimEnd('/') + "/"
                    val parentLoc = archiveLocation(archivePath, innerPath)
                    val directChildren = mutableMapOf<String, VFile>()

                    for (i in 0 until numItems) {
                        val itemPath = archive.getProperty(i, PropID.PATH) as? String ?: continue
                        val isDir = archive.getProperty(i, PropID.IS_FOLDER) as? Boolean ?: false

                        if (!itemPath.startsWith(prefix)) continue
                        val relativePath = itemPath.removePrefix(prefix)
                        if (relativePath.isEmpty()) continue

                        val segments = relativePath.split("/").filter { it.isNotEmpty() }
                        if (segments.isEmpty()) continue

                        val directChildName = segments.first()
                        if (directChildName in directChildren) continue

                        val isChildDir = segments.size > 1 ||
                            (segments.size == 1 && relativePath.endsWith("/")) ||
                            isDir

                        val childEntryPath = prefix + directChildName
                        val sizeBytes = if (!isChildDir) {
                            (archive.getProperty(i, PropID.SIZE) as? Long) ?: 0L
                        } else null
                        val modified = (archive.getProperty(i, PropID.LAST_MODIFICATION_TIME) as? Date)
                            ?.time

                        directChildren[directChildName] = VFile(
                            id = archiveLocation(archivePath, childEntryPath),
                            name = directChildName,
                            location = archiveLocation(archivePath, childEntryPath),
                            parentLocation = parentLoc,
                            kind = if (isChildDir) VFileKind.DIRECTORY else VFileKind.FILE,
                            sizeBytes = sizeBytes,
                            modifiedAtEpochMillis = modified,
                            hidden = false,
                            capabilities = setOf(VFileCapability.READ_CONTENT, VFileCapability.READ_METADATA),
                        )
                    }

                    directChildren.values.toList()
                }
            }
        }

    /**
     * 解压全部或指定内部路径到目标目录。
     */
    suspend fun extract(
        archivePath: String,
        targetDirectory: String,
        innerPath: String = "",
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            openArchive(archivePath).use { archive ->
                val numItems = archive.numberOfItems
                val prefix = if (innerPath.isBlank()) "" else innerPath.trimEnd('/') + "/"
                val targetDir = File(targetDirectory)
                targetDir.mkdirs()

                val indicesToExtract = mutableListOf<Int>()
                for (i in 0 until numItems) {
                    val itemPath = archive.getProperty(i, PropID.PATH) as? String ?: continue
                    if (prefix.isEmpty() || itemPath.startsWith(prefix)) {
                        indicesToExtract.add(i)
                    }
                }

                archive.extract(
                    indicesToExtract.toIntArray(),
                    false,
                    ArchiveExtractCallback(archive, targetDir, prefix),
                )
            }
        }
    }

    private fun openArchive(path: String): IInArchive {
        val raf = RandomAccessFile(path, "r")
        val inStream = RandomAccessFileInStream(raf)
        return SevenZip.openInArchive(null, inStream)
    }
}

/**
 * 7-Zip 解压回调。
 */
private class ArchiveExtractCallback(
    private val archive: IInArchive,
    private val targetDir: File,
    private val prefix: String,
) : IArchiveExtractCallback {

    private var currentOutputStream: FileOutputStream? = null

    override fun getStream(
        index: Int,
        extractAskMode: ExtractAskMode,
    ): ISequentialOutStream? {
        if (extractAskMode != ExtractAskMode.EXTRACT) return null

        val itemPath = archive.getProperty(index, PropID.PATH) as? String ?: return null
        val isDir = archive.getProperty(index, PropID.IS_FOLDER) as? Boolean ?: false
        val relativePath = if (prefix.isNotEmpty()) itemPath.removePrefix(prefix) else itemPath

        val outFile = File(targetDir, relativePath)
        if (isDir) {
            outFile.mkdirs()
            return null
        }
        outFile.parentFile?.mkdirs()
        val fos = FileOutputStream(outFile)
        currentOutputStream = fos
        return ISequentialOutStream { data ->
            fos.write(data)
            data.size
        }
    }

    override fun prepareOperation(extractAskMode: ExtractAskMode) {}

    override fun setOperationResult(
        extractOperationResult: ExtractOperationResult,
    ) {
        currentOutputStream?.close()
        currentOutputStream = null
    }

    override fun setTotal(total: Long) {}

    override fun setCompleted(complete: Long) {}
}
