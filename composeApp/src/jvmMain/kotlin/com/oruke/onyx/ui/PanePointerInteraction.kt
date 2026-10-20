package com.oruke.onyx.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.oruke.onyx.app.component.PaneIntent

/**
 * 仅观察文件区域的按下与全部松开，不消费子控件手势，取消或离开访问时也释放交互状态。
 * @param tabId 渲染所属标签。
 * @param visitId 渲染所属访问。
 * @param dispatch 组件事件入口，排序策略由组件处理。
 * @return 增加指针状态报告的修饰符。
 */
@Composable
internal fun Modifier.trackPanePointerInteraction(
    tabId: String,
    visitId: Long,
    dispatch: (PaneIntent) -> Unit,
): Modifier {
    val currentDispatch = rememberUpdatedState(dispatch)
    return pointerInput(tabId, visitId) {
        var active = false
        try {
            awaitPointerEventScope {
                while (true) {
                    val pressed = awaitPointerEvent(PointerEventPass.Initial).changes.any { it.pressed }
                    if (active != pressed) {
                        active = pressed
                        currentDispatch.value(PaneIntent.ListPointerInteraction(tabId, visitId, active))
                    }
                }
            }
        } finally {
            if (active) currentDispatch.value(PaneIntent.ListPointerInteraction(tabId, visitId, false))
        }
    }
}
