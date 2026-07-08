package com.oruke.onyx.app.cache

/** 平台菜单缓存作用域，描述一组可复用的系统菜单动作快照。 */
internal data class PlatformMenuCacheScope(
    /** 宿主平台标识，例如 `windows`、`linux` 或 `macos`。 */
    val platform: String,

    /** 作用域键，通常由菜单来源、文件类型和选择数量组成。 */
    val scopeKey: String,
)

/** 已扁平化的平台菜单动作，用于在数据库中保存级联菜单树。 */
internal data class CachedPlatformMenuAction(
    /** 平台动作原始 ID，执行时继续交给对应平台服务解释。 */
    val actionId: String,

    /** 父动作 ID，空值表示顶层菜单项。 */
    val parentActionId: String?,

    /** 同级排序位置，用于恢复显示顺序。 */
    val sortOrder: Int,

    /** 用户可见菜单名称。 */
    val displayName: String,

    /** 平台命令模板或执行标识，组菜单为空字符串。 */
    val command: String,

    /** 图标路径或平台图标标识，无法解析时为空。 */
    val iconPath: String?,
)
