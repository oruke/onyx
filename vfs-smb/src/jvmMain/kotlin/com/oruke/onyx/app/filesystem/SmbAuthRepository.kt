package com.oruke.onyx.app.filesystem

/**
 * SMB 认证信息仓库。
 */
interface SmbAuthRepository {
    /**
     * 获取指定位置的 SMB 认证上下文。
     *
     * @param location SMB 位置。
     * @return 认证上下文。
     */
    fun authContext(location: String): VfsAuthContext

    /**
     * 空认证仓库。
     */
    data object None : SmbAuthRepository {
        override fun authContext(location: String): VfsAuthContext = VfsAuthContext.None
    }
}

/**
 * 基于统一远程认证存储的 SMB 认证仓库。
 *
 * @property remoteAuthStore 远程认证存储。
 */
class RemoteAuthStoreSmbAuthRepository(
    private val remoteAuthStore: RemoteAuthStore,
) : SmbAuthRepository {
    override fun authContext(location: String): VfsAuthContext {
        return remoteAuthStore.authContext(VfsProtocol.SMB, location)
    }
}
