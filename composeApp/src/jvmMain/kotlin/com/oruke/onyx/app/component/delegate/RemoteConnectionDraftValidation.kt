package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.app.component.RemoteConnectionDialogError
import com.oruke.onyx.app.component.RemoteConnectionDraft
import com.oruke.onyx.app.component.RemoteConnectionLocation
import com.oruke.onyx.app.component.RootDialogState
import com.oruke.onyx.app.component.hasCredentialInput
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.RemoteConnectionProtocol
import com.oruke.onyx.core.model.S3AddressingStyle
import com.oruke.onyx.core.model.S3ProviderPreset
import com.oruke.onyx.core.model.requiresExplicitEndpoint
import com.oruke.onyx.vfs.api.VfsProtocol
import com.oruke.onyx.vfs.s3.S3EndpointResolver

/**
 * 校验网络位置保存草稿。
 *
 * @param canReuseStoredSecret 是否允许沿用已有连接的已保存密钥。
 * @return 首个可展示校验错误；草稿有效时返回空。
 */
internal fun RemoteConnectionDraft.saveValidationError(
    canReuseStoredSecret: Boolean,
): RemoteConnectionDialogError? = when {
    name.isBlank() -> RemoteConnectionDialogError.NAME_EMPTY
    location.isBlank() -> RemoteConnectionDialogError.LOCATION_EMPTY
    !RemoteConnectionLocation.isValid(protocol, location) -> RemoteConnectionDialogError.LOCATION_INVALID
    else -> s3ConfigurationValidationError() ?: credentialValidationError(canReuseStoredSecret)
}

/**
 * 校验 S3 服务商预设、Endpoint、Region 与寻址方式的组合。
 *
 * @return 首个 S3 配置错误；非 S3 草稿或配置有效时返回空。
 */
internal fun RemoteConnectionDraft.s3ConfigurationValidationError(): RemoteConnectionDialogError? = when {
    protocol != RemoteConnectionProtocol.S3 -> null
    s3Config.provider.requiresExplicitEndpoint() && s3Config.endpoint.isBlank() -> {
        RemoteConnectionDialogError.S3_ENDPOINT_INVALID
    }
    s3Config.provider in REGION_REQUIRED_S3_PROVIDERS && s3Config.region.isBlank() -> {
        RemoteConnectionDialogError.S3_REGION_EMPTY
    }
    s3Config.provider in VIRTUAL_HOSTED_ONLY_S3_PROVIDERS &&
        s3Config.addressingStyle != S3AddressingStyle.VIRTUAL_HOSTED -> {
        RemoteConnectionDialogError.S3_ENDPOINT_INVALID
    }
    !S3EndpointResolver.isValid(s3Config) -> RemoteConnectionDialogError.S3_ENDPOINT_INVALID
    else -> null
}

/**
 * 校验协议要求的凭据字段。
 *
 * SMB 与 WebDAV 允许匿名访问；S3 Provider 必须使用 AWS 凭据。已有连接在密钥未修改时可以继续读取凭据存储。
 *
 * @param canReuseStoredSecret 是否允许沿用已有连接的已保存密钥。
 * @return 首个凭据校验错误；凭据有效时返回空。
 */
internal fun RemoteConnectionDraft.credentialValidationError(
    canReuseStoredSecret: Boolean,
): RemoteConnectionDialogError? = when {
    protocol == RemoteConnectionProtocol.S3 && username.isBlank() -> RemoteConnectionDialogError.USERNAME_EMPTY
    protocol == RemoteConnectionProtocol.S3 && secret.isBlank() && !canReuseStoredSecret -> {
        RemoteConnectionDialogError.SECRET_EMPTY
    }
    hasCredentialInput() && username.isBlank() -> RemoteConnectionDialogError.USERNAME_EMPTY
    else -> null
}

/**
 * 校验网络位置测试草稿。
 *
 * @param canReuseStoredSecret 是否允许沿用已有连接的已保存密钥。
 * @return 首个可展示校验错误；草稿有效时返回空。
 */
internal fun RemoteConnectionDraft.testValidationError(
    canReuseStoredSecret: Boolean,
): RemoteConnectionDialogError? = when {
    location.isBlank() -> RemoteConnectionDialogError.LOCATION_EMPTY
    !RemoteConnectionLocation.isValid(protocol, location) -> RemoteConnectionDialogError.LOCATION_INVALID
    else -> s3ConfigurationValidationError() ?: credentialValidationError(canReuseStoredSecret)
}

/** 必须显式填写 Region 的云服务商。 */
private val REGION_REQUIRED_S3_PROVIDERS = setOf(
    S3ProviderPreset.TENCENT_COS,
    S3ProviderPreset.ALIBABA_OSS,
)

/** 仅支持虚拟主机寻址的服务商预设。 */
private val VIRTUAL_HOSTED_ONLY_S3_PROVIDERS = setOf(
    S3ProviderPreset.TENCENT_COS,
    S3ProviderPreset.ALIBABA_OSS,
)

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
