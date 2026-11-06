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
 * @param baseContextFactory 持有 DNS、DFS、缓冲区和 SMB 传输池的基础上下文工厂。
 */
internal class JcifsSmbContextProvider(
    /** 按需创建共享 jcifs 基础上下文的工厂，避免应用启动时在主线程初始化加密提供者。 */
    private val baseContextFactory: () -> CIFSContext = { BaseContext(PropertyConfiguration(Properties())) },
) : AutoCloseable {
    /**
     * 使用已创建的 jcifs 基础上下文构造提供器。
     *
     * @param baseContext 所有 SMB 操作共享的 jcifs 基础上下文。
     */
    constructor(baseContext: CIFSContext) : this({ baseContext })

    /** 延迟初始化的共享 jcifs 基础上下文。 */
    private val lazyBaseContext = lazy(LazyThreadSafetyMode.SYNCHRONIZED, baseContextFactory)

    /** 所有 SMB 操作共享的 jcifs 基础上下文。 */
    private val baseContext: CIFSContext
        get() = lazyBaseContext.value
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
        if (lazyBaseContext.isInitialized()) {
            baseContext.close()
        }
    }
}
