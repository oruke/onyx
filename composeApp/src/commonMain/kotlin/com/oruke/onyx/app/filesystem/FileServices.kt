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
    ): Result<Unit>

    suspend fun move(
        entries: List<VFile>,
        targetDirectoryLocation: String,
    ): Result<Unit>

    suspend fun delete(entries: List<VFile>): Result<Unit>

    suspend fun rename(
        entry: VFile,
        targetName: String,
    ): Result<VFile>
}

interface ExternalOpenService {
    suspend fun open(entry: VFile): Result<Unit>
}

interface TrashService {
    val isSupported: Boolean

    suspend fun moveToTrash(entries: List<VFile>): Result<Unit>
}

interface SettingsRepository {
    suspend fun loadSettings(): Result<OnyxSettings?>

    suspend fun saveSettings(settings: OnyxSettings): Result<Unit>
}

interface SessionRepository {
    suspend fun loadSession(): Result<AppSessionSnapshot?>

    suspend fun saveSession(snapshot: AppSessionSnapshot): Result<Unit>
}
