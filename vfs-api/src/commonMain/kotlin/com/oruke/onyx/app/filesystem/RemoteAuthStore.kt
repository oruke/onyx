package com.oruke.onyx.app.filesystem

interface RemoteAuthStore {
    fun authContext(
        protocol: VfsProtocol,
        location: String,
    ): VfsAuthContext

    fun put(
        protocol: VfsProtocol,
        location: String,
        authContext: VfsAuthContext,
    )

    fun clear(
        protocol: VfsProtocol,
        location: String,
    )
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
