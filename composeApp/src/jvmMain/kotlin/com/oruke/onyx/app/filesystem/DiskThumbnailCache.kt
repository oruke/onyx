package com.oruke.onyx.app.filesystem

import com.oruke.onyx.app.OnyxLogger
import com.oruke.onyx.app.storage.OnyxDataDirectories
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image as SkiaImage

/**
 * 本地磁盘缩略图 LRU 缓存。
 *
 * 将已缩放的画廊与预览缩略图压缩为 WebP（兜底 JPEG）持久化在应用缓存目录，
 * 使窗口入托盘释放全部内存位图后，重新打开大型 NAS 或压缩包目录时无需重复读取原图与 7-Zip 解压。
 *
 * @param cacheDirectory 磁盘缩略图缓存目录，默认位于 `OnyxDataDirectories.cacheDirectory()/thumbnails`。
 * @param maxBytes 磁盘缓存总容量上限（默认 512 MB），超出时按文件最后访问/修改时间 LRU 裁剪至 85% 水位。
 */
internal class DiskThumbnailCache(
    /** 磁盘缩略图存放目录。 */
    private val cacheDirectory: Path = OnyxDataDirectories.cacheDirectory().resolve(DEFAULT_SUBDIR_NAME),
    /** 允许占用的最大磁盘字节数。 */
    private val maxBytes: Long = DEFAULT_MAX_DISK_BYTES,
) {
    /** 串行化目录裁剪与清空操作，避免并发扫描磁盘。 */
    private val maintenanceLock = Any()

    /** 当前估算磁盘占用字节数；为 `-1` 表示尚未扫描初始化。 */
    private val estimatedBytes = AtomicLong(UNINITIALIZED_BYTES)

    /** 累计写入次数计数器，用于定期校准真实磁盘体积。 */
    private val writesSinceScan = AtomicLong(0L)

    /**
     * 按缩略图缓存键读取本地磁盘缓存的编码字节；未命中返回 `null`。
     *
     * @param cacheKey 包含路径、修改时间、文件大小与目标边长的版本化缓存键。
     * @return 已缓存的 WebP/JPEG 编码字节；未命中或损坏时返回 `null`。
     */
    fun getBytes(cacheKey: String): ByteArray? {
        val file = resolveExistingCacheFile(cacheKey) ?: return null
        return runCatching {
            val bytes = Files.readAllBytes(file)
            if (bytes.isEmpty()) {
                Files.deleteIfExists(file)
                null
            } else {
                touchIfStale(file)
                bytes
            }
        }.getOrNull()
    }

    /**
     * 将已缩放完成的 Skia 缩略图编码写入本地磁盘缓存，并在超限时触发 LRU 裁剪。
     *
     * @param cacheKey 版本化缩略图缓存键。
     * @param skiaImage 已缩放到目标尺寸的原生 Skia 图像。
     */
    fun putImage(cacheKey: String, skiaImage: SkiaImage) {
        if (maxBytes <= 0L) return
        val encoded = encodeThumbnail(skiaImage) ?: return
        if (encoded.bytes.size.toLong() > maxBytes) return
        runCatching {
            Files.createDirectories(cacheDirectory)
            val hash = sha256Hex(cacheKey)
            val target = cacheDirectory.resolve("$hash.${encoded.extension}")
            val previousSize = if (Files.isRegularFile(target)) Files.size(target) else 0L
            writeAtomically(target, encoded.bytes)
            updateEstimatedBytesAndTrim(encoded.bytes.size.toLong() - previousSize)
        }.onFailure { failure ->
            OnyxLogger.warn(TAG, "写入磁盘缩略图缓存失败: $cacheKey", failure)
        }
    }

    /**
     * 统计当前磁盘缩略图缓存目录的真实总字节占用。
     *
     * @return 缓存目录内文件总字节数。
     */
    suspend fun readUsageBytes(): Long = withContext(Dispatchers.IO) {
        synchronized(maintenanceLock) {
            val total = scanCacheEntries().sumOf { entry -> entry.sizeBytes }
            estimatedBytes.set(total)
            writesSinceScan.set(0L)
            total
        }
    }

    /**
     * 清空本地磁盘缩略图缓存目录中的所有缓存文件。
     *
     * @return 清理结果；失败时保留底层异常。
     */
    suspend fun clear(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            synchronized(maintenanceLock) {
                if (Files.isDirectory(cacheDirectory)) {
                    Files.list(cacheDirectory).use { stream ->
                        stream.forEach { entry ->
                            if (Files.isRegularFile(entry)) {
                                Files.deleteIfExists(entry)
                            }
                        }
                    }
                }
                estimatedBytes.set(0L)
                writesSinceScan.set(0L)
            }
        }
    }

    /**
     * 查找缓存键对应的已存在磁盘缓存文件（优先 WebP，其次 JPEG）。
     *
     * @param cacheKey 版本化缩略图缓存键。
     * @return 存在的缓存文件路径；未命中返回 `null`。
     */
    private fun resolveExistingCacheFile(cacheKey: String): Path? {
        if (!Files.isDirectory(cacheDirectory)) return null
        val hash = sha256Hex(cacheKey)
        val webp = cacheDirectory.resolve("$hash.$EXT_WEBP")
        if (Files.isRegularFile(webp)) return webp
        val jpeg = cacheDirectory.resolve("$hash.$EXT_JPEG")
        return jpeg.takeIf(Files::isRegularFile)
    }

    /**
     * 当命中文件时间戳距今超过退让阈值时刷新修改时间以维持 LRU 顺序，避免高频小写入。
     *
     * @param file 已命中的缓存文件。
     */
    private fun touchIfStale(file: Path) {
        runCatching {
            val now = System.currentTimeMillis()
            val lastModified = Files.getLastModifiedTime(file).toMillis()
            if (now - lastModified >= TOUCH_INTERVAL_MS) {
                Files.setLastModifiedTime(file, FileTime.fromMillis(now))
            }
        }
    }

    /**
     * 更新磁盘缓存估算体积，超出上限或达到校准周期时按 LRU 淘汰最久未使用的缩略图文件。
     *
     * @param deltaBytes 本次写入带来的净字节变化。
     */
    private fun updateEstimatedBytesAndTrim(deltaBytes: Long) {
        val current = estimatedBytes.updateAndGet { prev ->
            if (prev < 0L) UNINITIALIZED_BYTES else (prev + deltaBytes).coerceAtLeast(0L)
        }
        val writes = writesSinceScan.incrementAndGet()
        if (current >= 0L && current <= maxBytes && writes < RESCAN_WRITE_THRESHOLD) {
            return
        }
        synchronized(maintenanceLock) {
            val entries = scanCacheEntries()
            var total = entries.sumOf { entry -> entry.sizeBytes }
            if (total > maxBytes) {
                val targetBytes = (maxBytes * TRIM_TARGET_RATIO).toLong()
                val sortedOldestFirst = entries.sortedBy { entry -> entry.lastModifiedMillis }
                for (entry in sortedOldestFirst) {
                    if (total <= targetBytes) break
                    if (runCatching { Files.deleteIfExists(entry.path) }.getOrDefault(false)) {
                        total = (total - entry.sizeBytes).coerceAtLeast(0L)
                    }
                }
            }
            estimatedBytes.set(total)
            writesSinceScan.set(0L)
        }
    }

    /**
     * 扫描缓存目录内所有有效缓存文件及其体积和时间戳。
     *
     * @return 当前缓存文件清单。
     */
    private fun scanCacheEntries(): List<DiskCacheEntry> {
        if (!Files.isDirectory(cacheDirectory)) return emptyList()
        return runCatching {
            Files.list(cacheDirectory).use { stream ->
                buildList {
                    stream.forEach { path ->
                        val name = path.fileName?.toString().orEmpty()
                        if (Files.isRegularFile(path) && (name.endsWith(".$EXT_WEBP") || name.endsWith(".$EXT_JPEG"))) {
                            runCatching {
                                DiskCacheEntry(
                                    path = path,
                                    sizeBytes = Files.size(path),
                                    lastModifiedMillis = Files.getLastModifiedTime(path).toMillis(),
                                )
                            }.getOrNull()?.let(::add)
                        }
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    /**
     * 编码缩略图为紧凑 WebP（质量 82），若当前平台 Skia WebP 编码不可用则回退 JPEG。
     *
     * @param skiaImage 已缩放的原生 Skia 图像。
     * @return 编码后的字节与扩展名；编码失败返回 `null`。
     */
    private fun encodeThumbnail(skiaImage: SkiaImage): EncodedThumbnail? {
        val webpBytes = runCatching {
            skiaImage.encodeToData(EncodedImageFormat.WEBP, THUMBNAIL_ENCODE_QUALITY)?.use { data -> data.bytes }
        }.getOrNull()
        if (webpBytes != null && webpBytes.isNotEmpty()) {
            return EncodedThumbnail(bytes = webpBytes, extension = EXT_WEBP)
        }
        val jpegBytes = runCatching {
            skiaImage.encodeToData(EncodedImageFormat.JPEG, THUMBNAIL_ENCODE_QUALITY)?.use { data -> data.bytes }
        }.getOrNull()
        return if (jpegBytes != null && jpegBytes.isNotEmpty()) {
            EncodedThumbnail(bytes = jpegBytes, extension = EXT_JPEG)
        } else {
            null
        }
    }

    /**
     * 先写临时文件再原子替换到目标路径，防止进程中断留下半截损坏图片。
     *
     * @param target 最终缓存文件路径。
     * @param bytes 完整编码字节。
     */
    private fun writeAtomically(target: Path, bytes: ByteArray) {
        val tempFile = Files.createTempFile(cacheDirectory, "thumb-", ".tmp")
        try {
            Files.write(tempFile, bytes)
            try {
                Files.move(
                    tempFile,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: UnsupportedOperationException) {
                Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            runCatching { Files.deleteIfExists(tempFile) }
        }
    }

    /**
     * 计算缓存键的 SHA-256 十六进制文件名，规避路径特殊字符与超长文件名问题。
     *
     * @param key 原始缓存键。
     * @return 64 位小写十六进制哈希。
     */
    private fun sha256Hex(key: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(StandardCharsets.UTF_8))
        val chars = CharArray(digest.size * 2)
        for (i in digest.indices) {
            val byteVal = digest[i].toInt() and BYTE_MASK
            chars[i * 2] = HEX_CHARS[byteVal ushr NIBBLE_SHIFT]
            chars[i * 2 + 1] = HEX_CHARS[byteVal and NIBBLE_MASK]
        }
        return String(chars)
    }

    /** 已编码缩略图载荷。 */
    private class EncodedThumbnail(
        /** 编码字节数组。 */
        val bytes: ByteArray,
        /** 对应文件扩展名（`webp` 或 `jpg`）。 */
        val extension: String,
    )

    /** 单个磁盘缓存文件元数据快照。 */
    private class DiskCacheEntry(
        /** 缓存文件路径。 */
        val path: Path,
        /** 文件字节大小。 */
        val sizeBytes: Long,
        /** 最近访问或修改时间戳（毫秒）。 */
        val lastModifiedMillis: Long,
    )

    internal companion object {
        /** 日志标签。 */
        private const val TAG = "DiskThumbnailCache"

        /** 默认缓存子目录名。 */
        private const val DEFAULT_SUBDIR_NAME = "thumbnails"

        /** 默认磁盘缩略图缓存容量上限（512 MB）。 */
        const val DEFAULT_MAX_DISK_BYTES: Long = 512L * 1024 * 1024

        /** 触发超限裁剪后的目标保留比例（85%）。 */
        private const val TRIM_TARGET_RATIO: Double = 0.85

        /** 缩略图压缩质量（兼顾画质与体积，单张约 8–25 KB）。 */
        private const val THUMBNAIL_ENCODE_QUALITY: Int = 82

        /** 命中缓存后更新文件时间戳的最小间隔（1 小时），避免高频元数据写入。 */
        private const val TOUCH_INTERVAL_MS: Long = 3_600_000L

        /** 累计写入达到该次数后重新校准一次目录总大小。 */
        private const val RESCAN_WRITE_THRESHOLD: Long = 64L

        /** 尚未扫描初始化的估算体积标记。 */
        private const val UNINITIALIZED_BYTES: Long = -1L

        /** WebP 文件扩展名。 */
        private const val EXT_WEBP = "webp"

        /** JPEG 文件扩展名。 */
        private const val EXT_JPEG = "jpg"

        /** 十六进制字符表。 */
        private const val HEX_CHARS = "0123456789abcdef"

        /** 单字节掩码。 */
        private const val BYTE_MASK = 0xFF

        /** 高四位右移位数。 */
        private const val NIBBLE_SHIFT = 4

        /** 低四位掩码。 */
        private const val NIBBLE_MASK = 0x0F
    }
}
