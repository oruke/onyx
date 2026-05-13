package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.AppSessionSnapshot
import com.oruke.onyx.core.model.BackgroundTask
import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import kotlinx.coroutines.flow.Flow

interface FileRepository {
    suspend fun list(location: String): Result<List<VFile>>

    fun defaultLocation(): String
}

interface FileCommandService {
    suspend fun copy(
        entries: List<VFile>,
        targetDirectoryLocation: String,
        conflictStrategy: TransferConflictStrategy = TransferConflictStrategy.KEEP_BOTH,
    ): Result<Unit>

    suspend fun move(
        entries: List<VFile>,
        targetDirectoryLocation: String,
        conflictStrategy: TransferConflictStrategy = TransferConflictStrategy.KEEP_BOTH,
    ): Result<Unit>

    suspend fun delete(entries: List<VFile>): Result<Unit>

    suspend fun rename(
        entry: VFile,
        targetName: String,
    ): Result<VFile>

    suspend fun createFile(
        parentLocation: String,
        name: String,
    ): Result<VFile>

    suspend fun createDirectory(
        parentLocation: String,
        name: String,
    ): Result<VFile>
}

interface RoutableFileCommandService : FileCommandService {
    fun supports(location: String): Boolean
}

data class VfsContentSource(
    val name: String,
    val sizeBytes: Long?,
    val chunks: Flow<ByteArray>,
)

interface RoutableVfsContentService {
    fun supports(location: String): Boolean

    suspend fun readFile(entry: VFile): Result<VfsContentSource>

    suspend fun writeFile(
        parentLocation: String,
        name: String,
        chunks: Flow<ByteArray>,
        conflictStrategy: TransferConflictStrategy = TransferConflictStrategy.KEEP_BOTH,
    ): Result<VFile?>
}

