package com.oruke.onyx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.oruke.onyx.app.component.SearchPanelState
import com.oruke.onyx.core.model.VFile
import kotlinx.coroutines.delay

/**
 * 快速打开浮层在多个渲染职责间共享的视觉和交互常量。
 */
internal object QuickOpenPanelDefaults {
    /** 快捷打开输入停止后的自动搜索延迟，单位为毫秒。 */
    const val SEARCH_DEBOUNCE_MILLIS = 300L

    /** 快捷打开结果列表的初始选中下标。 */
    const val INITIAL_SELECTED_INDEX = 0

    /** 快捷打开结果不存在匹配文本时的下标标识。 */
    const val NO_MATCH_INDEX = -1

    /** 浮层遮罩叠加到主内容上的黑色透明度。 */
    const val OVERLAY_DIM_ALPHA = 0.35f

    /** 选中结果左侧强调线的宽度。 */
    val SELECTED_INDICATOR_WIDTH = 3.dp

    /** 结果名称和位置在一行中各自占据的可用宽度权重。 */
    const val RESULT_TEXT_COLUMN_WEIGHT = 0.5f
}

/**
 * 描述快速打开面板可发出的组件层动作。
 */
internal data class QuickOpenPanelActions(
    /** 搜索关键字变更回调。 */
    val onQueryChange: (String) -> Unit,
    /** 延迟结束后执行搜索的回调。 */
    val onExecuteSearch: () -> Unit,
    /** 关闭浮层的回调。 */
    val onClose: () -> Unit,
    /** 打开结果的回调。 */
    val onOpenResult: (VFile) -> Unit,
    /** 在结果所在目录中定位结果的回调。 */
    val onOpenResultInFolder: (VFile) -> Unit,
)

/**
 * 描述待展示的快速打开结果右键菜单。
 */
internal data class QuickOpenContextMenuRequest(
    /** 右键点击的目标文件。 */
    val file: VFile,
    /** 菜单在窗口坐标系中的锚点位置。 */
    val anchorOffset: IntOffset,
)

/**
 * 渲染全局快速打开浮层，并将搜索、打开和右键菜单动作委托给组件层。
 *
 * @param state 当前搜索面板状态。
 * @param locationLabel 将文件位置转换为可展示文本的函数。
 * @param onQueryChange 搜索关键字变更回调。
 * @param onExecuteSearch 延迟结束后执行搜索的回调。
 * @param onClose 关闭浮层的回调。
 * @param onOpenResult 打开选中结果的回调。
 * @param onOpenResultInFolder 在结果所在目录中定位结果的回调。
 * @param modifier 应用于全屏浮层的修饰符。
 */
@Composable
internal fun QuickOpenPanel(
    state: SearchPanelState,
    locationLabel: (String) -> String,
    onQueryChange: (String) -> Unit,
    onExecuteSearch: () -> Unit,
    onClose: () -> Unit,
    onOpenResult: (VFile) -> Unit,
    onOpenResultInFolder: (VFile) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    var selectedIndex by remember {
        mutableIntStateOf(QuickOpenPanelDefaults.INITIAL_SELECTED_INDEX)
    }
    var contextMenuRequest by remember { mutableStateOf<QuickOpenContextMenuRequest?>(null) }
    val actions = QuickOpenPanelActions(
        onQueryChange = onQueryChange,
        onExecuteSearch = onExecuteSearch,
        onClose = onClose,
        onOpenResult = onOpenResult,
        onOpenResultInFolder = onOpenResultInFolder,
    )

    RequestQuickOpenInputFocus(focusRequester)
    KeepQuickOpenSelectionVisible(selectedIndex, state.results, listState)
    ExecuteQuickOpenSearchAfterQuerySettles(state.query, actions)
    SynchronizeQuickOpenSelectedIndex(state.results.size, selectedIndex) { selectedIndex = it }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = QuickOpenPanelDefaults.OVERLAY_DIM_ALPHA))
            .clickable(onClick = actions.onClose),
    ) {
        QuickOpenDialogSurface(
            state = state,
            focusRequester = focusRequester,
            listState = listState,
            selectedIndex = selectedIndex,
            locationLabel = locationLabel,
            actions = actions,
            onSelectedIndexChange = { selectedIndex = it },
            onShowContextMenu = { index, file, offset ->
                selectedIndex = index
                contextMenuRequest = QuickOpenContextMenuRequest(file, offset)
            },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 64.dp),
        )
        QuickOpenResultContextMenu(
            request = contextMenuRequest,
            actions = actions,
            onClose = { contextMenuRequest = null },
        )
    }
}

/**
 * 在快速打开浮层首次展示时将焦点交给关键字输入框。
 *
 * @param focusRequester 需要请求焦点的输入框请求器。
 */
@Composable
private fun RequestQuickOpenInputFocus(focusRequester: FocusRequester) {
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}

/**
 * 在用户停止输入后延迟执行搜索，避免每次输入都立即触发 I/O 搜索。
 *
 * @param query 当前关键字。
 * @param actions 快速打开面板可发出的组件层动作。
 */
@Composable
private fun ExecuteQuickOpenSearchAfterQuerySettles(
    query: String,
    actions: QuickOpenPanelActions,
) {
    LaunchedEffect(query) {
        if (query.isNotBlank()) {
            delay(QuickOpenPanelDefaults.SEARCH_DEBOUNCE_MILLIS)
            actions.onExecuteSearch()
        }
    }
}

/**
 * 将选中下标限定在结果列表的有效范围内。
 *
 * @param resultCount 当前结果数量。
 * @param selectedIndex 当前选中下标。
 * @param onSelectedIndexChange 需要修正选中下标时的回调。
 */
@Composable
private fun SynchronizeQuickOpenSelectedIndex(
    resultCount: Int,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
) {
    LaunchedEffect(resultCount) {
        val synchronizedIndex = if (resultCount == QuickOpenPanelDefaults.INITIAL_SELECTED_INDEX) {
            QuickOpenPanelDefaults.INITIAL_SELECTED_INDEX
        } else {
            selectedIndex.coerceIn(
                QuickOpenPanelDefaults.INITIAL_SELECTED_INDEX,
                resultCount - 1,
            )
        }
        if (synchronizedIndex != selectedIndex) {
            onSelectedIndexChange(synchronizedIndex)
        }
    }
}
