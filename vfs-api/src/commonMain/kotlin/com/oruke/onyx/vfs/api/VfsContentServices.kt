package com.oruke.onyx.vfs.api

import com.oruke.onyx.core.model.VFile
import kotlinx.coroutines.flow.Flow

/**
 * VFS 文件内容源，供跨 provider 复制和预览等流程以流式方式读取文件。
 *
 * @param name 文件名。
 * @param sizeBytes 文件大小，未知时为 null。
 * @param chunks 文件内容分块流。
 */
data class VfsContentSource(
    val name: String,
    val sizeBytes: Long?,
    val chunks: Flow<ByteArray>,
)

/**
 * 可按位置路由的 VFS 内容服务，负责读取和写入文件内容流。
 */
interface RoutableVfsContentService {
    /**
     * 判断当前内容服务是否支持指定位置。
     *
     * @param location 待检查 VFS 位置。
     * @return 支持时返回 true。
     */
    fun supports(location: String): Boolean

    /**
     * 读取文件内容。
     *
     * @param entry 待读取文件条目。
     * @return 文件内容源。
     */
    suspend fun readFile(entry: VFile): Result<VfsContentSource>

    /**
     * 写入文件内容。
     *
     * @param parentLocation 目标父目录位置。
     * @param name 目标文件名。
     * @param chunks 文件内容分块流。
     * @param conflictStrategy 名称冲突处理策略。
     * @return 写入后的文件条目；部分 provider 可能返回 null。
     */
    suspend fun writeFile(
        parentLocation: String,
        name: String,
        chunks: Flow<ByteArray>,
        conflictStrategy: TransferConflictStrategy = TransferConflictStrategy.KEEP_BOTH,
    ): Result<VFile?>
}
