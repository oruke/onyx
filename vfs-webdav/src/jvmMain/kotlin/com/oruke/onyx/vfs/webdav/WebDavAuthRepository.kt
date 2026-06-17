package com.oruke.onyx.vfs.webdav

import com.oruke.onyx.vfs.api.RemoteAuthStore
import com.oruke.onyx.vfs.api.VfsAuthContext
import com.oruke.onyx.vfs.api.VfsProtocol

/**
 * WebDAV 认证信息仓库。
 */
interface WebDavAuthRepository {
    /**
     * 获取指定位置的 WebDAV 认证上下文。
     *
     * @param location WebDAV 位置。
     * @return 认证上下文。
     */
    fun authContext(location: String): VfsAuthContext

    /**
     * 空认证仓库。
     */
    data object None : WebDavAuthRepository {
        override fun authContext(location: String): VfsAuthContext = VfsAuthContext.None
    }
}

/**
 * 基于统一远程认证存储的 WebDAV 认证仓库。
 *
 * @property remoteAuthStore 远程认证存储。
 */
class RemoteAuthStoreWebDavAuthRepository(
    private val remoteAuthStore: RemoteAuthStore,
) : WebDavAuthRepository {
    override fun authContext(location: String): VfsAuthContext {
        return remoteAuthStore.authContext(VfsProtocol.WEBDAV, location)
    }
}
