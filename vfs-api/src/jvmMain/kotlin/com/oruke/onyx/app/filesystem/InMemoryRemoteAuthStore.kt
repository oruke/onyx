package com.oruke.onyx.app.filesystem

class InMemoryRemoteAuthStore : RemoteAuthStore {
    private val authContexts = mutableMapOf<RemoteAuthScope, VfsAuthContext>()

    @Synchronized
    override fun authContext(
        protocol: VfsProtocol,
        location: String,
    ): VfsAuthContext {
        return authContexts[remoteAuthScope(protocol, location)] ?: VfsAuthContext.None
    }

    @Synchronized
    override fun put(
        protocol: VfsProtocol,
        location: String,
        authContext: VfsAuthContext,
    ) {
        authContexts[remoteAuthScope(protocol, location)] = authContext
    }

    @Synchronized
    override fun clear(
        protocol: VfsProtocol,
        location: String,
    ) {
        authContexts.remove(remoteAuthScope(protocol, location))
    }
}
