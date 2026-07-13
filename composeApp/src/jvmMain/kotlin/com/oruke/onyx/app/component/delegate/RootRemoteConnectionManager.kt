package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.app.OnyxLogger
import com.oruke.onyx.app.component.PaneComponent
import com.oruke.onyx.app.component.PaneState
import com.oruke.onyx.app.component.RemoteConnectionDialogError
import com.oruke.onyx.app.component.RemoteConnectionDraft
import com.oruke.onyx.app.component.RemoteConnectionLocation
import com.oruke.onyx.app.component.RemoteConnectionTestState
import com.oruke.onyx.app.component.RemoteCredentialsDialogError
import com.oruke.onyx.app.component.RemoteCredentialsDraft
import com.oruke.onyx.app.component.RootDialogState
import com.oruke.onyx.app.component.normalizedLocation
import com.oruke.onyx.app.component.refresh
import com.oruke.onyx.app.component.toRemoteConnectionDraft
import com.oruke.onyx.app.component.toRemoteConnectionProfile
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.RemoteConnectionProfile
import com.oruke.onyx.core.model.defaultS3ConnectionConfig
import com.oruke.onyx.shared.filesystem.toI18nMessage
import com.oruke.onyx.vfs.api.RemoteAuthStore
import com.oruke.onyx.vfs.api.RemoteCredentialSaveResult
import com.oruke.onyx.vfs.api.VfsConnectionTestService
import com.oruke.onyx.vfs.api.VfsProviderError
import com.oruke.onyx.vfs.api.VfsProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 远程连接管理委托 — 负责远程连接配置的 CRUD、测试、凭据提交。
 *
 * 从 DefaultRootComponent 剥离的纯业务逻辑。
 */
