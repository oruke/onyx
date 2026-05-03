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
            "cbz", "cbr", "epub",
        )
        // tar.xx 双扩展名
        private val ARCHIVE_COMPOUND_EXTENSIONS = setOf(
            "tar.gz", "tar.bz2", "tar.xz", "tar.lzma", "tar.zst",
        )

        fun isArchive(fileName: String): Boolean {
            val lower = fileName.lowercase()
            if (ARCHIVE_COMPOUND_EXTENSIONS.any { lower.endsWith(".$it") }) return true
            val ext = lower.substringAfterLast('.', "")
            return ext in ARCHIVE_EXTENSIONS
        }

        /** 判断 location 是否为 archive:// 协议 */
        fun isArchiveLocation(location: String): Boolean {
            return location.startsWith(ARCHIVE_SCHEME)
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

        /**
         * 计算 archive:// location 的上级路径。
         * - 压缩包内部子目录 → 上级内部路径
         * - 压缩包根 → 压缩包所在的物理目录
         */
        fun archiveParentLocation(location: String): String? {
            val (archivePath, innerPath) = parseArchiveLocation(location) ?: return null
            if (innerPath.isBlank()) {
                // 已在压缩包根 → 返回压缩包所在的物理目录
                return java.nio.file.Path.of(archivePath).parent?.toString()
            }
            val trimmed = innerPath.trimEnd('/')
            val lastSlash = trimmed.lastIndexOf('/')
            val parentInner = if (lastSlash > 0) trimmed.substring(0, lastSlash) else ""
            return archiveLocation(archivePath, parentInner)
        }

        /** 从 archive location 提取显示标题 */
        fun archiveLocationTitle(location: String): String {
            val (archivePath, innerPath) = parseArchiveLocation(location) ?: return location
            if (innerPath.isBlank()) {
                return java.nio.file.Path.of(archivePath).fileName?.toString() ?: archivePath
            }
            val trimmed = innerPath.trimEnd('/')
            return trimmed.substringAfterLast('/')
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

    /**
     * 从压缩包中提取单个文件到内存字节数组。
     *
     * @param archivePath 压缩包物理路径
     * @param innerPath   压缩包内条目路径
     * @return 文件字节数组，如果未找到则返回 null
     */
    suspend fun extractToBytes(
        archivePath: String,
        innerPath: String,
    ): Result<ByteArray?> = withContext(Dispatchers.IO) {
        runCatching {
            openArchive(archivePath).use { archive ->
                val numItems = archive.numberOfItems
                var targetIndex = -1
                for (i in 0 until numItems) {
                    val itemPath = archive.getProperty(i, PropID.PATH) as? String ?: continue
                    if (itemPath == innerPath) {
                        targetIndex = i
                        break
                    }
                }
                if (targetIndex < 0) return@runCatching null

                val size = (archive.getProperty(targetIndex, PropID.SIZE) as? Long) ?: 0L
                val buffer = java.io.ByteArrayOutputStream(size.toInt().coerceAtLeast(1024))
                val idx = targetIndex
                archive.extract(
                    intArrayOf(idx),
                    false,
                    object : IArchiveExtractCallback {
                        override fun getStream(
                            index: Int,
                            extractAskMode: ExtractAskMode,
                        ): ISequentialOutStream? {
                            if (extractAskMode != ExtractAskMode.EXTRACT) return null
                            if (index != idx) return null
                            return ISequentialOutStream { data ->
                                buffer.write(data)
                                data.size
                            }
                        }
                        override fun prepareOperation(extractAskMode: ExtractAskMode) {}
                        override fun setOperationResult(result: ExtractOperationResult) {}
                        override fun setTotal(total: Long) {}
                        override fun setCompleted(complete: Long) {}
                    },
                )
                buffer.toByteArray()
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
