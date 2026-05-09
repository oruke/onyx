package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.AppSessionSnapshot
import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.core.model.VFile

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

class ProviderBackedFileCommandService(
    services: List<RoutableFileCommandService>,
) : FileCommandService {
    private val services = services.toList()

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
            entries.groupByCommandService().forEach { (service, serviceEntries) ->
                if (!service.supports(targetDirectoryLocation)) {
                    throw VfsProviderNotFoundException(targetDirectoryLocation)
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
            entries.groupByCommandService().forEach { (service, serviceEntries) ->
                if (!service.supports(targetDirectoryLocation)) {
                    throw VfsProviderNotFoundException(targetDirectoryLocation)
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
            entries.groupByCommandService().forEach { (service, serviceEntries) ->
                service.delete(serviceEntries).getOrThrow()
            }
        }
    }

    override suspend fun rename(
        entry: VFile,
        targetName: String,
    ): Result<VFile> {
        return serviceFor(entry.location).fold(
            onSuccess = { service -> service.rename(entry, targetName) },
            onFailure = { failure -> Result.failure(failure) },
        )
    }

    override suspend fun createFile(
        parentLocation: String,
        name: String,
    ): Result<VFile> {
        return serviceFor(parentLocation).fold(
            onSuccess = { service -> service.createFile(parentLocation, name) },
            onFailure = { failure -> Result.failure(failure) },
        )
    }

    override suspend fun createDirectory(
        parentLocation: String,
        name: String,
    ): Result<VFile> {
        return serviceFor(parentLocation).fold(
            onSuccess = { service -> service.createDirectory(parentLocation, name) },
            onFailure = { failure -> Result.failure(failure) },
        )
    }

    private fun List<VFile>.groupByCommandService(): Map<RoutableFileCommandService, List<VFile>> {
        return groupBy { entry -> serviceFor(entry.location).getOrThrow() }
    }

    private fun serviceFor(location: String): Result<RoutableFileCommandService> {
        val service = services.firstOrNull { candidate -> candidate.supports(location) }
        return if (service != null) {
            Result.success(service)
        } else {
            Result.failure(VfsProviderNotFoundException(location))
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
    /** 启动命令 */
    val command: String,
    /** 应用图标路径（可选） */
    val iconPath: String? = null,
)

/**
 * "打开方式"服务 — 查询和启动关联应用。
 */
interface OpenWithService {
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

interface SettingsRepository {
    suspend fun loadSettings(): Result<OnyxSettings?>

    suspend fun saveSettings(settings: OnyxSettings): Result<Unit>
}

interface SessionRepository {
    suspend fun loadSession(): Result<AppSessionSnapshot?>

    suspend fun saveSession(snapshot: AppSessionSnapshot): Result<Unit>
}
