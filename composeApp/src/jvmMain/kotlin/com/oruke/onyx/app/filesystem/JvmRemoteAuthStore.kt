package com.oruke.onyx.app.filesystem

import java.io.StringReader
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.Properties
import java.util.concurrent.TimeUnit
import com.oruke.onyx.vfs.api.RemoteAuthScope
import com.oruke.onyx.vfs.api.RemoteAuthStore
import com.oruke.onyx.vfs.api.RemoteKeyringAuthStore
import com.oruke.onyx.vfs.api.RemoteCredentialSavePolicy
import com.oruke.onyx.vfs.api.RemoteCredentialSaveResult
import com.oruke.onyx.vfs.api.VfsAuthContext
import com.oruke.onyx.vfs.api.VfsProtocol
import com.oruke.onyx.vfs.api.InMemoryRemoteAuthStore
import com.oruke.onyx.vfs.api.candidateRemoteAuthScopes
import com.oruke.onyx.vfs.api.remoteAuthScope

/**
 * 操作系统安全凭据存储后端抽象，默认委托平台钥匙串实现，测试可注入内存桩验证跨层优先级与清理行为。
 */
internal interface JvmKeyringSecretBackend {
    /** 当前运行环境钥匙串是否可用。 */
    fun isAvailable(): Boolean

    /** 读取指定精确作用域密钥。 */
    fun lookup(scope: RemoteAuthScope): String?

    /** 写入指定精确作用域密钥。 */
    fun store(scope: RemoteAuthScope, secret: String): Boolean

    /** 清理指定精确作用域密钥。 */
    fun clear(scope: RemoteAuthScope): Boolean
}

/**
 * 组合会话凭据与操作系统安全凭据存储的 JVM 远程认证仓库。
 *
 * 按候选作用域从最具体到最宽泛逐层查询：同一作用域内先查会话/临时存储再查系统钥匙串，
 * 避免宽泛的主机级会话凭据劫持更精确路径的系统钥匙串凭据。
 *
 * @param sessionStore 当前应用会话凭据存储。
 * @param keyringBackend 可选系统钥匙串后端实现；为空时自动接入宿主平台钥匙串。
 */
