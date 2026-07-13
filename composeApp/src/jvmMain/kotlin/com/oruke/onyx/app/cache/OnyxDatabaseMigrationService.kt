package com.oruke.onyx.app.cache

import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/**
 * Onyx SQLite schema 迁移服务，按显式版本顺序原子执行经过审查的 SQL。
 *
 * SQLite 的 `user_version` 只用于本应用 schema 版本，不与业务缓存键混用。旧版数据库没有版本号时从 0 开始，
 * 首个迁移中的 `IF NOT EXISTS` 会保留已有数据并补齐缺失对象。
 */
internal class OnyxDatabaseMigrationService(
    private val databaseUrl: String,
) {
    /**
     * 将目标数据库迁移到当前应用支持的 schema 版本。
     *
     * @throws SQLException 连接、DDL 或事务提交失败时抛出。
     * @throws IllegalStateException 数据库版本高于当前应用支持版本时抛出，避免旧应用破坏新 schema。
     */
    fun migrate() {
        DriverManager.getConnection(databaseUrl).use { connection ->
            val currentVersion = connection.readSchemaVersion()
            check(currentVersion <= CURRENT_SCHEMA_VERSION) {
                "Database schema version $currentVersion is newer than supported version $CURRENT_SCHEMA_VERSION"
            }

            connection.autoCommit = false
            try {
                MIGRATIONS
                    .asSequence()
                    .filter { migration -> migration.version > currentVersion }
                    .forEach { migration -> connection.applyMigration(migration) }
                connection.commit()
            } catch (exception: SQLException) {
                connection.rollback()
                throw exception
            }
        }
    }

    /**
     * 读取 SQLite 内建的应用 schema 版本。
     *
     * @return 当前 schema 版本；从未迁移的数据库返回 0。
     */
    private fun Connection.readSchemaVersion(): Int =
        createStatement().use { statement ->
            statement.executeQuery("PRAGMA user_version").use { resultSet ->
                check(resultSet.next()) { "SQLite did not return PRAGMA user_version" }
                resultSet.getInt(1)
            }
        }

    /**
     * 执行单个版本的全部 DDL，并仅在全部成功后推进 schema 版本。
     *
     * @param migration 待执行的有序迁移定义。
     */
    private fun Connection.applyMigration(migration: DatabaseMigration) {
        createStatement().use { statement ->
            migration.statements.forEach { sql -> statement.execute(sql) }
            statement.execute("PRAGMA user_version = ${migration.version}")
        }
    }

    /** 单个数据库版本及其有序 SQL 语句集合。 */
    private class DatabaseMigration(
        /** 迁移完成后的 schema 版本。 */
        val version: Int,
        /** 必须在同一事务内按顺序执行的 SQL。 */
        val statements: List<String>,
    )

    private companion object {
        /** 当前应用能够读写的最高 schema 版本。 */
        const val CURRENT_SCHEMA_VERSION = 2

        /** 经过审查且只能顺序追加的 schema 迁移清单。 */
        val MIGRATIONS = listOf(
            DatabaseMigration(
                version = 1,
                statements = listOf(
                    """
                    CREATE TABLE IF NOT EXISTS cache_metadata (
                        key VARCHAR(160) NOT NULL PRIMARY KEY,
                        value TEXT NOT NULL
                    )
                    """.trimIndent(),
                    """
                    CREATE TABLE IF NOT EXISTS platform_menu_source_fingerprint (
                        platform VARCHAR(32) NOT NULL,
                        scope_key VARCHAR(512) NOT NULL,
                        fingerprint TEXT NOT NULL,
                        refreshed_at_millis BIGINT NOT NULL,
                        PRIMARY KEY (platform, scope_key)
                    )
                    """.trimIndent(),
                    """
                    CREATE TABLE IF NOT EXISTS platform_menu_action (
                        platform VARCHAR(32) NOT NULL,
                        scope_key VARCHAR(512) NOT NULL,
                        action_id VARCHAR(2048) NOT NULL,
                        parent_action_id VARCHAR(2048) NULL,
                        sort_order INT NOT NULL,
                        display_name VARCHAR(512) NOT NULL,
                        command TEXT NOT NULL,
                        icon_path TEXT NULL,
                        PRIMARY KEY (platform, scope_key, action_id)
                    )
                    """.trimIndent(),
                    """
                    CREATE INDEX IF NOT EXISTS idx_platform_menu_action_scope_parent
                    ON platform_menu_action (platform, scope_key, parent_action_id)
                    """.trimIndent(),
                    """
                    CREATE TABLE IF NOT EXISTS settings_document (
                        key VARCHAR(64) NOT NULL PRIMARY KEY,
                        json TEXT NOT NULL,
                        updated_at_millis BIGINT NOT NULL
                    )
                    """.trimIndent(),
                    """
                    CREATE TABLE IF NOT EXISTS favorite_location (
                        location TEXT NOT NULL PRIMARY KEY,
                        sort_order INT NOT NULL,
                        updated_at_millis BIGINT NOT NULL
                    )
                    """.trimIndent(),
                    """
                    CREATE TABLE IF NOT EXISTS recent_location (
                        location TEXT NOT NULL PRIMARY KEY,
                        sort_order INT NOT NULL,
                        updated_at_millis BIGINT NOT NULL
                    )
                    """.trimIndent(),
                    """
                    CREATE TABLE IF NOT EXISTS remote_connection (
                        id VARCHAR(128) NOT NULL PRIMARY KEY,
                        name VARCHAR(256) NOT NULL,
                        protocol VARCHAR(32) NOT NULL,
                        location TEXT NOT NULL,
                        username VARCHAR(256) NOT NULL,
                        domain VARCHAR(256) NOT NULL,
                        save_policy VARCHAR(64) NOT NULL,
                        sort_order INT NOT NULL,
                        updated_at_millis BIGINT NOT NULL
                    )
                    """.trimIndent(),
                ),
            ),
            DatabaseMigration(
                version = 2,
                statements = listOf(
                    "ALTER TABLE remote_connection ADD COLUMN s3_provider VARCHAR(32) NOT NULL DEFAULT 'AMAZON_S3'",
                    "ALTER TABLE remote_connection ADD COLUMN s3_endpoint TEXT NOT NULL DEFAULT ''",
                    "ALTER TABLE remote_connection ADD COLUMN s3_region VARCHAR(128) NOT NULL DEFAULT ''",
                    "ALTER TABLE remote_connection ADD COLUMN s3_addressing_style " +
                        "VARCHAR(32) NOT NULL DEFAULT 'VIRTUAL_HOSTED'",
                    "UPDATE remote_connection SET s3_region = domain WHERE protocol = 'S3' AND s3_region = ''",
                ),
            ),
        )
    }
}