internal class RootRemoteConnectionManager(
    private val scope: CoroutineScope,
    private val connectionTestService: VfsConnectionTestService,
    private val remoteAuthStore: RemoteAuthStore,
    private val dialogState: MutableStateFlow<RootDialogState?>,
    private val remoteConnections: () -> List<RemoteConnectionProfile>,
    private val replaceRemoteConnections: (List<RemoteConnectionProfile>) -> Unit,
    private val paneState: (PaneId) -> PaneState,
    private val paneComponent: (PaneId) -> PaneComponent,
    private val openLocationInActivePane: (String) -> Unit,
) {
    /** 远程凭据与连接测试的 IO 操作服务。 */
    private val operationService = RemoteConnectionOperationService(connectionTestService, remoteAuthStore)

    /** 当前连接测试任务。 */
    private var connectionTestJob: Job? = null

    /** 当前连接测试运行标识，用于拒绝过期结果。 */
    private var connectionTestRunId: String? = null

    /** 当前网络位置保存任务。 */
    private var connectionSaveJob: Job? = null

    /** 当前网络位置保存运行标识。 */
    private var connectionSaveRunId: String? = null

    /** 当前即时凭据提交任务。 */
    private var credentialSubmitJob: Job? = null

    /** 当前即时凭据提交运行标识。 */
    private var credentialSubmitRunId: String? = null

    /**
     * 打开网络位置管理窗口。
     */
    fun openRemoteConnections() {
        cancelCredentialSubmission()
        dialogState.value = RootDialogState.RemoteConnections()
    }

    /**
     * 更新网络位置编辑草稿。
     *
     * @param draft 用户当前输入的网络位置配置草稿。
     */
    fun updateRemoteConnectionDraft(draft: RemoteConnectionDraft) {
        val currentDialog = dialogState.value as? RootDialogState.RemoteConnections ?: return
        cancelConnectionTest()
        val currentDraft = currentDialog.remoteConnectionDraft
        val nextDraft = when {
            currentDraft.protocol != draft.protocol -> {
                RemoteConnectionLocation.switchProtocol(currentDraft, draft.protocol)
            }

            currentDraft.s3Config.provider != draft.s3Config.provider -> {
                draft.copy(s3Config = draft.s3Config.provider.defaultS3ConnectionConfig())
            }

            else -> draft
        }
        dialogState.value = currentDialog.copy(
            remoteConnectionDraft = nextDraft,
            remoteConnectionError = null,
            remoteConnectionTestState = RemoteConnectionTestState.Idle,
        )
    }

    /**
     * 进入指定网络位置的编辑模式。
     *
     * @param profile 需要编辑的网络位置配置。
     */
    fun editRemoteConnection(profile: RemoteConnectionProfile) {
        cancelConnectionTest()
        val currentDialog = dialogState.value as? RootDialogState.RemoteConnections
        dialogState.value = (currentDialog ?: RootDialogState.RemoteConnections()).copy(
            editingRemoteConnectionId = profile.id,
            remoteConnectionDraft = profile.toRemoteConnectionDraft(),
            remoteConnectionError = null,
            remoteConnectionTestState = RemoteConnectionTestState.Idle,
        )
    }

    /**
     * 准备新建网络位置。
     */
    fun newRemoteConnection() {
        cancelConnectionTest()
        val currentDialog = dialogState.value as? RootDialogState.RemoteConnections
        dialogState.value = (currentDialog ?: RootDialogState.RemoteConnections()).copy(
            editingRemoteConnectionId = null,
            remoteConnectionDraft = RemoteConnectionDraft(),
            remoteConnectionError = null,
            remoteConnectionTestState = RemoteConnectionTestState.Idle,
        )
    }

    /**
     * 保存当前网络位置草稿，并将非密钥字段写回缓存系统中的连接列表。
     */
    fun saveRemoteConnectionDraft() {
        val currentDialog = dialogState.value as? RootDialogState.RemoteConnections
        if (currentDialog != null && connectionSaveJob?.isActive != true) {
            val draft = currentDialog.remoteConnectionDraft
            val existing = currentDialog.editingRemoteConnectionId?.let { editingId ->
                remoteConnections().firstOrNull { connection -> connection.id == editingId }
            }
            val validationError = draft.saveValidationError(
                canReuseStoredSecret = existing != null && !draft.secretChanged,
            )
            if (validationError != null) {
                dialogState.value = currentDialog.copy(remoteConnectionError = validationError)
            } else {
                val location = draft.normalizedLocation()
                val profile = draft.toRemoteConnectionProfile(
                    id = currentDialog.editingRemoteConnectionId ?: UUID.randomUUID().toString(),
                    location = location,
                )
                val runId = UUID.randomUUID().toString()
                connectionSaveRunId = runId
                dialogState.value = currentDialog.copy(saving = true, remoteConnectionError = null)
                connectionSaveJob = scope.launch {
                    try {
                        val outcome = operationService.synchronizeCredentials(existing, draft, location)
                        if (connectionSaveRunId == runId) {
                            val failure = outcome.exceptionOrNull()
                            when {
                                failure != null -> {
                                    OnyxLogger.error(
                                        "RootRemoteConnectionManager",
                                        "保存远程连接凭据失败",
                                        failure,
                                    )
                                    updateConnectionSaveError(
                                        runId,
                                        RemoteConnectionDialogError.CREDENTIAL_SAVE_FAILED,
                                    )
                                }

                                outcome.getOrNull() == RemoteCredentialSaveResult.UNSUPPORTED -> {
                                    updateConnectionSaveError(
                                        runId,
                                        RemoteConnectionDialogError.SYSTEM_KEYRING_UNAVAILABLE,
                                    )
                                }

                                else -> {
                                    val nextConnections = remoteConnections()
                                        .filterNot { connection -> connection.id == profile.id } + profile
                                    replaceRemoteConnections(nextConnections)
                                    val latestDialog = dialogState.value as? RootDialogState.RemoteConnections
                                    if (latestDialog != null) {
                                        dialogState.value = if (
                                            latestDialog.matchesSaveOrigin(
                                                currentDialog.editingRemoteConnectionId,
                                                draft,
                                            )
                                        ) {
                                            RootDialogState.RemoteConnections()
                                        } else {
                                            latestDialog.copy(saving = false)
                                        }
                                    }
                                }
                            }
                        }
                    } finally {
                        if (connectionSaveRunId == runId) {
                            connectionSaveRunId = null
                            connectionSaveJob = null
                        }
                    }
                }
            }
        }
    }

    /**
     * 对当前草稿执行连接可达性测试。
     */
    fun testRemoteConnectionDraft() {
        val currentDialog = dialogState.value as? RootDialogState.RemoteConnections
        if (currentDialog != null) {
            val draft = currentDialog.remoteConnectionDraft
            val existing = currentDialog.editingRemoteConnectionId?.let { editingId ->
                remoteConnections().firstOrNull { connection -> connection.id == editingId }
            }
            val validationError = draft.testValidationError(
                canReuseStoredSecret = existing != null && !draft.secretChanged,
            )
            if (validationError != null) {
                dialogState.value = currentDialog.copy(remoteConnectionError = validationError)
            } else {
                cancelConnectionTest()
                val runId = UUID.randomUUID().toString()
                connectionTestRunId = runId
                dialogState.value = currentDialog.copy(
                    remoteConnectionError = null,
                    remoteConnectionTestState = RemoteConnectionTestState.Testing,
                )
                connectionTestJob = scope.launch {
                    try {
                        val outcome = operationService.testConnection(existing, draft)
                        if (connectionTestRunId == runId) {
                            val latestDialog = dialogState.value as? RootDialogState.RemoteConnections
                            if (latestDialog != null) {
                                val testState = outcome.getOrElse { failure ->
                                    RemoteConnectionTestState.Failed(failure.toI18nMessage())
                                }
                                dialogState.value = latestDialog.copy(remoteConnectionTestState = testState)
                            }
                        }
                    } finally {
                        if (connectionTestRunId == runId) {
                            connectionTestRunId = null
                            connectionTestJob = null
                        }
                    }
                }
            }
        }
    }

    /**
     * 删除指定网络位置配置。
     *
     * @param id 需要删除的网络位置 ID。
     */
    fun deleteRemoteConnection(id: String) {
        val dialogOpen = dialogState.value is RootDialogState.RemoteConnections
        val profile = remoteConnections().firstOrNull { connection -> connection.id == id }
        if (dialogOpen && profile != null) {
            cancelConnectionTest()
            scope.launch {
                operationService.deleteCredentials(profile)
                    .onSuccess {
                        replaceRemoteConnections(remoteConnections().filterNot { connection -> connection.id == id })
                        val latestDialog = dialogState.value as? RootDialogState.RemoteConnections
                        if (latestDialog != null) {
                            val editingId = latestDialog.editingRemoteConnectionId
                            dialogState.value = latestDialog.copy(
                                editingRemoteConnectionId = editingId?.takeUnless { it == id },
                                remoteConnectionDraft = if (editingId == id) {
                                    RemoteConnectionDraft()
                                } else {
                                    latestDialog.remoteConnectionDraft
                                },
                                remoteConnectionError = null,
                                remoteConnectionTestState = RemoteConnectionTestState.Idle,
                            )
                        }
                    }
                    .onFailure { failure ->
                        OnyxLogger.error("RootRemoteConnectionManager", "删除远程连接凭据失败", failure)
                        val latestDialog = dialogState.value as? RootDialogState.RemoteConnections
                        if (latestDialog != null) {
                            dialogState.value = latestDialog.copy(
                                remoteConnectionError = RemoteConnectionDialogError.CREDENTIAL_SAVE_FAILED,
                            )
                        }
                    }
            }
        }
    }

    /**
     * 打开网络位置并关闭管理窗口。
     *
     * @param location 目标 VFS 位置。
     */
    fun openRemoteConnection(location: String) {
        cancelConnectionTest()
        dialogState.value = null
        openLocationInActivePane(location)
    }

    /**
     * 更新即时认证对话框草稿。
     *
     * @param draft 用户当前输入的凭据草稿。
     */
    fun updateRemoteCredentialsDraft(draft: RemoteCredentialsDraft) {
        val currentDialog = dialogState.value as? RootDialogState.RemoteCredentials ?: return
        dialogState.value = currentDialog.copy(
            draft = draft,
            rejected = false,
            error = null,
        )
    }

    /**
     * 在 IO 线程保存即时认证凭据，并在成功后刷新对应面板。
     */
    fun submitRemoteCredentials() {
        val currentDialog = dialogState.value as? RootDialogState.RemoteCredentials
        if (currentDialog != null && credentialSubmitJob?.isActive != true) {
            val username = currentDialog.draft.username.trim()
            if (username.isEmpty()) {
                dialogState.value = currentDialog.copy(error = RemoteCredentialsDialogError.USERNAME_EMPTY)
            } else {
                val runId = UUID.randomUUID().toString()
                credentialSubmitRunId = runId
                dialogState.value = currentDialog.copy(submitting = true, error = null)
                credentialSubmitJob = scope.launch {
                    try {
                        val outcome = operationService.submitCredentials(currentDialog, username)
                        if (credentialSubmitRunId == runId) {
                            val failure = outcome.exceptionOrNull()
                            when {
                                failure != null -> {
                                    OnyxLogger.error(
                                        "RootRemoteConnectionManager",
                                        "保存即时远程凭据失败",
                                        failure,
                                    )
                                    updateCredentialSubmitError(
                                        runId,
                                        RemoteCredentialsDialogError.CREDENTIAL_SAVE_FAILED,
                                    )
                                }

                                outcome.getOrNull() == RemoteCredentialSaveResult.UNSUPPORTED -> {
                                    updateCredentialSubmitError(
                                        runId,
                                        RemoteCredentialsDialogError.SYSTEM_KEYRING_UNAVAILABLE,
                                    )
                                }

                                else -> {
                                    dialogState.value = null
                                    paneComponent(currentDialog.paneId).refresh()
                                }
                            }
                        }
                    } finally {
                        if (credentialSubmitRunId == runId) {
                            credentialSubmitRunId = null
                            credentialSubmitJob = null
                        }
                    }
                }
            }
        }
    }

    /**
     * 根据 VFS 认证错误打开或更新即时认证对话框。
     *
     * @param paneId 请求认证的面板。
     * @param error VFS 认证错误。
     */
    fun requestRemoteCredentials(
        paneId: PaneId,
        error: VfsProviderError,
    ) {
        val protocol = error.protocol
        if (protocol != VfsProtocol.SMB && protocol != VfsProtocol.WEBDAV) {
            return
        }
        val location = error.location ?: paneState(paneId).location
        val rejected = error is VfsProviderError.AuthenticationRejected
        val currentDialog = dialogState.value as? RootDialogState.RemoteCredentials
        if (currentDialog?.matchesRequest(paneId, protocol, location) == true) {
            if (rejected && !currentDialog.rejected) {
                dialogState.value = currentDialog.copy(rejected = true)
            }
            return
        }

        cancelCredentialSubmission()
        dialogState.value = RootDialogState.RemoteCredentials(
            paneId = paneId,
            protocol = protocol,
            location = location,
            rejected = rejected,
        )
    }

    /**
     * 取消当前连接测试并立即使其结果失效。
     */
    private fun cancelConnectionTest() {
        connectionTestRunId = null
        connectionTestJob?.cancel()
        connectionTestJob = null
    }

    /**
     * 取消当前即时凭据提交并立即使其结果失效。
     */
    private fun cancelCredentialSubmission() {
        credentialSubmitRunId = null
        credentialSubmitJob?.cancel()
        credentialSubmitJob = null
    }

    /**
     * 将指定保存任务的错误写回仍然打开的网络位置窗口。
     *
     * @param runId 保存任务运行标识。
     * @param error 需要展示的保存错误。
     */
    private fun updateConnectionSaveError(
        runId: String,
        error: RemoteConnectionDialogError,
    ) {
        if (connectionSaveRunId != runId) return
        val latestDialog = dialogState.value as? RootDialogState.RemoteConnections ?: return
        dialogState.value = latestDialog.copy(
            saving = false,
            remoteConnectionError = error,
        )
    }

    /**
     * 将指定提交任务的错误写回仍然打开的即时凭据窗口。
     *
     * @param runId 提交任务运行标识。
     * @param error 需要展示的凭据错误。
     */
    private fun updateCredentialSubmitError(
        runId: String,
        error: RemoteCredentialsDialogError,
    ) {
        if (credentialSubmitRunId != runId) return
        val latestDialog = dialogState.value as? RootDialogState.RemoteCredentials ?: return
        dialogState.value = latestDialog.copy(
            submitting = false,
            error = error,
        )
    }
}