class ProviderBackedFileCommandService(
    services: List<RoutableFileCommandService>,
    contentServices: List<RoutableVfsContentService> = emptyList(),
    private val providerRegistry: VfsProviderRegistry? = null,
) : FileCommandService {
    private val services = services.toList()
    private val contentServices = contentServices.toList()

    init {
        require(this.services.isNotEmpty()) {
            "At least one file command service must be registered"
        }
    }

    override suspend fun copy(
        entries: List<VFile>,
        targetDirectoryLocation: String,
        conflictStrategy: TransferConflictStrategy,
    ): Result<Unit> {
        if (entries.isEmpty()) return Result.success(Unit)
        return runCatching {
            entries.groupByCommandService(VfsProviderCapability.COPY).forEach { (service, serviceEntries) ->
                if (!service.supports(targetDirectoryLocation)) {
                    val copied = copyAcrossProviders(
                        entries = serviceEntries,
                        targetDirectoryLocation = targetDirectoryLocation,
                        conflictStrategy = conflictStrategy,
                    ).getOrThrow()
                    if (!copied) {
                        throw crossProviderUnsupportedFor(
                            sourceLocation = serviceEntries.firstOrNull()?.location,
                            targetLocation = targetDirectoryLocation,
                            capability = VfsProviderCapability.COPY,
                        )
                    }
                    return@forEach
                }
                service.copy(
                    entries = serviceEntries,
                    targetDirectoryLocation = targetDirectoryLocation,
                    conflictStrategy = conflictStrategy,
                ).getOrThrow()
            }
        }
    }

    override suspend fun move(
        entries: List<VFile>,
        targetDirectoryLocation: String,
        conflictStrategy: TransferConflictStrategy,
    ): Result<Unit> {
        if (entries.isEmpty()) return Result.success(Unit)
        return runCatching {
            entries.groupByCommandService(VfsProviderCapability.MOVE).forEach { (service, serviceEntries) ->
                if (!service.supports(targetDirectoryLocation)) {
                    val moved = moveAcrossProviders(
                        sourceCommandService = service,
                        entries = serviceEntries,
                        targetDirectoryLocation = targetDirectoryLocation,
                        conflictStrategy = conflictStrategy,
                    ).getOrThrow()
                    if (!moved) {
                        throw crossProviderUnsupportedFor(
                            sourceLocation = serviceEntries.firstOrNull()?.location,
                            targetLocation = targetDirectoryLocation,
                            capability = VfsProviderCapability.MOVE,
                        )
                    }
                    return@forEach
                }
                service.move(
                    entries = serviceEntries,
                    targetDirectoryLocation = targetDirectoryLocation,
                    conflictStrategy = conflictStrategy,
                ).getOrThrow()
            }
        }
    }

    override suspend fun delete(entries: List<VFile>): Result<Unit> {
        if (entries.isEmpty()) return Result.success(Unit)
        return runCatching {
            entries.groupByCommandService(VfsProviderCapability.DELETE).forEach { (service, serviceEntries) ->
                service.delete(serviceEntries).getOrThrow()
            }
        }
    }

    override suspend fun rename(
        entry: VFile,
        targetName: String,
    ): Result<VFile> {
        return serviceFor(entry.location, VfsProviderCapability.RENAME).fold(
            onSuccess = { service -> service.rename(entry, targetName) },
            onFailure = { failure -> Result.failure(failure) },
        )
    }

    override suspend fun createFile(
        parentLocation: String,
        name: String,
    ): Result<VFile> {
        return serviceFor(parentLocation, VfsProviderCapability.CREATE_FILE).fold(
            onSuccess = { service -> service.createFile(parentLocation, name) },
            onFailure = { failure -> Result.failure(failure) },
        )
    }

    override suspend fun createDirectory(
        parentLocation: String,
        name: String,
    ): Result<VFile> {
        return serviceFor(parentLocation, VfsProviderCapability.CREATE_DIRECTORY).fold(
            onSuccess = { service -> service.createDirectory(parentLocation, name) },
            onFailure = { failure -> Result.failure(failure) },
        )
    }

    private fun List<VFile>.groupByCommandService(
        capability: VfsProviderCapability,
    ): Map<RoutableFileCommandService, List<VFile>> {
        return groupBy { entry -> serviceFor(entry.location, capability).getOrThrow() }
    }

    private fun serviceFor(
        location: String,
        capability: VfsProviderCapability,
    ): Result<RoutableFileCommandService> {
        val service = services.firstOrNull { candidate -> candidate.supports(location) }
        return if (service != null) {
            Result.success(service)
        } else {
            Result.failure(unsupportedFor(location, capability))
        }
    }

    private suspend fun copyAcrossProviders(
        entries: List<VFile>,
        targetDirectoryLocation: String,
        conflictStrategy: TransferConflictStrategy,
    ): Result<Boolean> {
        if (contentServices.isEmpty()) {
            return Result.success(false)
        }
        val hasDirectories = entries.any { entry -> entry.kind == VFileKind.DIRECTORY }
        if (hasDirectories && providerRegistry == null) {
            return Result.success(false)
        }
        return contentServiceFor(targetDirectoryLocation, VfsProviderCapability.WRITE_CONTENT).fold(
            onSuccess = { targetContentService ->
                runCatching {
                    val targetCommandService = if (hasDirectories) {
                        serviceFor(targetDirectoryLocation, VfsProviderCapability.CREATE_DIRECTORY).getOrThrow()
                    } else {
                        null
                    }
                    entries.forEach { entry ->
                        copyEntryAcrossProviders(
                            entry = entry,
                            targetDirectoryLocation = targetDirectoryLocation,
                            targetContentService = targetContentService,
                            targetCommandService = targetCommandService,
                            conflictStrategy = conflictStrategy,
                        )
                    }
                    true
                }
            },
            onFailure = { failure ->
                if (failure is VfsProviderNotFoundException) {
                    Result.success(false)
                } else {
                    Result.failure(failure)
                }
            },
        )
    }

    /**
     * 递归复制一个跨 provider 条目。
     *
     * 文件通过统一内容流读写；目录先在目标 provider 创建同名目录，再通过 `VfsProviderRegistry`
     * 列出子项递归复制。这样目录级跨 provider 传输仍遵守 VFS/provider 边界，不直接解析平台路径。
     *
     * @param entry 当前需要复制的源条目。
     * @param targetDirectoryLocation 目标父目录位置。
     * @param targetContentService 目标 provider 的内容写入服务。
     * @param targetCommandService 目标 provider 的目录创建服务；复制目录时必需。
     * @param conflictStrategy 文件冲突处理策略。
     */
    private suspend fun copyEntryAcrossProviders(
        entry: VFile,
        targetDirectoryLocation: String,
        targetContentService: RoutableVfsContentService,
        targetCommandService: RoutableFileCommandService?,
        conflictStrategy: TransferConflictStrategy,
    ) {
        when (entry.kind) {
            VFileKind.FILE -> copyFileAcrossProviders(
                entry = entry,
                targetDirectoryLocation = targetDirectoryLocation,
                targetContentService = targetContentService,
                conflictStrategy = conflictStrategy,
            )

            VFileKind.DIRECTORY -> {
                val directoryService = targetCommandService
                    ?: throw unsupportedFor(targetDirectoryLocation, VfsProviderCapability.CREATE_DIRECTORY)
                val targetDirectory = directoryService.createDirectory(targetDirectoryLocation, entry.name).getOrThrow()
                val children = providerRegistry
                    ?.list(entry.location)
                    ?.getOrThrow()
                    ?: throw crossProviderUnsupportedFor(
                        sourceLocation = entry.location,
                        targetLocation = targetDirectoryLocation,
                        capability = VfsProviderCapability.COPY,
                    )
                children.forEach { child ->
                    copyEntryAcrossProviders(
                        entry = child,
                        targetDirectoryLocation = targetDirectory.location,
                        targetContentService = targetContentService,
                        targetCommandService = targetCommandService,
                        conflictStrategy = conflictStrategy,
                    )
                }
            }
        }
    }

    /**
     * 复制单个跨 provider 文件。
     *
     * @param entry 当前需要复制的源文件。
     * @param targetDirectoryLocation 目标父目录位置。
     * @param targetContentService 目标 provider 的内容写入服务。
     * @param conflictStrategy 文件冲突处理策略。
     */
    private suspend fun copyFileAcrossProviders(
        entry: VFile,
        targetDirectoryLocation: String,
        targetContentService: RoutableVfsContentService,
        conflictStrategy: TransferConflictStrategy,
    ) {
        val sourceContentService = contentServiceFor(
            location = entry.location,
            capability = VfsProviderCapability.READ_CONTENT,
        ).getOrThrow()
        val source = sourceContentService.readFile(entry).getOrThrow()
        targetContentService.writeFile(
            parentLocation = targetDirectoryLocation,
            name = source.name,
            chunks = source.chunks,
            conflictStrategy = conflictStrategy,
        ).getOrThrow()
    }

    private suspend fun moveAcrossProviders(
        sourceCommandService: RoutableFileCommandService,
        entries: List<VFile>,
        targetDirectoryLocation: String,
        conflictStrategy: TransferConflictStrategy,
    ): Result<Boolean> {
        return copyAcrossProviders(
            entries = entries,
            targetDirectoryLocation = targetDirectoryLocation,
            conflictStrategy = conflictStrategy,
        ).mapCatching { copied ->
            if (copied) {
                sourceCommandService.delete(entries).getOrThrow()
            }
            copied
        }
    }

    private fun contentServiceFor(
        location: String,
        capability: VfsProviderCapability,
    ): Result<RoutableVfsContentService> {
        val service = contentServices.firstOrNull { candidate -> candidate.supports(location) }
        return if (service != null) {
            Result.success(service)
        } else {
            Result.failure(unsupportedFor(location, capability))
        }
    }

    private fun unsupportedFor(
        location: String,
        capability: VfsProviderCapability,
    ): Throwable {
        val provider = providerRegistry?.providerFor(location)?.getOrNull()
        return if (provider != null) {
            VfsProviderException(
                VfsProviderError.UnsupportedOperation(
                    protocol = provider.protocol,
                    location = location,
                    capability = capability,
                )
            )
        } else {
            VfsProviderNotFoundException(location)
        }
    }

    private fun crossProviderUnsupportedFor(
        sourceLocation: String?,
        targetLocation: String,
        capability: VfsProviderCapability,
    ): Throwable {
        val sourceProvider = sourceLocation?.let { location -> providerRegistry?.providerFor(location)?.getOrNull() }
        val targetProvider = providerRegistry?.providerFor(targetLocation)?.getOrNull()
        return if (sourceProvider != null && targetProvider != null) {
            VfsProviderException(
                VfsProviderError.CrossProviderTransferUnsupported(
                    protocol = targetProvider.protocol,
                    location = targetLocation,
                    sourceProtocol = sourceProvider.protocol,
                    sourceLocation = sourceLocation,
                    capability = capability,
                )
            )
        } else {
            unsupportedFor(targetLocation, capability)
        }
    }
}

