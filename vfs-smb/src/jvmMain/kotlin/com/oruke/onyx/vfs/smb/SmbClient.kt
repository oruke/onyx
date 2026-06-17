package com.oruke.onyx.vfs.smb

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.vfs.api.TransferConflictStrategy
import com.oruke.onyx.vfs.api.VfsAuthContext
import com.oruke.onyx.vfs.api.VfsContentSource
import kotlinx.coroutines.flow.Flow

/**
 * SMB provider 使用的底层 client。
 */
interface SmbClient {
    /**
     * 测试 SMB 连接。
     *
     * @param location SMB 位置。
     * @param authContext 认证上下文。
     */
    suspend fun testConnection(
        location: String,
        authContext: VfsAuthContext,
    )

    /**
     * 列出目录直接子项。
     *
     * @param location SMB 目录位置。
     * @param authContext 认证上下文。
     * @return 子项列表。
     */
    suspend fun list(
        location: String,
        authContext: VfsAuthContext,
    ): List<VFile>

    /**
     * 复制 SMB 条目。
     *
     * @param entries 待复制条目。
     * @param targetDirectoryLocation 目标目录位置。
     * @param conflictStrategy 名称冲突策略。
     * @param authContext 认证上下文。
     */
    suspend fun copy(
        entries: List<VFile>,
        targetDirectoryLocation: String,
        conflictStrategy: TransferConflictStrategy,
        authContext: VfsAuthContext,
    )

    /**
     * 移动 SMB 条目。
     *
     * @param entries 待移动条目。
     * @param targetDirectoryLocation 目标目录位置。
     * @param conflictStrategy 名称冲突策略。
     * @param authContext 认证上下文。
     */
    suspend fun move(
        entries: List<VFile>,
        targetDirectoryLocation: String,
        conflictStrategy: TransferConflictStrategy,
        authContext: VfsAuthContext,
    )

    /**
     * 删除 SMB 条目。
     *
     * @param entries 待删除条目。
     * @param authContext 认证上下文。
     */
    suspend fun delete(
        entries: List<VFile>,
        authContext: VfsAuthContext,
    )

    /**
     * 重命名 SMB 条目。
     *
     * @param entry 待重命名条目。
     * @param targetName 目标名称。
     * @param authContext 认证上下文。
     * @return 重命名后的条目。
     */
    suspend fun rename(
        entry: VFile,
        targetName: String,
        authContext: VfsAuthContext,
    ): VFile

    /**
     * 创建 SMB 文件。
     *
     * @param parentLocation 父目录位置。
     * @param name 文件名。
     * @param authContext 认证上下文。
     * @return 创建后的文件条目。
     */
    suspend fun createFile(
        parentLocation: String,
        name: String,
        authContext: VfsAuthContext,
    ): VFile

    /**
     * 创建 SMB 目录。
     *
     * @param parentLocation 父目录位置。
     * @param name 目录名。
     * @param authContext 认证上下文。
     * @return 创建后的目录条目。
     */
    suspend fun createDirectory(
        parentLocation: String,
        name: String,
        authContext: VfsAuthContext,
    ): VFile

    /**
     * 读取 SMB 文件内容。
     *
     * @param entry 文件条目。
     * @param authContext 认证上下文。
     * @return 文件内容源。
     */
    suspend fun readFile(
        entry: VFile,
        authContext: VfsAuthContext,
    ): VfsContentSource

    /**
     * 写入 SMB 文件内容。
     *
     * @param parentLocation 父目录位置。
     * @param name 文件名。
     * @param chunks 内容块。
     * @param conflictStrategy 名称冲突策略。
     * @param authContext 认证上下文。
     * @return 写入后的文件；SKIP 时可能返回 null。
     */
    suspend fun writeFile(
        parentLocation: String,
        name: String,
        chunks: Flow<ByteArray>,
        conflictStrategy: TransferConflictStrategy,
        authContext: VfsAuthContext,
    ): VFile?
}
