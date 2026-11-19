package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.app.component.RemoteConnectionDialogError
import com.oruke.onyx.app.component.RemoteConnectionDraft
import com.oruke.onyx.app.component.RemoteConnectionTestState
import com.oruke.onyx.app.component.RootDialogState
import com.oruke.onyx.app.component.toRemoteConnectionDraft
import com.oruke.onyx.core.model.RemoteConnectionProfile
import com.oruke.onyx.core.model.RemoteConnectionProtocol
import com.oruke.onyx.core.model.RemoteConnectionSavePolicy
import com.oruke.onyx.core.model.S3AddressingStyle
import com.oruke.onyx.core.model.S3ProviderPreset
import com.oruke.onyx.vfs.api.InMemoryRemoteAuthStore
import com.oruke.onyx.vfs.api.VfsConnectionTestRequest
import com.oruke.onyx.vfs.api.VfsConnectionTestResult
import com.oruke.onyx.vfs.api.VfsConnectionTestService
import com.oruke.onyx.vfs.api.VfsProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

/** 远程连接管理委托并发状态测试。 */
class RootRemoteConnectionManagerTest {
    /** 校验组件层在跨协议族切换时隔离旧地址和凭据。 */
    @Test
    fun isolatesCredentialsWhenProtocolChanges() = runTest {
        val initialDraft = RemoteConnectionDraft(
            protocol = RemoteConnectionProtocol.SMB,
            location = "smb://server/share/",
            username = "user",
            secret = "password",
            domain = "DOMAIN",
        )
        val dialogState = MutableStateFlow<RootDialogState?>(
            RootDialogState.RemoteConnections(remoteConnectionDraft = initialDraft),
        )
        val manager = createManager(this, dialogState, DelayedConnectionTestService())

        manager.updateRemoteConnectionDraft(initialDraft.copy(protocol = RemoteConnectionProtocol.S3))

        val updated = (dialogState.value as RootDialogState.RemoteConnections).remoteConnectionDraft
        assertEquals(RemoteConnectionProtocol.S3, updated.protocol)
        assertEquals("", updated.location)
        assertEquals("", updated.username)
        assertEquals("", updated.secret)
        assertEquals("", updated.domain)
        assertEquals(true, updated.secretChanged)
    }

    /** 验证切换 S3 服务商时由组件层恢复该预设的 Endpoint 与寻址默认值。 */
    @Test
    fun appliesProviderDefaultsWhenS3PresetChanges() = runTest {
        val initialDraft = RemoteConnectionDraft(
            protocol = RemoteConnectionProtocol.S3,
            location = "s3://sample-bucket/",
        )
        val dialogState = MutableStateFlow<RootDialogState?>(
            RootDialogState.RemoteConnections(remoteConnectionDraft = initialDraft),
        )
        val manager = createManager(this, dialogState, DelayedConnectionTestService())

        manager.updateRemoteConnectionDraft(
            initialDraft.copy(
                s3Config = initialDraft.s3Config.copy(
                    provider = S3ProviderPreset.MINIO,
                    endpoint = "https://stale-endpoint.example.test",
                ),
            ),
        )

        val updated = (dialogState.value as RootDialogState.RemoteConnections).remoteConnectionDraft.s3Config
        assertEquals(S3ProviderPreset.MINIO, updated.provider)
        assertEquals("", updated.endpoint)
        assertEquals("us-east-1", updated.region)
        assertEquals(S3AddressingStyle.PATH_STYLE, updated.addressingStyle)
    }

    /**
     * 校验用户修改草稿后，已取消测试的旧结果不能覆盖当前状态。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun ignoresStaleConnectionTestResultAfterDraftChanges() = runTest {
        val dialogState = MutableStateFlow<RootDialogState?>(RootDialogState.RemoteConnections())
        val testService = DelayedConnectionTestService()
        val manager = createManager(this, dialogState, testService)
        val firstDraft = RemoteConnectionDraft(
            name = "First",
            location = "smb://host/first/",
        )
        manager.updateRemoteConnectionDraft(firstDraft)
        manager.testRemoteConnectionDraft()
        testService.started.await()

        val secondDraft = firstDraft.copy(name = "Second", location = "smb://host/second/")
        manager.updateRemoteConnectionDraft(secondDraft)
        testService.result.complete(
            VfsConnectionTestResult.Reachable(
                protocol = VfsProtocol.SMB,
                location = firstDraft.location,
                capabilities = emptySet(),
            )
        )
        advanceUntilIdle()

        val latest = dialogState.value as RootDialogState.RemoteConnections
        assertEquals(secondDraft, latest.remoteConnectionDraft)
        assertEquals(RemoteConnectionTestState.Idle, latest.remoteConnectionTestState)
    }

    /**
     * 校验编辑已有连接且当前存储中无旧凭据时，将保存策略升级为系统钥匙串但不重填密码会被拦截并报 `SECRET_EMPTY`。
     */
    @Test
    fun rejectsPolicyUpgradeWithoutSecretWhenStoredCredentialIsMissing() = runBlocking {
        val existing = RemoteConnectionProfile(
            id = "conn-1",
            name = "Office NAS",
            protocol = RemoteConnectionProtocol.SMB,
            location = "smb://nas.local/shareA/",
            username = "alice",
            savePolicy = RemoteConnectionSavePolicy.SESSION,
        )
        var connections = listOf(existing)
        val dialogState = MutableStateFlow<RootDialogState?>(
            RootDialogState.RemoteConnections(
                editingRemoteConnectionId = existing.id,
                remoteConnectionDraft = existing.toRemoteConnectionDraft().copy(
                    savePolicy = RemoteConnectionSavePolicy.SYSTEM_KEYRING,
                ),
            ),
        )
        val manager = RootRemoteConnectionManager(
            scope = this,
            connectionTestService = DelayedConnectionTestService(),
            remoteAuthStore = InMemoryRemoteAuthStore(),
            dialogState = dialogState,
            remoteConnections = { connections },
            replaceRemoteConnections = { next -> connections = next },
            paneState = { error("Pane state is not used by this test") },
            paneComponent = { error("Pane component is not used by this test") },
            openLocationInActivePane = { error("Open location is not used by this test") },
        )

        manager.saveRemoteConnectionDraft()
        withTimeout(5_000L) {
            while ((dialogState.value as? RootDialogState.RemoteConnections)?.saving == true) {
                delay(10L)
            }
        }

        val latest = dialogState.value as RootDialogState.RemoteConnections
        assertEquals(RemoteConnectionDialogError.SECRET_EMPTY, latest.remoteConnectionError)
        assertEquals(RemoteConnectionSavePolicy.SESSION, connections.single().savePolicy)
    }

