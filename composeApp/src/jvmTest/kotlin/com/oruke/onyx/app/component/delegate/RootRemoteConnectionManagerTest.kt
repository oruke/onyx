package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.app.component.RemoteConnectionDraft
import com.oruke.onyx.app.component.RemoteConnectionTestState
import com.oruke.onyx.app.component.RootDialogState
import com.oruke.onyx.core.model.RemoteConnectionProfile
import com.oruke.onyx.vfs.api.InMemoryRemoteAuthStore
import com.oruke.onyx.vfs.api.VfsConnectionTestRequest
import com.oruke.onyx.vfs.api.VfsConnectionTestResult
import com.oruke.onyx.vfs.api.VfsConnectionTestService
import com.oruke.onyx.vfs.api.VfsProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** 远程连接管理委托并发状态测试。 */
class RootRemoteConnectionManagerTest {
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
