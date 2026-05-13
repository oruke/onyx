package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileCapability
import com.oruke.onyx.core.model.VFileKind
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbAuthException
import jcifs.smb.SmbException
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.net.MalformedURLException
import java.net.UnknownHostException
import java.util.Properties

/**
 * 基于 jcifs-ng 的 SMB 客户端实现。
 */
class JcifsSmbClient : SmbClient {
    override suspend fun testConnection(
        location: String,
        authContext: VfsAuthContext,
    ) = withSmbContext(location, authContext) { context ->
        val directory = SmbFile(location, context)
        if (!directory.exists()) {
            throw VfsProviderException(VfsProviderError.NotFound(VfsProtocol.SMB, location))
        }
        if (!directory.isDirectory) {
            throw VfsProviderException(
                VfsProviderError.UnsupportedOperation(
                    protocol = VfsProtocol.SMB,
                    location = location,
                    capability = null,
                )
            )
        }
    }

    override suspend fun list(
        location: String,
        authContext: VfsAuthContext,
    ): List<VFile> = withSmbContext(location, authContext) { context ->
            val directory = SmbFile(location, context)
            if (!directory.exists()) {
                throw VfsProviderException(VfsProviderError.NotFound(VfsProtocol.SMB, location))
            }
            if (!directory.isDirectory) {
                throw VfsProviderException(
                    VfsProviderError.UnsupportedOperation(
                        protocol = VfsProtocol.SMB,
                        location = location,
                        capability = null,
                    )
                )
            }
            directory.listFiles()
                .map { child -> child.toVFile(parentLocation = directory.canonicalPath) }
                .sortedWith(
                    compareByDescending<VFile> { entry -> entry.kind == VFileKind.DIRECTORY }
                        .thenBy { entry -> entry.name.lowercase() }
                )
    }

    override suspend fun copy(
        entries: List<VFile>,
        targetDirectoryLocation: String,
        conflictStrategy: TransferConflictStrategy,
        authContext: VfsAuthContext,
    ) = withSmbContext(targetDirectoryLocation, authContext) { context ->
        val targetDirectory = requireDirectory(targetDirectoryLocation, context)
        entries.forEach { entry ->
            val source = SmbFile(entry.location, context)
            val target = resolveTransferTarget(
                source = source,
                targetDirectory = targetDirectory,
                conflictStrategy = conflictStrategy,
            ) ?: return@forEach

            if (conflictStrategy == TransferConflictStrategy.OVERWRITE && target.exists()) {
                target.delete()
            }
            source.copyTo(target)
        }
    }

    override suspend fun move(
        entries: List<VFile>,
        targetDirectoryLocation: String,
        conflictStrategy: TransferConflictStrategy,
        authContext: VfsAuthContext,
    ) = withSmbContext(targetDirectoryLocation, authContext) { context ->
        val targetDirectory = requireDirectory(targetDirectoryLocation, context)
        entries.forEach { entry ->
            val source = SmbFile(entry.location, context)
            val target = resolveTransferTarget(
                source = source,
                targetDirectory = targetDirectory,
                conflictStrategy = conflictStrategy,
            ) ?: return@forEach

            if (conflictStrategy == TransferConflictStrategy.OVERWRITE && target.exists()) {
                target.delete()
            }
            try {
                source.renameTo(target)
            } catch (_: SmbException) {
                source.copyTo(target)
                source.delete()
            }
        }
    }

    override suspend fun delete(
        entries: List<VFile>,
        authContext: VfsAuthContext,
    ) = withSmbContext(entries.firstOrNull()?.location, authContext) { context ->
        entries.forEach { entry ->
            SmbFile(entry.location, context).delete()
        }
    }

    override suspend fun rename(
        entry: VFile,
        targetName: String,
        authContext: VfsAuthContext,
    ): VFile = withSmbContext(entry.location, authContext) { context ->
        val source = SmbFile(entry.location, context)
        if (!source.exists()) {
            throw VfsProviderException(VfsProviderError.NotFound(VfsProtocol.SMB, entry.location))
        }
        val directory = source.isDirectory
        val sanitizedTargetName = targetName.trim()
        validateTargetName(sanitizedTargetName)
        val parentLocation = source.parent
        val parent = SmbFile(parentLocation, context)
        val target = SmbFile(parent, sanitizedTargetName.withDirectoryMarker(directory))
        if (target.canonicalPath == source.canonicalPath) {
            return@withSmbContext source.toVFile(parentLocation = parent.canonicalPath)
        }
        if (target.exists()) {
            throw VfsProviderException(VfsProviderError.AlreadyExists(VfsProtocol.SMB, target.canonicalPath))
        }
        source.renameTo(target)
        target.toVFile(parentLocation = parent.canonicalPath)
    }

