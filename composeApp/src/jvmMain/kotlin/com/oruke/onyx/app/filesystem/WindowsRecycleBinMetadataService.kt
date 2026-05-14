package com.oruke.onyx.app.filesystem

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.pathString
import kotlin.streams.toList

/**
 * Windows 回收站元数据服务，负责等待并解析 Shell 写入的 `$I` / `$R` 文件。
 *
 * JVM 的 `Desktop.moveToTrash` 只返回移动是否成功，不返回回收站内路径；该服务通过 `$I`
 * 元数据把原路径映射回真实 `$R` 内容路径，供撤销逻辑恢复文件。
 */
internal object WindowsRecycleBinMetadataService {
    /**
     * 等待 Windows Shell 写入 `$I` 回收站元数据。
     *
     * @param originalPath 移入回收站前的本地路径。
     * @param movedAtMillis 发起移动时的时间戳，用于排除同一路径旧记录。
     * @return 找到的 Windows 回收站记录；超时未找到时返回 `null`。
     */
    fun awaitRecord(
        originalPath: Path,
        movedAtMillis: Long,
    ): WindowsRecycleBinRecord? {
        repeat(WINDOWS_RECYCLE_SCAN_RETRY_COUNT) {
            val record = findRecord(originalPath, movedAtMillis)
            if (record != null) return record
            Thread.sleep(WINDOWS_RECYCLE_SCAN_DELAY_MS)
        }
        return findRecord(originalPath, movedAtMillis)
    }

    /**
     * 扫描当前磁盘的 Windows 回收站并匹配原路径。
     *
     * @param originalPath 移入回收站前的本地路径。
     * @param movedAtMillis 发起移动时的时间戳。
     * @return 最可能对应本次移动的回收站记录。
     */
    private fun findRecord(
        originalPath: Path,
        movedAtMillis: Long,
    ): WindowsRecycleBinRecord? {
        val recycleRoot = originalPath.root?.resolve(WINDOWS_RECYCLE_DIRECTORY_NAME) ?: return null
        val normalizedOriginal = originalPath.normalize().toAbsolutePath().pathString
        return recycleRoot.children()
            .asSequence()
            .filter { directory -> Files.isDirectory(directory) }
            .flatMap { directory -> directory.children().asSequence() }
            .filter { candidate -> candidate.fileName.toString().startsWith(WINDOWS_RECYCLE_INFO_PREFIX) }
            .mapNotNull { infoPath -> parseInfo(infoPath) }
            .filter { record -> record.originalLocation.equals(normalizedOriginal, ignoreCase = true) }
            .filter { record -> record.wasDeletedDuringCurrentMove(movedAtMillis) }
            .filter { record -> Files.exists(record.contentPath) }
            .maxByOrNull { record -> record.deletedAtMillis }
    }

    /**
     * 解析 Windows `$I` 回收站元数据文件。
     *
     * @param infoPath `$I` 元数据文件路径。
     * @return 解析成功的回收站记录；格式不符合预期时返回 `null`。
     */
    private fun parseInfo(infoPath: Path): WindowsRecycleBinRecord? {
        val bytes = runCatching { Files.readAllBytes(infoPath) }.getOrNull()
        var record: WindowsRecycleBinRecord? = null
        if (bytes != null && bytes.size >= WINDOWS_RECYCLE_INFO_MIN_BYTES) {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val version = buffer.long
            val originalLocation = when (version) {
                WINDOWS_RECYCLE_VERSION_ONE -> decodeVersionOnePath(bytes)
                WINDOWS_RECYCLE_VERSION_TWO -> decodeVersionTwoPath(bytes)
                else -> null
            }
            if (originalLocation != null) {
                val contentName = infoPath.fileName.toString().replaceFirst(
                    WINDOWS_RECYCLE_INFO_PREFIX,
                    WINDOWS_RECYCLE_CONTENT_PREFIX,
                )
                record = WindowsRecycleBinRecord(
                    infoPath = infoPath,
                    contentPath = infoPath.resolveSibling(contentName),
                    originalLocation = originalLocation,
                    deletedAtMillis = windowsFileTimeToMillis(buffer.getLong(WINDOWS_RECYCLE_DELETED_AT_OFFSET)),
                )
            }
        }
        return record
    }

    /**
     * 解析 Windows 回收站版本 1 元数据中的原路径。
     *
     * @param bytes `$I` 文件二进制内容。
     * @return 原始路径；解析失败时返回 `null`。
     */
    private fun decodeVersionOnePath(bytes: ByteArray): String? {
        if (bytes.size <= WINDOWS_RECYCLE_V1_PATH_OFFSET) return null
        return String(
            bytes,
            WINDOWS_RECYCLE_V1_PATH_OFFSET,
            bytes.size - WINDOWS_RECYCLE_V1_PATH_OFFSET,
            StandardCharsets.UTF_16LE,
        ).trimEnd('\u0000')
            .takeIf { path -> path.isNotBlank() }
    }

