package com.oruke.onyx.vfs.api

import com.oruke.onyx.core.model.AppSessionSnapshot
import com.oruke.onyx.core.model.BackgroundTask
import com.oruke.onyx.core.model.OnyxSettings

/**
 * 设置持久化仓储。
 */
interface SettingsRepository {
    /**
     * 加载应用设置。
     *
     * @return 当前设置；尚未保存过时返回 null。
     */
    suspend fun loadSettings(): Result<OnyxSettings?>

    /**
     * 保存应用设置。
     *
     * @param settings 待保存设置。
     * @return 操作结果。
     */
    suspend fun saveSettings(settings: OnyxSettings): Result<Unit>
}

/**
 * 会话快照持久化仓储。
 */
interface SessionRepository {
    /**
     * 加载上次会话快照。
     *
     * @return 会话快照；尚未保存过时返回 null。
     */
    suspend fun loadSession(): Result<AppSessionSnapshot?>

    /**
     * 保存会话快照。
     *
     * @param snapshot 待保存快照。
     * @return 操作结果。
     */
    suspend fun saveSession(snapshot: AppSessionSnapshot): Result<Unit>
}

/**
 * 后台任务持久化仓储。
 */
interface TaskPersistenceRepository {
    /**
     * 加载仍需展示的任务。
     *
     * @return 任务列表。
     */
    suspend fun loadTasks(): Result<List<BackgroundTask>>

    /**
     * 保存当前任务列表。
     *
     * @param tasks 当前任务列表。
     * @return 操作结果。
     */
    suspend fun saveTasks(tasks: List<BackgroundTask>): Result<Unit>

    /**
     * 归档已完成或已关闭的任务。
     *
     * @param tasks 待归档任务。
     * @return 操作结果。
     */
    suspend fun archiveTasks(tasks: List<BackgroundTask>): Result<Unit> = Result.success(Unit)
}
