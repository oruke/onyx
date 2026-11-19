package com.oruke.onyx.vfs.api

/**
 * 仅在当前 JVM 进程中保存远程认证信息的线程安全存储。
 */
class InMemoryRemoteAuthStore : RemoteAuthStore {
    private val sessionAuthContexts = mutableMapOf<RemoteAuthScope, VfsAuthContext>()
    private val transientAuthContexts = mutableMapOf<RemoteAuthScope, VfsAuthContext>()

    @Synchronized
    override fun authContext(
        protocol: VfsProtocol,
        location: String,
    ): VfsAuthContext {
        for (scope in candidateRemoteAuthScopes(protocol, location)) {
            val exact = authContextForScope(scope)
            if (exact != VfsAuthContext.None) return exact
        }
        return VfsAuthContext.None
    }

    @Synchronized
    override fun peekAuthContext(
        protocol: VfsProtocol,
        location: String,
    ): VfsAuthContext {
        for (scope in candidateRemoteAuthScopes(protocol, location)) {
            val exact = peekAuthContextForScope(scope)
            if (exact != VfsAuthContext.None) return exact
        }
        return VfsAuthContext.None
    }

    @Synchronized
    override fun peekExactAuthContext(
        protocol: VfsProtocol,
        location: String,
    ): VfsAuthContext = peekAuthContextForScope(remoteAuthScope(protocol, location))

    @Synchronized
    override fun authContextForScope(scope: RemoteAuthScope): VfsAuthContext {
        return transientAuthContexts.remove(scope)
            ?: sessionAuthContexts[scope]
            ?: VfsAuthContext.None
    }

    @Synchronized
    override fun peekAuthContextForScope(scope: RemoteAuthScope): VfsAuthContext {
        return transientAuthContexts[scope]
            ?: sessionAuthContexts[scope]
            ?: VfsAuthContext.None
    }

    @Synchronized
    override fun clearScope(scope: RemoteAuthScope) {
        sessionAuthContexts.remove(scope)
        transientAuthContexts.remove(scope)
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
                sessionAuthContexts.remove(scope)
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