    override suspend fun createFile(
        parentLocation: String,
        name: String,
        authContext: VfsAuthContext,
    ): VFile = withSmbContext(parentLocation, authContext) { context ->
        val parent = requireDirectory(parentLocation, context)
        val sanitizedName = name.trim()
        validateTargetName(sanitizedName)
        val target = SmbFile(parent, sanitizedName)
        if (target.exists()) {
            throw VfsProviderException(VfsProviderError.AlreadyExists(VfsProtocol.SMB, target.canonicalPath))
        }
        target.createNewFile()
        target.toVFile(parentLocation = parent.canonicalPath)
    }

    override suspend fun createDirectory(
        parentLocation: String,
        name: String,
        authContext: VfsAuthContext,
    ): VFile = withSmbContext(parentLocation, authContext) { context ->
        val parent = requireDirectory(parentLocation, context)
        val relativePath = normalizeRelativeDirectoryPath(name)
        val target = SmbFile(parent, "$relativePath/")
        if (target.exists()) {
            throw VfsProviderException(VfsProviderError.AlreadyExists(VfsProtocol.SMB, target.canonicalPath))
        }
        target.mkdirs()
        target.toVFile(parentLocation = parent.canonicalPath)
    }

    override suspend fun readFile(
        entry: VFile,
        authContext: VfsAuthContext,
    ): VfsContentSource = withSmbContext(entry.location, authContext) { context ->
        val source = SmbFile(entry.location, context)
        if (!source.exists()) {
            throw VfsProviderException(VfsProviderError.NotFound(VfsProtocol.SMB, entry.location))
        }
        if (source.isDirectory) {
            throw VfsProviderException(
                VfsProviderError.UnsupportedOperation(
                    protocol = VfsProtocol.SMB,
                    location = entry.location,
                    capability = VfsProviderCapability.READ_CONTENT,
                )
            )
        }
        VfsContentSource(
            name = source.name.trimEnd('/'),
            sizeBytes = source.length(),
            chunks = flow {
                source.inputStream.use { input ->
                    val buffer = ByteArray(CONTENT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        emit(buffer.copyOf(read))
                    }
                }
            }.flowOn(Dispatchers.IO),
        )
    }

    override suspend fun writeFile(
        parentLocation: String,
        name: String,
        chunks: Flow<ByteArray>,
        conflictStrategy: TransferConflictStrategy,
        authContext: VfsAuthContext,
    ): VFile? = withSmbContext(parentLocation, authContext) { context ->
        val parent = requireDirectory(parentLocation, context)
        val target = resolveContentTarget(
            name = name,
            targetDirectory = parent,
            conflictStrategy = conflictStrategy,
        ) ?: return@withSmbContext null
        if (conflictStrategy == TransferConflictStrategy.OVERWRITE && target.exists()) {
            if (target.isDirectory) {
                throw VfsProviderException(
                    VfsProviderError.UnsupportedOperation(
                        protocol = VfsProtocol.SMB,
                        location = target.canonicalPath,
                        capability = VfsProviderCapability.WRITE_CONTENT,
                    )
                )
            }
            target.delete()
        }
        target.outputStream.use { output ->
            chunks.collect { chunk -> output.write(chunk) }
        }
        target.toVFile(parentLocation = parent.canonicalPath)
    }

    private suspend fun <T> withSmbContext(
        location: String?,
        authContext: VfsAuthContext,
        block: suspend (CIFSContext) -> T,
    ): T = withContext(Dispatchers.IO) {
        val errorLocation = location
        try {
            block(baseContext(authContext))
        } catch (failure: VfsProviderException) {
            throw failure
        } catch (failure: SmbAuthException) {
            throw VfsProviderException(
                if (authContext == VfsAuthContext.None) {
                    VfsProviderError.AuthenticationRequired(VfsProtocol.SMB, errorLocation)
                } else {
                    VfsProviderError.AuthenticationRejected(
                        protocol = VfsProtocol.SMB,
                        location = errorLocation,
                        reason = failure.message,
                    )
                }
            )
        } catch (failure: SmbException) {
            throw VfsProviderException(failure.toProviderError(errorLocation))
        } catch (failure: UnknownHostException) {
            throw VfsProviderException(
                VfsProviderError.NetworkFailure(
                    protocol = VfsProtocol.SMB,
                    location = errorLocation,
                    reason = failure.message,
                )
            )
        } catch (failure: MalformedURLException) {
            throw VfsProviderException(
                VfsProviderError.NotFound(
                    protocol = VfsProtocol.SMB,
                    location = errorLocation,
                )
            )
        }
    }

