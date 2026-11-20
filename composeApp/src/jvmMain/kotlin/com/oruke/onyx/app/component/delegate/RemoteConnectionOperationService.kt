package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.app.component.RemoteConnectionDraft
import com.oruke.onyx.app.component.RemoteConnectionTestState
import com.oruke.onyx.app.component.RootDialogState
import com.oruke.onyx.app.component.normalizedLocation
import com.oruke.onyx.app.component.toVfsProtocol
import com.oruke.onyx.core.model.RemoteConnectionProfile
import com.oruke.onyx.core.model.RemoteConnectionProtocol
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
 * 远程连接凭据同步结果。
 */
internal sealed interface RemoteCredentialSyncOutcome {
    /**
     * 凭据校验通过并完成同步。
     *
     * @property saveResult 实际写入结果；无需写入时为空。
     */
    data class Completed(val saveResult: RemoteCredentialSaveResult?) : RemoteCredentialSyncOutcome

    /** 编辑已有连接时存储中已无旧密钥可复用，必须由用户重新输入密钥。 */
    data object SecretRequired : RemoteCredentialSyncOutcome
}

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
     * @param otherConnections 除当前编辑项外的其余已保存连接列表。
     * @return 同步结果或异常。
     */
    suspend fun synchronizeCredentials(
        existing: RemoteConnectionProfile?,
        draft: RemoteConnectionDraft,
        normalizedLocation: String,
        otherConnections: List<RemoteConnectionProfile> = emptyList(),
    ): Result<RemoteCredentialSyncOutcome> = runSuspendCatching {
        withContext(Dispatchers.IO) {
            if (existing != null && credentialService.requiresSecretReentry(existing, draft)) {
                RemoteCredentialSyncOutcome.SecretRequired
            } else {
                RemoteCredentialSyncOutcome.Completed(
                    credentialService.synchronize(existing, draft, normalizedLocation, otherConnections),
                )
            }
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
                s3ConnectionConfig = draft.s3Config.takeIf {
                    draft.protocol == RemoteConnectionProtocol.S3
                },
            )
        }
        when (val result = connectionTestService.testConnection(request)) {
            is VfsConnectionTestResult.Reachable -> RemoteConnectionTestState.Reachable(
                capabilities = result.capabilities.toSet(),
            )

            is VfsConnectionTestResult.Failed -> RemoteConnectionTestState.Failed(
                reason = result.error.toI18nMessage(),
            )
        }
    }

    /**
     * 删除连接关联的会话与系统凭据（仅在无其他连接引用同一作用域时清理）。
     *
     * @param profile 即将删除的连接配置。
     * @param otherConnections 其余已保存连接列表。
     * @return 成功或异常结果。
     */
    suspend fun deleteCredentials(
        profile: RemoteConnectionProfile,
        otherConnections: List<RemoteConnectionProfile> = emptyList(),
    ): Result<Unit> = runSuspendCatching {
        withContext(Dispatchers.IO) { credentialService.delete(profile, otherConnections) }
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
            val authContext = if (dialog.protocol == com.oruke.onyx.vfs.api.VfsProtocol.S3) {
                VfsAuthContext.AwsCredentials(
                    accessKeyId = username,
                    secretAccessKey = dialog.draft.password,
                    sessionToken = dialog.draft.domain.trim().ifBlank { null },
                )
            } else {
                VfsAuthContext.UsernamePassword(
                    username = username,
                    password = dialog.draft.password,
                    domain = dialog.draft.domain.trim().ifBlank { null },
                )
            }
            remoteAuthStore.put(
                protocol = dialog.protocol,
                location = dialog.location,
                authContext = authContext,
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
