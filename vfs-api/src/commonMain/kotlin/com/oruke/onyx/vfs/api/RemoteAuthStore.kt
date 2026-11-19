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
     * 以非消费方式查询连接可用的认证上下文，不会清除一次性 `DO_NOT_SAVE` 凭据。
     *
     * @param protocol 远程协议。
     * @param location 连接位置。
     * @return 匹配的认证上下文；没有凭据时返回 [VfsAuthContext.None]。
     */
    fun peekAuthContext(
        protocol: VfsProtocol,
        location: String,
    ): VfsAuthContext = authContext(protocol, location)

    /**
     * 以非消费方式仅查询连接精确作用域内的认证上下文，不向父路径或仅主机作用域回退。
     *
     * 用于编辑已有连接时校验本连接是否真实存在可复用密钥，防止误盗同主机父级连接的密码。
     *
     * @param protocol 远程协议。
     * @param location 连接位置。
     * @return 仅属于精确作用域的认证上下文；不存在时返回 [VfsAuthContext.None]。
     */
    fun peekExactAuthContext(
        protocol: VfsProtocol,
        location: String,
    ): VfsAuthContext = peekAuthContext(protocol, location)

    /**
     * 读取指定精确作用域内的认证上下文（一次性凭据会被消费），不做父路径回退。
     *
     * @param scope 目标精确凭据作用域。
     * @return 精确命中的认证上下文；不存在时返回 [VfsAuthContext.None]。
     */
    fun authContextForScope(scope: RemoteAuthScope): VfsAuthContext = VfsAuthContext.None

    /**
     * 以非消费方式读取指定精确作用域内的认证上下文，不做父路径回退。
     *
     * @param scope 目标精确凭据作用域。
     * @return 精确命中的认证上下文；不存在时返回 [VfsAuthContext.None]。
     */
    fun peekAuthContextForScope(scope: RemoteAuthScope): VfsAuthContext = VfsAuthContext.None

    /**
     * 清除指定精确作用域内的会话、临时及持久化凭据（例如清理不再被引用的旧版仅主机记录）。
     *
     * @param scope 待清理的精确作用域。
     */
    fun clearScope(scope: RemoteAuthScope) = Unit

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

/**
 * 远程凭据的规范化隔离作用域，按协议、Scheme、主机与路径根段隔离同主机下的多个连接。
 *
 * @property protocol 远程协议。
 * @property scheme 规范化小写 Scheme。
 * @property authority 规范化小写主机与端口（或 S3 Bucket 名）。
 * @property pathScope 规范化路径作用域（例如 SMB 共享名 `/share`、WebDAV/S3 根前缀 `/dav`；根级为空串）。
 */
data class RemoteAuthScope(
    val protocol: VfsProtocol,
    val scheme: String,
    val authority: String,
    val pathScope: String = "",
) {
    /** 合并主机与可选路径作用域后的稳定标识，用于系统钥匙串定位与作用域比对。 */
    val scopedAuthority: String = if (pathScope.isEmpty()) authority else "$authority$pathScope"
}

/**
 * 解析远程连接位置对应的精确认证作用域。
 *
 * - `SMB` 按 `scheme + host[:port] + /share` 隔离（同一共享内的所有子路径命中同一作用域）。
 * - `WEBDAV` / `S3` 按规范化完整路径前缀隔离，查询时配合 [candidateRemoteAuthScopes] 做最长前缀匹配。
 *
 * @param protocol 远程协议。
 * @param location 连接位置。
 * @return 精确凭据作用域。
 */
fun remoteAuthScope(
    protocol: VfsProtocol,
    location: String,
): RemoteAuthScope {
    val parsed = parseLocationScopeParts(protocol, location)
    val pathScope = when {
        parsed.segments.isEmpty() -> ""
        protocol == VfsProtocol.SMB -> "/${parsed.segments.first().lowercase()}"
        else -> "/" + parsed.segments.joinToString("/")
    }
    return RemoteAuthScope(
        protocol = protocol,
        scheme = parsed.scheme,
        authority = parsed.authority,
        pathScope = pathScope,
    )
}

/**
 * 返回给定位置在查询凭据时按优先级从最具体到最宽泛排列的候选作用域列表。
 *
 * 包含最末兜底的旧版仅主机作用域（`pathScope = ""`），兼容历史已存入系统钥匙串的凭据。
 *
 * @param protocol 远程协议。
 * @param location 请求访问的远程路径（可为连接根路径或任意深层子路径）。
 * @return 按最长前缀优先去重后的候选作用域列表。
 */
fun candidateRemoteAuthScopes(
    protocol: VfsProtocol,
    location: String,
): List<RemoteAuthScope> {
    val parsed = parseLocationScopeParts(protocol, location)
    val hostOnly = RemoteAuthScope(
        protocol = protocol,
        scheme = parsed.scheme,
        authority = parsed.authority,
        pathScope = "",
    )
    if (parsed.segments.isEmpty()) {
        return listOf(hostOnly)
    }
    if (protocol == VfsProtocol.SMB) {
        val shareScope = hostOnly.copy(pathScope = "/${parsed.segments.first().lowercase()}")
        return listOf(shareScope, hostOnly)
    }
    return buildList {
        for (depth in parsed.segments.size downTo 1) {
            add(hostOnly.copy(pathScope = "/" + parsed.segments.take(depth).joinToString("/")))
        }
        add(hostOnly)
    }
}

/** 解析远程位置得到的规范化组成部分。 */
private data class ParsedLocationScopeParts(
    val scheme: String,
    val authority: String,
    val segments: List<String>,
)

/** 拆分远程位置中的 Scheme、Authority 与有效路径段。 */
private fun parseLocationScopeParts(
    protocol: VfsProtocol,
    location: String,
): ParsedLocationScopeParts {
    val trimmed = location.trim()
    val schemeSeparator = trimmed.indexOf("://")
    if (schemeSeparator < 0) {
        return ParsedLocationScopeParts(
            scheme = protocol.name.lowercase(),
            authority = trimmed.lowercase(),
            segments = emptyList(),
        )
    }
    val scheme = trimmed.substring(0, schemeSeparator).lowercase()
    val remainder = trimmed.substring(schemeSeparator + 3)
        .substringBefore('?')
        .substringBefore('#')
        .replace('\\', '/')
    val slashIndex = remainder.indexOf('/')
    val authority = (if (slashIndex >= 0) remainder.substring(0, slashIndex) else remainder).lowercase()
    val rawPath = if (slashIndex >= 0) remainder.substring(slashIndex + 1) else ""
    val segments = rawPath.split('/').filter { segment -> segment.isNotEmpty() }
    return ParsedLocationScopeParts(
        scheme = scheme,
        authority = authority,
        segments = segments,
    )
}
