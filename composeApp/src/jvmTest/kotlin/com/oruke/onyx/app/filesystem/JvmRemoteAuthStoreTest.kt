package com.oruke.onyx.app.filesystem

import com.oruke.onyx.vfs.api.InMemoryRemoteAuthStore
import com.oruke.onyx.vfs.api.RemoteAuthScope
import com.oruke.onyx.vfs.api.RemoteCredentialSavePolicy
import com.oruke.onyx.vfs.api.RemoteCredentialSaveResult
import com.oruke.onyx.vfs.api.VfsAuthContext
import com.oruke.onyx.vfs.api.VfsProtocol
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * JVM 远程认证仓库跨层作用域优先级与旧版仅主机钥匙串清理回归测试。
 */
class JvmRemoteAuthStoreTest {
    /**
     * 更精确路径上的系统钥匙串凭据必须优先于宽泛主机级会话凭据，绝不被宽泛会话记录劫持。
     */
    @Test
    fun prefersMoreSpecificKeyringScopeOverBroaderHostSessionScope() {
        val keyring = FakeKeyringSecretBackend()
        val session = InMemoryRemoteAuthStore()
        val store = JvmRemoteAuthStore(sessionStore = session, keyringBackend = keyring)
        val aliceAuth = VfsAuthContext.UsernamePassword(username = "alice", password = "alice-keyring-password")
        val hostAuth = VfsAuthContext.UsernamePassword(username = "root", password = "host-session-password")

        // 先将精确路径凭据存入系统钥匙串，再清空会话模拟重启，随后在会话中打开根路径。
        assertEquals(
            RemoteCredentialSaveResult.STORED_IN_SYSTEM_KEYRING,
            store.put(VfsProtocol.WEBDAV, "webdavs://cloud.example/dav/alice/", aliceAuth, RemoteCredentialSavePolicy.SYSTEM_KEYRING),
        )
        store.clearSession()
        store.put(VfsProtocol.WEBDAV, "webdavs://cloud.example/", hostAuth, RemoteCredentialSavePolicy.SESSION)

        // 访问 /dav/alice 下子文件必须命中更精确的钥匙串凭据而非宽泛的根路径会话凭据。
        assertEquals(aliceAuth, store.authContext(VfsProtocol.WEBDAV, "webdavs://cloud.example/dav/alice/doc.txt"))
        assertEquals(aliceAuth, store.peekAuthContext(VfsProtocol.WEBDAV, "webdavs://cloud.example/dav/alice/doc.txt"))
        // 精确作用域查询 /dav/bob 不得回退窃取根路径密码。
        assertEquals(VfsAuthContext.None, store.peekExactAuthContext(VfsProtocol.WEBDAV, "webdavs://cloud.example/dav/bob/"))
        // 访问未单独配置的 /other 路径才回退命中根路径凭据。
        assertEquals(hostAuth, store.authContext(VfsProtocol.WEBDAV, "webdavs://cloud.example/other/file.txt"))
    }

    /**
     * 校验 `clearScope` 可精确清理历史遗留的仅主机钥匙串记录，避免删连接后旧凭据复活。
     */
    @Test
    fun clearsLegacyHostOnlyKeyringScopeCleanly() {
        val keyring = FakeKeyringSecretBackend()
        val store = JvmRemoteAuthStore(sessionStore = InMemoryRemoteAuthStore(), keyringBackend = keyring)
        val legacyAuth = VfsAuthContext.UsernamePassword(username = "legacy", password = "old-password")

        store.put(VfsProtocol.SMB, "smb://nas.local/", legacyAuth, RemoteCredentialSavePolicy.SYSTEM_KEYRING)
        store.clearSession()
        assertEquals(legacyAuth, store.authContext(VfsProtocol.SMB, "smb://nas.local/shareA/file.txt"))

        store.clearScope(RemoteAuthScope(VfsProtocol.SMB, "smb", "nas.local", ""))
        assertEquals(VfsAuthContext.None, store.authContext(VfsProtocol.SMB, "smb://nas.local/shareA/file.txt"))
    }

    private class FakeKeyringSecretBackend : JvmKeyringSecretBackend {
        private val secrets = mutableMapOf<RemoteAuthScope, String>()

        override fun isAvailable(): Boolean = true
        override fun lookup(scope: RemoteAuthScope): String? = secrets[scope]
        override fun store(scope: RemoteAuthScope, secret: String): Boolean {
            secrets[scope] = secret
            return true
        }
        override fun clear(scope: RemoteAuthScope): Boolean = secrets.remove(scope) != null
    }
}