enum class TransferConflictStrategy {
    KEEP_BOTH,
    OVERWRITE,
    SKIP,
}

interface ExternalOpenService {
    suspend fun open(entry: VFile): Result<Unit>
}

interface TextClipboardService {
    suspend fun copyText(text: String): Result<Unit>
}

interface TrashService {
    val isSupported: Boolean

    suspend fun moveToTrash(entries: List<VFile>): Result<Unit>
}

/**
 * "打开方式"可选应用 — 从系统注册的应用中查询。
 */
data class OpenWithApp(
    /** 应用唯一标识（Linux 为 .desktop 文件名，Windows 为 ProgId） */
    val id: String,
    /** 显示名称 */
    val displayName: String,
    /** 平台启动标识 */
    val command: String,
    /** 应用图标路径（可选） */
    val iconPath: String? = null,
)

/**
 * 系统已注册的右键菜单动作。
 */
data class SystemMenuAction(
    val id: String,
    val displayName: String,
    val command: String,
    val iconPath: String? = null,
    val children: List<SystemMenuAction> = emptyList(),
)

enum class FileContextMenuSectionKind {
    OPEN_WITH,
    SYSTEM,
}

enum class FileContextMenuLabel {
    OPEN_WITH_OTHER,
}

data class FileContextMenuRequest(
    val entries: List<VFile>,
)

