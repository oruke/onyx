package com.oruke.onyx.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
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

/**
 * 隔离滚动条自身的指针按下、拖拽与释放事件，阻断其向父级列表容器冒泡而误触发框选、清空选择、右键菜单或双击返回上级。
 *
 * 该修饰符挂载在 `VerticalScrollbar` / `HorizontalScrollbar` 外层并在 [PointerEventPass.Main] 阶段运行：
 * 滚动条内部的滑块拖拽（`scrollbarDrag`）与轨道点击翻页（`scrollOnPressTrack`）已先一步完成响应，
 * 随后在此处统一消费整段按下手势的所有指针变更，同时保留鼠标悬停高亮与滚轮滚动事件。
 *
 * @param onActivate 激活所属面板回调。
 * @param onDismissContextMenu 关闭上下文菜单回调。
 * @return 附加滚动条指针事件隔离后的修饰符。
 */
@Composable
internal fun Modifier.isolateScrollbarPointerEvents(
    onActivate: () -> Unit = {},
    onDismissContextMenu: () -> Unit = {},
): Modifier {
    val currentOnActivate = rememberUpdatedState(onActivate)
    val currentOnDismissContextMenu = rememberUpdatedState(onDismissContextMenu)
    return pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val initialEvent = awaitPointerEvent(PointerEventPass.Main)
                if (initialEvent.type != PointerEventType.Press) continue
                currentOnActivate.value()
                currentOnDismissContextMenu.value()
                initialEvent.changes.forEach { it.consume() }
                if (initialEvent.changes.any { it.pressed }) {
                    while (true) {
                        val followUp = awaitPointerEvent(PointerEventPass.Main)
                        followUp.changes.forEach { it.consume() }
                        if (followUp.changes.none { it.pressed }) break
                    }
                }
            }
        }
    }
}
