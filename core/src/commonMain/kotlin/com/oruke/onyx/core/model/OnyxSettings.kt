package com.oruke.onyx.core.model

import kotlinx.serialization.Serializable

@Serializable
/** Onyx 可持久化用户设置。 */
data class OnyxSettings(
    /** 全局 UI 缩放百分比。 */
    val uiScale: Int = 100,
    /** 首选界面语言。 */
    val preferredLocale: AppLocale = AppLocale.SYSTEM,
    /** 新窗口默认面板布局。 */
    val defaultLayoutMode: PaneLayoutMode = PaneLayoutMode.DUAL_VERTICAL,
    /** 新标签页默认视图模式。 */
    val defaultViewMode: ViewMode = ViewMode.DETAILS,
    /** 默认删除策略。 */
    val deleteMode: DeleteMode = DeleteMode.MOVE_TO_TRASH_PREFERRED,
    /** 是否显示侧边栏。 */
    val sidebarVisible: Boolean = true,
    /** 是否在侧边栏显示目录树。 */
    val sidebarTreeVisible: Boolean = true,
    /** 是否显示面板状态栏。 */
    val statusBarVisible: Boolean = true,
    /** 收藏 VFS 位置。 */
    val favoriteLocations: List<String> = emptyList(),
    /** 最近访问 VFS 位置。 */
    val recentLocations: List<String> = emptyList(),
    /** 已保存远程连接配置，不包含密钥。 */
    val remoteConnections: List<RemoteConnectionProfile> = emptyList(),
    /** 文件列表行高，单位 dp。 */
    val listRowHeightDp: Int = 22,
    /** 文件列表字号，单位 sp。 */
    val listFontSizeSp: Int = 12,
    /** 是否启用斑马纹行背景。 */
    val zebraStripeEnabled: Boolean = true,
    /** 详情视图中隐藏的列。 */
    val hiddenDetailsColumns: Set<DetailsColumn> = emptySet(),
    /** 命令快捷键覆盖；命令使用 UI 枚举名以避免 core 反向依赖 UI。 */
    val commandShortcutOverrides: List<CommandShortcutOverride> = emptyList(),
    /** 文件标签与颜色规则。 */
    val fileLabelRules: List<FileLabelRule> = emptyList(),
    /** 批量重命名预设。 */
    val batchRenamePresets: List<BatchRenamePreset> = emptyList(),
    /** 用户自定义工具栏、菜单和脚本命令。 */
    val userCommands: List<UserCommandDefinition> = emptyList(),
    /** 主窗口宽度。 */
    val mainWindowWidth: Int = 1200,
    /** 主窗口高度。 */
    val mainWindowHeight: Int = 800,
    /** 设置窗口宽度。 */
    val settingsWindowWidth: Int = 720,
    /** 设置窗口高度。 */
    val settingsWindowHeight: Int = 520,
    /** 批量重命名窗口宽度。 */
    val batchRenameWindowWidth: Int = 800,
    /** 批量重命名窗口高度。 */
    val batchRenameWindowHeight: Int = 620,
)

@Serializable
/** 单个命令的快捷键覆盖配置。 */
data class CommandShortcutOverride(
    /** UI 命令枚举名。 */
    val command: String,
    /** 主按键名称；空值表示禁用快捷键。 */
    val key: String? = null,
    /** 组合修饰键。 */
    val modifiers: Set<CommandShortcutModifierSetting> = emptySet(),
)

@Serializable
/** 可持久化快捷键修饰键。 */
enum class CommandShortcutModifierSetting {
    PRIMARY,
    SHIFT,
    ALT,
}

@Serializable
/** 应用界面语言。 */
enum class AppLocale {
    SYSTEM,
    ENGLISH,
    SIMPLIFIED_CHINESE,
    JAPANESE,
}

@Serializable
/** 文件删除策略。 */
enum class DeleteMode {
    MOVE_TO_TRASH_PREFERRED,
    PERMANENT,
}

@Serializable
/** 不包含密钥的远程连接配置。 */
data class RemoteConnectionProfile(
    /** 连接唯一 ID。 */
    val id: String,
    /** 用户可见名称。 */
    val name: String,
    /** 连接协议。 */
    val protocol: RemoteConnectionProtocol,
    /** 远程 VFS 位置。 */
    val location: String,
    /** 登录用户名或访问密钥 ID。 */
    val username: String = "",
    /** SMB 域或 S3 Region。 */
    val domain: String = "",
    /** 凭据保存策略。 */
    val savePolicy: RemoteConnectionSavePolicy = RemoteConnectionSavePolicy.SESSION,
)

@Serializable
/** 远程连接协议。 */
enum class RemoteConnectionProtocol {
    SMB,
    WEBDAV,
    WEBDAVS,
    S3,
}

@Serializable
/** 远程凭据保存策略。 */
enum class RemoteConnectionSavePolicy {
    DO_NOT_SAVE,
    SESSION,
    SYSTEM_KEYRING,
}
