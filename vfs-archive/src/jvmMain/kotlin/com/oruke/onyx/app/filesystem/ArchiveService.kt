package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileCapability
import com.oruke.onyx.core.model.VFileKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.sf.sevenzipjbinding.ExtractAskMode
import net.sf.sevenzipjbinding.ExtractOperationResult
import net.sf.sevenzipjbinding.IArchiveExtractCallback
import net.sf.sevenzipjbinding.ICryptoGetTextPassword
import net.sf.sevenzipjbinding.IInArchive
import net.sf.sevenzipjbinding.ISequentialOutStream
import net.sf.sevenzipjbinding.PropID
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream
import java.io.Closeable
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
class ArchiveService(
    private val logger: ArchiveServiceLogger = ArchiveServiceLogger.NoOp,
) {

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
                openArchive(archivePath).use { handle ->
                    val archive = handle.archive
                    val numItems = archive.numberOfItems
                    val prefix = if (innerPath.isBlank()) "" else innerPath.trimEnd('/') + "/"
                    val parentLoc = archiveLocation(archivePath, innerPath)
                    val directChildren = mutableMapOf<String, VFile>()

                    for (i in 0 until numItems) {
                        val itemPath = archive.itemPath(i) ?: continue
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
        password: String? = null,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            openArchive(archivePath, password).use { handle ->
                val archive = handle.archive
                val numItems = archive.numberOfItems
                val prefix = if (innerPath.isBlank()) "" else innerPath.trimEnd('/') + "/"
                val targetDir = File(targetDirectory)
                targetDir.mkdirs()

                val indicesToExtract = mutableListOf<Int>()
                for (i in 0 until numItems) {
                    val itemPath = archive.itemPath(i) ?: continue
                    if (prefix.isEmpty() || itemPath.startsWith(prefix)) {
                        indicesToExtract.add(i)
                    }
                }

                val callback = FileExtractCallback(archive, targetDir, prefix, password)
                archive.extract(
                    indicesToExtract.toIntArray(),
                    false,
                    callback,
                )
                if (callback.errors.isNotEmpty()) {
                    error("解压失败: ${callback.errors.joinToString(", ")}")
                }
            }
        }
    }

    /**
     * 解压到独立目录：在 targetDirectory 下创建与压缩包同名（去扩展名）的子目录，解压到其中。
     */
    suspend fun extractToDirectory(
        archivePath: String,
        targetDirectory: String,
        password: String? = null,
    ): Result<Unit> {
        val archiveName = File(archivePath).nameWithoutExtension
        val subDir = File(targetDirectory, archiveName).absolutePath
        return extract(archivePath, subDir, password = password)
    }

    /**
     * 智能解压到独立目录：
     * - 如果压缩包根目录恰好只有一个子目录，则直接解压到 targetDirectory（避免多余嵌套）
     * - 否则，行为等同于 extractToDirectory（在 targetDirectory 下创建同名子目录）
     */
    suspend fun extractSmart(
        archivePath: String,
        targetDirectory: String,
        password: String? = null,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // 先检查根目录结构
            val rootEntries = list(archivePath).getOrThrow()
            val hasSingleRootDir = rootEntries.size == 1 && rootEntries[0].kind == VFileKind.DIRECTORY
            if (hasSingleRootDir) {
                // 单一根目录 → 直接解压到 targetDirectory，保持原有目录名
                extract(archivePath, targetDirectory, password = password).getOrThrow()
            } else {
                // 多个根条目 → 创建同名子目录
                extractToDirectory(archivePath, targetDirectory, password = password).getOrThrow()
            }
        }
    }

    /**
     * 检查压缩包是否需要密码（是否加密）。
     */
    suspend fun isEncrypted(archivePath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            openArchive(archivePath).use { handle ->
                val archive = handle.archive
                val numItems = archive.numberOfItems
                (0 until numItems).any { i ->
                    archive.getProperty(i, PropID.ENCRYPTED) as? Boolean ?: false
                }
            }
        } catch (e: Exception) {
            logger.warn("ArchiveService", "检测压缩包加密状态异常: $archivePath", e)
            false
        }
    }

    /**
     * 验证密码是否正确。
     *
     * 策略：
     * 1. 先用 test 模式解压（不写数据），检测 ExtractOperationResult
     * 2. 如果 test 模式返回 OK，再解压到内存并比对 CRC32
     * 3. 对 ZipCrypto 等不返回 WRONG_PASSWORD 的格式，CRC 比对是唯一可靠手段
     *
     * @return true = 密码正确，false = 密码错误或验证失败
     */
    suspend fun verifyPassword(archivePath: String, password: String): Boolean = withContext(Dispatchers.IO) {
        try {
            openArchive(archivePath, password).use { handle ->
                val archive = handle.archive
                val numItems = archive.numberOfItems
                // 找到第一个加密的非空文件条目
                val testIndex = (0 until numItems).firstOrNull { i ->
                    val encrypted = archive.getProperty(i, PropID.ENCRYPTED) as? Boolean ?: false
                    val isDir = archive.getProperty(i, PropID.IS_FOLDER) as? Boolean ?: false
                    val size = archive.getProperty(i, PropID.SIZE) as? Long ?: 0L
                    encrypted && !isDir && size > 0
                } ?: return@withContext true // 没有加密文件条目 → 密码正确

                // 获取压缩包存储的 CRC（可能为 null）
                val storedCrc = archive.getProperty(testIndex, PropID.CRC) as? Int

                // ── 步骤 1：test 模式解压，检测 ExtractOperationResult ──
                val testCallback = TestExtractCallback(password)
                archive.extract(intArrayOf(testIndex), true, testCallback)
                if (testCallback.errors.isNotEmpty()) return@withContext false

                // ── 步骤 2：解压到内存，CRC32 比对 ──
                if (storedCrc != null) {
                    val buffer = java.io.ByteArrayOutputStream()
                    val memCallback = MemoryExtractCallback(buffer, password)
                    archive.extract(intArrayOf(testIndex), false, memCallback)
                    if (memCallback.errors.isNotEmpty()) return@withContext false

                    val data = buffer.toByteArray()
                    if (data.isEmpty()) return@withContext false

                    // 计算解压数据的 CRC32 并与存储值比较
                    val crc32 = java.util.zip.CRC32()
                    crc32.update(data)
                    val computedCrc = crc32.value.toInt()
                    return@withContext computedCrc == storedCrc
                }

                // 没有存储 CRC 信息且 test 模式通过 → 视为正确
                true
            }
        } catch (e: Exception) {
            logger.error("ArchiveService", "verifyPassword 异常", e)
            false
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
            openArchive(archivePath).use { handle ->
                val archive = handle.archive
                val numItems = archive.numberOfItems
                var targetIndex = -1
                for (i in 0 until numItems) {
                    val itemPath = archive.itemPath(i) ?: continue
                    if (itemPath == innerPath) {
                        targetIndex = i
                        break
                    }
                }
                if (targetIndex < 0) return@runCatching null

                val size = (archive.getProperty(targetIndex, PropID.SIZE) as? Long) ?: 0L
                val buffer = java.io.ByteArrayOutputStream(size.toInt().coerceAtLeast(1024))
                val callback = MemoryExtractCallback(buffer)
                archive.extract(intArrayOf(targetIndex), false, callback)
                buffer.toByteArray()
            }
        }
    }

    /**
     * 将压缩包内指定条目解压到临时目录（用于拖放到外部应用）。
     *
     * @param archivePath 压缩包物理路径
     * @param entryPaths  要解压的条目路径列表
     * @param targetDir   解压目标目录
     * @param password    解压密码（可选）
     */
    suspend fun extractEntriesToTemp(
        archivePath: String,
        entryPaths: List<String>,
        targetDir: String,
        password: String? = null,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val targetDirectory = File(targetDir)
            targetDirectory.mkdirs()

            // 计算选中条目的公共父目录，用于相对化输出路径
            // 例如 entryPaths=["dir1/dir2"] → parentPrefix="dir1/"
            // 这样 "dir1/dir2/file.txt" → 输出为 "dir2/file.txt"
            val parentPrefix = run {
                val parents = entryPaths.map { path ->
                    val lastSlash = path.trimEnd('/').lastIndexOf('/')
                    if (lastSlash >= 0) path.substring(0, lastSlash + 1) else ""
                }
                val common = parents.minByOrNull { it.length } ?: ""
                if (parents.all { it.startsWith(common) }) common else ""
            }

            openArchive(archivePath, password).use { handle ->
                val archive = handle.archive
                val numItems = archive.numberOfItems
                // 收集需要解压的 index
                val targetIndices = mutableListOf<Int>()
                val entryPathSet = entryPaths.toSet()
                for (i in 0 until numItems) {
                    val itemPath = archive.itemPath(i) ?: continue
                    // 精确匹配，或者是目标条目的子路径
                    if (itemPath in entryPathSet || entryPathSet.any { prefix ->
                            itemPath.startsWith(prefix.trimEnd('/') + "/")
                        }) {
                        targetIndices.add(i)
                    }
                }
                if (targetIndices.isEmpty()) {
                    error("未找到匹配的条目: $entryPaths")
                }

                val callback = FileExtractCallback(
                    archive = archive,
                    targetDir = targetDirectory,
                    prefix = parentPrefix,
                    password = password,
                )
                archive.extract(targetIndices.toIntArray(), false, callback)
                if (callback.errors.isNotEmpty()) {
                    error("解压失败: ${callback.errors.joinToString(", ")}")
                }
            }
        }
    }

    // ── 内部工具 ──────────────────────────────────────────────────────────────

    /**
     * 打开压缩包，返回 [ArchiveHandle]。
     * 调用方**必须**通过 `.use { }` 使用，以确保 `IInArchive` 和底层 `RandomAccessFile` 都被正确关闭。
     */
    private fun openArchive(path: String, password: String? = null): ArchiveHandle {
        val raf = RandomAccessFile(path, "r")
        return try {
            val inStream = RandomAccessFileInStream(raf)
            val archive = if (password != null) {
                SevenZip.openInArchive(null, inStream, password)
            } else {
                SevenZip.openInArchive(null, inStream)
            }
            ArchiveHandle(raf, archive)
        } catch (e: Exception) {
            raf.close()
            throw e
        }
    }
}

