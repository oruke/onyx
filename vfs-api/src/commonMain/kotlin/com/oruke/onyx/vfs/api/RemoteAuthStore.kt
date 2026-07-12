package com.oruke.onyx.vfs.api

/**
 * 远程凭据保存策略。
 */
enum class RemoteCredentialSavePolicy {
    /** 仅供当前请求使用，不持久化。 */
    DO_NOT_SAVE,

    /** 保存到当前应用会话。 */
    SESSION,

    /** 保存到操作系统安全凭据存储。 */
    SYSTEM_KEYRING,
}

/**
 * 远程凭据保存结果。
 */
enum class RemoteCredentialSaveResult {
    /** 凭据仅可用于紧接着发起的请求。 */
    AVAILABLE_FOR_CURRENT_REQUEST,

    /** 凭据已保存到当前应用会话。 */
    STORED_FOR_SESSION,

    /** 凭据已保存到操作系统安全凭据存储。 */
    STORED_IN_SYSTEM_KEYRING,

    /** 当前运行环境不支持请求的保存策略。 */
    UNSUPPORTED,
}

/**
 * 远程协议认证信息存储，按协议与连接位置隔离凭据。
 */
interface RemoteAuthStore {
    /**
     * 读取连接可用的认证上下文。
     *
     * @param protocol 远程协议。
     * @param location 连接位置。
     * @return 匹配的认证上下文；没有凭据时返回 [VfsAuthContext.None]。
     */
    fun authContext(
        protocol: VfsProtocol,
        location: String,
    ): VfsAuthContext

    /**
     * 将认证上下文保存到当前应用会话。
     *
     * @param protocol 远程协议。
     * @param location 连接位置。
     * @param authContext 待保存的认证上下文。
     */
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

    /**
     * 按指定策略保存认证上下文。
     *
     * @param protocol 远程协议。
     * @param location 连接位置。
     * @param authContext 待保存的认证上下文。
     * @param savePolicy 凭据保存策略。
     * @return 实际保存结果。
     */
    fun put(
        protocol: VfsProtocol,
        location: String,
        authContext: VfsAuthContext,
        savePolicy: RemoteCredentialSavePolicy,
    ): RemoteCredentialSaveResult

    /**
     * 清除指定连接的会话与临时凭据。
     *
     * @param protocol 远程协议。
     * @param location 连接位置。
     */
    fun clear(
        protocol: VfsProtocol,
        location: String,
    )

    /**
     * 清除当前应用会话中的全部远程凭据。
     */
    fun clearSession()
}

/**
 * 支持操作系统安全凭据存储的远程认证信息存储。
 */
interface RemoteKeyringAuthStore : RemoteAuthStore {
    /**
     * 判断当前运行环境能否使用操作系统安全凭据存储。
     *
     * @return 可用时返回 `true`。
     */
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
