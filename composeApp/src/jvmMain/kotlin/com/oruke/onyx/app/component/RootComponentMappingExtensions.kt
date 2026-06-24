package com.oruke.onyx.app.component

import com.oruke.onyx.app.component.delegate.FileOperationHistoryState
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.PaneSessionSnapshot
import com.oruke.onyx.core.model.RemoteConnectionProfile
import com.oruke.onyx.core.model.RemoteConnectionProtocol
import com.oruke.onyx.core.model.RemoteConnectionSavePolicy
import com.oruke.onyx.shared.filesystem.toI18nMessage
import com.oruke.onyx.vfs.api.RemoteCredentialSavePolicy
import com.oruke.onyx.vfs.api.VfsAuthContext
import com.oruke.onyx.vfs.api.VfsProtocol

internal fun RemoteConnectionProfile.toRemoteConnectionDraft(): RemoteConnectionDraft {
    return RemoteConnectionDraft(
        name = name,
        protocol = protocol,
        location = location,
        username = username,
        domain = domain,
        savePolicy = savePolicy,
    )
}

internal fun RemoteConnectionDraft.toRemoteConnectionProfile(
    id: String,
    location: String,
): RemoteConnectionProfile {
    return RemoteConnectionProfile(
        id = id,
        name = name.trim(),
        protocol = protocol,
        location = location,
        username = username.trim(),
        domain = domain.trim(),
        savePolicy = savePolicy,
    )
}

internal fun RemoteConnectionDraft.hasCredentialInput(): Boolean {
    return username.isNotBlank() || secret.isNotBlank() || domain.isNotBlank()
}

internal fun RemoteConnectionDraft.toAuthContextOrNull(): VfsAuthContext? {
    if (!hasCredentialInput()) return null
    return when (protocol) {
        RemoteConnectionProtocol.S3 -> VfsAuthContext.AwsCredentials(
            accessKeyId = username.trim(),
            secretAccessKey = secret,
            region = domain.trim().ifBlank { null },
        )

        RemoteConnectionProtocol.SMB,
        RemoteConnectionProtocol.WEBDAV,
        RemoteConnectionProtocol.WEBDAVS -> VfsAuthContext.UsernamePassword(
            username = username.trim(),
            password = secret,
            domain = domain.trim().ifBlank { null },
        )
    }
}

internal fun RemoteConnectionDraft.normalizedLocation(): String {
    val trimmed = location.trim()
    val withScheme = if ("://" in trimmed) {
        trimmed
    } else {
        "${protocol.defaultScheme()}://${trimmed.trimStart('/')}"
    }
    return if (withScheme.contains('?') || withScheme.contains('#') || withScheme.endsWith('/')) {
        withScheme
    } else {
        "$withScheme/"
    }
}

internal fun RemoteConnectionProtocol.defaultScheme(): String {
    return when (this) {
        RemoteConnectionProtocol.SMB -> "smb"
        RemoteConnectionProtocol.WEBDAV -> "webdav"
        RemoteConnectionProtocol.WEBDAVS -> "webdavs"
        RemoteConnectionProtocol.S3 -> "s3"
    }
}

internal fun RemoteConnectionProtocol.toVfsProtocol(): VfsProtocol {
    return when (this) {
        RemoteConnectionProtocol.SMB -> VfsProtocol.SMB
        RemoteConnectionProtocol.WEBDAV,
        RemoteConnectionProtocol.WEBDAVS -> VfsProtocol.WEBDAV
        RemoteConnectionProtocol.S3 -> VfsProtocol.S3
    }
}

internal fun RemoteConnectionSavePolicy.toRemoteCredentialSavePolicy(): RemoteCredentialSavePolicy {
    return when (this) {
        RemoteConnectionSavePolicy.DO_NOT_SAVE -> RemoteCredentialSavePolicy.DO_NOT_SAVE
        RemoteConnectionSavePolicy.SESSION -> RemoteCredentialSavePolicy.SESSION
        RemoteConnectionSavePolicy.SYSTEM_KEYRING -> RemoteCredentialSavePolicy.SYSTEM_KEYRING
    }
}

internal fun Throwable.toSearchErrorMessage(): I18nMessage {
    return toI18nMessage()
}

internal fun PaneComponent.toPaneSessionSnapshot(): PaneSessionSnapshot {
    return PaneSessionSnapshot(
        activeTabId = state.value.activeTabId,
        tabs = tabStatesInDisplayOrder().map { tab -> tab.toTabSnapshot() },
    )
}

/**
 * 将文件操作历史委托状态映射为 RootState 使用的公共状态。
 *
 * @return 根组件文件操作历史状态。
 */
internal fun FileOperationHistoryState.toRootOperationHistoryState(): OperationHistoryState {
    return OperationHistoryState(
        canUndo = canUndo,
        canRedo = canRedo,
    )
}
