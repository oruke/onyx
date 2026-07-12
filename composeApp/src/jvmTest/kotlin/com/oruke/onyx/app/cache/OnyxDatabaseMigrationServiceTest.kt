package com.oruke.onyx.app.cache

import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SQLite schema 迁移测试，验证首次建库、旧库补齐和版本保护。 */
class OnyxDatabaseMigrationServiceTest {
    /** 验证空数据库会一次性创建完整 schema 并写入版本号。 */
    @Test
    fun initializeBuildsVersionedSchema() = runTest {
        val databasePath = createDatabasePath()

        OnyxLocalDatabaseService(databasePath).initialize().getOrThrow()

        assertEquals(1, readSchemaVersion(databasePath))
        assertEquals(EXPECTED_TABLES, readApplicationTables(databasePath))
    }

    /** 验证没有版本号的旧数据库会保留现有表并补齐缺失表。 */
    @Test
    fun initializeMigratesLegacyUnversionedSchema() = runTest {
        val databasePath = createDatabasePath()
        DriverManager.getConnection(databaseUrl(databasePath)).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "CREATE TABLE cache_metadata (key VARCHAR(160) NOT NULL PRIMARY KEY, value TEXT NOT NULL)"
                )
            }
        }

        OnyxLocalDatabaseService(databasePath).initialize().getOrThrow()

        assertEquals(1, readSchemaVersion(databasePath))
        assertEquals(EXPECTED_TABLES, readApplicationTables(databasePath))
    }

    /** 验证旧版本应用拒绝打开由未来版本创建的数据库。 */
    @Test
    fun initializeRejectsNewerSchemaVersion() = runTest {
        val databasePath = createDatabasePath()
        DriverManager.getConnection(databaseUrl(databasePath)).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA user_version = 2")
            }
        }

        val result = OnyxLocalDatabaseService(databasePath).initialize()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }

    /**
     * 创建单测独占的 SQLite 文件路径。
     *
     * @return 尚不存在的数据库文件路径。
     */
    private fun createDatabasePath(): Path =
        createTempDirectory(prefix = "onyx-schema-test-").resolve("onyx.db")

    /**
     * 读取数据库的 SQLite schema 版本。
     *
     * @param databasePath 数据库文件路径。
     * @return `PRAGMA user_version` 的整数值。
     */
    private fun readSchemaVersion(databasePath: Path): Int =
        DriverManager.getConnection(databaseUrl(databasePath)).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA user_version").use { resultSet ->
                    check(resultSet.next())
                    resultSet.getInt(1)
                }
            }
        }

    /**
     * 读取数据库中除 SQLite 内部对象之外的应用表名。
     *
     * @param databasePath 数据库文件路径。
     * @return 应用表名集合。
     */
    private fun readApplicationTables(databasePath: Path): Set<String> =
        DriverManager.getConnection(databaseUrl(databasePath)).use { connection ->
            connection.prepareStatement(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'"
            ).use { statement ->
                statement.executeQuery().use { resultSet ->
                    buildSet {
                        while (resultSet.next()) add(resultSet.getString(1))
                    }
                }
            }
        }

    /**
     * 构造 SQLite JDBC URL。
     *
     * @param databasePath 数据库文件路径。
     * @return JDBC URL。
     */
    private fun databaseUrl(databasePath: Path): String = "jdbc:sqlite:${databasePath.toAbsolutePath()}"

    private companion object {
        /** 当前首版 schema 应创建的全部业务表。 */
        val EXPECTED_TABLES = setOf(
            "cache_metadata",
            "favorite_location",
            "platform_menu_action",
            "platform_menu_source_fingerprint",
            "recent_location",
            "remote_connection",
            "settings_document",
        )
    }
}
