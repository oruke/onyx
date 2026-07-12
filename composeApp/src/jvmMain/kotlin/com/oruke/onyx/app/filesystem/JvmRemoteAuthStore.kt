package com.oruke.onyx.app.filesystem

import java.io.StringReader
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.Properties
import java.util.concurrent.TimeUnit
import com.oruke.onyx.vfs.api.RemoteAuthStore
import com.oruke.onyx.vfs.api.RemoteKeyringAuthStore
import com.oruke.onyx.vfs.api.RemoteCredentialSavePolicy
import com.oruke.onyx.vfs.api.RemoteCredentialSaveResult
import com.oruke.onyx.vfs.api.VfsAuthContext
import com.oruke.onyx.vfs.api.VfsProtocol
import com.oruke.onyx.vfs.api.InMemoryRemoteAuthStore

/**
 * 组合会话凭据与操作系统安全凭据存储的 JVM 远程认证仓库。
 *
 * @param sessionStore 当前应用会话凭据存储。
 */
class JvmRemoteAuthStore(
    private val sessionStore: RemoteAuthStore = InMemoryRemoteAuthStore(),
) : RemoteKeyringAuthStore {
    private val hostPlatform: HostPlatform by lazy { detectHostPlatform() }
    private val windowsCredentialManager: JvmWindowsCredentialManager by lazy { JvmWindowsCredentialManager() }
    private val macOsKeychainManager: JvmMacOsKeychainManager by lazy { JvmMacOsKeychainManager() }
    private val systemKeyringAvailable: Boolean by lazy { detectSystemKeyringAvailable() }

    override fun authContext(
        protocol: VfsProtocol,
        location: String,
    ): VfsAuthContext {
        val sessionContext = sessionStore.authContext(protocol, location)
        return if (sessionContext != VfsAuthContext.None) {
            sessionContext
        } else if (isSystemKeyringAvailable()) {
            lookupSecret(remoteCredentialKey(protocol, location))
                ?.let(::decodeAuthContext)
                ?: VfsAuthContext.None
        } else {
            VfsAuthContext.None
        }
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
                val stored = isSystemKeyringAvailable() && storeSecret(
                    key = remoteCredentialKey(protocol, location),
                    secret = encodeAuthContext(authContext),
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
        sessionStore.clear(protocol, location)
        if (isSystemKeyringAvailable()) {
            clearSecret(remoteCredentialKey(protocol, location))
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

    private fun remoteCredentialKey(
        protocol: VfsProtocol,
        location: String,
    ): RemoteCredentialKey {
        val schemeSeparator = location.indexOf("://")
        if (schemeSeparator < 0) {
            return RemoteCredentialKey(
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
        return RemoteCredentialKey(
            protocol = protocol,
            scheme = scheme,
            authority = authority,
        )
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
        val protocol: VfsProtocol,
        val scheme: String,
        val authority: String,
    ) {
        val serviceName: String = "$KEYRING_APPLICATION.${protocol.name.lowercase()}.$scheme"
        val windowsTargetName: String = "$serviceName.$authority"
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
