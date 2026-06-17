package com.oruke.onyx.shared.filesystem

import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.vfs.api.VfsProviderError
import com.oruke.onyx.vfs.api.VfsProviderException
import com.oruke.onyx.vfs.api.VfsProtocol

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

private fun String?.detailOrFallback(
    fallback: String?,
    protocol: VfsProtocol,
): String {
    return takeIf { !it.isNullOrBlank() }
        ?: fallback.detailOrProtocol(protocol)
}

private fun String?.detailOrProtocol(protocol: VfsProtocol): String {
    return takeIf { !it.isNullOrBlank() } ?: protocol.name
}
