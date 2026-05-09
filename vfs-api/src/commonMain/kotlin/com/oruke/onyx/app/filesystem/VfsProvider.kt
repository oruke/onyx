package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.VFile

enum class VfsProtocol {
    LOCAL,
    ARCHIVE,
    SMB,
    WEBDAV,
    S3,
}

enum class VfsProviderCapability {
    WATCH,
    TRASH,
    EXTERNAL_OPEN,
    READ_PREVIEW,
    THUMBNAIL,
    CREATE_FILE,
    CREATE_DIRECTORY,
    RENAME,
    DELETE,
    COPY,
    MOVE,
}

sealed interface VfsAuthContext {
    data object None : VfsAuthContext

    data class UsernamePassword(
        val username: String,
        val password: String,
        val domain: String? = null,
    ) : VfsAuthContext

    data class BearerToken(
        val token: String,
    ) : VfsAuthContext

    data class AwsCredentials(
        val accessKeyId: String,
        val secretAccessKey: String,
        val sessionToken: String? = null,
        val region: String? = null,
    ) : VfsAuthContext
}

sealed interface VfsProviderError {
    val protocol: VfsProtocol
    val location: String?

    data class AuthenticationRequired(
        override val protocol: VfsProtocol,
        override val location: String? = null,
    ) : VfsProviderError

    data class AuthenticationRejected(
        override val protocol: VfsProtocol,
        override val location: String? = null,
        val reason: String? = null,
    ) : VfsProviderError

    data class PermissionDenied(
        override val protocol: VfsProtocol,
        override val location: String? = null,
        val reason: String? = null,
    ) : VfsProviderError

    data class NotFound(
        override val protocol: VfsProtocol,
        override val location: String? = null,
    ) : VfsProviderError

    data class AlreadyExists(
        override val protocol: VfsProtocol,
        override val location: String? = null,
    ) : VfsProviderError

    data class NetworkFailure(
        override val protocol: VfsProtocol,
        override val location: String? = null,
        val reason: String? = null,
    ) : VfsProviderError

    data class UnsupportedOperation(
        override val protocol: VfsProtocol,
        override val location: String? = null,
        val capability: VfsProviderCapability? = null,
    ) : VfsProviderError

    data class CrossProviderTransferUnsupported(
        override val protocol: VfsProtocol,
        override val location: String? = null,
        val sourceProtocol: VfsProtocol,
        val sourceLocation: String?,
        val capability: VfsProviderCapability,
    ) : VfsProviderError
}

class VfsProviderException(
    val error: VfsProviderError,
) : IllegalStateException(error.toString())

class VfsProviderNotFoundException(
    location: String,
) : IllegalStateException("No VFS provider supports location: $location")

interface VfsProvider {
    val protocol: VfsProtocol

    val capabilities: Set<VfsProviderCapability>

    fun supports(location: String): Boolean

    fun defaultLocation(): String? = null

    suspend fun list(location: String): Result<List<VFile>>

    suspend fun totalSizeBytes(entries: List<VFile>): Result<Long> {
        return Result.success(entries.sumOf { entry -> entry.sizeBytes ?: 0L })
    }
}

class VfsProviderRegistry(
    providers: List<VfsProvider>,
) {
    private val providers = providers.toList()

    init {
        require(this.providers.isNotEmpty()) {
            "At least one VFS provider must be registered"
        }
    }

    fun providerFor(location: String): Result<VfsProvider> {
        val provider = providers.firstOrNull { candidate -> candidate.supports(location) }
        return if (provider != null) {
            Result.success(provider)
        } else {
            Result.failure(VfsProviderNotFoundException(location))
        }
    }

    fun defaultLocation(): String {
        return providers.firstNotNullOfOrNull { provider -> provider.defaultLocation() }
            ?: error("No VFS provider supplies a default location")
    }

    suspend fun list(location: String): Result<List<VFile>> {
        return providerFor(location).fold(
            onSuccess = { provider -> provider.list(location) },
            onFailure = { failure -> Result.failure(failure) },
        )
    }

    suspend fun totalSizeBytes(entries: List<VFile>): Result<Long> {
        return runCatching {
            entries
                .groupBy { entry -> providerFor(entry.location).getOrThrow() }
                .entries
                .sumOf { (provider, providerEntries) ->
                    provider.totalSizeBytes(providerEntries).getOrThrow()
                }
        }
    }
}
