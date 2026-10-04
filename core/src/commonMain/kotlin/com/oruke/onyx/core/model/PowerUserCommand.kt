package com.oruke.onyx.core.model

import kotlinx.serialization.Serializable

/**
 * 批量重命名大小写策略。
 */
@Serializable
enum class BatchRenameCaseMode {
    KEEP,
    LOWER,
    UPPER,
    TITLE,
}

/**
 * 可保存的批量重命名预设。
 */
@Serializable
data class BatchRenamePreset(
    /** 预设唯一标识。 */
    val id: String,
    /** 用户配置的显示名称。 */
    val name: String,
    /** 匹配表达式或普通替换目标。 */
    val pattern: String = "",
    /** 替换模板，支持名称、扩展名、序号、大小和修改时间变量。 */
    val replacement: String,
    /** 是否按正则捕获组处理。 */
    val useRegex: Boolean = false,
    /** 序号起始值。 */
    val startIndex: Int = 1,
    /** 序号补零宽度。 */
    val padding: Int = 0,
    /** 输出完整名称的大小写策略。 */
    val caseMode: BatchRenameCaseMode = BatchRenameCaseMode.KEEP,
    /** 应用表单预设；为空时继续按模板字段规划。 */
    val draft: BatchRenameDraft? = null,
)

/**
 * 用户命令展示位置。
 */
@Serializable
enum class UserCommandPlacement {
    TOOLBAR,
    CONTEXT_MENU,
    COMMAND_PALETTE,
}

/**
 * 用户可配置工具、菜单或脚本命令。
 */
@Serializable
data class UserCommandDefinition(
    /** 命令唯一标识。 */
    val id: String,
    /** 用户配置的显示名称。 */
    val name: String,
    /** 可执行程序入口，直接启动，不经过 Shell。 */
    val executable: String,
    /** 独立参数模板，支持位置、父目录、名称和选中条目变量。 */
    val argumentTemplate: List<String> = emptyList(),
    /** 本地工作目录模板，空值表示跟随当前面板位置。 */
    val workingDirectoryTemplate: String? = null,
    /** 展示该命令的工具栏、菜单或命令面板入口。 */
    val placements: Set<UserCommandPlacement> = setOf(UserCommandPlacement.COMMAND_PALETTE),
)