interface ArchiveServiceLogger {
    fun warn(
        tag: String,
        message: String,
        throwable: Throwable,
    )

    fun error(
        tag: String,
        message: String,
        throwable: Throwable,
    )

    data object NoOp : ArchiveServiceLogger {
        override fun warn(
            tag: String,
            message: String,
            throwable: Throwable,
        ) = Unit

        override fun error(
            tag: String,
            message: String,
            throwable: Throwable,
        ) = Unit
    }
}

// ── 内部辅助类型 ─────────────────────────────────────────────────────────────

/**
 * 封装 [IInArchive] 和底层 [RandomAccessFile]，确保两者统一关闭。
 */
private class ArchiveHandle(
    private val raf: RandomAccessFile,
    val archive: IInArchive,
) : Closeable {
    override fun close() {
        try {
            archive.close()
        } finally {
            raf.close()
        }
    }
}

/**
 * 从 [IInArchive] 中读取条目路径，并统一将 `\` 替换为 `/`。
 *
 * 7-Zip-JBinding 在 Windows 上返回反斜杠路径，此扩展确保所有路径一致使用正斜杠。
 */
private fun IInArchive.itemPath(index: Int): String? =
    (getProperty(index, PropID.PATH) as? String)?.replace('\\', '/')

/**
 * 解压错误结果的统一格式化。
 */
