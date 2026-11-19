package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.app.component.toRemoteConnectionDraft
import com.oruke.onyx.app.component.toVfsProtocol
import com.oruke.onyx.core.model.RemoteConnectionProfile
import com.oruke.onyx.core.model.RemoteConnectionProtocol
import com.oruke.onyx.core.model.RemoteConnectionSavePolicy
import com.oruke.onyx.vfs.api.InMemoryRemoteAuthStore
import com.oruke.onyx.vfs.api.RemoteCredentialSavePolicy
import com.oruke.onyx.vfs.api.RemoteCredentialSaveResult
import com.oruke.onyx.vfs.api.RemoteKeyringAuthStore
import com.oruke.onyx.vfs.api.VfsAuthContext
import com.oruke.onyx.vfs.api.VfsProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 远程连接凭据协调服务回归测试。 */
class RemoteConnectionCredentialServiceTest {
    /**
     * 校验只修改连接名称不会用空密码覆盖系统钥匙串。
     */
    @Test
    fun preservesStoredSecretWhenOnlyConnectionNameChanges() {
        val store = RecordingRemoteAuthStore().apply { seed(PROFILE, STORED_AUTH) }
        val service = RemoteConnectionCredentialService(store)
        val draft = PROFILE.toRemoteConnectionDraft().copy(name = "Renamed")

        val result = service.synchronize(PROFILE, draft, PROFILE.location)

        assertNull(result)
        assertTrue(store.putCalls.isEmpty())
        assertEquals(STORED_AUTH, store.authContext(VfsProtocol.SMB, PROFILE.location))
    }

    /**
     * 校验修改用户名时会保留原密码并更新凭据元数据。
     */
    @Test
    fun preservesStoredSecretWhenCredentialMetadataChanges() {
        val store = RecordingRemoteAuthStore().apply { seed(PROFILE, STORED_AUTH) }
        val service = RemoteConnectionCredentialService(store)
        val draft = PROFILE.toRemoteConnectionDraft().copy(username = "next-user")

        service.synchronize(PROFILE, draft, PROFILE.location)

        val saved = store.putCalls.single().authContext as VfsAuthContext.UsernamePassword
        assertEquals("next-user", saved.username)
        assertEquals("stored-password", saved.password)
    }

    /**
     * 校验切换为不保存策略时会删除旧会话和系统凭据。
     */
    @Test
    fun clearsStoredCredentialWhenPolicyChangesToDoNotSave() {
        val store = RecordingRemoteAuthStore().apply { seed(PROFILE, STORED_AUTH) }
        val service = RemoteConnectionCredentialService(store)
        val draft = PROFILE.toRemoteConnectionDraft().copy(
            savePolicy = RemoteConnectionSavePolicy.DO_NOT_SAVE,
        )

        service.synchronize(PROFILE, draft, PROFILE.location)

        assertEquals(listOf(VfsProtocol.SMB to PROFILE.location), store.clearCalls)
        assertTrue(store.putCalls.isEmpty())
        assertEquals(VfsAuthContext.None, store.authContext(VfsProtocol.SMB, PROFILE.location))
    }

    /**
     * 校验用户明确修改密码后才会写入新的密钥值。
     */
    @Test
    fun writesSecretOnlyWhenUserChangesSecretField() {
        val store = RecordingRemoteAuthStore().apply { seed(PROFILE, STORED_AUTH) }
        val service = RemoteConnectionCredentialService(store)
        val draft = PROFILE.toRemoteConnectionDraft().copy(
            secret = "next-password",
            secretChanged = true,
        )

        service.synchronize(PROFILE, draft, PROFILE.location)

        val saved = store.putCalls.single().authContext as VfsAuthContext.UsernamePassword
        assertEquals("next-password", saved.password)
    }

    /**
     * 校验已有连接测试会读取旧凭据，而不是构造空密码。
     */
    @Test
    fun resolvesStoredSecretForExistingConnectionTest() {
        val store = RecordingRemoteAuthStore().apply { seed(PROFILE, STORED_AUTH) }
        val service = RemoteConnectionCredentialService(store)

        val authContext = service.authContextForTest(PROFILE, PROFILE.toRemoteConnectionDraft())

        assertEquals(STORED_AUTH, authContext)
    }

