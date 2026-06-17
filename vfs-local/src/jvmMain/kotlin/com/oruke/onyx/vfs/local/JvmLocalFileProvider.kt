package com.oruke.onyx.vfs.local

import com.oruke.onyx.core.model.OnyxError
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileCapability
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.vfs.api.FileRepository
import com.oruke.onyx.vfs.api.RoutableFileCommandService
import com.oruke.onyx.vfs.api.RoutableVfsContentService
import com.oruke.onyx.vfs.api.TransferConflictStrategy
import com.oruke.onyx.vfs.api.VfsContentSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.IOException
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

class JvmLocalFileProvider : FileRepository, RoutableFileCommandService, RoutableVfsContentService {
    override fun supports(location: String): Boolean {
        return !location.contains("://")
    }

    override suspend fun list(location: String): Result<List<VFile>> = withContext(Dispatchers.IO) {
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

    override fun defaultLocation(): String = Path.of(System.getProperty("user.home")).toAbsolutePath().pathString

    override suspend fun delete(entries: List<VFile>): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            entries.forEach { entry ->
                deletePathRecursively(Path.of(entry.location))
            }
        }.mapDeleteError()
    }

    override suspend fun copy(
        entries: List<VFile>,
        targetDirectoryLocation: String,
        conflictStrategy: TransferConflictStrategy,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val targetDirectory = resolveTargetDirectory(targetDirectoryLocation)
            entries.forEach { entry ->
                copyPathToDirectory(
                    source = Path.of(entry.location),
                    targetDirectory = targetDirectory,
                    conflictStrategy = conflictStrategy,
                )
            }
        }.mapUnitError()
    }

    override suspend fun move(
        entries: List<VFile>,
        targetDirectoryLocation: String,
        conflictStrategy: TransferConflictStrategy,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val targetDirectory = resolveTargetDirectory(targetDirectoryLocation)
            entries.forEach { entry ->
                movePathToDirectory(
                    source = Path.of(entry.location),
                    targetDirectory = targetDirectory,
                    conflictStrategy = conflictStrategy,
                )
            }
        }.mapUnitError()
    }

    override suspend fun rename(
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

    override suspend fun createFile(
        parentLocation: String,
        name: String,
    ): Result<VFile> = withContext(Dispatchers.IO) {
        runCatching {
            val parentDirectory = resolveTargetDirectory(parentLocation)
            val target = resolveCreateTarget(parentDirectory, name)
            Files.createFile(target)
            target.toVFile(parentDirectory)
        }.mapVFileError()
    }

    override suspend fun createDirectory(
        parentLocation: String,
        name: String,
    ): Result<VFile> = withContext(Dispatchers.IO) {
        runCatching {
            val parentDirectory = resolveTargetDirectory(parentLocation)
            val target = resolveCreateDirectoryTarget(parentDirectory, name)
            Files.createDirectories(target)
            target.toVFile(target.parent ?: parentDirectory)
        }.mapVFileError()
    }

    override suspend fun readFile(entry: VFile): Result<VfsContentSource> = withContext(Dispatchers.IO) {
        runCatching {
            val path = Path.of(entry.location).normalize().toAbsolutePath()
            require(Files.exists(path)) {
                throw NoSuchFileException(path.pathString)
            }
            require(!Files.isDirectory(path)) {
                throw IllegalArgumentException("${entry.location} is not a file")
            }
            VfsContentSource(
                name = path.fileName.toString(),
                sizeBytes = Files.size(path),
                chunks = flow {
                    Files.newInputStream(path).use { input ->
                        val buffer = ByteArray(CONTENT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            emit(buffer.copyOf(read))
                        }
                    }
                }.flowOn(Dispatchers.IO),
            )
        }.mapContentSourceError()
    }

    override suspend fun writeFile(
        parentLocation: String,
        name: String,
        chunks: kotlinx.coroutines.flow.Flow<ByteArray>,
        conflictStrategy: TransferConflictStrategy,
    ): Result<VFile?> = withContext(Dispatchers.IO) {
        runCatching {
            val parentDirectory = resolveTargetDirectory(parentLocation)
            val target = buildContentTargetPath(
                name = name,
                targetDirectory = parentDirectory,
                conflictStrategy = conflictStrategy,
            ) ?: return@runCatching null
            if (conflictStrategy == TransferConflictStrategy.OVERWRITE && Files.exists(target)) {
                deletePathRecursively(target)
            }
            Files.newOutputStream(target).use { output ->
                chunks.collect { chunk -> output.write(chunk) }
            }
            target.toVFile(parentDirectory)
        }.mapNullableVFileError()
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
            hidden = runCatching { Files.isHidden(this) }.getOrDefault(false) || name.startsWith("."),
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

    private fun resolveCreateTarget(
        parentDirectory: Path,
        name: String,
    ): Path {
        val sanitizedName = name.trim()
        validateTargetName(sanitizedName)
        val target = parentDirectory.resolve(sanitizedName).normalize().toAbsolutePath()
        if (target == parentDirectory) {
            throw IllegalArgumentException("Cannot create entry with empty name")
        }
        if (Files.exists(target)) {
            throw FileAlreadyExistsException(target.pathString)
        }
        return target
    }

    private fun resolveCreateDirectoryTarget(
        parentDirectory: Path,
        rawName: String,
    ): Path {
        val normalizedRelativePath = normalizeRelativeDirectoryPath(rawName)
        val target = parentDirectory.resolve(normalizedRelativePath).normalize().toAbsolutePath()
        require(target.startsWith(parentDirectory)) {
            throw IllegalArgumentException("Directory path must stay inside the current location")
        }
        if (Files.exists(target)) {
            throw FileAlreadyExistsException(target.pathString)
        }
        return target
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

    private fun Result<VFile?>.mapNullableVFileError(): Result<VFile?> {
        return exceptionOrNull()?.let { throwable ->
            Result.failure(throwable.toOnyxError().toException())
        } ?: this
    }

    private fun Result<VfsContentSource>.mapContentSourceError(): Result<VfsContentSource> {
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
        conflictStrategy: TransferConflictStrategy,
    ) {
        val normalizedSource = source.normalize().toAbsolutePath()
        val target = buildTargetPath(
            source = normalizedSource,
            targetDirectory = targetDirectory,
            conflictStrategy = conflictStrategy,
        ) ?: return
        if (conflictStrategy == TransferConflictStrategy.OVERWRITE && Files.exists(target)) {
            deletePathRecursively(target)
        }
        copyPathRecursively(
            source = normalizedSource,
            target = target,
        )
    }

    private fun movePathToDirectory(
        source: Path,
        targetDirectory: Path,
        conflictStrategy: TransferConflictStrategy,
    ) {
        val normalizedSource = source.normalize().toAbsolutePath()
        val target = buildTargetPath(
            source = normalizedSource,
            targetDirectory = targetDirectory,
            conflictStrategy = conflictStrategy,
        ) ?: return

        if (conflictStrategy == TransferConflictStrategy.OVERWRITE && Files.exists(target)) {
            deletePathRecursively(target)
        }

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
        conflictStrategy: TransferConflictStrategy,
    ): Path? {
        require(Files.exists(source)) {
            throw NoSuchFileException(source.pathString)
        }

        val directTarget = targetDirectory.resolve(source.fileName.toString()).normalize().toAbsolutePath()
        val target = when {
            !Files.exists(directTarget) -> directTarget
            conflictStrategy == TransferConflictStrategy.KEEP_BOTH -> availableTargetPath(
                source = source,
                targetDirectory = targetDirectory,
            )

            conflictStrategy == TransferConflictStrategy.OVERWRITE -> directTarget
            conflictStrategy == TransferConflictStrategy.SKIP -> return null
            else -> directTarget
        }
        if (target == source) {
            throw IllegalArgumentException("Source and target cannot be the same path")
        }
        if (Files.isDirectory(source) && targetDirectory.startsWith(source)) {
            throw IllegalArgumentException("Cannot place a directory into itself")
        }
        return target
    }

    private fun availableTargetPath(
        source: Path,
        targetDirectory: Path,
    ): Path {
        return availableTargetPath(
            originalName = source.fileName.toString(),
            isDirectory = Files.isDirectory(source),
            targetDirectory = targetDirectory,
        )
    }

    private fun availableTargetPath(
        originalName: String,
        isDirectory: Boolean,
        targetDirectory: Path,
    ): Path {
        val dotIndex = originalName.lastIndexOf('.')
        val hasExtension = !isDirectory && dotIndex > 0 && dotIndex < originalName.lastIndex
        val baseName = if (hasExtension) originalName.substring(0, dotIndex) else originalName
        val extension = if (hasExtension) originalName.substring(dotIndex) else ""

        var candidate = targetDirectory.resolve(originalName).normalize().toAbsolutePath()
        var copyIndex = 1
        while (Files.exists(candidate)) {
            val suffix = if (copyIndex == 1) " copy" else " copy $copyIndex"
            candidate = targetDirectory.resolve("$baseName$suffix$extension").normalize().toAbsolutePath()
            copyIndex += 1
        }
        return candidate
    }

    private fun buildContentTargetPath(
        name: String,
        targetDirectory: Path,
        conflictStrategy: TransferConflictStrategy,
    ): Path? {
        val sanitizedName = name.trim()
        validateTargetName(sanitizedName)
        val directTarget = targetDirectory.resolve(sanitizedName).normalize().toAbsolutePath()
        require(directTarget.startsWith(targetDirectory)) {
            throw IllegalArgumentException("Target path must stay inside the current location")
        }
        return when {
            !Files.exists(directTarget) -> directTarget
            conflictStrategy == TransferConflictStrategy.KEEP_BOTH -> availableTargetPath(
                originalName = sanitizedName,
                isDirectory = false,
                targetDirectory = targetDirectory,
            )

            conflictStrategy == TransferConflictStrategy.OVERWRITE -> directTarget
            conflictStrategy == TransferConflictStrategy.SKIP -> null
            else -> directTarget
        }
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

    private fun normalizeRelativeDirectoryPath(rawPath: String): String {
        val trimmed = rawPath.trim()
        require(!trimmed.startsWith('/') && !trimmed.startsWith('\\')) {
            throw IllegalArgumentException("Directory path must be relative")
        }
        require(!trimmed.matches(Regex("^[A-Za-z]:.*"))) {
            throw IllegalArgumentException("Directory path must be relative")
        }
        val normalized = rawPath
            .trim()
            .replace('\\', '/')
            .trim('/')
        require(normalized.isNotBlank()) {
            throw IllegalArgumentException("Directory path cannot be blank")
        }
        val segments = normalized.split('/')
        require(segments.all { segment ->
            segment.isNotBlank() && segment != "." && segment != ".."
        }) {
            throw IllegalArgumentException("Directory path contains invalid segments")
        }
        return segments.joinToString("/")
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

    private companion object {
        const val CONTENT_BUFFER_SIZE = 64 * 1024
    }
}
