package com.oruke.onyx.ui

import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.unit.IntOffset
import com.oruke.onyx.core.model.FileTransferOperation
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.ui.theme.FileDropTarget
import com.oruke.onyx.ui.theme.FileDropZone
import com.oruke.onyx.ui.theme.TabDropTarget
import com.oruke.onyx.ui.theme.TabDropZone

/**
 * 面板标签与文件拖放所需的共享状态和回调。
 */
internal data class PaneDragBindings(
    /** 各面板标签栏当前注册的放置区域。 */
    val tabDropZones: SnapshotStateMap<PaneId, TabDropZone>,
    /** 当前命中的标签放置目标。 */
    val tabDropTarget: TabDropTarget?,
    /** 当前命中的文件放置目标。 */
    val fileDropTarget: FileDropTarget?,
    /** 文件视图当前注册的放置区域。 */
    val fileDropZones: SnapshotStateMap<String, FileDropZone>,
    /** 完成标签拖放的回调。 */
    val onTabDrop: (PaneId, String, IntOffset) -> Unit,
    /** 更新标签拖拽位置的回调。 */
    val onTabDragPositionChange: (IntOffset) -> Unit,
    /** 结束标签拖拽的回调。 */
    val onTabDragEnd: () -> Unit,
    /** 开始文件拖拽的回调。 */
    val onFileDragStart: (PaneId, FileTransferOperation) -> Unit,
    /** 更新文件拖拽位置的回调。 */
    val onFileDragPositionChange: (IntOffset) -> Unit,
    /** 结束文件拖拽并可选执行放置的回调。 */
    val onFileDragEnd: (IntOffset?) -> Unit,
)
