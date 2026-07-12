package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.app.component.toRemoteConnectionDraft
import com.oruke.onyx.app.component.toVfsProtocol
import com.oruke.onyx.core.model.RemoteConnectionProfile
import com.oruke.onyx.core.model.RemoteConnectionProtocol
import com.oruke.onyx.core.model.RemoteConnectionSavePolicy
import com.oruke.onyx.vfs.api.RemoteCredentialSavePolicy
import com.oruke.onyx.vfs.api.RemoteCredentialSaveResult
import com.oruke.onyx.vfs.api.RemoteKeyringAuthStore
import com.oruke.onyx.vfs.api.VfsAuthContext
import com.oruke.onyx.vfs.api.VfsProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
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