class JvmRemoteAuthStore internal constructor(
    private val sessionStore: RemoteAuthStore,
    private val keyringBackend: JvmKeyringSecretBackend?,
) : RemoteKeyringAuthStore {
    constructor(
        sessionStore: RemoteAuthStore = InMemoryRemoteAuthStore(),
    ) : this(sessionStore = sessionStore, keyringBackend = null)

    private val hostPlatform: HostPlatform by lazy { detectHostPlatform() }
    private val windowsCredentialManager: JvmWindowsCredentialManager by lazy { JvmWindowsCredentialManager() }
    private val macOsKeychainManager: JvmMacOsKeychainManager by lazy { JvmMacOsKeychainManager() }
    private val systemKeyringAvailable: Boolean by lazy {
        keyringBackend?.isAvailable() ?: detectSystemKeyringAvailable()
    }

    override fun authContext(
        protocol: VfsProtocol,
        location: String,
    ): VfsAuthContext {
        for (scope in candidateRemoteAuthScopes(protocol, location)) {
            val exact = authContextForScope(scope)
            if (exact != VfsAuthContext.None) {
                return exact
            }
        }
        return VfsAuthContext.None
    }

    override fun peekAuthContext(
        protocol: VfsProtocol,
        location: String,
    ): VfsAuthContext {
        for (scope in candidateRemoteAuthScopes(protocol, location)) {
            val exact = peekAuthContextForScope(scope)
            if (exact != VfsAuthContext.None) {
                return exact
            }
        }
        return VfsAuthContext.None
    }

    override fun peekExactAuthContext(
        protocol: VfsProtocol,
        location: String,
    ): VfsAuthContext = peekAuthContextForScope(remoteAuthScope(protocol, location))

    override fun authContextForScope(scope: RemoteAuthScope): VfsAuthContext {
        val sessionContext = sessionStore.authContextForScope(scope)
        if (sessionContext != VfsAuthContext.None) {
            return sessionContext
        }
        return lookupKeyringForExactScope(scope)
    }

    override fun peekAuthContextForScope(scope: RemoteAuthScope): VfsAuthContext {
        val sessionContext = sessionStore.peekAuthContextForScope(scope)
        if (sessionContext != VfsAuthContext.None) {
            return sessionContext
        }
        return lookupKeyringForExactScope(scope)
    }

    /**
     * 在系统钥匙串中仅查询给定精确作用域的凭据记录。
     *
     * @param scope 精确凭据作用域。
     * @return 解码后的认证上下文；未命中或系统钥匙串不可用时返回 [VfsAuthContext.None]。
     */
    private fun lookupKeyringForExactScope(scope: RemoteAuthScope): VfsAuthContext {
        if (!isSystemKeyringAvailable()) return VfsAuthContext.None
        val raw = keyringBackend?.lookup(scope) ?: lookupSecret(RemoteCredentialKey(scope))
        return raw?.let(::decodeAuthContext) ?: VfsAuthContext.None
    }

    override fun put(
        protocol: VfsProtocol,
        location: String,
        authContext: VfsAuthContext,
        savePolicy: RemoteCredentialSavePolicy,
    ): RemoteCredentialSaveResult {
        return when (savePolicy) {
            RemoteCredentialSavePolicy.DO_NOT_SAVE,
            RemoteCredentialSavePolicy.SESSION -> sessionStore.put(
                protocol = protocol,
                location = location,
                authContext = authContext,
                savePolicy = savePolicy,
            )

            RemoteCredentialSavePolicy.SYSTEM_KEYRING -> {
                val scope = remoteAuthScope(protocol, location)
                val encoded = encodeAuthContext(authContext)
                val stored = isSystemKeyringAvailable() && (
                    keyringBackend?.store(scope, encoded)
                        ?: storeSecret(key = RemoteCredentialKey(scope), secret = encoded)
                    )
                if (stored) {
                    sessionStore.put(
                        protocol = protocol,
                        location = location,
                        authContext = authContext,
                        savePolicy = RemoteCredentialSavePolicy.SESSION,
                    )
                    RemoteCredentialSaveResult.STORED_IN_SYSTEM_KEYRING
                } else {
                    RemoteCredentialSaveResult.UNSUPPORTED
                }
            }
        }
    }

    override fun clear(
        protocol: VfsProtocol,
        location: String,
    ) {
        clearScope(remoteAuthScope(protocol, location))
    }

    override fun clearScope(scope: RemoteAuthScope) {
        sessionStore.clearScope(scope)
        if (isSystemKeyringAvailable()) {
            keyringBackend?.clear(scope) ?: clearSecret(RemoteCredentialKey(scope))
        }
    }

    override fun clearSession() {
        sessionStore.clearSession()
    }

    override fun isSystemKeyringAvailable(): Boolean {
        return systemKeyringAvailable
    }

    private fun detectSystemKeyringAvailable(): Boolean {
        return when (hostPlatform) {
            HostPlatform.LINUX -> commandSucceeds(listOf("secret-tool", "--help"))
            HostPlatform.MACOS -> macOsKeychainManager.isAvailable()
            HostPlatform.WINDOWS -> windowsCredentialManager.isAvailable()
            HostPlatform.OTHER -> false
        }
    }

    private fun lookupSecret(key: RemoteCredentialKey): String? {
        return when (hostPlatform) {
            HostPlatform.LINUX -> runCommand(
                listOf(
                    "secret-tool",
                    "lookup",
                    "application",
                    KEYRING_APPLICATION,
                    "protocol",
                    key.protocol.name,
                    "scheme",
                    key.scheme,
                    "authority",
                    key.authority,
                )
            ).takeIf { it.exitCode == 0 }?.output?.trimEnd()

            HostPlatform.MACOS -> macOsKeychainManager.read(
                serviceName = key.serviceName,
                accountName = key.authority,
            )

            HostPlatform.WINDOWS -> windowsCredentialManager.read(key.windowsTargetName)
            HostPlatform.OTHER -> null
        }
    }

    private fun storeSecret(
        key: RemoteCredentialKey,
        secret: String,
    ): Boolean {
        return when (hostPlatform) {
            HostPlatform.LINUX -> runCommand(
                command = listOf(
                    "secret-tool",
                    "store",
                    "--label",
                    "Onyx ${key.protocol.name} ${key.authority}",
                    "application",
                    KEYRING_APPLICATION,
                    "protocol",
                    key.protocol.name,
                    "scheme",
                    key.scheme,
                    "authority",
                    key.authority,
                ),
                input = secret,
            ).exitCode == 0

            HostPlatform.MACOS -> macOsKeychainManager.write(
                serviceName = key.serviceName,
                accountName = key.authority,
                secret = secret,
            )

            HostPlatform.WINDOWS -> windowsCredentialManager.write(
                targetName = key.windowsTargetName,
                userName = key.authority,
                secret = secret,
            )

            HostPlatform.OTHER -> false
        }
    }

    private fun clearSecret(key: RemoteCredentialKey): Boolean {
        return when (hostPlatform) {
            HostPlatform.LINUX -> runCommand(
                listOf(
                    "secret-tool",
                    "clear",
                    "application",
                    KEYRING_APPLICATION,
                    "protocol",
                    key.protocol.name,
                    "scheme",
                    key.scheme,
                    "authority",
                    key.authority,
                )
            ).exitCode == 0

            HostPlatform.MACOS -> macOsKeychainManager.delete(
                serviceName = key.serviceName,
                accountName = key.authority,
            )

            HostPlatform.WINDOWS -> windowsCredentialManager.delete(key.windowsTargetName)
            HostPlatform.OTHER -> false
        }
    }

    private fun commandSucceeds(command: List<String>): Boolean {
        return runCommand(command).exitCode == 0
    }

    private fun runCommand(
        command: List<String>,
        input: String? = null,
    ): ProcessResult {
        return runCatching {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            process.outputStream.use { output ->
                if (input != null) {
                    output.write(input.toByteArray(StandardCharsets.UTF_8))
                }
            }
            val completed = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                return ProcessResult(exitCode = -1, output = "")
            }
            ProcessResult(
                exitCode = process.exitValue(),
                output = process.inputStream.readBytes().decodePlatformProcessOutput(),
            )
        }.getOrDefault(ProcessResult(exitCode = -1, output = ""))
    }

    private fun detectHostPlatform(): HostPlatform {
        val osName = System.getProperty("os.name").lowercase(Locale.ROOT)
        return when {
            osName.contains("mac") || osName.contains("darwin") -> HostPlatform.MACOS
            osName.contains("win") -> HostPlatform.WINDOWS
            osName.contains("nux") || osName.contains("nix") || osName.contains("linux") -> HostPlatform.LINUX
            else -> HostPlatform.OTHER
        }
    }

    private fun encodeAuthContext(authContext: VfsAuthContext): String {
        val properties = Properties()
        when (authContext) {
            is VfsAuthContext.UsernamePassword -> {
                properties.setProperty("type", "username_password")
                properties.setProperty("username", authContext.username)
                properties.setProperty("password", authContext.password)
                properties.setProperty("domain", authContext.domain.orEmpty())
            }

            is VfsAuthContext.AwsCredentials -> {
                properties.setProperty("type", "aws_credentials")
                properties.setProperty("accessKeyId", authContext.accessKeyId)
                properties.setProperty("secretAccessKey", authContext.secretAccessKey)
                properties.setProperty("sessionToken", authContext.sessionToken.orEmpty())
                properties.setProperty("region", authContext.region.orEmpty())
            }

            is VfsAuthContext.BearerToken -> {
                properties.setProperty("type", "bearer_token")
                properties.setProperty("token", authContext.token)
            }

            VfsAuthContext.None -> {
                properties.setProperty("type", "none")
            }
        }
        val writer = StringWriter()
        properties.store(writer, null)
        return writer.toString()
    }

    private fun decodeAuthContext(secret: String): VfsAuthContext? {
        val properties = Properties()
        properties.load(StringReader(secret))
        return when (properties.getProperty("type")) {
            "username_password" -> VfsAuthContext.UsernamePassword(
                username = properties.getProperty("username").orEmpty(),
                password = properties.getProperty("password").orEmpty(),
                domain = properties.getProperty("domain").takeIf { !it.isNullOrBlank() },
            )

            "aws_credentials" -> VfsAuthContext.AwsCredentials(
                accessKeyId = properties.getProperty("accessKeyId").orEmpty(),
                secretAccessKey = properties.getProperty("secretAccessKey").orEmpty(),
                sessionToken = properties.getProperty("sessionToken").takeIf { !it.isNullOrBlank() },
                region = properties.getProperty("region").takeIf { !it.isNullOrBlank() },
            )

            "bearer_token" -> VfsAuthContext.BearerToken(
                token = properties.getProperty("token").orEmpty(),
            )

            "none" -> VfsAuthContext.None
            else -> null
        }
    }

    private data class RemoteCredentialKey(
        val scope: RemoteAuthScope,
    ) {
        val protocol: VfsProtocol = scope.protocol
        val scheme: String = scope.scheme
        val authority: String = scope.scopedAuthority
        val serviceName: String = "$KEYRING_APPLICATION.${protocol.name.lowercase()}.$scheme"
        val windowsTargetName: String = "$serviceName.${scope.scopedAuthority}"
    }

    private data class ProcessResult(
        val exitCode: Int,
        val output: String,
    )

    private enum class HostPlatform {
        WINDOWS,
        MACOS,
        LINUX,
        OTHER,
    }

    private companion object {
        const val KEYRING_APPLICATION = "com.oruke.onyx.remote"
        const val PROCESS_TIMEOUT_SECONDS = 5L
    }
}
