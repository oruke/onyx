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
    private val paneState: (PaneId) -> PaneState,
    private val paneComponent: (PaneId) -> PaneComponent,
    private val openLocationInActivePane: (String) -> Unit,
) {

    fun updateRemoteConnectionDraft(draft: RemoteConnectionDraft) {
        val currentDialog = dialogState.value as? RootDialogState.Settings ?: return
        dialogState.value = currentDialog.copy(
            remoteConnectionDraft = draft,
            remoteConnectionError = null,
            remoteConnectionTestState = RemoteConnectionTestState.Idle,
        )
    }

    fun editRemoteConnection(profile: RemoteConnectionProfile) {
        val currentDialog = dialogState.value as? RootDialogState.Settings ?: return
        dialogState.value = currentDialog.copy(
            editingRemoteConnectionId = profile.id,
            remoteConnectionDraft = profile.toRemoteConnectionDraft(),
            remoteConnectionError = null,
            remoteConnectionTestState = RemoteConnectionTestState.Idle,
        )
    }

    fun newRemoteConnection() {
        val currentDialog = dialogState.value as? RootDialogState.Settings ?: return
        dialogState.value = currentDialog.copy(
            editingRemoteConnectionId = null,
            remoteConnectionDraft = RemoteConnectionDraft(),
            remoteConnectionError = null,
            remoteConnectionTestState = RemoteConnectionTestState.Idle,
        )
    }

    fun saveRemoteConnectionDraft() {
        val currentDialog = dialogState.value as? RootDialogState.Settings ?: return
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
        val nextConnections = currentDialog.draft.remoteConnections
            .filterNot { existing -> existing.id == profile.id } + profile
        dialogState.value = currentDialog.copy(
            draft = currentDialog.draft.copy(remoteConnections = nextConnections),
            editingRemoteConnectionId = null,
            remoteConnectionDraft = RemoteConnectionDraft(),
            remoteConnectionError = null,
            remoteConnectionTestState = RemoteConnectionTestState.Idle,
        )
    }

    fun testRemoteConnectionDraft() {
        val currentDialog = dialogState.value as? RootDialogState.Settings ?: return
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
            val latestDialog = dialogState.value as? RootDialogState.Settings ?: return@launch
            dialogState.value = latestDialog.copy(remoteConnectionTestState = testState)
        }
    }

    fun deleteRemoteConnection(id: String) {
        val currentDialog = dialogState.value as? RootDialogState.Settings ?: return
        val nextDraft = currentDialog.draft.copy(
            remoteConnections = currentDialog.draft.remoteConnections.filterNot { profile -> profile.id == id },
        )
        val editingId = currentDialog.editingRemoteConnectionId
        dialogState.value = currentDialog.copy(
            draft = nextDraft,
            editingRemoteConnectionId = editingId?.takeUnless { it == id },
            remoteConnectionDraft = if (editingId == id) RemoteConnectionDraft() else currentDialog.remoteConnectionDraft,
            remoteConnectionError = null,
            remoteConnectionTestState = RemoteConnectionTestState.Idle,
        )
    }

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
