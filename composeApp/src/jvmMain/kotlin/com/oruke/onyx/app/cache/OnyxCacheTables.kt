package com.oruke.onyx.app.cache

import org.jetbrains.exposed.v1.core.Table

/** Onyx 本地缓存库的键值元数据表，记录平台指纹等轻量业务状态。 */
internal object CacheMetadataTable : Table("cache_metadata") {
    /** 元数据键名，必须在整库内保持唯一。 */
    val key = varchar("key", length = 160)

    /** 元数据值，使用文本保存以兼容版本号、哈希和 JSON 摘要。 */
    val value = text("value")

    /** 元数据表主键，避免同一键被重复写入。 */
    override val primaryKey = PrimaryKey(key)
}

/** 平台菜单缓存的来源指纹表，用于判断已缓存菜单是否来自同一批系统注册源。 */
internal object PlatformMenuSourceFingerprintTable : Table("platform_menu_source_fingerprint") {
    /** 宿主平台标识，例如 `windows`、`linux` 或 `macos`。 */
    val platform = varchar("platform", length = 32)

    /** 缓存作用域，通常由菜单来源、文件类型和选择数量组成。 */
    val scopeKey = varchar("scope_key", length = 512)

    /** 来源指纹，当前版本主要由菜单快照内容或平台源摘要计算得到。 */
    val fingerprint = text("fingerprint")

    /** 最近一次刷新时间，使用 epoch millis，便于跨平台直接比较。 */
    val refreshedAtMillis = long("refreshed_at_millis")

    /** 平台与作用域共同构成唯一缓存来源。 */
    override val primaryKey = PrimaryKey(platform, scopeKey)
}

/** 平台菜单动作快照表，按作用域保存可直接渲染的级联菜单树。 */
internal object PlatformMenuActionTable : Table("platform_menu_action") {
    /** 宿主平台标识，例如 `windows`、`linux` 或 `macos`。 */
    val platform = varchar("platform", length = 32)

    /** 缓存作用域，限定当前菜单动作适用的文件类型或选择场景。 */
    val scopeKey = varchar("scope_key", length = 512)

    /** 平台动作原始 ID，执行时继续交给对应平台服务解释。 */
    val actionId = varchar("action_id", length = 2_048)

    /** 父动作 ID，空值表示顶层菜单项。 */
    val parentActionId = varchar("parent_action_id", length = 2_048).nullable()

    /** 同级排序位置，用于恢复平台扫描时的显示顺序。 */
    val sortOrder = integer("sort_order")

    /** 用户可见菜单名称。 */
    val displayName = varchar("display_name", length = 512)

    /** 平台命令模板或执行标识，组菜单为空字符串。 */
    val command = text("command")

    /** 图标路径或平台图标标识，无法解析时为空。 */
    val iconPath = text("icon_path").nullable()

    /** 平台、作用域与动作 ID 共同保证缓存项唯一。 */
    override val primaryKey = PrimaryKey(platform, scopeKey, actionId)

    init {
        index(
            customIndexName = "idx_platform_menu_action_scope_parent",
            isUnique = false,
            platform,
            scopeKey,
            parentActionId,
        )
    }
}

/** 设置文档表，保存完整 OnyxSettings JSON 以兼容所有现有设置字段。 */
internal object SettingsDocumentTable : Table("settings_document") {
    /** 设置文档键名，当前固定为 `default`，为未来多配置档预留。 */
    val key = varchar("key", length = 64)

    /** 设置文档 JSON，保持与 kotlinx.serialization 的 OnyxSettings 结构一致。 */
    val json = text("json")

    /** 最近一次保存时间，使用 epoch millis。 */
    val updatedAtMillis = long("updated_at_millis")

    /** 设置文档主键，避免重复写入默认配置。 */
    override val primaryKey = PrimaryKey(key)
}

/** 收藏位置表，用于将设置中的收藏位置拆成可查询、可排序的结构化数据。 */
internal object FavoriteLocationTable : Table("favorite_location") {
    /** 收藏位置完整 VFS 路径。 */
    val location = text("location")

    /** 显示排序位置，保持用户设置中的列表顺序。 */
    val sortOrder = integer("sort_order")

    /** 最近一次写入时间，使用 epoch millis。 */
    val updatedAtMillis = long("updated_at_millis")

    /** 收藏位置本身必须唯一。 */
    override val primaryKey = PrimaryKey(location)
}

/** 最近位置表，用于保存侧边栏最近访问路径。 */
internal object RecentLocationTable : Table("recent_location") {
    /** 最近访问位置完整 VFS 路径。 */
    val location = text("location")

    /** 显示排序位置，值越小越靠前。 */
    val sortOrder = integer("sort_order")

    /** 最近一次写入时间，使用 epoch millis。 */
    val updatedAtMillis = long("updated_at_millis")

    /** 最近位置本身必须唯一。 */
    override val primaryKey = PrimaryKey(location)
}

/** 远程连接配置表，仅保存非密钥字段，密码仍交给系统凭据或会话态处理。 */
internal object RemoteConnectionTable : Table("remote_connection") {
    /** 远程连接唯一 ID。 */
    val id = varchar("id", length = 128)

    /** 用户可见连接名称。 */
    val name = varchar("name", length = 256)

    /** 连接协议枚举名。 */
    val protocol = varchar("protocol", length = 32)

    /** 远程位置 URI 或 VFS 路径。 */
    val location = text("location")

    /** 连接用户名，不包含密码或 token。 */
    val username = varchar("username", length = 256)

    /** SMB 域或兼容协议的域字段。 */
    val domain = varchar("domain", length = 256)

    /** 凭据保存策略枚举名。 */
    val savePolicy = varchar("save_policy", length = 64)

    /** 显示排序位置，保持用户设置中的列表顺序。 */
    val sortOrder = integer("sort_order")

    /** 最近一次写入时间，使用 epoch millis。 */
    val updatedAtMillis = long("updated_at_millis")

    /** 远程连接 ID 必须唯一。 */
    override val primaryKey = PrimaryKey(id)
}
