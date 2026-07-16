package com.oruke.onyx.vfs.smb

import com.oruke.onyx.vfs.api.VfsAuthContext
import kotlin.test.Test
import kotlin.test.assertSame

/**
 * jcifs SMB 上下文提供器测试。
 */
class JcifsSmbContextProviderTest {
    /**
     * 校验不同认证包装仍复用同一个底层 SMB 传输池。
     *
     * @return 无返回值。
     */
    @Test
    fun reusesTransportPoolAcrossOperations() {
        val provider = JcifsSmbContextProvider()
        try {
            val anonymousContext = provider.contextFor(VfsAuthContext.None)
            val authenticatedContext = provider.contextFor(
                VfsAuthContext.UsernamePassword(
                    username = "user",
                    password = "test-credential",
                    domain = "domain",
                )
            )

            assertSame(anonymousContext.transportPool, authenticatedContext.transportPool)
        } finally {
            provider.close()
        }
    }
}
