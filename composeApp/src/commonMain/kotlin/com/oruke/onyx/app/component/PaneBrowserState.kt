package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.ViewMode

/** 文件列表视口的普通数据锚点，独立于 Compose 状态对象。 */
internal data class PaneViewportAnchor(
    /** 首个可见真实条目 ID。 */
    val entryId: String?,
    /** 条目消失时使用的备用索引。 */
    val index: Int,
    /** 首项在当前行中的逻辑偏移。 */
    val offsetDp: Float,
    /** 详情视图的横向逻辑偏移。 */
    val horizontalOffsetDp: Float = 0f,
    /** 锚点消失时优先定位的后继条目。 */
    val followingIds: List<String> = emptyList(),
    /** 没有后继时用于定位的前驱条目，按距离从近到远排列。 */
    val precedingIds: List<String> = emptyList(),
)

/** 单次目录访问的交互状态；历史恢复复用该访问身份。 */
internal data class PaneBrowserState(
    /** 访问编号，与标签 ID 组合后唯一。 */
    val visitId: Long = 0,
    /** 用户选择、编辑或滚动产生的交互版本。 */
    val interactionRevision: Long = 0,
    /** 按视图模式保存的最近视口。 */
    val viewports: Map<ViewMode, PaneViewportAnchor> = emptyMap(),
    /** 已应用的共享目录内容版本。 */
    val contentRevision: Long = -1,
    /** 共享内容的有界身份变化日志，视口适配器按自己的版本重放。 */
    val identityChanges: List<com.oruke.onyx.core.model.DirectoryRevisionChange> = emptyList(),
    /** 文件区域仍有按住的指针，覆盖点击、框选和文件拖拽。 */
    val pointerInteractionActive: Boolean = false,
    /** 用户正在滚动，后台变化暂不改变已有条目的相对顺序。 */
    val scrollInteractionActive: Boolean = false,
    /** 曾因交互推迟排序，最后一项交互结束后需要重新派生顺序。 */
    val directoryOrderDeferred: Boolean = false,
)

/** 历史访问的轻量快照，目录内容由应用级目录缓存保存。 */
internal data class PaneVisitSnapshot(
    /** 不含目录条目和编辑草稿的标签交互状态。 */
    val state: TabState,
    /** 离开时的内联展开位置与深度，不保留重复子项数据。 */
    val expansions: Map<String, Int>,
)

/** 跨面板转移的运行时标签载荷，独立于磁盘会话格式。 */
internal data class PaneTabTransfer(
    /** 完整标签状态及访问历史。 */
    val tab: PaneTabState,
    /** 当前访问的展开位置及深度。 */
    val expansions: Map<String, Int>,
)
