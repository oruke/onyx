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
    /** 已折叠的侧边栏分组；缺少该字段的旧配置按全部展开处理。 */
    val collapsedSidebarSections: Set<SidebarSectionKey> = emptySet(),
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
    /** 文件区域独立颜色，不影响窗口、菜单与对话框主题。 */
    val fileListColors: FileListColors = FileListColors(),
    /** 批量重命名预设。 */
    val batchRenamePresets: List<BatchRenamePreset> = emptyList(),
    /** 用户自定义工具栏、菜单和脚本命令。 */
    val userCommands: List<UserCommandDefinition> = emptyList(),
    /** 主窗口宽度。 */
    val mainWindowWidth: Int = 1200,
    /** 主窗口高度。 */
    val mainWindowHeight: Int = 800,
    /** 主窗口是否最大化。 */
    val mainWindowMaximized: Boolean = false,
    /** 图片查看器窗口宽度。 */
    val imageViewerWindowWidth: Int = 1200,
    /** 图片查看器窗口高度。 */
    val imageViewerWindowHeight: Int = 800,
    /** 图片查看器窗口是否最大化。 */
    val imageViewerWindowMaximized: Boolean = false,
    /** 设置窗口宽度。 */
    val settingsWindowWidth: Int = 860,
    /** 设置窗口高度。 */
    val settingsWindowHeight: Int = 640,
    /** 完整搜索窗口宽度。 */
    val searchWindowWidth: Int = 900,
    /** 完整搜索窗口高度。 */
    val searchWindowHeight: Int = 650,
    /** 完整搜索窗口是否最大化。 */
    val searchWindowMaximized: Boolean = false,
    /** 任务中心窗口宽度。 */
    val taskCenterWindowWidth: Int = 760,
    /** 任务中心窗口高度。 */
    val taskCenterWindowHeight: Int = 540,
    /** 任务中心窗口是否最大化。 */
    val taskCenterWindowMaximized: Boolean = false,
    /** 批量重命名窗口宽度。 */
    val batchRenameWindowWidth: Int = 800,
    /** 批量重命名窗口高度。 */
    val batchRenameWindowHeight: Int = 620,
    /** 全局搜索历史，最新在前，上限 20 条。 */
    val searchHistory: List<String> = emptyList(),
    /** 是否优先使用操作系统关联应用图标（未关联类型或远程协议回退至内置彩色矢量图标）。 */
    val useSystemFileIcons: Boolean = true,
    /** 明暗主题模式（跟随系统、浅色、深色）。 */
    val themeMode: OnyxThemeMode = OnyxThemeMode.SYSTEM,
    /** 内置精选主题配色预设。 */
    val themePreset: OnyxThemePreset = OnyxThemePreset.DEFAULT,
    /** 全局界面主题颜色覆盖。 */
    val themeColors: OnyxThemeColors = OnyxThemeColors(),
    /** 用户保存的自定义命名主题列表。 */
    val customThemes: List<CustomThemePreset> = emptyList(),
    /** 当前激活的自定义主题 ID；空值表示使用内置预设或临时微调。 */
    val activeCustomThemeId: String? = null,
    /** 关闭主窗口时是否最小化到系统托盘保持后台热常驻（秒起）。 */
    val closeToTray: Boolean = true,
    /** 启动时是否自动恢复上次打开的窗口与标签页会话。 */
    val restoreSessionOnStartup: Boolean = true,
    /** 新建标签页时是否默认显示隐藏文件。 */
    val defaultShowHiddenItems: Boolean = false,
    /** 是否在文件列表与画廊中显示文件扩展名。 */
    val showFileExtensions: Boolean = true,
    /** 是否启用文件名自然数字排序（例如 2.txt 排在 10.txt 前）。 */
    val naturalSortEnabled: Boolean = true,
    /** 排序时是否始终将文件夹排在普通文件上方。 */
    val directoriesFirst: Boolean = true,
    /** 是否使用单击直接打开文件或进入目录（默认双击打开）。 */
    val singleClickToOpen: Boolean = false,
    /** 双击文件列表空白区域时是否返回上一级目录。 */
    val doubleClickEmptySpaceToNavigateUp: Boolean = true,
    /** 删除文件或目录前是否弹出确认对话框。 */
    val confirmBeforeDelete: Boolean = true,
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
    /** SMB 域；其他协议保持为空。 */
    val domain: String = "",
    /** S3 协议专属连接参数。 */
    val s3Config: S3ConnectionConfig = S3ConnectionConfig(),
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
