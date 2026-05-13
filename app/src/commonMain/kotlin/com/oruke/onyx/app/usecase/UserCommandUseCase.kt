package com.oruke.onyx.app.usecase

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
            executable = command.executable.render(context),
            arguments = command.argumentTemplate.map { argument -> argument.render(context) },
            workingDirectory = command.workingDirectoryTemplate?.render(context)
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

/**
 * 渲染命令模板变量。
 *
 * @param context 模板上下文。
 * @return 渲染后的字符串。
 */
private fun String.render(context: UserCommandTemplateContext): String {
    val entry = context.primaryEntry
    return replace("{location}", entry?.location ?: context.currentLocation)
        .replace("{parent}", entry?.parentLocation ?: context.currentLocation)
        .replace("{name}", entry?.name.orEmpty())
        .replace("{selection}", context.selection.joinToString(" ") { item -> item.location })
}
