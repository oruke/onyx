package com.oruke.onyx.vfs.api

class InMemoryRemoteAuthStore : RemoteAuthStore {
    private val sessionAuthContexts = mutableMapOf<RemoteAuthScope, VfsAuthContext>()
    private val transientAuthContexts = mutableMapOf<RemoteAuthScope, VfsAuthContext>()

    @Synchronized
    override fun authContext(
        protocol: VfsProtocol,
        location: String,
    ): VfsAuthContext {
        val scope = remoteAuthScope(protocol, location)
        return transientAuthContexts.remove(scope)
            ?: sessionAuthContexts[scope]
            ?: VfsAuthContext.None
    }

    @Synchronized
    override fun put(
        protocol: VfsProtocol,
        location: String,
        authContext: VfsAuthContext,
        savePolicy: RemoteCredentialSavePolicy,
    ): RemoteCredentialSaveResult {
        val scope = remoteAuthScope(protocol, location)
        return when (savePolicy) {
            RemoteCredentialSavePolicy.DO_NOT_SAVE -> {
                transientAuthContexts[scope] = authContext
                RemoteCredentialSaveResult.AVAILABLE_FOR_CURRENT_REQUEST
            }

            RemoteCredentialSavePolicy.SESSION -> {
                sessionAuthContexts[scope] = authContext
                transientAuthContexts.remove(scope)
                RemoteCredentialSaveResult.STORED_FOR_SESSION
            }

            RemoteCredentialSavePolicy.SYSTEM_KEYRING -> RemoteCredentialSaveResult.UNSUPPORTED
        }
    }

    @Synchronized
    override fun put(
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

    @Synchronized
    override fun clear(
        protocol: VfsProtocol,
        location: String,
    ) {
        val scope = remoteAuthScope(protocol, location)
        sessionAuthContexts.remove(scope)
        transientAuthContexts.remove(scope)
    }

    @Synchronized
    override fun clearSession() {
        sessionAuthContexts.clear()
        transientAuthContexts.clear()
    }
}
