package com.oruke.onyx.app.cache

import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.vfs.api.SettingsRepository
import kotlinx.serialization.json.Json

/**
 * SQLite 设置仓库，负责把设置文档和可查询列表写入本地缓存系统。
 *
 * @param databaseService 本地 SQLite 数据库服务。
 * @param settingsDatabaseRepository 设置与网络位置表访问仓库。
 * @param useStartupPreload 是否在首次读取时消费启动期后台预加载结果。
 */
internal class SqliteSettingsRepository(
    private val databaseService: OnyxLocalDatabaseService,
    private val settingsDatabaseRepository: SettingsDatabaseRepository,
    private val useStartupPreload: Boolean = false,
) : SettingsRepository {
    /**
     * 加载应用设置，从 SQLite 设置文档恢复通用设置，并从结构化缓存表恢复网络位置。
     *
     * @return 当前设置；尚未保存过时返回 `null`。
     */
    override suspend fun loadSettings(): Result<OnyxSettings?> {
        if (useStartupPreload) {
            OnyxStartupPreloader.consumePreloadedSettings()?.let { preloaded ->
                return preloaded.await()
            }
        }
        return runCatching {
            val cachedSettings = databaseService.withJdbcConnection { connection ->
                val settingsJson = settingsDatabaseRepository.loadSettingsJsonFast(connection)
                    ?: return@withJdbcConnection null
                val decodedSettings = SettingsJson.decodeFromString<OnyxSettings>(settingsJson)
                decodedSettings.copy(
                    remoteConnections = settingsDatabaseRepository.loadRemoteConnectionsFast(connection),
                )
            }
            if (cachedSettings != null) {
                return@runCatching cachedSettings
            }
            null
        }
    }

    /**
     * 保存应用设置，同时同步收藏、最近位置和网络位置缓存表。
     *
     * @param settings 待保存设置。
     * @return 操作结果。
     */
    override suspend fun saveSettings(settings: OnyxSettings): Result<Unit> {
        if (useStartupPreload) {
            OnyxStartupPreloader.consumePreloadedSettings()?.cancel()
        }
        return runCatching {
            val encoded = SettingsJson.encodeToString(settings.withoutRemoteConnections())
            databaseService.transaction {
                settingsDatabaseRepository.replaceSettings(
                    settings = settings,
                    json = encoded,
                    updatedAtMillis = System.currentTimeMillis(),
                )
            }
        }
    }

    /**
     * 生成不含网络位置列表的设置文档，避免网络配置继续滞留在 JSON 文档缓存中。
     *
     * @return 仅包含通用设置字段的设置副本。
     */
    private fun OnyxSettings.withoutRemoteConnections(): OnyxSettings {
        return copy(remoteConnections = emptyList())
    }

    private companion object {
        /** 设置序列化器，允许读取旧版本设置中不存在的新字段。 */
        val SettingsJson = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }
    }
}
