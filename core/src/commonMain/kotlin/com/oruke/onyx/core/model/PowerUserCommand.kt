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
 *
 * @property id 预设唯一标识。
 * @property name 预设显示名称。
 * @property pattern 匹配表达式或普通替换目标。
 * @property replacement 替换模板，支持 `{name}`、`{ext}`、`{index}`、`{size}`、`{modified}`。
 * @property useRegex 是否按正则捕获组处理。
 * @property startIndex 序号起始值。
 * @property padding 序号补零宽度。
 * @property caseMode 输出名称大小写策略。
 */
@Serializable
data class BatchRenamePreset(
    val id: String,
    val name: String,
    val pattern: String = "",
    val replacement: String,
    val useRegex: Boolean = false,
    val startIndex: Int = 1,
    val padding: Int = 0,
    val caseMode: BatchRenameCaseMode = BatchRenameCaseMode.KEEP,
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
 *
 * @property id 命令唯一标识。
 * @property name 命令显示名称。
 * @property executable 可执行文件或脚本入口。
 * @property argumentTemplate 参数模板，支持 `{location}`、`{parent}`、`{name}`、`{selection}`。
 * @property workingDirectoryTemplate 工作目录模板，空值表示跟随当前面板位置。
 * @property placements 命令出现的位置。
 */
@Serializable
data class UserCommandDefinition(
    val id: String,
    val name: String,
    val executable: String,
    val argumentTemplate: List<String> = emptyList(),
    val workingDirectoryTemplate: String? = null,
    val placements: Set<UserCommandPlacement> = setOf(UserCommandPlacement.COMMAND_PALETTE),
)
