package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.FileLabelStyle
import com.oruke.onyx.core.model.UserCommandDefinition
import com.oruke.onyx.core.model.UserCommandPlacement
import com.oruke.onyx.core.model.VFile

/** 面向 UI 的用户规则和自定义命令查询接口。 */
internal interface RootCustomizationServices {
    /**
     * 计算当前条目及树形展开子项的规则颜色。
     * @param entries 已加载的文件条目。
     * @return 以条目标识索引的样式。
     */
    fun matchFileLabels(entries: List<VFile>): Map<String, FileLabelStyle> = emptyMap()

    /**
     * 获取指定入口可展示的用户命令。
     * @param placement 工具栏、菜单或命令面板。
     * @return 按用户配置顺序排列的命令。
     */
    fun userCommandsForPlacement(placement: UserCommandPlacement): List<UserCommandDefinition> = emptyList()
}
