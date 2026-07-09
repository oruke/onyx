package com.oruke.onyx.app.cache

import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.core.model.RemoteConnectionProfile
import com.oruke.onyx.core.model.RemoteConnectionProtocol
import com.oruke.onyx.core.model.RemoteConnectionSavePolicy
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** SQLite 设置仓储测试，验证完整设置文档与结构化索引行为。 */
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
        val settingsJson = databaseService.transaction {
            settingsDatabaseRepository.loadSettingsJson()
        }

        assertEquals(settings, loaded)
        assertEquals(settings.favoriteLocations, structuredFavorites)
        assertEquals(settings.recentLocations, structuredRecent)
        assertEquals(settings.remoteConnections, structuredConnections)
        assertNotNull(settingsJson)
        assertFalse(settingsJson.contains("remote-demo"))
    }

    /**
     * 验证 SQLite 尚无设置时不会读取旧 JSON，调用方会继续使用默认设置。
     */
    @Test
    fun loadSettingsReturnsNullWhenDatabaseIsEmpty() = runTest {
        val databaseService = createDatabaseService()
        val settingsDatabaseRepository = SettingsDatabaseRepository()
        val repository = SqliteSettingsRepository(
            databaseService = databaseService,
            settingsDatabaseRepository = settingsDatabaseRepository,
        )

        val loaded = repository.loadSettings().getOrThrow()
        val settingsJson = databaseService.transaction {
            settingsDatabaseRepository.loadSettingsJson()
        }

        assertNull(loaded)
        assertNull(settingsJson)
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

}
