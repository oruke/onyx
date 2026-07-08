package com.oruke.onyx.app.cache

import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.vfs.api.SettingsRepository
import kotlinx.serialization.json.Json

/** SQLite 设置仓储，负责从旧 JSON 文件迁移并保存完整设置与结构化索引。 */
internal class SqliteSettingsRepository(
    private val databaseService: OnyxLocalDatabaseService,
    private val settingsDatabaseRepository: SettingsDatabaseRepository,
    private val legacyRepository: SettingsRepository,
) : SettingsRepository {
    /**
     * 加载应用设置，优先读取 SQLite，缺失时从旧 JSON 设置迁移。
     *
     * @return 当前设置；尚未保存过时返回 `null`。
     */
    override suspend fun loadSettings(): Result<OnyxSettings?> {
        return runCatching {
            val settingsJson = databaseService.transaction {
                settingsDatabaseRepository.loadSettingsJson()
            }
            if (settingsJson != null) {
                return@runCatching SettingsJson.decodeFromString<OnyxSettings>(settingsJson)
            }

            val legacySettings = legacyRepository.loadSettings().getOrThrow()
            if (legacySettings != null) {
                saveSettings(legacySettings).getOrThrow()
            }
            legacySettings
        }
    }

    /**
     * 保存应用设置，同时同步结构化收藏、最近位置和远程连接表。
     *
     * @param settings 待保存设置。
     * @return 操作结果。
     */
    override suspend fun saveSettings(settings: OnyxSettings): Result<Unit> {
        return runCatching {
            val encoded = SettingsJson.encodeToString(settings)
            databaseService.transaction {
                settingsDatabaseRepository.replaceSettings(
                    settings = settings,
                    json = encoded,
                    updatedAtMillis = System.currentTimeMillis(),
                )
            }
        }
    }

    private companion object {
        /** 设置序列化器，允许读取旧版本设置中不存在的新字段。 */
        val SettingsJson = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }
    }
}
