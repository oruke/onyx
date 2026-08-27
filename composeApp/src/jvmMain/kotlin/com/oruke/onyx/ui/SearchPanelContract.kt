package com.oruke.onyx.ui

import androidx.compose.ui.unit.IntOffset
import com.oruke.onyx.app.component.SearchFilters
import com.oruke.onyx.core.model.SearchScope
import com.oruke.onyx.core.model.VFile

/**
 * 搜索面板渲染所需的展示配置。
 *
 * 此对象只承载 UI 展示数据，避免搜索面板入口散列传递布局、历史记录和位置格式化能力。
 */
internal data class SearchPanelPresentation(
    /** 抽屉面板当前高度占可用区域的比例。 */
    val drawerHeightFraction: Float,
    /** 可供用户快速复用的历史查询。 */
    val searchHistory: List<String>,
    /** 将底层位置标识转换为可显示目录名称的函数。 */
    val locationLabel: (String) -> String,
)

/**
 * 搜索面板向组件层发出的用户动作。
 *
 * 回调保持在 UI contract 内，避免 Composable 同时依赖多个无关业务入口。
 */
internal data class SearchPanelActions(
    /** 更新当前搜索关键词。 */
    val onQueryChange: (String) -> Unit,
    /** 开始执行当前关键词和筛选条件的搜索。 */
    val onSearch: () -> Unit,
    /** 取消正在运行的搜索任务。 */
    val onCancel: () -> Unit,
    /** 关闭搜索面板。 */
    val onClose: () -> Unit,
    /** 打开搜索结果。 */
    val onOpenResult: (VFile) -> Unit,
    /** 在结果所在目录中定位搜索结果。 */
    val onOpenResultInFolder: (VFile) -> Unit,
    /** 更新搜索范围。 */
    val onUpdateScope: (SearchScope) -> Unit,
    /** 更新结构化搜索筛选条件。 */
    val onUpdateFilters: (SearchFilters) -> Unit,
    /** 持久化搜索抽屉的高度比例。 */
    val onSetDrawerHeight: (Float) -> Unit,
    /** 清空用户保存的搜索历史。 */
    val onClearHistory: () -> Unit,
)

/**
 * 搜索结果列表的局部交互回调。
 *
 * 此对象由搜索面板内部创建，用于把列表选择和右键菜单状态与业务动作解耦。
 */
internal data class SearchResultListActions(
    /** 变更当前键盘导航选中的结果索引。 */
    val onSelectIndex: (Int) -> Unit,
    /** 在指定窗口坐标展示结果右键菜单。 */
    val onShowContextMenu: (VFile, IntOffset) -> Unit,
)