private fun formatExtractError(result: ExtractOperationResult): String = when (result) {
    ExtractOperationResult.WRONG_PASSWORD -> "密码错误"
    ExtractOperationResult.DATAERROR -> "数据错误（密码可能不正确）"
    ExtractOperationResult.CRCERROR -> "CRC 校验失败（密码可能不正确）"
    else -> result.name
}

/**
 * 解压到文件的回调 — 将条目写入目标目录。
 */
private class FileExtractCallback(
    private val archive: IInArchive,
    private val targetDir: File,
    private val prefix: String,
    private val password: String? = null,
) : IArchiveExtractCallback, ICryptoGetTextPassword {

    private var currentOutputStream: FileOutputStream? = null
    private var currentOutFile: File? = null
    val errors = mutableListOf<String>()

    override fun getStream(
        index: Int,
        extractAskMode: ExtractAskMode,
    ): ISequentialOutStream? {
        if (extractAskMode != ExtractAskMode.EXTRACT) return null

        val itemPath = archive.itemPath(index) ?: return null
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
        currentOutFile = outFile
        return ISequentialOutStream { data ->
            fos.write(data)
            data.size
        }
    }

    override fun prepareOperation(extractAskMode: ExtractAskMode) {}

    override fun setOperationResult(extractOperationResult: ExtractOperationResult) {
        currentOutputStream?.close()
        currentOutputStream = null
        if (extractOperationResult != ExtractOperationResult.OK) {
            // 删除失败产出的文件（避免 0B 空文件残留）
            currentOutFile?.let { file ->
                if (file.exists()) file.delete()
            }
            errors.add(formatExtractError(extractOperationResult))
        }
        currentOutFile = null
    }

    override fun setTotal(total: Long) {}
    override fun setCompleted(complete: Long) {}
    override fun cryptoGetTextPassword(): String = password ?: ""
}

