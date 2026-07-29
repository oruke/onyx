package com.oruke.onyx.vfs.s3

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.S3ConnectionConfig
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
 * S3 provider 使用的底层 client。
 */
interface S3Client {
    /**
     * 测试 S3 连接。
     *
     * @param location S3 位置。
     * @param authContext AWS 凭据。
     * @param connectionConfig Endpoint 与寻址配置。
     */
    suspend fun testConnection(
        location: S3Location,
        authContext: VfsAuthContext.AwsCredentials,
        connectionConfig: S3ConnectionConfig,
    )

    /**
     * 列出目录直接子项。
     *
     * @param location S3 目录位置。
     * @param authContext AWS 凭据。
     * @param connectionConfig Endpoint 与寻址配置。
     * @return 子项列表。
     */
    suspend fun list(
        location: S3Location,
        authContext: VfsAuthContext.AwsCredentials,
        connectionConfig: S3ConnectionConfig,
    ): List<VFile>

    /**
     * 分页列出 S3 目录条目。
     *
     * @param location 当前目录位置。
     * @param pageSize 单页最大条目数。
     * @param pageToken S3 continuation token；第一页为 null。
     * @param authContext AWS 凭据。
     * @param connectionConfig Endpoint 与寻址配置。
     * @return 当前页条目和下一页 token。
     */
    suspend fun listPage(
        location: S3Location,
        pageSize: Int,
        pageToken: String?,
        authContext: VfsAuthContext.AwsCredentials,
        connectionConfig: S3ConnectionConfig,
    ): S3ListPage {
        return S3ListPage(
            entries = list(location, authContext, connectionConfig),
            nextContinuationToken = null,
        )
    }

    /**
     * 读取 S3 对象内容。
     *
     * @param entry 文件条目。
     * @param location S3 对象位置。
     * @param authContext AWS 凭据。
     * @param connectionConfig Endpoint 与寻址配置。
     * @return 文件内容源。
     */
    suspend fun readFile(
        entry: VFile,
        location: S3Location,
        authContext: VfsAuthContext.AwsCredentials,
        connectionConfig: S3ConnectionConfig,
    ): VfsContentSource {
        throw VfsProviderException(
            VfsProviderError.UnsupportedOperation(
                protocol = VfsProtocol.S3,
                location = entry.location,
                capability = VfsProviderCapability.READ_CONTENT,
            )
        )
    }

    /**
     * 打开 S3 对象的随机访问句柄。
     *
     * @param location S3 对象位置。
     * @param mode 打开模式。
     * @param authContext AWS 凭据。
     * @param connectionConfig Endpoint 与寻址配置。
     * @return HTTP Range 随机访问句柄。
     */
    suspend fun openRandomAccess(
        location: S3Location,
        mode: VfsRandomAccessMode,
        authContext: VfsAuthContext.AwsCredentials,
        connectionConfig: S3ConnectionConfig,
    ): VfsRandomAccessHandle {
        throw VfsProviderException(
            VfsProviderError.UnsupportedOperation(
                protocol = VfsProtocol.S3,
                location = location.toLocation(location.objectKey, directory = false),
                capability = if (mode == VfsRandomAccessMode.READ) {
                    VfsProviderCapability.READ_RANDOM_ACCESS
                } else {
                    VfsProviderCapability.WRITE_RANDOM_ACCESS
                },
            )
        )
    }

    /**
     * 写入 S3 对象内容。
     *
     * @param parentLocation 目标父目录。
     * @param name 文件名。
     * @param chunks 内容块。
     * @param conflictStrategy 名称冲突策略。
     * @param authContext AWS 凭据。
     * @param connectionConfig Endpoint 与寻址配置。
     * @return 写入后的文件；SKIP 时可能返回 null。
     */
    suspend fun writeFile(
        parentLocation: S3Location,
        name: String,
        chunks: Flow<ByteArray>,
        conflictStrategy: TransferConflictStrategy,
        authContext: VfsAuthContext.AwsCredentials,
        connectionConfig: S3ConnectionConfig,
    ): VFile? {
        throw VfsProviderException(
            VfsProviderError.UnsupportedOperation(
                protocol = VfsProtocol.S3,
                location = parentLocation.directoryLocation,
                capability = VfsProviderCapability.WRITE_CONTENT,
            )
        )
    }

    /**
     * 删除 S3 对象。
     *
     * @param location 对象位置。
     * @param authContext AWS 凭据。
     * @param connectionConfig Endpoint 与寻址配置。
     */
    suspend fun deleteObject(
        location: S3Location,
        authContext: VfsAuthContext.AwsCredentials,
        connectionConfig: S3ConnectionConfig,
    ) {
        throw VfsProviderException(
            VfsProviderError.UnsupportedOperation(
                protocol = VfsProtocol.S3,
                location = location.toLocation(location.objectKey, directory = false),
                capability = VfsProviderCapability.DELETE,
            )
        )
    }

    /**
     * 创建目录占位对象。
     *
     * @param location 目录位置。
     * @param authContext AWS 凭据。
     * @param connectionConfig Endpoint 与寻址配置。
     */
    suspend fun createDirectory(
        location: S3Location,
        authContext: VfsAuthContext.AwsCredentials,
        connectionConfig: S3ConnectionConfig,
    ) {
        throw VfsProviderException(
            VfsProviderError.UnsupportedOperation(
                protocol = VfsProtocol.S3,
                location = location.directoryLocation,
                capability = VfsProviderCapability.CREATE_DIRECTORY,
            )
        )
    }

    /**
     * 判断对象是否存在。
     *
     * @param location 对象位置。
     * @param authContext AWS 凭据。
     * @param connectionConfig Endpoint 与寻址配置。
     * @return 存在时返回 true。
     */
    suspend fun objectExists(
        location: S3Location,
        authContext: VfsAuthContext.AwsCredentials,
        connectionConfig: S3ConnectionConfig,
    ): Boolean {
        throw VfsProviderException(
            VfsProviderError.UnsupportedOperation(
                protocol = VfsProtocol.S3,
                location = location.toLocation(location.objectKey, directory = false),
                capability = VfsProviderCapability.READ_CONTENT,
            )
        )
    }
}
