package com.oruke.onyx.vfs.smb

import com.oruke.onyx.vfs.api.VfsAuthContext
import com.oruke.onyx.vfs.api.VfsProviderError
import com.oruke.onyx.vfs.api.VfsProviderException
import com.oruke.onyx.vfs.api.VfsProtocol
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import java.util.Properties

/**
 * jcifs SMB 上下文提供器，在应用生命周期内共享传输池并按请求附加认证信息。
 *
 * @param baseContext 持有 DNS、DFS、缓冲区和 SMB 传输池的基础上下文。
 */
internal class JcifsSmbContextProvider(
    /** 所有 SMB 操作共享的 jcifs 基础上下文。 */
    private val baseContext: CIFSContext = BaseContext(PropertyConfiguration(Properties())),
) : AutoCloseable {
    /**
     * 返回复用基础传输池的认证上下文。
     *
     * @param authContext 当前 SMB 位置对应的统一认证上下文。
     * @return 与基础上下文共享连接池的 jcifs 上下文。
     */
    fun contextFor(authContext: VfsAuthContext): CIFSContext {
        return when (authContext) {
            VfsAuthContext.None -> baseContext.withAnonymousCredentials()
            is VfsAuthContext.UsernamePassword -> baseContext.withCredentials(
                NtlmPasswordAuthenticator(
                    authContext.domain.orEmpty(),
                    authContext.username,
                    authContext.password,
                )
            )

            else -> throw VfsProviderException(
                VfsProviderError.UnsupportedOperation(
                    protocol = VfsProtocol.SMB,
                    capability = null,
                )
            )
        }
    }

    /**
     * 关闭共享传输池并注销 jcifs 资源。
     *
     * @return 无返回值。
     */
    override fun close() {
        baseContext.close()
    }
}
