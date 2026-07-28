package com.oruke.onyx.vfs.archive

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileCapability
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.vfs.api.TransferConflictStrategy
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
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.Date
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

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
    /** 归档处理日志出口。 */
    private val logger: ArchiveServiceLogger = ArchiveServiceLogger.NoOp,

    /** ZIP 系列归档写入服务。 */
    private val zipMutationService: ZipArchiveMutationService = ZipArchiveMutationService(),

    /** 系统 tar 命令名或可执行文件路径。 */
    tarCommand: String = DEFAULT_TAR_COMMAND,

    /** 系统 tar 能力探测超时秒数。 */
    tarRuntimeTimeoutSeconds: Long = 5L,
) {
    /** `.tar.zst` / `.tzst` 专用系统 tar 执行器。 */
    private val tarZstdArchiveService = TarZstdArchiveService(tarCommand, tarRuntimeTimeoutSeconds)

    /** 7-Zip 归档加密状态与密码检查器。 */
    private val archiveInspector = SevenZipArchiveInspector(logger)

    /**
     * 压缩包格式识别与 `archive://` 地址工具。
     */
    companion object {
        private const val ARCHIVE_SCHEME = "archive://"
        private const val DEFAULT_TAR_COMMAND = "tar"
        private val ARCHIVE_EXTENSIONS = setOf(
            "zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz",
            "lzma", "cab", "iso", "arj", "lzh", "z", "cpio",
            "cbz", "cbr", "epub", "tzst",
        )
        // tar.xx 双扩展名
        private val ARCHIVE_COMPOUND_EXTENSIONS = setOf(
            "tar.gz", "tar.bz2", "tar.xz", "tar.lzma", "tar.zst",
        )

        /**
         * 判断文件名是否属于当前可识别的压缩包格式。
         *
         * @param fileName 待检测的文件名或路径尾段。
         * @return `true` 表示文件扩展名可作为压缩包打开。
         */
        fun isArchive(fileName: String): Boolean {
            val lower = fileName.lowercase()
            if (ARCHIVE_COMPOUND_EXTENSIONS.any { lower.endsWith(".$it") }) return true
            val ext = lower.substringAfterLast('.', "")
            return ext in ARCHIVE_EXTENSIONS
        }

        /**
         * 判断 location 是否为 `archive://` 协议地址。
         *
         * @param location 待检测的 VFS 地址。
         * @return `true` 表示该地址指向压缩包内部位置。
         */
        fun isArchiveLocation(location: String): Boolean {
            return location.startsWith(ARCHIVE_SCHEME)
        }

        /**
         * 组装压缩包内部条目的 VFS 地址。
         *
         * @param archivePath 压缩包在本地文件系统中的真实路径。
         * @param entryPath 压缩包内部条目路径，空字符串表示压缩包根目录。
         * @return `archive://` 协议地址。
         */
        fun archiveLocation(archivePath: String, entryPath: String = ""): String {
            return "$ARCHIVE_SCHEME$archivePath!/$entryPath"
        }

        /**
         * 解析压缩包 VFS 地址中的物理路径与内部条目路径。
         *
         * @param location `archive://` 协议地址。
         * @return 物理压缩包路径与内部条目路径；无法解析时返回 `null`。
         */
        fun parseArchiveLocation(location: String): Pair<String, String>? {
            val locationBody = location.takeIf { candidate -> candidate.startsWith(ARCHIVE_SCHEME) }
                ?.removePrefix(ARCHIVE_SCHEME)
            val delimiterIndex = locationBody?.let(::archiveDelimiterIndex) ?: -1
            return if (locationBody != null && delimiterIndex >= 0) {
                locationBody.substring(0, delimiterIndex) to locationBody.substring(delimiterIndex + 2)
            } else {
                null
            }
        }

        private fun archiveDelimiterIndex(locationBody: String): Int {
            var start = 0
            while (true) {
                val index = locationBody.indexOf("!/", start)
                if (index < 0) return -1
                val archivePath = locationBody.substring(0, index)
                if (isArchive(archivePath)) return index
                start = index + 2
            }
        }

        /**
         * 计算 `archive://` 地址的上级位置。
         *
         * - 压缩包内部子目录返回上级内部路径。
         * - 压缩包根目录返回压缩包所在的物理目录。
         *
         * @param location `archive://` 协议地址。
         * @return 上级 VFS 地址；无法解析或无上级时返回 `null`。
         */
        fun archiveParentLocation(location: String): String? {
            val (archivePath, innerPath) = parseArchiveLocation(location) ?: return null
            return if (innerPath.isBlank()) {
                // 已在压缩包根 → 返回压缩包所在的物理目录
                java.nio.file.Path.of(archivePath).parent?.toString()
            } else {
                val trimmed = innerPath.trimEnd('/')
                val lastSlash = trimmed.lastIndexOf('/')
                val parentInner = if (lastSlash > 0) trimmed.substring(0, lastSlash) else ""
                archiveLocation(archivePath, parentInner)
            }
        }

        /**
         * 从压缩包 VFS 地址提取面板标签可显示的标题。
         *
         * @param location `archive://` 协议地址。
         * @return 内部目录名、压缩包文件名或原始地址。
         */
        fun archiveLocationTitle(location: String): String {
            val (archivePath, innerPath) = parseArchiveLocation(location) ?: return location
            return if (innerPath.isBlank()) {
                java.nio.file.Path.of(archivePath).fileName?.toString() ?: archivePath
            } else {
                innerPath.trimEnd('/').substringAfterLast('/')
            }
        }
    }

    /**
     * 列出压缩包内指定路径下的直接子条目。
     */
    suspend fun list(archivePath: String, innerPath: String = ""): Result<List<VFile>> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (archivePath.isTarZstdArchive()) {
                    return@runCatching tarZstdArchiveService.list(archivePath, innerPath)
                }
                openSevenZipArchive(archivePath).use { handle ->
                    val archive = handle.archive
                    val prefix = if (innerPath.isBlank()) "" else innerPath.trimEnd('/') + "/"
                    val parentLoc = archiveLocation(archivePath, innerPath)
                    val directChildren = mutableMapOf<String, VFile>()
                    for (index in 0 until archive.numberOfItems) {
                        archive.directChildEntry(index, archivePath, prefix, parentLoc)?.let { (name, child) ->
                            directChildren.putIfAbsent(name, child)
                        }
                    }
                    directChildren.values.toList()
                }
            }
        }

    /**
     * 解压全部或指定内部路径到目标目录。
     *
     * @param archivePath 压缩包物理路径。
     * @param targetDirectory 解压目标目录。
     * @param innerPath 可选压缩包内部路径。
     * @param password 可选解压密码。
     * @param progressSink 解压字节进度接收器；外部 tar 运行时可能无法回调。
     * @return 解压结果。
     */
    suspend fun extract(
        archivePath: String,
        targetDirectory: String,
        innerPath: String = "",
        password: String? = null,
        progressSink: ArchiveProgressSink = ArchiveProgressSink.NoOp,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (archivePath.isTarZstdArchive()) {
                tarZstdArchiveService.extract(archivePath, targetDirectory, innerPath)
                return@runCatching
            }
            openSevenZipArchive(archivePath, password).use { handle ->
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

                val callback = FileExtractCallback(
                    archive = archive,
                    targetDirectory = targetDir,
                    prefix = prefix,
                    password = password,
                    progressSink = progressSink,
                )
                archive.extract(
                    indicesToExtract.toIntArray(),
                    false,
                    callback,
                )
                if (callback.errors.isNotEmpty()) {
                    error("解压失败: ${callback.errors.joinToString(", ")}")
                }
                callback.completeProgress()
            }
        }
    }

    /**
     * 在目标目录下创建与压缩包同名的子目录并解压到其中。
     *
     * @param archivePath 压缩包物理路径。
     * @param targetDirectory 解压目标父目录。
     * @param password 可选解压密码。
     * @param progressSink 解压字节进度接收器。
     * @return 解压结果。
     */
    suspend fun extractToDirectory(
        archivePath: String,
        targetDirectory: String,
        password: String? = null,
        progressSink: ArchiveProgressSink = ArchiveProgressSink.NoOp,
    ): Result<Unit> {
        val archiveName = File(archivePath).nameWithoutExtension
        val subDir = File(targetDirectory, archiveName).absolutePath
        return extract(
            archivePath = archivePath,
            targetDirectory = subDir,
            password = password,
            progressSink = progressSink,
        )
    }

    /**
     * 按压缩包根结构选择直接解压或创建同名子目录。
     *
     * @param archivePath 压缩包物理路径。
     * @param targetDirectory 解压目标父目录。
     * @param password 可选解压密码。
     * @param progressSink 解压字节进度接收器。
     * @return 解压结果。
     */
    suspend fun extractSmart(
        archivePath: String,
        targetDirectory: String,
        password: String? = null,
        progressSink: ArchiveProgressSink = ArchiveProgressSink.NoOp,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // 先检查根目录结构
            val rootEntries = list(archivePath).getOrThrow()
            val hasSingleRootDir = rootEntries.size == 1 && rootEntries[0].kind == VFileKind.DIRECTORY
            if (hasSingleRootDir) {
                // 单一根目录 → 直接解压到 targetDirectory，保持原有目录名
                extract(
                    archivePath = archivePath,
                    targetDirectory = targetDirectory,
                    password = password,
                    progressSink = progressSink,
                ).getOrThrow()
            } else {
                // 多个根条目 → 创建同名子目录
                extractToDirectory(
                    archivePath = archivePath,
                    targetDirectory = targetDirectory,
                    password = password,
                    progressSink = progressSink,
                ).getOrThrow()
            }
        }
    }

    /**
     * 检查压缩包是否需要密码（是否加密）。
     */
    suspend fun isEncrypted(archivePath: String): Boolean = withContext(Dispatchers.IO) {
        archiveInspector.isEncrypted(archivePath)
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
        archiveInspector.verifyPassword(archivePath, password)
    }

    /**
     * 从压缩包中提取单个文件到内存字节数组。
     *
     * @param archivePath 压缩包物理路径
     * @param innerPath   压缩包内条目路径
     * @param maxBytes    允许读取的最大字节数，超过时返回 null；为空表示不限制
     * @return 文件字节数组，如果未找到则返回 null
     */
    suspend fun extractToBytes(
        archivePath: String,
        innerPath: String,
        maxBytes: Long? = null,
    ): Result<ByteArray?> = withContext(Dispatchers.IO) {
        runCatching {
            if (archivePath.isTarZstdArchive()) {
                val bytes = tarZstdArchiveService.extractEntryToBytes(archivePath, innerPath)
                if (maxBytes != null && bytes != null && bytes.size > maxBytes) return@runCatching null
                return@runCatching bytes
            }
            openSevenZipArchive(archivePath).use { handle ->
                val archive = handle.archive
                val numItems = archive.numberOfItems
                val targetIndex = (0 until numItems).firstOrNull { index ->
                    archive.itemPath(index) == innerPath
                } ?: -1
                if (targetIndex < 0) return@runCatching null

                val size = (archive.getProperty(targetIndex, PropID.SIZE) as? Long) ?: 0L
                if (maxBytes != null && size > maxBytes) return@runCatching null
                val buffer = ByteArrayOutputStream(size.toInt().coerceAtLeast(1024))
                val callback = MemoryExtractCallback(buffer)
                archive.extract(intArrayOf(targetIndex), false, callback)
                if (callback.errors.isNotEmpty()) {
                    error("解压失败: ${callback.errors.joinToString(", ")}")
                }
                buffer.toByteArray()
            }
        }
    }

    /**
     * 将压缩包内指定条目解压到临时目录（用于拖放到外部应用）。
     *
     * @param archivePath 压缩包物理路径。
     * @param entryPaths 要解压的条目路径列表。
     * @param targetDir 解压目标目录。
     * @param password 可选解压密码。
     * @param progressSink 解压字节进度接收器。
     * @return 解压结果。
     */
    suspend fun extractEntriesToTemp(
        archivePath: String,
        entryPaths: List<String>,
        targetDir: String,
        password: String? = null,
        progressSink: ArchiveProgressSink = ArchiveProgressSink.NoOp,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (archivePath.isTarZstdArchive()) {
                tarZstdArchiveService.extractEntries(archivePath, entryPaths, targetDir)
                return@runCatching
            }
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

            openSevenZipArchive(archivePath, password).use { handle ->
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
                    targetDirectory = targetDirectory,
                    prefix = parentPrefix,
                    password = password,
                    progressSink = progressSink,
                )
                archive.extract(targetIndices.toIntArray(), false, callback)
                if (callback.errors.isNotEmpty()) {
                    error("解压失败: ${callback.errors.joinToString(", ")}")
                }
            }
        }
    }

    /**
     * 检测当前运行时是否可处理 `.tar.zst` / `.tzst`。
     *
     * @return 检测结果；失败时包含用户可见错误。
     */
    suspend fun checkTarZstdRuntime(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            tarZstdArchiveService.ensureRuntimeAvailable(".tar.zst")
        }
    }

    /**
     * 在压缩包内部创建目录。
     *
     * @param archivePath 压缩包物理路径。
     * @param innerPath 压缩包内部目录路径。
     * @return 操作结果。
     */
    suspend fun createDirectoryInArchive(
        archivePath: String,
        innerPath: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            ensureMutableZipArchive(archivePath)
            zipMutationService.createDirectory(archivePath, innerPath)
        }
    }

    /**
     * 删除压缩包内部条目。
     *
     * @param archivePath 压缩包物理路径。
     * @param innerPaths 需要删除的内部路径。
     * @return 操作结果。
     */
    suspend fun deleteEntriesInArchive(
        archivePath: String,
        innerPaths: List<String>,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            ensureMutableZipArchive(archivePath)
            zipMutationService.deleteEntries(archivePath, innerPaths)
        }
    }

    /**
     * 重命名压缩包内部条目。
     *
     * @param archivePath 压缩包物理路径。
     * @param sourceInnerPath 源内部路径。
     * @param targetInnerPath 目标内部路径。
     * @return 操作结果。
     */
    suspend fun renameEntryInArchive(
        archivePath: String,
        sourceInnerPath: String,
        targetInnerPath: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            ensureMutableZipArchive(archivePath)
            zipMutationService.renameEntry(
                archivePath = archivePath,
                sourcePath = sourceInnerPath,
                targetPath = targetInnerPath,
            )
        }
    }

    /**
     * 向压缩包内部追加文件。
     *
     * @param archivePath 压缩包物理路径。
     * @param innerPath 目标内部文件路径。
     * @param bytes 文件内容。
     * @param conflictStrategy 名称冲突处理策略。
     * @return 实际写入的内部路径；SKIP 且目标存在时返回 null。
     */
    suspend fun appendFileToArchive(
        archivePath: String,
        innerPath: String,
        bytes: ByteArray,
        conflictStrategy: TransferConflictStrategy = TransferConflictStrategy.KEEP_BOTH,
    ): Result<String?> = withContext(Dispatchers.IO) {
        runCatching {
            ensureMutableZipArchive(archivePath)
            zipMutationService.appendFile(
                archivePath = archivePath,
                entryPath = innerPath,
                bytes = bytes,
                conflictStrategy = conflictStrategy,
            )
        }
    }

    // ── 内部工具 ──────────────────────────────────────────────────────────────

    /**
     * 确认 ZIP 系列压缩包支持内部写入。
     *
     * @param archivePath 压缩包路径。
     */
    private fun ensureMutableZipArchive(archivePath: String) {
        if (!archivePath.isMutableZipArchive()) {
            throw ArchiveMutationException("当前压缩格式暂不支持内部写入: ${File(archivePath).name}")
        }
    }

}
