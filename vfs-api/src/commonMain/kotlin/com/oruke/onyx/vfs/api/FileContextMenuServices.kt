package com.oruke.onyx.vfs.api

import com.oruke.onyx.core.model.VFile

/**
 * “打开方式”候选应用，来自系统注册表、桌面入口或平台等价能力。
 *
 * @param id 应用唯一标识。
 * @param displayName 用户可见名称。
 * @param command 平台启动标识。
 * @param iconPath 应用图标路径，无法解析时为 null。
 */
data class OpenWithApp(
    val id: String,
    val displayName: String,
    val command: String,
    val iconPath: String? = null,
)

/**
 * 系统右键菜单动作，允许表达平台提供的级联菜单。
 *
 * @param id 动作唯一标识。
 * @param displayName 用户可见名称。
 * @param command 平台命令标识。
 * @param iconPath 图标路径，无法解析时为 null。
 * @param children 子动作列表，非空时表示级联菜单。
 */
data class SystemMenuAction(
    val id: String,
    val displayName: String,
    val command: String,
    val iconPath: String? = null,
    val children: List<SystemMenuAction> = emptyList(),
)

/**
 * 文件右键菜单分组类型。
 */
enum class FileContextMenuSectionKind {
    OPEN_WITH,
    SYSTEM,
}

/**
 * 文件右键菜单内置语义标签。
 */
enum class FileContextMenuLabel {
    OPEN_WITH_OTHER,
}

/**
 * 文件右键菜单查询请求。
 *
 * @param entries 当前菜单作用的文件条目。
 */
data class FileContextMenuRequest(
    val entries: List<VFile>,
)

/**
 * 文件右键菜单分组。
 *
 * @param kind 分组类型。
 * @param items 分组下的菜单项。
 */
data class FileContextMenuSection(
    val kind: FileContextMenuSectionKind,
    val items: List<FileContextMenuItem>,
)

/**
 * 文件右键菜单项。
 *
 * @param id 菜单项唯一标识。
 * @param displayName 用户可见名称。
 * @param label 内置语义标签。
 * @param iconPath 图标路径，无法解析时为 null。
 * @param command 点击时执行的命令。
 * @param children 子菜单项，非空时表示级联菜单。
 */
data class FileContextMenuItem(
    val id: String,
    val displayName: String,
    val label: FileContextMenuLabel? = null,
    val iconPath: String? = null,
    val command: FileContextMenuCommand? = null,
    val children: List<FileContextMenuItem> = emptyList(),
)

/**
 * 文件右键菜单命令。
 */
sealed interface FileContextMenuCommand {
    /**
     * 使用指定应用打开文件。
     *
     * @param app 目标应用。
     */
    data class OpenWith(
        val app: OpenWithApp,
    ) : FileContextMenuCommand

    /** 打开系统“选择其他应用”入口。 */
    data object OpenWithChooser : FileContextMenuCommand

    /**
     * 执行系统菜单动作。
     *
     * @param action 平台动作。
     */
    data class SystemAction(
        val action: SystemMenuAction,
    ) : FileContextMenuCommand
}

/**
 * “打开方式”服务，负责查询和启动关联应用。
 */
interface OpenWithService {
    /**
     * 判断当前条目是否可交给系统打开方式处理。
     *
     * @param entry 待检查条目。
     * @return 支持时返回 true。
     */
    fun supports(entry: VFile): Boolean

    /**
     * 查询指定文件的可用打开方式应用列表。
     *
     * @param entry 目标文件。
     * @return 候选应用列表。
     */
    suspend fun listApps(entry: VFile): List<OpenWithApp>

    /**
     * 使用指定应用打开文件。
     *
     * @param entry 目标文件。
     * @param app 目标应用。
     * @return 操作结果。
     */
    suspend fun openWith(entry: VFile, app: OpenWithApp): Result<Unit>

    /**
     * 打开系统“选择应用”对话框。
     *
     * @param entry 目标文件。
     * @return 操作结果。
     */
    suspend fun openWithChooser(entry: VFile): Result<Unit>
}

/**
 * 系统菜单服务，负责查询并执行平台原生右键菜单动作。
 */
interface SystemMenuService {
    /**
     * 查询指定条目的系统菜单动作。
     *
     * @param entries 当前菜单作用的条目。
     * @return 系统菜单动作列表。
     */
    suspend fun listActions(entries: List<VFile>): List<SystemMenuAction>

    /**
     * 执行系统菜单动作。
     *
     * @param action 待执行动作。
     * @param entries 当前菜单作用的条目。
     * @return 操作结果。
     */
    suspend fun execute(action: SystemMenuAction, entries: List<VFile>): Result<Unit>
}

/**
 * 统一文件右键菜单服务，组合打开方式和系统菜单能力。
 */
interface FileContextMenuService {
    /**
     * 判断条目是否支持打开方式分组。
     *
     * @param entry 待检查条目。
     * @return 支持时返回 true。
     */
    fun supportsOpenWith(entry: VFile): Boolean

    /**
     * 查询右键菜单分组。
     *
     * @param request 查询请求。
     * @return 菜单分组列表。
     */
    suspend fun listSections(request: FileContextMenuRequest): List<FileContextMenuSection>

    /**
     * 执行右键菜单命令。
     *
     * @param command 待执行命令。
     * @param entries 当前命令作用的条目。
     * @return 操作结果。
     */
    suspend fun execute(command: FileContextMenuCommand, entries: List<VFile>): Result<Unit>
}
