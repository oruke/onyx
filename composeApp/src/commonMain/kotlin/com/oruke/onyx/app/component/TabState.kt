package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.DetailsColumn
import com.oruke.onyx.core.model.DetailsSort
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.PaneInlineEditState
import com.oruke.onyx.core.model.PaneInspectorState
import com.oruke.onyx.core.model.PaneOperationFeedback
import com.oruke.onyx.core.model.PaneStatusInfo
import com.oruke.onyx.core.model.ViewMode

/** 单标签当前访问的可转移状态，普通目录与设备概览分别保存视图偏好。 */
internal data class TabState(
    /** 当前真实或应用内 VFS 位置。 */
    val location: String,
    /** 是否有可返回的浏览历史。 */
    val canGoBack: Boolean,
    /** 是否有可前进的浏览历史。 */
    val canGoForward: Boolean,
    /** 普通目录详情视图的列顺序。 */
    val detailsColumns: List<DetailsColumn>,
    /** 普通目录详情视图的列宽。 */
    val detailsColumnWeights: Map<DetailsColumn, Float>,
    /** 普通目录的排序配置。 */
    val detailsSort: DetailsSort,
    /** 普通目录视图偏好，不承担此电脑的概览模式。 */
    val viewMode: ViewMode,
    /** 当前访问的筛选文本。 */
    val filterQuery: String,
    /** 已选中条目的稳定身份。 */
    val selectedEntryIds: Set<String>,
    /** 范围选择的起始条目。 */
    val selectionAnchorId: String?,
    /** 键盘选择与打开动作的焦点条目。 */
    val selectionFocusId: String?,
    /** 面板状态栏的条目与选择统计。 */
    val statusInfo: PaneStatusInfo,
    /** 当前新建或重命名编辑状态。 */
    val inlineEditState: PaneInlineEditState?,
    /** 当前预览与详细信息面板状态。 */
    val inspectorState: PaneInspectorState,
    /** 当前访问保留的业务操作反馈。 */
    val operationFeedback: PaneOperationFeedback?,
    /** 是否显示隐藏文件。 */
    val showHiddenItems: Boolean,
    /** 普通详情列表隐藏的列。 */
    val hiddenColumns: Set<DetailsColumn>,
    /** 普通文件画廊的项目边长。 */
    val galleryItemSizeDp: Int,
    /** 当前访问的目录、设备概览或加载结果。 */
    val entriesState: PaneEntriesState,
    /** 尚未完成的显式滚动定位目标。 */
    val pendingScrollToEntryId: String? = null,
    /** 当前访问的浏览状态，目录刷新不会重置。 */
    val browserState: PaneBrowserState = PaneBrowserState(),
    /** 已有快照正在后台校验，列表继续显示。 */
    val refreshing: Boolean = false,
    /** 后台更新失败详情，旧列表保持可见。 */
    val directoryError: I18nMessage? = null,
    /** 观察能力降级原因，后台轮询仍然可用。 */
    val directoryObservationError: I18nMessage? = null,
    /** 此电脑独立记忆概览或紧凑列表，默认使用磁盘概览。 */
    val computerViewMode: ViewMode = ViewMode.GALLERY,
)
