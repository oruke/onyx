package com.oruke.onyx.vfs.archive

import com.oruke.onyx.vfs.api.RoutableVfsRandomAccessService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.sf.sevenzipjbinding.ExtractAskMode
import net.sf.sevenzipjbinding.ExtractOperationResult
import net.sf.sevenzipjbinding.IArchiveExtractCallback
import net.sf.sevenzipjbinding.ICryptoGetTextPassword
import net.sf.sevenzipjbinding.IInArchive
import net.sf.sevenzipjbinding.ISequentialOutStream
import net.sf.sevenzipjbinding.PropID
import java.io.ByteArrayOutputStream

/**
 * 使用统一 VFS 随机访问能力读取压缩包封面图片。
 *
 * 该服务与 UI 解码逻辑解耦，仅负责在压缩包中选择第一张图片并提取原始字节。
 */
internal class ArchiveThumbnailReader(
    /** 可路由到本地或远程协议的随机访问服务。 */
    private val randomAccessService: RoutableVfsRandomAccessService,
) {
    private companion object {
        /** 避免依据不可信归档元数据一次性申请过大的初始缓冲区。 */
        const val MAX_INITIAL_BUFFER_BYTES = 1024 * 1024
    }

    /** 可作为压缩包封面的图片扩展名。 */
    private val imageExtensions = setOf(
        "png",
        "jpg",
        "jpeg",
        "gif",
        "webp",
        "bmp",
        "ico",
        "tiff",
        "tif",
    )

    /**
     * 从压缩包中提取按路径排序后的第一张大小受控图片。
     *
     * @param archivePath 压缩包 VFS 位置，可为本地、SMB、WebDAV 或 S3 位置。
     * @param maxBytes 允许提取的最大图片字节数。
     * @param password 可选归档密码。
     * @return 图片原始字节；压缩包内没有合适图片时返回 `null`。
     */
    suspend fun extractFirstImage(
        archivePath: String,
        maxBytes: Long,
        password: String? = null,
    ): Result<ByteArray?> = withContext(Dispatchers.IO) {
        runCatching {
            openSevenZipArchive(archivePath, randomAccessService, password).use { handle ->
                val archive = handle.archive
                val target = archive.findFirstImage(maxBytes) ?: return@runCatching null
                val initialCapacity = target.size
                    .coerceAtMost(MAX_INITIAL_BUFFER_BYTES.toLong())
                    .toInt()
                    .coerceAtLeast(DEFAULT_BUFFER_SIZE)
                val output = ByteArrayOutputStream(initialCapacity)
                val callback = ThumbnailExtractCallback(
                    targetIndex = target.index,
                    output = output,
                    maxBytes = maxBytes,
                    password = password,
                )
                archive.extract(intArrayOf(target.index), false, callback)
                callback.requireSuccessful()
                output.toByteArray().takeIf(ByteArray::isNotEmpty)
            }
        }
    }

    /**
     * 查找路径排序最靠前且大小未超过限制的图片条目。
     *
     * @param maxBytes 允许读取的最大图片字节数。
     * @return 符合条件的图片条目；不存在时返回 `null`。
     */
    private fun IInArchive.findFirstImage(maxBytes: Long): ArchiveImageEntry? {
        return (0 until numberOfItems)
            .mapNotNull { index -> readImageEntry(index, maxBytes) }
            .minByOrNull(ArchiveImageEntry::path)
    }

    /**
     * 将指定归档条目转换为图片候选。
     *
     * @param index 归档条目索引。
     * @param maxBytes 允许读取的最大图片字节数。
     * @return 图片候选；目录、非图片或超限条目返回 `null`。
     */
    private fun IInArchive.readImageEntry(
        index: Int,
        maxBytes: Long,
    ): ArchiveImageEntry? {
        val isDirectory = getProperty(index, PropID.IS_FOLDER) as? Boolean ?: false
        val path = getProperty(index, PropID.PATH) as? String ?: return null
        val size = getProperty(index, PropID.SIZE) as? Long ?: 0L
        val extension = path.substringAfterLast('.', "").lowercase()
        return if (!isDirectory && extension in imageExtensions && size <= maxBytes) {
            ArchiveImageEntry(index, path, size)
        } else {
            null
        }
    }

    /**
     * 压缩包图片候选。
     */
    private data class ArchiveImageEntry(
        /** 条目在压缩包中的索引。 */
        val index: Int,
        /** 用于稳定选择封面的条目路径。 */
        val path: String,
        /** 解压前声明的字节数。 */
        val size: Long,
    )

    /**
     * 将单个封面条目写入内存，并保留 7-Zip 返回的操作结果。
     */
    private class ThumbnailExtractCallback(
        /** 需要提取的条目索引。 */
        private val targetIndex: Int,
        /** 条目内容输出缓冲区。 */
        private val output: ByteArrayOutputStream,
        /** 允许写入内存的最大字节数。 */
        private val maxBytes: Long,
        /** 可选归档密码。 */
        private val password: String?,
    ) : IArchiveExtractCallback, ICryptoGetTextPassword {
        /** 7-Zip 返回的最终提取结果。 */
        private var operationResult: ExtractOperationResult? = null

        /**
         * 为目标条目提供顺序输出流。
         *
         * @param index 当前条目索引。
         * @param extractAskMode 当前提取模式。
         * @return 目标条目的内存输出流；其他条目返回 `null`。
         */
        override fun getStream(
            index: Int,
            extractAskMode: ExtractAskMode,
        ): ISequentialOutStream? {
            if (index != targetIndex || extractAskMode != ExtractAskMode.EXTRACT) return null
            return ISequentialOutStream { data ->
                check(output.size().toLong() + data.size <= maxBytes) {
                    "压缩包封面实际数据超过读取上限: $maxBytes"
                }
                output.write(data)
                data.size
            }
        }

        /**
         * 接收操作准备通知。
         *
         * @param extractAskMode 当前提取模式。
         */
        override fun prepareOperation(extractAskMode: ExtractAskMode) = Unit

        /**
         * 保存 7-Zip 返回的提取结果。
         *
         * @param result 当前条目的提取结果。
         */
        override fun setOperationResult(result: ExtractOperationResult) {
            operationResult = result
        }

        /**
         * 接收总字节数通知。
         *
         * @param total 预计提取总字节数。
         */
        override fun setTotal(total: Long) = Unit

        /**
         * 接收已完成字节数通知。
         *
         * @param complete 已完成字节数。
         */
        override fun setCompleted(complete: Long) = Unit

        /** @return 当前归档密码；无密码时返回空字符串供 7-Zip 继续判定。 */
        override fun cryptoGetTextPassword(): String = password ?: ""

        /**
         * 校验提取结果，防止把 CRC 错误或密码错误的数据交给图片解码器。
         */
        fun requireSuccessful() {
            check(operationResult == ExtractOperationResult.OK) {
                "压缩包封面提取失败: ${operationResult ?: "未返回结果"}"
            }
        }
    }
}
