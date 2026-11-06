package com.oruke.onyx.app.cache

import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.core.model.RemoteConnectionProfile
import com.oruke.onyx.core.model.RemoteConnectionProtocol
import com.oruke.onyx.core.model.RemoteConnectionSavePolicy
import com.oruke.onyx.core.model.S3AddressingStyle
import com.oruke.onyx.core.model.S3ConnectionConfig
import com.oruke.onyx.core.model.S3ProviderPreset
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.sql.Connection

/** 设置数据库 Repository，负责 OnyxSettings 文档与结构化索引表的 Exposed DSL 读写。 */
internal class SettingsDatabaseRepository {
    /**
     * 使用原生 JDBC 读取完整设置 JSON 文档，用于启动期免 Exposed 类加载快速恢复。
     *
     * @param connection 已打开的 SQLite JDBC 连接。
     * @return 已保存的设置 JSON；不存在时返回 `null`。
     */
    fun loadSettingsJsonFast(connection: Connection): String? {
        return connection.prepareStatement(
            "SELECT json FROM settings_document WHERE key = ? LIMIT 1",
        ).use { statement ->
            statement.setString(1, DEFAULT_SETTINGS_KEY)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.getString(1) else null
            }
        }
    }

    /**
     * 使用原生 JDBC 读取结构化远程连接配置，用于启动期免 Exposed 类加载快速恢复。
     *
     * @param connection 已打开的 SQLite JDBC 连接。
     * @return 按用户排序恢复的远程连接配置列表。
     */
    @Suppress("MagicNumber")
    fun loadRemoteConnectionsFast(connection: Connection): List<RemoteConnectionProfile> {
        return connection.prepareStatement(
            """
            SELECT id, name, protocol, location, username, domain,
                   s3_provider, s3_endpoint, s3_region, s3_addressing_style, save_policy
            FROM remote_connection
            ORDER BY sort_order ASC
            """.trimIndent(),
        ).use { statement ->
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(
                            RemoteConnectionProfile(
                                id = resultSet.getString(1),
                                name = resultSet.getString(2),
                                protocol = resultSet.getString(3)
                                    .toEnumOrDefault(RemoteConnectionProtocol.SMB),
                                location = resultSet.getString(4),
                                username = resultSet.getString(5),
                                domain = resultSet.getString(6),
                                s3Config = S3ConnectionConfig(
                                    provider = resultSet.getString(7)
                                        .toEnumOrDefault(S3ProviderPreset.AMAZON_S3),
                                    endpoint = resultSet.getString(8),
                                    region = resultSet.getString(9),
                                    addressingStyle = resultSet.getString(10)
                                        .toEnumOrDefault(S3AddressingStyle.VIRTUAL_HOSTED),
                                ),
                                savePolicy = resultSet.getString(11)
                                    .toEnumOrDefault(RemoteConnectionSavePolicy.SESSION),
                            ),
                        )
                    }
                }
            }
        }
    }

    /**
     * 读取完整设置 JSON 文档。
     *
     * @return 已保存的设置 JSON；不存在时返回 `null`。
     */
    fun loadSettingsJson(): String? {
        return SettingsDocumentTable
            .selectAll()
            .where { SettingsDocumentTable.key eq DEFAULT_SETTINGS_KEY }
            .firstOrNull()
            ?.get(SettingsDocumentTable.json)
    }

    /**
     * 替换完整设置文档，并同步收藏、最近位置和远程连接结构化表。
     *
     * @param settings 当前完整设置。
     * @param json 当前完整设置 JSON。
     * @param updatedAtMillis 写入时间，使用 epoch millis。
     */
    fun replaceSettings(
        settings: OnyxSettings,
        json: String,
        updatedAtMillis: Long,
    ) {
        SettingsDocumentTable.deleteAll()
        SettingsDocumentTable.insert { row ->
            row[key] = DEFAULT_SETTINGS_KEY
            row[this.json] = json
            row[this.updatedAtMillis] = updatedAtMillis
        }
        replaceFavoriteLocations(settings.favoriteLocations, updatedAtMillis)
        replaceRecentLocations(settings.recentLocations, updatedAtMillis)
        replaceRemoteConnections(settings.remoteConnections, updatedAtMillis)
    }

    /**
     * 读取结构化收藏位置，用于验证和后续独立数据入口复用。
     *
     * @return 按用户排序恢复的收藏路径列表。
     */
    fun loadFavoriteLocations(): List<String> {
        return FavoriteLocationTable
            .selectAll()
            .orderBy(FavoriteLocationTable.sortOrder to SortOrder.ASC)
            .map { row -> row[FavoriteLocationTable.location] }
    }

    /**
     * 读取结构化最近位置，用于验证和后续独立数据入口复用。
     *
     * @return 按用户排序恢复的最近路径列表。
     */
    fun loadRecentLocations(): List<String> {
        return RecentLocationTable
            .selectAll()
            .orderBy(RecentLocationTable.sortOrder to SortOrder.ASC)
            .map { row -> row[RecentLocationTable.location] }
    }

    /**
     * 读取结构化远程连接配置，用于验证和后续独立数据入口复用。
     *
     * @return 按用户排序恢复的远程连接配置列表。
     */
    fun loadRemoteConnections(): List<RemoteConnectionProfile> {
        return RemoteConnectionTable
            .selectAll()
            .orderBy(RemoteConnectionTable.sortOrder to SortOrder.ASC)
            .map { row ->
                RemoteConnectionProfile(
                    id = row[RemoteConnectionTable.id],
                    name = row[RemoteConnectionTable.name],
                    protocol = row[RemoteConnectionTable.protocol].toEnumOrDefault(RemoteConnectionProtocol.SMB),
                    location = row[RemoteConnectionTable.location],
                    username = row[RemoteConnectionTable.username],
                    domain = row[RemoteConnectionTable.domain],
                    s3Config = S3ConnectionConfig(
                        provider = row[RemoteConnectionTable.s3Provider]
                            .toEnumOrDefault(S3ProviderPreset.AMAZON_S3),
                        endpoint = row[RemoteConnectionTable.s3Endpoint],
                        region = row[RemoteConnectionTable.s3Region],
                        addressingStyle = row[RemoteConnectionTable.s3AddressingStyle]
                            .toEnumOrDefault(S3AddressingStyle.VIRTUAL_HOSTED),
                    ),
                    savePolicy = row[RemoteConnectionTable.savePolicy]
                        .toEnumOrDefault(RemoteConnectionSavePolicy.SESSION),
                )
            }
    }

    /**
     * 替换收藏位置结构化表。
     *
     * @param locations 收藏路径列表。
     * @param updatedAtMillis 写入时间，使用 epoch millis。
     */
    private fun replaceFavoriteLocations(
        locations: List<String>,
        updatedAtMillis: Long,
    ) {
        FavoriteLocationTable.deleteAll()
        FavoriteLocationTable.batchInsert(locations.distinct()) { location ->
            this[FavoriteLocationTable.location] = location
            this[FavoriteLocationTable.sortOrder] = locations.indexOf(location)
            this[FavoriteLocationTable.updatedAtMillis] = updatedAtMillis
        }
    }

    /**
     * 替换最近位置结构化表。
     *
     * @param locations 最近路径列表。
     * @param updatedAtMillis 写入时间，使用 epoch millis。
     */
    private fun replaceRecentLocations(
        locations: List<String>,
        updatedAtMillis: Long,
    ) {
        RecentLocationTable.deleteAll()
        RecentLocationTable.batchInsert(locations.distinct()) { location ->
            this[RecentLocationTable.location] = location
            this[RecentLocationTable.sortOrder] = locations.indexOf(location)
            this[RecentLocationTable.updatedAtMillis] = updatedAtMillis
        }
    }

    /**
     * 替换远程连接结构化表。
     *
     * @param connections 远程连接配置列表。
     * @param updatedAtMillis 写入时间，使用 epoch millis。
     */
    private fun replaceRemoteConnections(
        connections: List<RemoteConnectionProfile>,
        updatedAtMillis: Long,
    ) {
        RemoteConnectionTable.deleteAll()
        RemoteConnectionTable.batchInsert(connections.distinctBy { connection -> connection.id }) { connection ->
            this[RemoteConnectionTable.id] = connection.id
            this[RemoteConnectionTable.name] = connection.name
            this[RemoteConnectionTable.protocol] = connection.protocol.name
            this[RemoteConnectionTable.location] = connection.location
            this[RemoteConnectionTable.username] = connection.username
            this[RemoteConnectionTable.domain] = connection.domain
            this[RemoteConnectionTable.s3Provider] = connection.s3Config.provider.name
            this[RemoteConnectionTable.s3Endpoint] = connection.s3Config.endpoint
            this[RemoteConnectionTable.s3Region] = connection.s3Config.region
            this[RemoteConnectionTable.s3AddressingStyle] = connection.s3Config.addressingStyle.name
            this[RemoteConnectionTable.savePolicy] = connection.savePolicy.name
            this[RemoteConnectionTable.sortOrder] = connections.indexOfFirst { candidate ->
                candidate.id == connection.id
            }
            this[RemoteConnectionTable.updatedAtMillis] = updatedAtMillis
        }
    }

    /**
     * 将稳定枚举名恢复为枚举值，遇到旧数据或未知值时使用默认值。
     *
     * @param fallback 无法解析时使用的默认值。
     * @return 解析后的枚举值。
     */
    private inline fun <reified T : Enum<T>> String.toEnumOrDefault(fallback: T): T {
        return enumValues<T>().firstOrNull { entry -> entry.name == this } ?: fallback
    }

    private companion object {
        /** 默认设置文档键名。 */
        const val DEFAULT_SETTINGS_KEY = "default"
    }
}
