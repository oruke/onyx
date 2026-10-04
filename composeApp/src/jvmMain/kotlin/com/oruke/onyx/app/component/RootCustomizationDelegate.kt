package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.FileLabelStyle
import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.core.model.UserCommandDefinition
import com.oruke.onyx.core.model.UserCommandPlacement
import com.oruke.onyx.core.model.VFile
import kotlinx.coroutines.flow.StateFlow

/**
 * 将用户配置转换为文件视图和命令入口状态，规则业务留在统一用例。
 * @param dependencies 文件规则和用户命令用例。
 * @param settings 所有窗口共享的持久化设置状态。
 */
internal class RootCustomizationDelegate(
    /** 统一规则匹配及命令规划能力。 */
    private val dependencies: RootCustomizationDependencies,
    /** 所有窗口共享的当前配置。 */
    private val settings: StateFlow<OnyxSettings>,
) : RootCustomizationServices {
    /**
     * 先配置的非空规则颜色优先，不覆盖选择语义。
     * @param entries 文件及已展开子项。
     * @return 组件计算的条目样式。
     */
    override fun matchFileLabels(entries: List<VFile>): Map<String, FileLabelStyle> =
        dependencies.labels.match(entries, settings.value.fileLabelRules).associate { match ->
            match.entry.id to FileLabelStyle(
                textColorArgb = match.rules.firstNotNullOfOrNull { it.textColorArgb },
                backgroundColorArgb = match.rules.firstNotNullOfOrNull { it.backgroundColorArgb },
            )
        }

    /**
     * 用已注册用例筛选用户命令。
     * @param placement 当前入口。
     * @return 已配置命令列表。
     */
    override fun userCommandsForPlacement(placement: UserCommandPlacement): List<UserCommandDefinition> =
        dependencies.commands.commandsForPlacement(settings.value.userCommands, placement)
}
