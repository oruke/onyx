package com.oruke.onyx.core.model

import kotlinx.serialization.Serializable

@Serializable
/** 应用退出时保存的双面板会话快照。 */
data class AppSessionSnapshot(
    /** 当前面板布局。 */
    val layoutMode: PaneLayoutMode,
    /** 双面板分割比例。 */
    val paneSplitFraction: Float,
    /** 当前活动面板。 */
    val activePane: PaneId,
    /** 主面板会话。 */
    val primaryPane: PaneSessionSnapshot,
    /** 次面板会话。 */
    val secondaryPane: PaneSessionSnapshot,
)

@Serializable
/** 单个面板的标签页会话快照。 */
data class PaneSessionSnapshot(
    /** 活动标签页 ID。 */
    val activeTabId: String,
    /** 面板全部标签页。 */
    val tabs: List<TabSnapshot>,
)

@Serializable
/** 单个标签页可持久化的浏览与视图状态。 */
data class TabSnapshot(
    /** 标签页唯一 ID。 */
    val id: String,
    /** 当前 VFS 位置。 */
    val location: String,
    /** 详情视图列顺序。 */
    val detailsColumns: List<DetailsColumn>,
    /** 详情视图列宽。 */
    val detailsColumnWeights: Map<DetailsColumn, Float>,
    /** 详情视图排序。 */
    val detailsSort: DetailsSort,
    /** 是否显示隐藏条目。 */
    val showHiddenItems: Boolean,
    /** 当前文件视图模式。 */
    val viewMode: ViewMode,
    /** 当前列表过滤文本。 */
    val filterQuery: String,
    /** 后退历史位置。 */
    val backStack: List<String>,
    /** 前进历史位置。 */
    val forwardStack: List<String>,
)

/** 向后兼容的标签会话类型别名。 */
typealias TabSessionSnapshot = TabSnapshot
