package com.oruke.onyx.vfs.webdav

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.vfs.api.TransferConflictStrategy
import com.oruke.onyx.vfs.api.VfsAuthContext
import com.oruke.onyx.vfs.api.VfsContentSource
import com.oruke.onyx.vfs.api.VfsProviderCapability
import com.oruke.onyx.vfs.api.VfsProviderError
import com.oruke.onyx.vfs.api.VfsProviderException
import com.oruke.onyx.vfs.api.VfsProtocol
import com.oruke.onyx.vfs.api.VfsRandomAccessHandle
import com.oruke.onyx.vfs.api.VfsRandomAccessMode
import kotlinx.coroutines.flow.Flow

/**
 * WebDAV provider 使用的底层 client。
 */
interface WebDavClient {
    /**
     * 测试 WebDAV 连接。
     *
     * @param location WebDAV 位置。
     * @param authContext 认证上下文。
     */
    suspend fun testConnection(
        location: String,
        authContext: VfsAuthContext,
    )

    /**
     * 列出目录直接子项。
     *
     * @param location WebDAV 目录位置。
     * @param authContext 认证上下文。
     * @return 子项列表。
     */
    suspend fun list(
        location: String,
        authContext: VfsAuthContext,
    ): List<VFile>

    /**
     * 删除 WebDAV 条目。
     *
     * @param entries 待删除条目。
     * @param authContext 认证上下文。
     */
    suspend fun delete(
        entries: List<VFile>,
        authContext: VfsAuthContext,
    )

    /**
     * 复制 WebDAV 条目。
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
    ) {
        throw VfsProviderException(
            VfsProviderError.UnsupportedOperation(
                protocol = VfsProtocol.WEBDAV,
                location = targetDirectoryLocation,
                capability = VfsProviderCapability.COPY,
            )
        )
    }

    /**
     * 移动 WebDAV 条目。
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
    ) {
        throw VfsProviderException(
            VfsProviderError.UnsupportedOperation(
                protocol = VfsProtocol.WEBDAV,
                location = targetDirectoryLocation,
                capability = VfsProviderCapability.MOVE,
            )
        )
    }

    /**
     * 重命名 WebDAV 条目。
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
     * 创建 WebDAV 文件。
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
     * 创建 WebDAV 目录。
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
     * 读取 WebDAV 文件内容。
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
     * 打开 WebDAV 文件的随机访问句柄。
     *
     * @param location WebDAV 文件位置。
     * @param mode 打开模式。
     * @param authContext 认证上下文。
     * @return HTTP Range 随机访问句柄。
     */
    suspend fun openRandomAccess(
        location: String,
        mode: VfsRandomAccessMode,
        authContext: VfsAuthContext,
    ): VfsRandomAccessHandle {
        throw VfsProviderException(
            VfsProviderError.UnsupportedOperation(
                protocol = VfsProtocol.WEBDAV,
                location = location,
                capability = if (mode == VfsRandomAccessMode.READ) {
                    VfsProviderCapability.READ_RANDOM_ACCESS
                } else {
                    VfsProviderCapability.WRITE_RANDOM_ACCESS
                },
            )
        )
    }

    /**
     * 写入 WebDAV 文件内容。
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