    /**
     * 校验快速并发删除两个共享同一作用域的书签时串行清理，不得双双跳过导致凭据泄漏。
     */
    @Test
    fun concurrentDeletionOfSharedScopeBookmarksStillClearsCredentials() = runBlocking {
        val store = InMemoryRemoteAuthStore()
        val first = RemoteConnectionProfile(
            id = "conn-a",
            name = "NAS A",
            protocol = RemoteConnectionProtocol.SMB,
            location = "smb://nas.local/shareA/",
            username = "alice",
            savePolicy = RemoteConnectionSavePolicy.SESSION,
        )
        val second = first.copy(id = "conn-b", name = "NAS A Copy")
        store.put(
            protocol = VfsProtocol.SMB,
            location = first.location,
            authContext = com.oruke.onyx.vfs.api.VfsAuthContext.UsernamePassword("alice", "pw"),
            savePolicy = com.oruke.onyx.vfs.api.RemoteCredentialSavePolicy.SESSION,
        )
        var connections = listOf(first, second)
        val dialogState = MutableStateFlow<RootDialogState?>(RootDialogState.RemoteConnections())
        val manager = RootRemoteConnectionManager(
            scope = this,
            connectionTestService = DelayedConnectionTestService(),
            remoteAuthStore = store,
            dialogState = dialogState,
            remoteConnections = { connections },
            replaceRemoteConnections = { next -> connections = next },
            paneState = { error("Pane state is not used by this test") },
            paneComponent = { error("Pane component is not used by this test") },
            openLocationInActivePane = { error("Open location is not used by this test") },
        )

        manager.deleteRemoteConnection(first.id)
        manager.deleteRemoteConnection(second.id)
        withTimeout(5_000L) {
            while (connections.isNotEmpty()) {
                delay(10L)
            }
        }

        assertEquals(
            com.oruke.onyx.vfs.api.VfsAuthContext.None,
            store.peekAuthContext(VfsProtocol.SMB, first.location),
        )
    }

    /**
     * 创建只启用连接测试路径的管理委托。
     *
     * @param scope 测试协程作用域。
     * @param dialogState 对话框状态。
     * @param testService 可控连接测试服务。
     * @return 测试用管理委托。
     */
    private fun createManager(
        scope: CoroutineScope,
        dialogState: MutableStateFlow<RootDialogState?>,
        testService: VfsConnectionTestService,
    ): RootRemoteConnectionManager {
        var connections = emptyList<RemoteConnectionProfile>()
        return RootRemoteConnectionManager(
            scope = scope,
            connectionTestService = testService,
            remoteAuthStore = InMemoryRemoteAuthStore(),
            dialogState = dialogState,
            remoteConnections = { connections },
            replaceRemoteConnections = { next -> connections = next },
            paneState = { error("Pane state is not used by this test") },
            paneComponent = { error("Pane component is not used by this test") },
            openLocationInActivePane = { error("Open location is not used by this test") },
        )
    }

    /** 提供可控完成时机的连接测试服务。 */
    private class DelayedConnectionTestService : VfsConnectionTestService {
        /** 测试请求已经进入服务的信号。 */
        val started = CompletableDeferred<Unit>()

        /** 测试调用最终返回值。 */
        val result = CompletableDeferred<VfsConnectionTestResult>()

        /**
         * 等待测试代码提供连接测试结果。
         *
         * @param request 当前连接测试请求。
         * @return 测试代码提供的结果。
         */
        override suspend fun testConnection(request: VfsConnectionTestRequest): VfsConnectionTestResult {
            started.complete(Unit)
            return result.await()
        }
    }
}