data class FileContextMenuSection(
    val kind: FileContextMenuSectionKind,
    val items: List<FileContextMenuItem>,
)

data class FileContextMenuItem(
    val id: String,
    val displayName: String,
    val label: FileContextMenuLabel? = null,
    val iconPath: String? = null,
    val command: FileContextMenuCommand? = null,
    val children: List<FileContextMenuItem> = emptyList(),
)

sealed interface FileContextMenuCommand {
    data class OpenWith(
        val app: OpenWithApp,
    ) : FileContextMenuCommand

    data object OpenWithChooser : FileContextMenuCommand

    data class SystemAction(
        val action: SystemMenuAction,
    ) : FileContextMenuCommand
}

interface SystemFileMaterializer {
    fun supports(entry: VFile): Boolean

    suspend fun materialize(entry: VFile): Result<VFile>
}

/**
 * "打开方式"服务 — 查询和启动关联应用。
 */
interface OpenWithService {
    /**
     * 当前条目是否能交给系统打开方式处理。
     */
    fun supports(entry: VFile): Boolean

    /**
     * 查询指定文件的可用打开方式应用列表。
     */
    suspend fun listApps(entry: VFile): List<OpenWithApp>

    /**
     * 使用指定应用打开文件。
     */
    suspend fun openWith(entry: VFile, app: OpenWithApp): Result<Unit>

    /**
     * 打开系统"选择应用"对话框。
     */
    suspend fun openWithChooser(entry: VFile): Result<Unit>
}

interface SystemMenuService {
    suspend fun listActions(entries: List<VFile>): List<SystemMenuAction>

    suspend fun execute(action: SystemMenuAction, entries: List<VFile>): Result<Unit>
}

interface FileContextMenuService {
    fun supportsOpenWith(entry: VFile): Boolean

    suspend fun listSections(request: FileContextMenuRequest): List<FileContextMenuSection>

    suspend fun execute(command: FileContextMenuCommand, entries: List<VFile>): Result<Unit>
}

interface SettingsRepository {
    suspend fun loadSettings(): Result<OnyxSettings?>

    suspend fun saveSettings(settings: OnyxSettings): Result<Unit>
}

interface SessionRepository {
    suspend fun loadSession(): Result<AppSessionSnapshot?>

    suspend fun saveSession(snapshot: AppSessionSnapshot): Result<Unit>
}

interface TaskPersistenceRepository {
    suspend fun loadTasks(): Result<List<BackgroundTask>>

    suspend fun saveTasks(tasks: List<BackgroundTask>): Result<Unit>

    suspend fun archiveTasks(tasks: List<BackgroundTask>): Result<Unit> = Result.success(Unit)
}
