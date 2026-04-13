package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.OnyxError
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileCapability
import com.oruke.onyx.core.model.VFileKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
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

    suspend fun delete(entries: List<VFile>): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            entries.forEach { entry ->
                deletePathRecursively(Path.of(entry.location))
            }
        }.mapDeleteError()
    }

    suspend fun copy(
        entries: List<VFile>,
        targetDirectoryLocation: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val targetDirectory = resolveTargetDirectory(targetDirectoryLocation)
            entries.forEach { entry ->
                copyPathToDirectory(
                    source = Path.of(entry.location),
                    targetDirectory = targetDirectory,
                )
            }
        }.mapUnitError()
    }

    suspend fun move(
        entries: List<VFile>,
        targetDirectoryLocation: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val targetDirectory = resolveTargetDirectory(targetDirectoryLocation)
            entries.forEach { entry ->
                movePathToDirectory(
                    source = Path.of(entry.location),
                    targetDirectory = targetDirectory,
                )
            }
        }.mapUnitError()
    }

    suspend fun rename(
        entry: VFile,
        targetName: String,
    ): Result<VFile> = withContext(Dispatchers.IO) {
        runCatching {
            val source = Path.of(entry.location).normalize().toAbsolutePath()
            val sanitizedTargetName = targetName.trim()
            validateTargetName(sanitizedTargetName)

            val target = source.resolveSibling(sanitizedTargetName).normalize().toAbsolutePath()
            if (target == source) {
                return@runCatching source.toVFile(source.parent ?: source.root ?: source)
            }
            if (Files.exists(target)) {
                throw FileAlreadyExistsException(target.pathString)
            }

            Files.move(source, target)
            target.toVFile(target.parent ?: target.root ?: target)
        }.mapVFileError()
    }

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

    private fun Result<Unit>.mapDeleteError(): Result<Unit> {
        return mapUnitError()
    }

    private fun Result<Unit>.mapUnitError(): Result<Unit> {
        return exceptionOrNull()?.let { throwable ->
            Result.failure(throwable.toOnyxError().toException())
        } ?: this
    }

    private fun Result<VFile>.mapVFileError(): Result<VFile> {
        return exceptionOrNull()?.let { throwable ->
            Result.failure(throwable.toOnyxError().toException())
        } ?: this
    }

    private fun resolveTargetDirectory(targetDirectoryLocation: String): Path {
        val targetDirectory = Path.of(targetDirectoryLocation).normalize().toAbsolutePath()
        require(Files.exists(targetDirectory)) {
            throw NoSuchFileException(targetDirectory.pathString)
        }
        require(Files.isDirectory(targetDirectory)) {
            throw IllegalArgumentException("$targetDirectoryLocation is not a directory")
        }
        return targetDirectory
    }

    private fun copyPathToDirectory(
        source: Path,
        targetDirectory: Path,
    ) {
        val normalizedSource = source.normalize().toAbsolutePath()
        val target = buildTargetPath(
            source = normalizedSource,
            targetDirectory = targetDirectory,
        )
        copyPathRecursively(
            source = normalizedSource,
            target = target,
        )
    }

    private fun movePathToDirectory(
        source: Path,
        targetDirectory: Path,
    ) {
        val normalizedSource = source.normalize().toAbsolutePath()
        val target = buildTargetPath(
            source = normalizedSource,
            targetDirectory = targetDirectory,
        )

        try {
            Files.move(normalizedSource, target)
        } catch (_: IOException) {
            copyPathRecursively(
                source = normalizedSource,
                target = target,
            )
            try {
                deletePathRecursively(normalizedSource)
            } catch (deleteFailure: Throwable) {
                runCatching { deletePathRecursively(target) }
                throw deleteFailure
            }
        }
    }

    private fun buildTargetPath(
        source: Path,
        targetDirectory: Path,
    ): Path {
        require(Files.exists(source)) {
            throw NoSuchFileException(source.pathString)
        }

        val target = targetDirectory.resolve(source.fileName.toString()).normalize().toAbsolutePath()
        if (target == source) {
            throw IllegalArgumentException("Source and target cannot be the same path")
        }
        if (Files.exists(target)) {
            throw FileAlreadyExistsException(target.pathString)
        }
        if (Files.isDirectory(source) && targetDirectory.startsWith(source)) {
            throw IllegalArgumentException("Cannot place a directory into itself")
        }
        return target
    }

    private fun copyPathRecursively(
        source: Path,
        target: Path,
    ) {
        if (Files.isDirectory(source)) {
            Files.walkFileTree(
                source,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(
                        dir: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        Files.createDirectories(target.resolve(source.relativize(dir)))
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(
                        file: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        Files.copy(
                            file,
                            target.resolve(source.relativize(file)),
                            StandardCopyOption.COPY_ATTRIBUTES,
                        )
                        return FileVisitResult.CONTINUE
                    }
                }
            )
        } else {
            Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES)
        }
    }

    private fun validateTargetName(targetName: String) {
        require(targetName.isNotBlank()) {
            throw IllegalArgumentException("Name cannot be blank")
        }
        require('/' !in targetName && '\\' !in targetName) {
            throw IllegalArgumentException("Name cannot contain path separators")
        }
    }

    private fun deletePathRecursively(path: Path) {
        if (!Files.exists(path)) {
            return
        }
        if (Files.isDirectory(path)) {
            Files.walk(path).use { stream ->
                stream
                    .sorted(Comparator.reverseOrder())
                    .forEach { current ->
                        Files.deleteIfExists(current)
                    }
            }
        } else {
            Files.deleteIfExists(path)
        }
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
