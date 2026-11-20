package com.oruke.onyx.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import com.oruke.onyx.core.model.VFile

/** 键盘向下移动结果选中的增量。 */
private const val QUICK_OPEN_NEXT_SELECTION_DELTA = 1

/** 键盘向上移动结果选中的增量。 */
private const val QUICK_OPEN_PREVIOUS_SELECTION_DELTA = -1

/** PageUp / PageDown 单次翻页的默认步长。 */
private const val QUICK_OPEN_PAGE_SELECTION_DELTA = 8

/**
 * 处理快速打开浮层的键盘导航和打开快捷键。
 *
 * @param event 当前键盘事件。
 * @param query 当前搜索输入框关键词；防抖窗口内尚无结果时按回车可立即触发搜索。
 * @param results 当前搜索结果。
 * @param selectedIndex 当前选中结果下标。
 * @param actions 快速打开面板可发出的组件层动作。
 * @param onSelectedIndexChange 选中下标变更回调。
 * @param recentLocations 查询为空时可键盘导航的最近位置列表。
 * @param recentSelectedIndex 最近位置列表当前选中下标。
 * @param onRecentSelectedIndexChange 最近位置选中下标变更回调。
 * @return 当前事件是否已被快速打开浮层消费。
 */
internal fun handleQuickOpenKeyboardEvent(
    event: KeyEvent,
    results: List<VFile>,
    selectedIndex: Int,
    actions: QuickOpenPanelActions,
    onSelectedIndexChange: (Int) -> Unit,
    query: String = "",
    recentLocations: List<String> = emptyList(),
    recentSelectedIndex: Int = QuickOpenPanelDefaults.INITIAL_SELECTED_INDEX,
    onRecentSelectedIndexChange: (Int) -> Unit = {},
): Boolean = if (event.type != KeyEventType.KeyDown) {
    false
} else {
    val showingRecentLocations = query.isBlank() && results.isEmpty() && recentLocations.isNotEmpty()
    val activeCount = if (showingRecentLocations) recentLocations.size else results.size
    val activeSelectedIndex = if (showingRecentLocations) recentSelectedIndex else selectedIndex
    val updateActiveSelection: (Int) -> Unit = if (showingRecentLocations) {
        onRecentSelectedIndexChange
    } else {
        onSelectedIndexChange
    }
    when (event.key) {
        Key.DirectionDown -> {
            moveQuickOpenSelectionIndex(
                selectedIndex = activeSelectedIndex,
                itemCount = activeCount,
                delta = QUICK_OPEN_NEXT_SELECTION_DELTA,
                onSelectedIndexChange = updateActiveSelection,
            )
            true
        }

        Key.DirectionUp -> {
            moveQuickOpenSelectionIndex(
                selectedIndex = activeSelectedIndex,
                itemCount = activeCount,
                delta = QUICK_OPEN_PREVIOUS_SELECTION_DELTA,
                onSelectedIndexChange = updateActiveSelection,
            )
            true
        }

        Key.PageDown -> {
            moveQuickOpenSelectionIndex(
                selectedIndex = activeSelectedIndex,
                itemCount = activeCount,
                delta = QUICK_OPEN_PAGE_SELECTION_DELTA,
                onSelectedIndexChange = updateActiveSelection,
            )
            true
        }

        Key.PageUp -> {
            moveQuickOpenSelectionIndex(
                selectedIndex = activeSelectedIndex,
                itemCount = activeCount,
                delta = -QUICK_OPEN_PAGE_SELECTION_DELTA,
                onSelectedIndexChange = updateActiveSelection,
            )
            true
        }

        Key.MoveHome -> {
            val primaryOnly = (event.isCtrlPressed || event.isMetaPressed) &&
                !event.isShiftPressed && !event.isAltPressed
            val unmodifiedOnEmptyQuery = query.isEmpty() &&
                !event.isCtrlPressed && !event.isMetaPressed && !event.isShiftPressed && !event.isAltPressed
            if (primaryOnly || unmodifiedOnEmptyQuery) {
                if (activeCount > 0) updateActiveSelection(QuickOpenPanelDefaults.INITIAL_SELECTED_INDEX)
                true
            } else {
                false
            }
        }

        Key.MoveEnd -> {
            val primaryOnly = (event.isCtrlPressed || event.isMetaPressed) &&
                !event.isShiftPressed && !event.isAltPressed
            val unmodifiedOnEmptyQuery = query.isEmpty() &&
                !event.isCtrlPressed && !event.isMetaPressed && !event.isShiftPressed && !event.isAltPressed
            if (primaryOnly || unmodifiedOnEmptyQuery) {
                if (activeCount > 0) updateActiveSelection(activeCount - 1)
                true
            } else {
                false
            }
        }

        Key.Enter, Key.NumPadEnter -> {
            if (showingRecentLocations) {
                recentLocations.getOrNull(recentSelectedIndex)?.let(actions.onOpenLocation)
            } else {
                val selectedFile = results.getOrNull(selectedIndex)
                if (selectedFile != null) {
                    openQuickOpenSelectedResult(
                        selectedFile = selectedFile,
                        shouldOpenInFolder = event.isCtrlPressed || event.isMetaPressed,
                        actions = actions,
                    )
                } else if (query.isNotBlank()) {
                    actions.onExecuteSearch()
                }
            }
            true
        }

        Key.Escape -> {
            actions.onClose()
            true
        }

        else -> false
    }
}

/**
 * 依据方向键在非空列表（搜索结果或最近位置）中移动当前选中项。
 *
 * @param selectedIndex 当前选中下标。
 * @param itemCount 列表条目总数。
 * @param delta 本次移动的下标增量。
 * @param onSelectedIndexChange 选中下标变更回调。
 */
private fun moveQuickOpenSelectionIndex(
    selectedIndex: Int,
    itemCount: Int,
    delta: Int,
    onSelectedIndexChange: (Int) -> Unit,
) {
    if (itemCount > 0) {
        val nextIndex = (selectedIndex + delta).coerceIn(
            QuickOpenPanelDefaults.INITIAL_SELECTED_INDEX,
            itemCount - 1,
        )
        onSelectedIndexChange(nextIndex)
    }
}

/**
 * 按 Enter 快捷键打开当前选中结果或在其父目录中定位。
 *
 * @param selectedFile 当前选中的文件；不存在时不执行动作。
 * @param shouldOpenInFolder 是否需要在父目录中定位。
 * @param actions 快速打开面板可发出的组件层动作。
 */
private fun openQuickOpenSelectedResult(
    selectedFile: VFile?,
    shouldOpenInFolder: Boolean,
    actions: QuickOpenPanelActions,
) {
    selectedFile?.let { file ->
        if (shouldOpenInFolder) {
            actions.onOpenResultInFolder(file)
        } else {
            actions.onOpenResult(file)
        }
    }
}
