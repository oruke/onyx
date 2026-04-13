package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.OnyxError
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileCapability
import com.oruke.onyx.core.model.VFileKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.pathString

class JvmLocalFileProvider {
    suspend fun list(location: String): Result<List<VFile>> = withContext(Dispatchers.IO) {
        runCatching {
            val directory = Path.of(location).normalize().toAbsolutePath()
            require(Files.exists(directory)) {
                throw NoSuchFileException(directory.pathString)
            }
            require(directory.isDirectory()) {
                throw IllegalArgumentException("$location is not a directory")
            }

            Files.newDirectoryStream(directory).use { stream ->
                stream
                    .map { child -> child.toVFile(directory) }
                    .sortedWith(
                        compareByDescending<VFile> { it.kind == VFileKind.DIRECTORY }
                            .thenBy { it.name.lowercase() }
                    )
                    .toList()
            }
        }.mapError()
    }

    fun defaultLocation(): String = Path.of(System.getProperty("user.home")).toAbsolutePath().pathString

    private fun Path.toVFile(parent: Path): VFile {
        val isDirectory = isDirectory()
        return VFile(
            id = toAbsolutePath().normalize().pathString,
            name = name.ifBlank { pathString },
            location = toAbsolutePath().normalize().pathString,
            parentLocation = parent.toAbsolutePath().normalize().pathString,
            kind = if (isDirectory) VFileKind.DIRECTORY else VFileKind.FILE,
            sizeBytes = if (isDirectory) null else Files.size(this),
            modifiedAtEpochMillis = getLastModifiedTime().toMillis(),
            capabilities = buildSet {
                add(VFileCapability.READ_METADATA)
                add(VFileCapability.RENAME)
                add(VFileCapability.DELETE)
                if (isDirectory) {
                    add(VFileCapability.LIST_CHILDREN)
                } else {
                    add(VFileCapability.READ_CONTENT)
                    add(VFileCapability.WRITE_CONTENT)
                }
            },
        )
    }

    private fun Result<List<VFile>>.mapError(): Result<List<VFile>> {
        return exceptionOrNull()?.let { throwable ->
            Result.failure(throwable.toOnyxError().toException())
        } ?: this
    }

    private fun Throwable.toOnyxError(): OnyxError {
        return when (this) {
            is AccessDeniedException -> OnyxError.AccessDenied(message = message ?: localizedMessage ?: "Access denied")
            is NoSuchFileException -> OnyxError.InvalidLocation(
                message = message ?: localizedMessage ?: "Invalid location"
            )

            is IllegalArgumentException -> OnyxError.InvalidLocation(
                message = message ?: localizedMessage ?: "Invalid location"
            )

            is IOException -> OnyxError.IoFailure(message = message ?: localizedMessage ?: "I/O failure")
            else -> OnyxError.IoFailure(message = message ?: localizedMessage ?: "Unexpected I/O failure")
        }
    }

    private fun OnyxError.toException(): IllegalStateException = IllegalStateException(message)
}
