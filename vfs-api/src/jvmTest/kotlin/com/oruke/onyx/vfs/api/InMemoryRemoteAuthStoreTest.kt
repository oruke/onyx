package com.oruke.onyx.vfs.api

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 内存远程认证存储作用域隔离与非消费查询回归测试。
 */
class InMemoryRemoteAuthStoreTest {
    /**
     * 同一主机下的不同 SMB 共享必须按共享路径隔离，写入与删除互不影响。
     */
    @Test
    fun isolatesSmbSharesOnSameHostAndMatchesNestedPaths() {
        val store = InMemoryRemoteAuthStore()
        val shareAAuth = VfsAuthContext.UsernamePassword(username = "alice", password = "secret-a")
        val shareBAuth = VfsAuthContext.UsernamePassword(username = "bob", password = "secret-b")

        store.put(VfsProtocol.SMB, "smb://nas.local/shareA/", shareAAuth, RemoteCredentialSavePolicy.SESSION)
        store.put(VfsProtocol.SMB, "smb://nas.local/shareB/", shareBAuth, RemoteCredentialSavePolicy.SESSION)

        assertEquals(shareAAuth, store.authContext(VfsProtocol.SMB, "smb://nas.local/shareA/docs/file.txt"))
        assertEquals(shareBAuth, store.authContext(VfsProtocol.SMB, "smb://nas.local/shareB/media/pic.png"))

        // 删除 shareB 不得影响 shareA 的凭据。
        store.clear(VfsProtocol.SMB, "smb://nas.local/shareB/")
        assertEquals(VfsAuthContext.None, store.authContext(VfsProtocol.SMB, "smb://nas.local/shareB/"))
        assertEquals(shareAAuth, store.authContext(VfsProtocol.SMB, "smb://nas.local/shareA/"))
    }

    /**
     * 同一主机下的不同 WebDAV 子路径支持最长前缀匹配与旧版主机兜底兼容。
     */
    @Test
    fun resolvesWebDavByLongestPrefixAndFallsBackToHostOnlyScope() {
        val store = InMemoryRemoteAuthStore()
        val hostAuth = VfsAuthContext.UsernamePassword(username = "default", password = "host-password")
        val aliceAuth = VfsAuthContext.UsernamePassword(username = "alice", password = "alice-password")
        val bobAuth = VfsAuthContext.UsernamePassword(username = "bob", password = "bob-password")

        store.put(VfsProtocol.WEBDAV, "webdavs://cloud.example/", hostAuth, RemoteCredentialSavePolicy.SESSION)
        store.put(VfsProtocol.WEBDAV, "webdavs://cloud.example/dav/alice/", aliceAuth, RemoteCredentialSavePolicy.SESSION)
        store.put(VfsProtocol.WEBDAV, "webdavs://cloud.example/dav/bob/", bobAuth, RemoteCredentialSavePolicy.SESSION)

        assertEquals(aliceAuth, store.authContext(VfsProtocol.WEBDAV, "webdavs://cloud.example/dav/alice/sub/a.txt"))
        assertEquals(bobAuth, store.authContext(VfsProtocol.WEBDAV, "webdavs://cloud.example/dav/bob/b.txt"))
        assertEquals(hostAuth, store.authContext(VfsProtocol.WEBDAV, "webdavs://cloud.example/other/c.txt"))
    }

    /**
     * `peekAuthContext` 查询一次性 `DO_NOT_SAVE` 凭据时不得提前消费它。
     */
    @Test
    fun peekDoesNotConsumeTransientDoNotSaveCredential() {
        val store = InMemoryRemoteAuthStore()
        val transientAuth = VfsAuthContext.UsernamePassword(username = "temp", password = "once-password")

        store.put(
            protocol = VfsProtocol.SMB,
            location = "smb://nas.local/temp/",
            authContext = transientAuth,
            savePolicy = RemoteCredentialSavePolicy.DO_NOT_SAVE,
        )

        // 模拟多次测试连接/复用校验：peek 不消耗。
        assertEquals(transientAuth, store.peekAuthContext(VfsProtocol.SMB, "smb://nas.local/temp/"))
        assertEquals(transientAuth, store.peekAuthContext(VfsProtocol.SMB, "smb://nas.local/temp/"))
        // 随后实际打开连接读取一次仍然可用，并在消费后清空。
        assertEquals(transientAuth, store.authContext(VfsProtocol.SMB, "smb://nas.local/temp/"))
        assertEquals(VfsAuthContext.None, store.authContext(VfsProtocol.SMB, "smb://nas.local/temp/"))
    }
}