    private fun baseContext(authContext: VfsAuthContext): CIFSContext {
        val properties = Properties()
        val base = BaseContext(PropertyConfiguration(properties))
        return when (authContext) {
            VfsAuthContext.None -> base.withAnonymousCredentials()
            is VfsAuthContext.UsernamePassword -> base.withCredentials(
                NtlmPasswordAuthenticator(
                    authContext.domain.orEmpty(),
                    authContext.username,
                    authContext.password,
                )
            )

            else -> throw VfsProviderException(
                VfsProviderError.UnsupportedOperation(
                    protocol = VfsProtocol.SMB,
                    capability = null,
                )
            )
        }
    }

    private fun requireDirectory(
        location: String,
        context: CIFSContext,
    ): SmbFile {
        val directory = SmbFile(location.withVfsTrailingSlash(), context)
        if (!directory.exists()) {
            throw VfsProviderException(VfsProviderError.NotFound(VfsProtocol.SMB, location))
        }
        if (!directory.isDirectory) {
            throw VfsProviderException(
                VfsProviderError.UnsupportedOperation(
                    protocol = VfsProtocol.SMB,
                    location = location,
                    capability = null,
                )
            )
        }
        return directory
    }

    private fun resolveTransferTarget(
        source: SmbFile,
        targetDirectory: SmbFile,
        conflictStrategy: TransferConflictStrategy,
    ): SmbFile? {
        if (!source.exists()) {
            throw VfsProviderException(VfsProviderError.NotFound(VfsProtocol.SMB, source.canonicalPath))
        }

        val sourceName = source.name.trimEnd('/')
        val directTarget = SmbFile(targetDirectory, sourceName.withDirectoryMarker(source.isDirectory))
        if (directTarget.canonicalPath == source.canonicalPath) {
            throw VfsProviderException(
                VfsProviderError.UnsupportedOperation(
                    protocol = VfsProtocol.SMB,
                    location = source.canonicalPath,
                    capability = null,
                )
            )
        }
        if (source.isDirectory && targetDirectory.canonicalPath.withVfsTrailingSlash()
                .startsWith(source.canonicalPath.withVfsTrailingSlash())
        ) {
            throw VfsProviderException(
                VfsProviderError.UnsupportedOperation(
                    protocol = VfsProtocol.SMB,
                    location = targetDirectory.canonicalPath,
                    capability = null,
                )
            )
        }

        return when {
            !directTarget.exists() -> directTarget
            conflictStrategy == TransferConflictStrategy.KEEP_BOTH -> availableTarget(
                source = source,
                targetDirectory = targetDirectory,
            )

            conflictStrategy == TransferConflictStrategy.OVERWRITE -> directTarget
            conflictStrategy == TransferConflictStrategy.SKIP -> null
            else -> directTarget
        }
    }

    private fun availableTarget(
        source: SmbFile,
        targetDirectory: SmbFile,
    ): SmbFile {
        val originalName = source.name.trimEnd('/')
        val directory = source.isDirectory
        val dotIndex = originalName.lastIndexOf('.')
        val hasExtension = !directory && dotIndex > 0 && dotIndex < originalName.lastIndex
        val baseName = if (hasExtension) originalName.substring(0, dotIndex) else originalName
        val extension = if (hasExtension) originalName.substring(dotIndex) else ""

        var copyIndex = 1
        var candidate = SmbFile(targetDirectory, originalName.withDirectoryMarker(directory))
        while (candidate.exists()) {
            val suffix = if (copyIndex == 1) " copy" else " copy $copyIndex"
            candidate = SmbFile(targetDirectory, "$baseName$suffix$extension".withDirectoryMarker(directory))
            copyIndex += 1
        }
        return candidate
    }

