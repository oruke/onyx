package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.app.component.PaneComponent
import com.oruke.onyx.app.component.PaneState
import com.oruke.onyx.app.component.RemoteConnectionDialogError
import com.oruke.onyx.app.component.RemoteConnectionDraft
import com.oruke.onyx.app.component.RemoteConnectionTestState
import com.oruke.onyx.app.component.RemoteCredentialsDialogError
import com.oruke.onyx.app.component.RemoteCredentialsDraft
import com.oruke.onyx.app.component.RootDialogState
import com.oruke.onyx.app.component.hasCredentialInput
import com.oruke.onyx.app.component.normalizedLocation
import com.oruke.onyx.app.component.refresh
import com.oruke.onyx.app.component.toAuthContextOrNull
import com.oruke.onyx.app.component.toRemoteConnectionDraft
import com.oruke.onyx.app.component.toRemoteConnectionProfile
import com.oruke.onyx.app.component.toRemoteCredentialSavePolicy
import com.oruke.onyx.app.component.toVfsProtocol
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.RemoteConnectionProfile
import com.oruke.onyx.shared.filesystem.toI18nMessage
import com.oruke.onyx.vfs.api.RemoteAuthStore
import com.oruke.onyx.vfs.api.RemoteCredentialSaveResult
import com.oruke.onyx.vfs.api.VfsAuthContext
import com.oruke.onyx.vfs.api.VfsConnectionTestRequest
import com.oruke.onyx.vfs.api.VfsConnectionTestResult
import com.oruke.onyx.vfs.api.VfsConnectionTestService
import com.oruke.onyx.vfs.api.VfsProviderError
import com.oruke.onyx.vfs.api.VfsProtocol
import kotlinx.coroutines.CoroutineScope
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

    /**
     * 打开网络位置管理窗口。
     */
    fun openRemoteConnections() {
        dialogState.value = RootDialogState.RemoteConnections()
    }

    /**
     * 更新网络位置编辑草稿。
     *
     * @param draft 用户当前输入的网络位置配置草稿。
     */
    fun updateRemoteConnectionDraft(draft: RemoteConnectionDraft) {
        val currentDialog = dialogState.value as? RootDialogState.RemoteConnections ?: return
        dialogState.value = currentDialog.copy(
            remoteConnectionDraft = draft,
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
        val currentDialog = dialogState.value as? RootDialogState.RemoteConnections ?: return
        val draft = currentDialog.remoteConnectionDraft
        val name = draft.name.trim()
        val rawLocation = draft.location.trim()
        when {
            name.isEmpty() -> {
                dialogState.value = currentDialog.copy(remoteConnectionError = RemoteConnectionDialogError.NAME_EMPTY)
                return
            }

            rawLocation.isEmpty() -> {
                dialogState.value = currentDialog.copy(remoteConnectionError = RemoteConnectionDialogError.LOCATION_EMPTY)
                return
            }

            draft.hasCredentialInput() && draft.username.trim().isEmpty() -> {
                dialogState.value = currentDialog.copy(remoteConnectionError = RemoteConnectionDialogError.USERNAME_EMPTY)
                return
            }
        }

        val location = draft.normalizedLocation()
        val authContext = draft.toAuthContextOrNull()
        if (authContext != null) {
            val saveResult = remoteAuthStore.put(
                protocol = draft.protocol.toVfsProtocol(),
                location = location,
                authContext = authContext,
                savePolicy = draft.savePolicy.toRemoteCredentialSavePolicy(),
            )
            if (saveResult == RemoteCredentialSaveResult.UNSUPPORTED) {
                dialogState.value = currentDialog.copy(
                    remoteConnectionError = RemoteConnectionDialogError.SYSTEM_KEYRING_UNAVAILABLE,
                )
                return
            }
        }

        val profile = draft.toRemoteConnectionProfile(
            id = currentDialog.editingRemoteConnectionId ?: UUID.randomUUID().toString(),
            location = location,
        )
        val nextConnections = remoteConnections()
            .filterNot { existing -> existing.id == profile.id } + profile
        replaceRemoteConnections(nextConnections)
        dialogState.value = currentDialog.copy(
            editingRemoteConnectionId = null,
            remoteConnectionDraft = RemoteConnectionDraft(),
            remoteConnectionError = null,
            remoteConnectionTestState = RemoteConnectionTestState.Idle,
        )
    }

    /**
     * 对当前草稿执行连接可达性测试。
     */
    fun testRemoteConnectionDraft() {
        val currentDialog = dialogState.value as? RootDialogState.RemoteConnections ?: return
        val draft = currentDialog.remoteConnectionDraft
        val rawLocation = draft.location.trim()
        if (rawLocation.isEmpty()) {
            dialogState.value = currentDialog.copy(remoteConnectionError = RemoteConnectionDialogError.LOCATION_EMPTY)
            return
        }
        if (draft.hasCredentialInput() && draft.username.trim().isEmpty()) {
            dialogState.value = currentDialog.copy(remoteConnectionError = RemoteConnectionDialogError.USERNAME_EMPTY)
            return
        }

        val request = VfsConnectionTestRequest(
            protocol = draft.protocol.toVfsProtocol(),
            location = draft.normalizedLocation(),
            authContext = draft.toAuthContextOrNull() ?: VfsAuthContext.None,
        )
        dialogState.value = currentDialog.copy(
            remoteConnectionError = null,
            remoteConnectionTestState = RemoteConnectionTestState.Testing,
        )
        scope.launch {
            val testState = when (val result = connectionTestService.testConnection(request)) {
                is VfsConnectionTestResult.Reachable -> RemoteConnectionTestState.Reachable(
                    capabilities = result.capabilities.mapTo(sortedSetOf()) { capability -> capability.name },
                )

                is VfsConnectionTestResult.Failed -> RemoteConnectionTestState.Failed(
                    reason = result.error.toI18nMessage(),
                )
            }
            val latestDialog = dialogState.value as? RootDialogState.RemoteConnections ?: return@launch
            dialogState.value = latestDialog.copy(remoteConnectionTestState = testState)
        }
    }

    /**
     * 删除指定网络位置配置。
     *
     * @param id 需要删除的网络位置 ID。
     */
    fun deleteRemoteConnection(id: String) {
        val currentDialog = dialogState.value as? RootDialogState.RemoteConnections ?: return
        replaceRemoteConnections(remoteConnections().filterNot { profile -> profile.id == id })
        val editingId = currentDialog.editingRemoteConnectionId
        dialogState.value = currentDialog.copy(
            editingRemoteConnectionId = editingId?.takeUnless { it == id },
            remoteConnectionDraft = if (editingId == id) RemoteConnectionDraft() else currentDialog.remoteConnectionDraft,
            remoteConnectionError = null,
            remoteConnectionTestState = RemoteConnectionTestState.Idle,
        )
    }

    /**
     * 打开网络位置并关闭管理窗口。
     *
     * @param location 目标 VFS 位置。
     */
    fun openRemoteConnection(location: String) {
        dialogState.value = null
        openLocationInActivePane(location)
    }

    fun updateRemoteCredentialsDraft(draft: RemoteCredentialsDraft) {
        val currentDialog = dialogState.value as? RootDialogState.RemoteCredentials ?: return
        dialogState.value = currentDialog.copy(
            draft = draft,
            rejected = false,
            error = null,
        )
    }

    fun submitRemoteCredentials() {
        val currentDialog = dialogState.value as? RootDialogState.RemoteCredentials ?: return
        val username = currentDialog.draft.username.trim()
        if (username.isEmpty()) {
            dialogState.value = currentDialog.copy(error = RemoteCredentialsDialogError.USERNAME_EMPTY)
            return
        }

        val saveResult = remoteAuthStore.put(
            protocol = currentDialog.protocol,
            location = currentDialog.location,
            authContext = VfsAuthContext.UsernamePassword(
                username = username,
                password = currentDialog.draft.password,
                domain = currentDialog.draft.domain.trim().ifBlank { null },
            ),
            savePolicy = currentDialog.draft.savePolicy,
        )
        if (saveResult == RemoteCredentialSaveResult.UNSUPPORTED) {
            dialogState.value = currentDialog.copy(
                error = RemoteCredentialsDialogError.SYSTEM_KEYRING_UNAVAILABLE,
            )
            return
        }
        dialogState.value = null
        paneComponent(currentDialog.paneId).refresh()
    }

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
        if (currentDialog != null &&
            currentDialog.paneId == paneId &&
            currentDialog.protocol == protocol &&
            currentDialog.location == location
        ) {
            if (rejected && !currentDialog.rejected) {
                dialogState.value = currentDialog.copy(rejected = true)
            }
            return
        }

        dialogState.value = RootDialogState.RemoteCredentials(
            paneId = paneId,
            protocol = protocol,
            location = location,
            rejected = rejected,
        )
    }
}
