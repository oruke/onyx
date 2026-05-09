package com.oruke.onyx.core.model

sealed interface OnyxError {
    val message: String?

    data class InvalidLocation(
        override val message: String,
    ) : OnyxError

    data class AccessDenied(
        override val message: String,
    ) : OnyxError

    data class IoFailure(
        override val message: String,
    ) : OnyxError
}
