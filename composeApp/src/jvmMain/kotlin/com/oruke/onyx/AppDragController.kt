package com.oruke.onyx

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntOffset
import com.oruke.onyx.app.component.PaneEntriesState
import com.oruke.onyx.app.component.RootComponent
import com.oruke.onyx.app.component.RootIntent
import com.oruke.onyx.app.platform.ExternalFileDragService
import com.oruke.onyx.core.model.FileTransferOperation
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.ui.PaneDragBindings
import com.oruke.onyx.ui.theme.FileDragState
import com.oruke.onyx.ui.theme.FileDropTarget
import com.oruke.onyx.ui.theme.FileDropZone
import com.oruke.onyx.ui.theme.TabDropTarget
import com.oruke.onyx.ui.theme.TabDropZone
import com.oruke.onyx.ui.theme.area
import com.oruke.onyx.ui.theme.containsPoint
import com.oruke.onyx.ui.theme.dropIndex
import com.oruke.onyx.ui.theme.paneState

/**
 * 管理应用窗口内的标签拖放、文件拖放及系统拖放衔接。
 */
internal class AppDragController(
    /** 根组件业务接口。 */
    private val rootComponent: RootComponent,
    /** 系统级文件拖放服务。 */
    private val externalFileDragService: ExternalFileDragService,
) {
    /** 各面板当前注册的标签放置区域。 */
    private val tabDropZones = mutableStateMapOf<PaneId, TabDropZone>()

    /** 文件视图当前注册的放置区域。 */
    private val fileDropZones = mutableStateMapOf<String, FileDropZone>()

    /** 当前标签放置目标。 */
    private var tabDropTarget by mutableStateOf<TabDropTarget?>(null)

    /** 当前文件拖拽来源和操作。 */
    var fileDragState by mutableStateOf<FileDragState?>(null)
        private set

    /** 当前文件放置目标。 */
    var fileDropTarget by mutableStateOf<FileDropTarget?>(null)
        private set

    /** 当前文件拖拽窗口坐标。 */
    var fileDragPosition by mutableStateOf<IntOffset?>(null)
        private set

    /** 面板渲染所需的最新拖放状态和回调。 */
    val bindings: PaneDragBindings
        get() = PaneDragBindings(
            tabDropZones = tabDropZones,
            tabDropTarget = tabDropTarget,
            fileDropTarget = fileDropTarget,
            fileDropZones = fileDropZones,
            onTabDrop = ::dropTab,
            onTabDragPositionChange = ::updateTabDragPosition,
            onTabDragEnd = ::endTabDrag,
            onFileDragStart = ::startFileDrag,
            onFileDragPositionChange = ::updateFileDragPosition,
            onFileDragEnd = ::endFileDrag,
        )

    /**
     * 将标签移动到窗口坐标命中的标签栏位置。
     *
     * @param sourcePaneId 来源面板。
     * @param tabId 标签标识。
     * @param windowPosition 放置窗口坐标。
     */
    private fun dropTab(sourcePaneId: PaneId, tabId: String, windowPosition: IntOffset) {
        val target = resolveTabDropTarget(windowPosition) ?: return
        rootComponent.dispatch(
            RootIntent.MoveTab(sourcePaneId, tabId, target.paneId, target.index),
        )
        tabDropTarget = null
    }

    /**
     * 更新标签拖拽位置及其命中目标。
     *
     * @param windowPosition 当前窗口坐标。
     */
    private fun updateTabDragPosition(windowPosition: IntOffset) {
        tabDropTarget = resolveTabDropTarget(windowPosition)
    }

    /** 结束标签拖拽并清理放置提示。 */
    private fun endTabDrag() {
        tabDropTarget = null
    }

    /**
     * 初始化文件拖拽，并根据来源和修饰键确定初始操作。
     *
     * @param sourcePaneId 来源面板。
     * @param operation 用户请求的文件操作。
     */
    private fun startFileDrag(sourcePaneId: PaneId, operation: FileTransferOperation) {
        val sourcePaneState = rootComponent.state.value.paneState(sourcePaneId)
        val entries = (sourcePaneState.entriesState as? PaneEntriesState.Ready)?.entries.orEmpty()
        val selectedEntries = entries.filter { it.id in sourcePaneState.selectedEntryIds }
        val isArchiveSource = rootComponent.prepareExternalDrag(selectedEntries)
        val effectiveOperation = when {
            isArchiveSource -> FileTransferOperation.EXTRACT
            operation == FileTransferOperation.COPY -> FileTransferOperation.COPY
            else -> FileTransferOperation.MOVE
        }
        fileDragState = FileDragState(
            sourcePaneId = sourcePaneId,
            operation = effectiveOperation,
            userForced = operation == FileTransferOperation.COPY,
        )
    }

    /**
     * 更新文件拖拽目标，并按来源和目标文件系统动态选择复制或移动。
     *
     * @param windowPosition 当前窗口坐标。
     */
    private fun updateFileDragPosition(windowPosition: IntOffset) {
        fileDragPosition = windowPosition
        fileDropTarget = resolveFileDropTarget(windowPosition)
        val dragState = fileDragState
        val target = fileDropTarget
        if (dragState != null && target != null) {
            val canResolveAutomatically = !dragState.userForced &&
                dragState.operation != FileTransferOperation.EXTRACT
            if (canResolveAutomatically) {
                val source = rootComponent.state.value.paneState(dragState.sourcePaneId).location
                val operation = rootComponent.resolveTransferOperation(source, target.targetDirectoryLocation)
                if (operation != dragState.operation) {
                    fileDragState = dragState.copy(operation = operation)
                }
            }
        }
    }

    /**
     * 完成内部文件放置，或在系统拖放已接管时仅清理本地状态。
     *
     * @param windowPosition 松开指针时的窗口坐标；系统拖放可能不提供该坐标。
     */
    private fun endFileDrag(windowPosition: IntOffset?) {
        val dragState = fileDragState
        val target = windowPosition?.let(::resolveFileDropTarget) ?: fileDropTarget
        if (!externalFileDragService.isSystemDragActive && dragState != null && target != null) {
            rootComponent.dispatch(
                RootIntent.RequestTransferSelectedToDirectory(
                    sourcePaneId = dragState.sourcePaneId,
                    targetDirectoryLocation = target.targetDirectoryLocation,
                    operation = dragState.operation,
                ),
            )
            rootComponent.dispatch(RootIntent.ActivatePane(target.paneId))
        }
        fileDragState = null
        fileDropTarget = null
        fileDragPosition = null
        externalFileDragService.clearPending()
    }

    /**
     * 解析当前窗口坐标对应的标签放置位置。
     *
     * @param windowPosition 当前窗口坐标。
     * @return 命中的面板和插入索引，无目标时返回 null。
     */
    private fun resolveTabDropTarget(windowPosition: IntOffset): TabDropTarget? {
        val target = tabDropZones.entries.firstOrNull { (_, zone) ->
            zone.bounds.containsPoint(windowPosition)
        } ?: return null
        return TabDropTarget(target.key, target.value.dropIndex(windowPosition))
    }

    /**
     * 解析当前窗口坐标对应的文件放置目录。
     *
     * @param windowPosition 当前窗口坐标。
     * @return 最精确的有效文件放置目标，无目标时返回 null。
     */
    private fun resolveFileDropTarget(windowPosition: IntOffset): FileDropTarget? {
        val dragState = fileDragState ?: return null
        val currentState = rootComponent.state.value
        val zone = fileDropZones.values
            .filter(::isCurrentFileDropZone)
            .filter { it.bounds.containsPoint(windowPosition) }
            .sortedWith(
                compareByDescending<FileDropZone> { it.directoryEntryId != null }
                    .thenBy { it.bounds.area },
            )
            .firstOrNull() ?: return null
        val sourceLocation = currentState.paneState(dragState.sourcePaneId).location
        val targetLocation = zone.directoryEntryId?.let { zone.targetDirectoryLocation }
            ?: currentState.paneState(zone.paneId).location
        return FileDropTarget(zone.paneId, targetLocation, zone.directoryEntryId).takeUnless {
            zone.paneId == dragState.sourcePaneId &&
                zone.directoryEntryId == null &&
                targetLocation == sourceLocation
        }
    }

    /**
     * 判断文件放置区域是否仍属于当前目录内容。
     *
     * @param zone 待校验的放置区域。
     * @return 区域仍然有效时返回 true。
     */
    private fun isCurrentFileDropZone(zone: FileDropZone): Boolean {
        val directoryEntryId = zone.directoryEntryId ?: return true
        val paneState = rootComponent.state.value.paneState(zone.paneId)
        val entries = (paneState.entriesState as? PaneEntriesState.Ready)?.entries.orEmpty()
        return entries.any { entry ->
            entry.id == directoryEntryId &&
                entry.location == zone.targetDirectoryLocation &&
                entry.kind == VFileKind.DIRECTORY
        }
    }
}
