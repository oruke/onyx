package com.oruke.onyx.app.cache

import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.core.model.RemoteConnectionProfile
import com.oruke.onyx.core.model.RemoteConnectionProtocol
import com.oruke.onyx.core.model.RemoteConnectionSavePolicy
import com.oruke.onyx.vfs.api.SettingsRepository
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

/** SQLite 设置仓储测试，验证完整设置文档、结构化索引和旧 JSON 迁移行为。 */
class SqliteSettingsRepositoryTest {
    /**
     * 验证保存完整设置时会同步收藏、最近位置和远程连接结构化表。
     */
    @Test
    fun saveSettingsWritesDocumentAndStructuredIndexes() = runTest {
        val databaseService = createDatabaseService()
        val settingsDatabaseRepository = SettingsDatabaseRepository()
        val repository = SqliteSettingsRepository(
            databaseService = databaseService,
            settingsDatabaseRepository = settingsDatabaseRepository,
            legacyRepository = StaticSettingsRepository(null),
        )
        val settings = sampleSettings()

        repository.saveSettings(settings).getOrThrow()
        val loaded = repository.loadSettings().getOrThrow()
        val structuredFavorites = databaseService.transaction {
            settingsDatabaseRepository.loadFavoriteLocations()
        }
        val structuredRecent = databaseService.transaction {
            settingsDatabaseRepository.loadRecentLocations()
        }
        val structuredConnections = databaseService.transaction {
            settingsDatabaseRepository.loadRemoteConnections()
        }

        assertEquals(settings, loaded)
        assertEquals(settings.favoriteLocations, structuredFavorites)
        assertEquals(settings.recentLocations, structuredRecent)
        assertEquals(settings.remoteConnections, structuredConnections)
    }

    /**
     * 验证 SQLite 尚无设置时会从旧仓储加载并迁移到 SQLite。
     */
    @Test
    fun loadSettingsMigratesLegacyRepositoryWhenDatabaseIsEmpty() = runTest {
        val databaseService = createDatabaseService()
        val settingsDatabaseRepository = SettingsDatabaseRepository()
        val settings = sampleSettings()
        val legacyRepository = StaticSettingsRepository(settings)
        val repository = SqliteSettingsRepository(
            databaseService = databaseService,
            settingsDatabaseRepository = settingsDatabaseRepository,
            legacyRepository = legacyRepository,
        )

        val loaded = repository.loadSettings().getOrThrow()
        val migratedJson = databaseService.transaction {
            settingsDatabaseRepository.loadSettingsJson()
        }

        assertEquals(settings, loaded)
        assertEquals(1, legacyRepository.loadCount)
        assertEquals(settings.favoriteLocations, databaseService.transaction {
            settingsDatabaseRepository.loadFavoriteLocations()
        })
        kotlin.test.assertNotNull(migratedJson)
    }

    /**
     * 创建指向临时 SQLite 文件的数据库服务。
     *
     * @return 可独立读写的本地数据库服务。
     */
    private fun createDatabaseService(): OnyxLocalDatabaseService {
        val databasePath = createTempDirectory(prefix = "onyx-settings-test-").resolve("onyx.db")
        return OnyxLocalDatabaseService(databasePath)
    }

    /**
     * 构造包含结构化字段的设置样例。
     *
     * @return 可用于保存和迁移验证的设置对象。
     */
    private fun sampleSettings(): OnyxSettings {
        return OnyxSettings(
            favoriteLocations = listOf("local:/workspace", "smb://server/share"),
            recentLocations = listOf("s3://bucket/library", "local:/workspace"),
            remoteConnections = listOf(
                RemoteConnectionProfile(
                    id = "remote-demo",
                    name = "Demo SMB",
                    protocol = RemoteConnectionProtocol.SMB,
                    location = "smb://server/share",
                    username = "demo-user",
                    domain = "WORKGROUP",
                    savePolicy = RemoteConnectionSavePolicy.SYSTEM_KEYRING,
                )
            ),
        )
    }

    /** 固定返回设置的旧仓储替身，用于验证首次迁移流程。 */
    private class StaticSettingsRepository(
        private val settings: OnyxSettings?,
    ) : SettingsRepository {
        /** 旧仓储被读取的次数。 */
        var loadCount: Int = 0
            private set

        /**
         * 返回预置设置并记录读取次数。
         *
         * @return 预置设置。
         */
        override suspend fun loadSettings(): Result<OnyxSettings?> {
            loadCount += 1
            return Result.success(settings)
        }

        /**
         * 旧仓储在 SQLite 模式下不再接收保存请求。
         *
         * @param settings 待保存设置。
         * @return 操作结果。
         */
        override suspend fun saveSettings(settings: OnyxSettings): Result<Unit> {
            return Result.success(Unit)
        }
    }
}
