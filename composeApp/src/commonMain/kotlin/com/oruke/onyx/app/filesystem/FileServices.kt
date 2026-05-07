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
