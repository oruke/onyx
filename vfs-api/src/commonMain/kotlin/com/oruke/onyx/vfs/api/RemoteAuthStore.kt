package com.oruke.onyx.vfs.api

enum class RemoteCredentialSavePolicy {
    DO_NOT_SAVE,
    SESSION,
    SYSTEM_KEYRING,
}

enum class RemoteCredentialSaveResult {
    AVAILABLE_FOR_CURRENT_REQUEST,
    STORED_FOR_SESSION,
    STORED_IN_SYSTEM_KEYRING,
    UNSUPPORTED,
}

interface RemoteAuthStore {
    fun authContext(
        protocol: VfsProtocol,
        location: String,
    ): VfsAuthContext

    fun put(
        protocol: VfsProtocol,
        location: String,
        authContext: VfsAuthContext,
    ) {
        put(
            protocol = protocol,
            location = location,
            authContext = authContext,
            savePolicy = RemoteCredentialSavePolicy.SESSION,
        )
    }

    fun put(
        protocol: VfsProtocol,
        location: String,
        authContext: VfsAuthContext,
        savePolicy: RemoteCredentialSavePolicy,
    ): RemoteCredentialSaveResult

    fun clear(
        protocol: VfsProtocol,
        location: String,
    )

    fun clearSession()
}

interface RemoteKeyringAuthStore : RemoteAuthStore {
    fun isSystemKeyringAvailable(): Boolean
}

internal data class RemoteAuthScope(
    val protocol: VfsProtocol,
    val scheme: String,
    val authority: String,
)

internal fun remoteAuthScope(
    protocol: VfsProtocol,
    location: String,
): RemoteAuthScope {
    val schemeSeparator = location.indexOf("://")
    if (schemeSeparator < 0) {
        return RemoteAuthScope(
            protocol = protocol,
            scheme = protocol.name.lowercase(),
            authority = location.trim().lowercase(),
        )
    }

    val scheme = location.substring(0, schemeSeparator).lowercase()
    val remainder = location.substring(schemeSeparator + 3)
    val authority = remainder
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
        .lowercase()
    return RemoteAuthScope(
        protocol = protocol,
        scheme = scheme,
        authority = authority,
    )
}
