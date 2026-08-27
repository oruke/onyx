package com.oruke.onyx.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import com.oruke.onyx.core.model.VFile

/** 键盘向下移动结果选中的增量。 */
private const val QUICK_OPEN_NEXT_SELECTION_DELTA = 1

/** 键盘向上移动结果选中的增量。 */
private const val QUICK_OPEN_PREVIOUS_SELECTION_DELTA = -1

/**
 * 处理快速打开浮层的键盘导航和打开快捷键。
 *
 * @param event 当前键盘事件。
 * @param results 当前搜索结果。
 * @param selectedIndex 当前选中结果下标。
 * @param actions 快速打开面板可发出的组件层动作。
 * @param onSelectedIndexChange 选中下标变更回调。
 * @return 当前事件是否已被快速打开浮层消费。
 */
internal fun handleQuickOpenKeyboardEvent(
    event: KeyEvent,
    results: List<VFile>,
    selectedIndex: Int,
    actions: QuickOpenPanelActions,
    onSelectedIndexChange: (Int) -> Unit,
): Boolean = if (event.type != KeyEventType.KeyDown) {
    false
} else {
    when (event.key) {
        Key.DirectionDown -> {
            moveQuickOpenSelection(
                selectedIndex = selectedIndex,
                results = results,
                delta = QUICK_OPEN_NEXT_SELECTION_DELTA,
                onSelectedIndexChange = onSelectedIndexChange,
            )
            true
        }

        Key.DirectionUp -> {
            moveQuickOpenSelection(
                selectedIndex = selectedIndex,
                results = results,
                delta = QUICK_OPEN_PREVIOUS_SELECTION_DELTA,
                onSelectedIndexChange = onSelectedIndexChange,
            )
            true
        }

        Key.Enter -> {
            openQuickOpenSelectedResult(
                selectedFile = results.getOrNull(selectedIndex),
                shouldOpenInFolder = event.isCtrlPressed || event.isMetaPressed,
                actions = actions,
            )
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
 * 依据方向键在非空搜索结果中移动当前选中项。
 *
 * @param selectedIndex 当前选中结果下标。
 * @param results 当前搜索结果。
 * @param delta 本次移动的下标增量。
 * @param onSelectedIndexChange 选中下标变更回调。
 */
private fun moveQuickOpenSelection(
    selectedIndex: Int,
    results: List<VFile>,
    delta: Int,
    onSelectedIndexChange: (Int) -> Unit,
) {
    if (results.isNotEmpty()) {
        val nextIndex = (selectedIndex + delta).coerceIn(
            QuickOpenPanelDefaults.INITIAL_SELECTED_INDEX,
            results.lastIndex,
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