    /**
     * 校验测试 `DO_NOT_SAVE` 连接不会提前消费一次性凭据，后续打开连接仍可拿到有效凭据。
     */
    @Test
    fun testingDoNotSaveConnectionDoesNotConsumeTransientCredential() {
        val store = InMemoryRemoteAuthStore()
        val service = RemoteConnectionCredentialService(store)
        val transientProfile = PROFILE.copy(savePolicy = RemoteConnectionSavePolicy.DO_NOT_SAVE)
        store.put(
            protocol = VfsProtocol.SMB,
            location = transientProfile.location,
            authContext = STORED_AUTH,
            savePolicy = RemoteCredentialSavePolicy.DO_NOT_SAVE,
        )

        val tested = service.authContextForTest(transientProfile, transientProfile.toRemoteConnectionDraft())
        assertEquals(STORED_AUTH, tested)
        // 紧接着实际打开连接仍可消费到同一份一次性凭据。
        assertEquals(STORED_AUTH, store.authContext(VfsProtocol.SMB, transientProfile.location))
    }

    /**
     * 校验存在同作用域其他连接时，删除当前连接不得误清其余连接共享的凭据；
     * 同时校验升级保存策略且存储中无旧密钥时必须要求重新输入密钥。
     */
    @Test
    fun preservesSharedScopeCredentialsOnDeleteAndRequiresSecretWhenUpgradingEmptyPolicy() {
        val store = InMemoryRemoteAuthStore()
        val service = RemoteConnectionCredentialService(store)
        val siblingProfile = PROFILE.copy(id = "remote-id-2", name = "Duplicate Bookmark")
        store.put(VfsProtocol.SMB, PROFILE.location, STORED_AUTH, RemoteCredentialSavePolicy.SESSION)

        service.delete(PROFILE, otherConnections = listOf(siblingProfile))
        assertEquals(STORED_AUTH, store.peekAuthContext(VfsProtocol.SMB, siblingProfile.location))

        service.delete(siblingProfile, otherConnections = emptyList())
        assertEquals(VfsAuthContext.None, store.peekAuthContext(VfsProtocol.SMB, siblingProfile.location))

        val noSecretSession = PROFILE.copy(savePolicy = RemoteConnectionSavePolicy.SESSION)
        val upgradedDraft = noSecretSession.toRemoteConnectionDraft().copy(
            savePolicy = RemoteConnectionSavePolicy.SYSTEM_KEYRING,
        )
        assertTrue(service.requiresSecretReentry(noSecretSession, upgradedDraft))
        assertFalse(service.requiresSecretReentry(noSecretSession, upgradedDraft.copy(secret = "pw", secretChanged = true)))
    }

    /**
     * 校验编辑子路径连接时不得通过父路径回退盗用根连接密码，同时删除主机上最后一个连接会顺带清理旧版仅主机凭据。
     */
    @Test
    fun doesNotStealParentScopePasswordOnEditAndClearsUnreferencedLegacyHost() {
        val store = InMemoryRemoteAuthStore()
        val service = RemoteConnectionCredentialService(store)
        val rootAuth = VfsAuthContext.UsernamePassword(username = "root", password = "root-password")
        store.put(VfsProtocol.WEBDAV, "webdavs://cloud.example/", rootAuth, RemoteCredentialSavePolicy.SESSION)

        val childProfile = RemoteConnectionProfile(
            id = "alice-dav",
            name = "Alice DAV",
            protocol = RemoteConnectionProtocol.WEBDAV,
            location = "webdavs://cloud.example/dav/alice/",
            username = "alice",
            savePolicy = RemoteConnectionSavePolicy.DO_NOT_SAVE,
        )
        val upgradeChildDraft = childProfile.toRemoteConnectionDraft().copy(
            savePolicy = RemoteConnectionSavePolicy.SESSION,
        )
        // 子路径本身无精确凭据，必须要求重填密码而非盗用父路径 root-password。
        assertTrue(service.requiresSecretReentry(childProfile, upgradeChildDraft))

        // 当主机上最后一个连接被删除且无其他连接引用同一主机时，历史仅主机凭据也会被清理。
        service.delete(childProfile, otherConnections = emptyList())
        assertEquals(VfsAuthContext.None, store.peekAuthContext(VfsProtocol.WEBDAV, "webdavs://cloud.example/"))
    }

