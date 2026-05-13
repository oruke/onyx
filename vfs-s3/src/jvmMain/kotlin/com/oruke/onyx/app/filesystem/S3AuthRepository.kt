package com.oruke.onyx.app.filesystem

/**
 * S3 认证信息仓库。
 */
interface S3AuthRepository {
    /**
     * 获取指定位置的 AWS 认证上下文。
     *
     * @param location S3 位置。
     * @return 认证上下文。
     */
    fun authContext(location: String): VfsAuthContext

    /**
     * 空认证仓库。
     */
    data object None : S3AuthRepository {
        override fun authContext(location: String): VfsAuthContext = VfsAuthContext.None
    }
}

/**
 * 基于统一远程认证存储的 S3 认证仓库。
 *
 * @property remoteAuthStore 远程认证存储。
 */
class RemoteAuthStoreS3AuthRepository(
    private val remoteAuthStore: RemoteAuthStore,
) : S3AuthRepository {
    override fun authContext(location: String): VfsAuthContext {
        return remoteAuthStore.authContext(VfsProtocol.S3, location)
    }
}
