package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.app.component.RemoteConnectionDialogError
import com.oruke.onyx.app.component.RemoteConnectionDraft
import com.oruke.onyx.app.component.RootDialogState
import com.oruke.onyx.app.component.hasCredentialInput
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.vfs.api.VfsProtocol

/**
 * 校验网络位置保存草稿。
 *
 * @return 首个可展示校验错误；草稿有效时返回空。
 */
internal fun RemoteConnectionDraft.saveValidationError(): RemoteConnectionDialogError? = when {
    name.isBlank() -> RemoteConnectionDialogError.NAME_EMPTY
    location.isBlank() -> RemoteConnectionDialogError.LOCATION_EMPTY
    hasCredentialInput() && username.isBlank() -> RemoteConnectionDialogError.USERNAME_EMPTY
    else -> null
}

/**
 * 校验网络位置测试草稿。
 *
 * @return 首个可展示校验错误；草稿有效时返回空。
 */
internal fun RemoteConnectionDraft.testValidationError(): RemoteConnectionDialogError? = when {
    location.isBlank() -> RemoteConnectionDialogError.LOCATION_EMPTY
    hasCredentialInput() && username.isBlank() -> RemoteConnectionDialogError.USERNAME_EMPTY
    else -> null
}

/**
 * 判断异步保存完成时窗口是否仍停留在发起保存的草稿。
 *
 * @param editingConnectionId 发起保存时的编辑连接 ID。
 * @param draft 发起保存时的草稿。
 * @return 窗口上下文未变化时返回 true。
 */
internal fun RootDialogState.RemoteConnections.matchesSaveOrigin(
    editingConnectionId: String?,
    draft: RemoteConnectionDraft,
): Boolean = this.editingRemoteConnectionId == editingConnectionId && remoteConnectionDraft == draft

/**
 * 判断即时凭据窗口是否已经表示同一认证请求。
 *
 * @param paneId 请求认证的面板。
 * @param protocol 请求协议。
 * @param location 请求位置。
 * @return 请求上下文完全相同时返回 true。
 */
internal fun RootDialogState.RemoteCredentials.matchesRequest(
    paneId: PaneId,
    protocol: VfsProtocol,
    location: String,
): Boolean = this.paneId == paneId && this.protocol == protocol && this.location == location