/**
 * 解压到内存的回调 — 将条目写入 [ByteArrayOutputStream]。
 */
private class MemoryExtractCallback(
    private val buffer: java.io.ByteArrayOutputStream,
    private val password: String? = null,
) : IArchiveExtractCallback, ICryptoGetTextPassword {

    val errors = mutableListOf<String>()

    override fun getStream(
        index: Int,
        extractAskMode: ExtractAskMode,
    ): ISequentialOutStream? {
        if (extractAskMode != ExtractAskMode.EXTRACT) return null
        return ISequentialOutStream { data ->
            buffer.write(data)
            data.size
        }
    }

    override fun prepareOperation(extractAskMode: ExtractAskMode) {}

    override fun setOperationResult(extractOperationResult: ExtractOperationResult) {
        if (extractOperationResult != ExtractOperationResult.OK) {
            errors.add(formatExtractError(extractOperationResult))
        }
    }

    override fun setTotal(total: Long) {}
    override fun setCompleted(complete: Long) {}
    override fun cryptoGetTextPassword(): String = password ?: ""
}

/**
 * 纯验证回调 — test 模式，不写入任何数据，只记录错误。
 */
private class TestExtractCallback(
    private val password: String? = null,
) : IArchiveExtractCallback, ICryptoGetTextPassword {

    val errors = mutableListOf<String>()

    override fun getStream(index: Int, extractAskMode: ExtractAskMode): ISequentialOutStream? = null
    override fun prepareOperation(extractAskMode: ExtractAskMode) {}

    override fun setOperationResult(extractOperationResult: ExtractOperationResult) {
        if (extractOperationResult != ExtractOperationResult.OK) {
            errors.add(formatExtractError(extractOperationResult))
        }
    }

    override fun setTotal(total: Long) {}
    override fun setCompleted(complete: Long) {}
    override fun cryptoGetTextPassword(): String = password ?: ""
}