    /** 记录一次凭据写入调用。 */
    private data class PutCall(
        /** 连接协议。 */
        val protocol: VfsProtocol,
        /** 连接位置。 */
        val location: String,
        /** 写入的认证上下文。 */
        val authContext: VfsAuthContext,
        /** 写入策略。 */
        val savePolicy: RemoteCredentialSavePolicy,
    )

    /** 可记录凭据读写行为的内存测试存储。 */
    private class RecordingRemoteAuthStore : RemoteKeyringAuthStore {
        /** 当前内存凭据。 */
        private val contexts = mutableMapOf<Pair<VfsProtocol, String>, VfsAuthContext>()

        /** 所有写入调用。 */
        val putCalls = mutableListOf<PutCall>()

        /** 所有清理调用。 */
        val clearCalls = mutableListOf<Pair<VfsProtocol, String>>()

        /**
         * 写入测试初始凭据。
         *
         * @param profile 凭据所属连接。
         * @param authContext 初始认证上下文。
         */
        fun seed(profile: RemoteConnectionProfile, authContext: VfsAuthContext) {
            contexts[profile.protocol.toVfsProtocol() to profile.location] = authContext
        }

        /**
         * 读取当前测试凭据。
         *
         * @param protocol 连接协议。
         * @param location 连接位置。
         * @return 当前认证上下文。
         */
        override fun authContext(protocol: VfsProtocol, location: String): VfsAuthContext {
            return contexts[protocol to location] ?: VfsAuthContext.None
        }

        /**
         * 记录并保存测试凭据。
         *
         * @param protocol 连接协议。
         * @param location 连接位置。
         * @param authContext 认证上下文。
         * @param savePolicy 保存策略。
         * @return 对应的成功结果。
         */
        override fun put(
            protocol: VfsProtocol,
            location: String,
            authContext: VfsAuthContext,
            savePolicy: RemoteCredentialSavePolicy,
        ): RemoteCredentialSaveResult {
            putCalls += PutCall(protocol, location, authContext, savePolicy)
            contexts[protocol to location] = authContext
            return when (savePolicy) {
                RemoteCredentialSavePolicy.DO_NOT_SAVE -> RemoteCredentialSaveResult.AVAILABLE_FOR_CURRENT_REQUEST
                RemoteCredentialSavePolicy.SESSION -> RemoteCredentialSaveResult.STORED_FOR_SESSION
                RemoteCredentialSavePolicy.SYSTEM_KEYRING -> RemoteCredentialSaveResult.STORED_IN_SYSTEM_KEYRING
            }
        }

        /**
         * 清理指定测试凭据。
         *
         * @param protocol 连接协议。
         * @param location 连接位置。
         */
        override fun clear(protocol: VfsProtocol, location: String) {
            clearCalls += protocol to location
            contexts.remove(protocol to location)
        }

        /** 清理全部测试凭据。 */
        override fun clearSession() {
            contexts.clear()
        }

        /** @return 测试存储始终支持系统钥匙串。 */
        override fun isSystemKeyringAvailable(): Boolean = true
    }

    private companion object {
        /** 测试连接配置。 */
        val PROFILE = RemoteConnectionProfile(
            id = "remote-id",
            name = "Remote",
            protocol = RemoteConnectionProtocol.SMB,
            location = "smb://host/share/",
            username = "stored-user",
            domain = "DOMAIN",
            savePolicy = RemoteConnectionSavePolicy.SYSTEM_KEYRING,
        )

        /** 测试连接原始认证上下文。 */
        val STORED_AUTH = VfsAuthContext.UsernamePassword(
            username = "stored-user",
            password = "stored-password",
            domain = "DOMAIN",
        )
    }
}