    private fun resolveContentTarget(
        name: String,
        targetDirectory: SmbFile,
        conflictStrategy: TransferConflictStrategy,
    ): SmbFile? {
        val sanitizedName = name.trim()
        validateTargetName(sanitizedName)
        val directTarget = SmbFile(targetDirectory, sanitizedName)
        return when {
            !directTarget.exists() -> directTarget
            conflictStrategy == TransferConflictStrategy.KEEP_BOTH -> availableContentTarget(
                originalName = sanitizedName,
                targetDirectory = targetDirectory,
            )

            conflictStrategy == TransferConflictStrategy.OVERWRITE -> directTarget
            conflictStrategy == TransferConflictStrategy.SKIP -> null
            else -> directTarget
        }
    }

    private fun availableContentTarget(
        originalName: String,
        targetDirectory: SmbFile,
    ): SmbFile {
        val dotIndex = originalName.lastIndexOf('.')
        val hasExtension = dotIndex > 0 && dotIndex < originalName.lastIndex
        val baseName = if (hasExtension) originalName.substring(0, dotIndex) else originalName
        val extension = if (hasExtension) originalName.substring(dotIndex) else ""

        var copyIndex = 1
        var candidate = SmbFile(targetDirectory, originalName)
        while (candidate.exists()) {
            val suffix = if (copyIndex == 1) " copy" else " copy $copyIndex"
            candidate = SmbFile(targetDirectory, "$baseName$suffix$extension")
            copyIndex += 1
        }
        return candidate
    }

    private fun SmbFile.toVFile(parentLocation: String): VFile {
        val directory = isDirectory
        val canonicalLocation = canonicalPath
        return VFile(
            id = canonicalLocation,
            name = name.trimEnd('/').ifBlank { canonicalLocation.trimEnd('/').substringAfterLast('/') },
            location = canonicalLocation,
            parentLocation = parentLocation,
            kind = if (directory) VFileKind.DIRECTORY else VFileKind.FILE,
            sizeBytes = if (directory) null else length(),
            modifiedAtEpochMillis = runCatching { lastModified() }.getOrNull(),
            hidden = runCatching { isHidden }.getOrDefault(false),
            capabilities = buildSet {
                add(VFileCapability.READ_METADATA)
                add(VFileCapability.RENAME)
                add(VFileCapability.DELETE)
                if (directory) {
                    add(VFileCapability.LIST_CHILDREN)
                } else {
                    add(VFileCapability.READ_CONTENT)
                    add(VFileCapability.WRITE_CONTENT)
                }
            },
        )
    }

    private fun validateTargetName(targetName: String) {
        if (targetName.isBlank()) {
            throw VfsProviderException(
                VfsProviderError.UnsupportedOperation(
                    protocol = VfsProtocol.SMB,
                    capability = null,
                )
            )
        }
        if ('/' in targetName || '\\' in targetName) {
            throw VfsProviderException(
                VfsProviderError.UnsupportedOperation(
                    protocol = VfsProtocol.SMB,
                    capability = null,
                )
            )
        }
    }

    private fun normalizeRelativeDirectoryPath(rawPath: String): String {
        val normalized = rawPath
            .trim()
            .replace('\\', '/')
            .trim('/')
        if (normalized.isBlank()) {
            throw VfsProviderException(
                VfsProviderError.UnsupportedOperation(
                    protocol = VfsProtocol.SMB,
                    capability = null,
                )
            )
        }
        val segments = normalized.split('/')
        if (segments.any { segment -> segment.isBlank() || segment == "." || segment == ".." }) {
            throw VfsProviderException(
                VfsProviderError.UnsupportedOperation(
                    protocol = VfsProtocol.SMB,
                    capability = null,
                )
            )
        }
        return segments.joinToString("/")
    }

    private fun String.withDirectoryMarker(directory: Boolean): String {
        return if (directory) "${trimEnd('/')}/" else trimEnd('/')
    }

    private fun SmbException.toProviderError(location: String?): VfsProviderError {
        val message = message
        return when {
            this is SmbAuthException -> VfsProviderError.AuthenticationRejected(VfsProtocol.SMB, location, message)
            message?.contains("access", ignoreCase = true) == true ->
                VfsProviderError.PermissionDenied(VfsProtocol.SMB, location, message)

            message?.contains("not found", ignoreCase = true) == true ->
                VfsProviderError.NotFound(VfsProtocol.SMB, location)

            message?.contains("already exists", ignoreCase = true) == true ||
                message?.contains("object name collision", ignoreCase = true) == true ->
                VfsProviderError.AlreadyExists(VfsProtocol.SMB, location)

            else -> VfsProviderError.NetworkFailure(VfsProtocol.SMB, location, message)
        }
    }

    private companion object {
        const val CONTENT_BUFFER_SIZE = 64 * 1024
    }
}
