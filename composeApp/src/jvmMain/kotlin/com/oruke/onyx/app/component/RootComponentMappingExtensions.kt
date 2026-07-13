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
        s3Config = s3Config,
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
        domain = domainForProtocol(),
        s3Config = s3Config.copy(
            endpoint = s3Config.endpoint.trim(),
            region = s3Config.region.trim(),
        ),
        savePolicy = savePolicy,
    )
}

internal fun RemoteConnectionDraft.hasCredentialInput(): Boolean {
    return username.isNotBlank() || secret.isNotBlank() || domainForProtocol().isNotBlank()
}

internal fun RemoteConnectionDraft.toAuthContextOrNull(): VfsAuthContext? {
    if (!hasCredentialInput()) return null
    return when (protocol) {
        RemoteConnectionProtocol.S3 -> VfsAuthContext.AwsCredentials(
            accessKeyId = username.trim(),
            secretAccessKey = secret,
            region = s3Config.region.trim().ifBlank { null },
        )

        RemoteConnectionProtocol.SMB -> VfsAuthContext.UsernamePassword(
            username = username.trim(),
            password = secret,
            domain = domain.trim().ifBlank { null },
        )

        RemoteConnectionProtocol.WEBDAV,
        RemoteConnectionProtocol.WEBDAVS -> VfsAuthContext.UsernamePassword(
            username = username.trim(),
            password = secret,
            domain = null,
        )
    }
}

/**
 * 返回当前协议实际使用的附加认证元数据。
 *
 * WebDAV Basic 认证不消费域字段，因此必须在进入配置和凭据存储前清空，避免隐藏字段残留。
 *
 * @return SMB 域；其他协议返回空字符串。
 */
internal fun RemoteConnectionDraft.domainForProtocol(): String {
    return when (protocol) {
        RemoteConnectionProtocol.SMB -> domain.trim()
        RemoteConnectionProtocol.S3,
        RemoteConnectionProtocol.WEBDAV,
        RemoteConnectionProtocol.WEBDAVS,
        -> ""
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
