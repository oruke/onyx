package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.app.component.RemoteConnectionDraft
import com.oruke.onyx.app.component.RemoteConnectionTestState
import com.oruke.onyx.app.component.RootDialogState
import com.oruke.onyx.app.component.normalizedLocation
import com.oruke.onyx.app.component.toVfsProtocol
import com.oruke.onyx.core.model.RemoteConnectionProfile
import com.oruke.onyx.shared.filesystem.toI18nMessage
import com.oruke.onyx.vfs.api.RemoteAuthStore
import com.oruke.onyx.vfs.api.RemoteCredentialSaveResult
import com.oruke.onyx.vfs.api.VfsAuthContext
import com.oruke.onyx.vfs.api.VfsConnectionTestRequest
import com.oruke.onyx.vfs.api.VfsConnectionTestResult
import com.oruke.onyx.vfs.api.VfsConnectionTestService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 远程连接阻塞操作服务，统一隔离凭据存储和连接测试的 IO 调度。
 *
 * 业务失败返回 [Result]，协程取消始终继续向上传播。
 */
internal class RemoteConnectionOperationService(
    /** 统一远程连接测试服务。 */
    private val connectionTestService: VfsConnectionTestService,
    /** 统一远程凭据存储。 */
    private val remoteAuthStore: RemoteAuthStore,
) {
    /** 凭据作用域与保存策略协调服务。 */
    private val credentialService = RemoteConnectionCredentialService(remoteAuthStore)

    /**
     * 同步网络位置编辑产生的凭据变化。
     *
     * @param existing 编辑前的连接；新建连接时为空。
     * @param draft 当前连接草稿。
     * @param normalizedLocation 规范化后的连接位置。
     * @return 写入结果或异常。
     */
    suspend fun synchronizeCredentials(
        existing: RemoteConnectionProfile?,
        draft: RemoteConnectionDraft,
        normalizedLocation: String,
    ): Result<RemoteCredentialSaveResult?> = runSuspendCatching {
        withContext(Dispatchers.IO) {
            credentialService.synchronize(existing, draft, normalizedLocation)
        }
    }

    /**
     * 使用草稿或已有存储凭据测试连接。
     *
     * @param existing 编辑前的连接；新建连接时为空。
     * @param draft 当前连接草稿。
     * @return 可直接写回组件状态的测试结果或异常。
     */
    suspend fun testConnection(
        existing: RemoteConnectionProfile?,
        draft: RemoteConnectionDraft,
    ): Result<RemoteConnectionTestState> = runSuspendCatching {
        val request = withContext(Dispatchers.IO) {
            VfsConnectionTestRequest(
                protocol = draft.protocol.toVfsProtocol(),
                location = draft.normalizedLocation(),
                authContext = credentialService.authContextForTest(existing, draft),
            )
        }
        when (val result = connectionTestService.testConnection(request)) {
            is VfsConnectionTestResult.Reachable -> RemoteConnectionTestState.Reachable(
                capabilities = result.capabilities.mapTo(sortedSetOf()) { capability -> capability.name },
            )

            is VfsConnectionTestResult.Failed -> RemoteConnectionTestState.Failed(
                reason = result.error.toI18nMessage(),
            )
        }
    }

    /**
     * 删除连接关联的会话与系统凭据。
     *
     * @param profile 即将删除的连接配置。
     * @return 成功或异常结果。
     */
    suspend fun deleteCredentials(profile: RemoteConnectionProfile): Result<Unit> = runSuspendCatching {
        withContext(Dispatchers.IO) { credentialService.delete(profile) }
    }

    /**
     * 保存即时认证窗口中的用户名密码。
     *
     * @param dialog 即时认证上下文和草稿。
     * @param username 已去除首尾空白的用户名。
     * @return 凭据写入结果或异常。
     */
    suspend fun submitCredentials(
        dialog: RootDialogState.RemoteCredentials,
        username: String,
    ): Result<RemoteCredentialSaveResult> = runSuspendCatching {
        withContext(Dispatchers.IO) {
            remoteAuthStore.put(
                protocol = dialog.protocol,
                location = dialog.location,
                authContext = VfsAuthContext.UsernamePassword(
                    username = username,
                    password = dialog.draft.password,
                    domain = dialog.draft.domain.trim().ifBlank { null },
                ),
                savePolicy = dialog.draft.savePolicy,
            )
        }
    }
}

/**
 * 执行可挂起操作并把非取消异常转换为 [Result]。
 *
 * @param block 待执行操作。
 * @return 成功值或非取消异常。
 */
private suspend fun <T> runSuspendCatching(block: suspend () -> T): Result<T> {
    val result = runCatching { block() }
    val failure = result.exceptionOrNull()
    if (failure is CancellationException) throw failure
    return result
}
