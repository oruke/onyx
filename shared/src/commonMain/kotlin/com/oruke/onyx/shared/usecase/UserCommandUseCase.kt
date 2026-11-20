package com.oruke.onyx.shared.usecase

import com.oruke.onyx.core.model.UserCommandDefinition
import com.oruke.onyx.core.model.UserCommandPlacement
import com.oruke.onyx.core.model.VFile

/**
 * 用户命令调用计划。
 *
 * @property executable 可执行文件或脚本入口。
 * @property arguments 渲染后的参数。
 * @property workingDirectory 渲染后的工作目录。
 */
data class UserCommandInvocation(
    val executable: String,
    val arguments: List<String>,
    val workingDirectory: String?,
)

/**
 * 用户命令用例，负责筛选命令和渲染模板。
 */
class UserCommandUseCase {
    /**
     * 根据展示位置筛选用户命令。
     *
     * @param commands 用户命令配置。
     * @param placement 目标展示位置。
     * @return 可展示的命令列表。
     */
    fun commandsForPlacement(
        commands: List<UserCommandDefinition>,
        placement: UserCommandPlacement,
    ): List<UserCommandDefinition> {
        return commands.filter { command -> placement in command.placements }
    }

    /**
     * 渲染用户命令调用计划。
     *
     * 采用单次占位符扫描替换，防止路径或文件名中包含 `{name}` / `{parent}` / `{selection}` 时发生二次模板注入，
     * 并自动过滤空白参数行避免向外部进程传入空字符串参数。
     *
     * @param command 用户命令配置。
     * @param currentLocation 当前面板位置。
     * @param selection 当前选择条目。
     * @return 可交给平台进程启动器的调用计划。
     */
    fun renderInvocation(
        command: UserCommandDefinition,
        currentLocation: String,
        selection: List<VFile>,
    ): UserCommandInvocation {
        val context = UserCommandTemplateContext(
            currentLocation = currentLocation,
            primaryEntry = selection.firstOrNull(),
            selection = selection,
        )
        return UserCommandInvocation(
            executable = command.executable.trim().render(context),
            arguments = command.argumentTemplate
                .map { raw -> raw.trim() }
                .filter { trimmed -> trimmed.isNotEmpty() }
                .flatMap { argument ->
                    // 整参数选择变量必须展开为多个独立参数，文件名内的空格不参与 Shell 解析。
                    if (argument == "{selection}") selection.map { it.location } else listOf(argument.render(context))
                },
            workingDirectory = command.workingDirectoryTemplate
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.render(context)
                ?: context.primaryEntry?.parentLocation
                ?: currentLocation,
        )
    }
}

/**
 * 用户命令模板上下文。
 *
 * @property currentLocation 当前面板位置。
 * @property primaryEntry 当前主条目。
 * @property selection 当前选择条目。
 */
private data class UserCommandTemplateContext(
    val currentLocation: String,
    val primaryEntry: VFile?,
    val selection: List<VFile>,
)

/** 模板占位符单次扫描正则。 */
private val USER_COMMAND_PLACEHOLDER_REGEX = Regex("\\{(location|parent|name|selection)\\}")

/**
 * 以单次扫描替换命令模板变量，杜绝多轮 `.replace()` 导致的二次变量注入。
 *
 * @param context 模板上下文。
 * @return 渲染后的字符串。
 */
private fun String.render(context: UserCommandTemplateContext): String {
    if ('{' !in this) return this
    val entry = context.primaryEntry
    return USER_COMMAND_PLACEHOLDER_REGEX.replace(this) { match ->
        when (match.groupValues[1]) {
            "location" -> entry?.location ?: context.currentLocation
            "parent" -> entry?.parentLocation ?: context.currentLocation
            "name" -> entry?.name.orEmpty()
            "selection" -> context.selection.joinToString(" ") { item -> item.location }
            else -> match.value
        }
    }
}