    /**
     * 解析 Windows 回收站版本 2 元数据中的原路径。
     *
     * @param bytes `$I` 文件二进制内容。
     * @return 原始路径；解析失败时返回 `null`。
     */
    private fun decodeVersionTwoPath(bytes: ByteArray): String? {
        var path: String? = null
        if (bytes.size >= WINDOWS_RECYCLE_V2_PATH_OFFSET) {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val charCount = buffer.getInt(WINDOWS_RECYCLE_V2_LENGTH_OFFSET)
            if (charCount > 0) {
                val byteCount = (charCount * WINDOWS_UTF16_BYTES_PER_CHAR)
                    .coerceAtMost(bytes.size - WINDOWS_RECYCLE_V2_PATH_OFFSET)
                path = String(
                    bytes,
                    WINDOWS_RECYCLE_V2_PATH_OFFSET,
                    byteCount,
                    StandardCharsets.UTF_16LE,
                ).trimEnd('\u0000')
                    .takeIf { value -> value.isNotBlank() }
            }
        }
        return path
    }

    /**
     * 将 Windows FILETIME 转换为 Unix epoch 毫秒。
     *
     * @param fileTime Windows FILETIME 时间。
     * @return Unix epoch 毫秒。
     */
    private fun windowsFileTimeToMillis(fileTime: Long): Long {
        return fileTime / WINDOWS_FILETIME_TICKS_PER_MILLI - WINDOWS_FILETIME_EPOCH_MILLIS
    }

    /**
     * 判断回收站元数据是否属于本次移动，避免命中同一路径的历史残留记录。
     *
     * @param movedAtMillis 本次调用 Shell 删除前记录的时间戳。
     * @return 属于本次删除窗口时返回 true。
     */
    private fun WindowsRecycleBinRecord.wasDeletedDuringCurrentMove(movedAtMillis: Long): Boolean {
        return deletedAtMillis >= movedAtMillis - WINDOWS_RECYCLE_TIMESTAMP_TOLERANCE_MS
    }

    /**
     * 安全列出目录子项；Windows 回收站中其他 SID 目录不可读时直接跳过。
     *
     * @return 当前目录可读取的子路径。
     */
    private fun Path.children(): List<Path> {
        return runCatching {
            if (!Files.isDirectory(this)) return emptyList()
            Files.list(this).use { stream -> stream.toList() }
        }.getOrDefault(emptyList())
    }

    /** Windows 回收站目录名。 */
    private const val WINDOWS_RECYCLE_DIRECTORY_NAME = "\$Recycle.Bin"

    /** Windows 回收站元数据文件名前缀。 */
    private const val WINDOWS_RECYCLE_INFO_PREFIX = "\$I"

    /** Windows 回收站内容文件名前缀。 */
    private const val WINDOWS_RECYCLE_CONTENT_PREFIX = "\$R"

    /** Windows `$I` 文件最小长度。 */
    private const val WINDOWS_RECYCLE_INFO_MIN_BYTES = 28

    /** Windows 回收站版本 1 标记。 */
    private const val WINDOWS_RECYCLE_VERSION_ONE = 1L

    /** Windows 回收站版本 2 标记。 */
    private const val WINDOWS_RECYCLE_VERSION_TWO = 2L

    /** Windows `$I` 删除时间偏移。 */
    private const val WINDOWS_RECYCLE_DELETED_AT_OFFSET = 16

    /** Windows 回收站版本 1 原路径偏移。 */
    private const val WINDOWS_RECYCLE_V1_PATH_OFFSET = 24

    /** Windows 回收站版本 2 路径长度偏移。 */
    private const val WINDOWS_RECYCLE_V2_LENGTH_OFFSET = 24

    /** Windows 回收站版本 2 原路径偏移。 */
    private const val WINDOWS_RECYCLE_V2_PATH_OFFSET = 28

    /** UTF-16 单字符字节数。 */
    private const val WINDOWS_UTF16_BYTES_PER_CHAR = 2

    /** Windows FILETIME 到毫秒的 tick 比例。 */
    private const val WINDOWS_FILETIME_TICKS_PER_MILLI = 10_000L

    /** Windows FILETIME 与 Unix epoch 的毫秒差。 */
    private const val WINDOWS_FILETIME_EPOCH_MILLIS = 11_644_473_600_000L

    /** Windows Shell 写入回收站元数据后的轮询次数。 */
    private const val WINDOWS_RECYCLE_SCAN_RETRY_COUNT = 20

    /** Windows Shell 回收站元数据轮询间隔。 */
    private const val WINDOWS_RECYCLE_SCAN_DELAY_MS = 50L

    /** Windows 回收站删除时间与调用时间的允许误差。 */
    private const val WINDOWS_RECYCLE_TIMESTAMP_TOLERANCE_MS = 1_000L
}

/**
 * Windows 回收站元数据解析结果。
 *
 * @property infoPath `$I` 元数据路径。
 * @property contentPath `$R` 实际内容路径。
 * @property originalLocation 移入回收站前的原始路径。
 * @property deletedAtMillis 删除时间。
 */
internal data class WindowsRecycleBinRecord(
    val infoPath: Path,
    val contentPath: Path,
    val originalLocation: String,
    val deletedAtMillis: Long,
)
