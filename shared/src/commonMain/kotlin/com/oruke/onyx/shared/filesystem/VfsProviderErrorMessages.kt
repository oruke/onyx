package com.oruke.onyx.shared.filesystem

import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.vfs.api.VfsProviderError
import com.oruke.onyx.vfs.api.VfsProviderException
import com.oruke.onyx.vfs.api.VfsProtocol

/**
 * 将结构化 VFS 错误映射为可延迟本地化的界面消息。
 *
 * @return 保留协议、位置或能力参数的国际化消息。
 */
fun VfsProviderError.toI18nMessage(): I18nMessage {
    return when (this) {
        is VfsProviderError.AuthenticationRequired -> I18nMessage(
            MessageKey.MSG_VFS_AUTHENTICATION_REQUIRED,
            protocol.name,
            location.detailOrProtocol(protocol),
        )

        is VfsProviderError.AuthenticationRejected -> I18nMessage(
            MessageKey.MSG_VFS_AUTHENTICATION_REJECTED,
            protocol.name,
            reason.detailOrFallback(location, protocol),
        )

        is VfsProviderError.PermissionDenied -> I18nMessage(
            MessageKey.MSG_VFS_PERMISSION_DENIED,
            protocol.name,
            reason.detailOrFallback(location, protocol),
        )

        is VfsProviderError.NotFound -> I18nMessage(
            MessageKey.MSG_VFS_NOT_FOUND,
            protocol.name,
            location.detailOrProtocol(protocol),
        )

        is VfsProviderError.AlreadyExists -> I18nMessage(
            MessageKey.MSG_VFS_ALREADY_EXISTS,
            protocol.name,
            location.detailOrProtocol(protocol),
        )

        is VfsProviderError.NetworkFailure -> I18nMessage(
            MessageKey.MSG_VFS_NETWORK_FAILURE,
            protocol.name,
            reason.detailOrFallback(location, protocol),
        )

        is VfsProviderError.UnsupportedOperation -> I18nMessage(
            MessageKey.MSG_VFS_UNSUPPORTED_OPERATION,
            protocol.name,
            capability?.name ?: location.detailOrProtocol(protocol),
        )

        is VfsProviderError.CrossProviderTransferUnsupported -> I18nMessage(
            MessageKey.MSG_CROSS_PROVIDER_TRANSFER_UNSUPPORTED,
            sourceProtocol.name,
            protocol.name,
        )
    }
}

/**
 * 将异常映射为可延迟本地化的界面消息。
 *
 * @param fallback 异常没有可展示详情时使用的消息键。
 * @return provider 异常的结构化消息或普通异常详情。
 */
fun Throwable.toI18nMessage(
    fallback: MessageKey = MessageKey.MSG_UNKNOWN_ERROR,
): I18nMessage {
    val providerError = (this as? VfsProviderException)?.error
    if (providerError != null) {
        return providerError.toI18nMessage()
    }
    val detail = message?.takeIf { it.isNotBlank() }
        ?: cause?.message?.takeIf { it.isNotBlank() }
    return if (detail != null) {
        I18nMessage(MessageKey.MSG_STRING_LITERAL, detail)
    } else {
        I18nMessage(fallback)
    }
}

/**
 * 选择当前详情、备用详情或协议名。
 *
 * @param fallback 当前详情为空时使用的备用详情。
 * @param protocol 最终兜底协议。
 * @return 非空展示详情。
 */
private fun String?.detailOrFallback(
    fallback: String?,
    protocol: VfsProtocol,
): String {
    return takeIf { !it.isNullOrBlank() }
        ?: fallback.detailOrProtocol(protocol)
}

/**
 * 选择当前详情或协议名。
 *
 * @param protocol 当前详情为空时使用的协议。
 * @return 非空展示详情。
 */
private fun String?.detailOrProtocol(protocol: VfsProtocol): String {
    return takeIf { !it.isNullOrBlank() } ?: protocol.name
}
