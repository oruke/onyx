package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.PaneId

/**
 * 通过统一根接口取得面板，命令判断无需依赖实现类或界面工具函数。
 * @param paneId 目标面板身份。
 * @return 当前面板组件。
 */
internal fun RootComponent.paneComponent(paneId: PaneId): PaneComponent = when (paneId) {
    PaneId.PRIMARY -> primaryPane
    PaneId.SECONDARY -> secondaryPane
}

/**
 * 同步读取子组件的最新状态；根聚合流用于渲染，可能尚未合并同一事件中的导航或选择。
 * @param paneId 需要进行命令校验的面板。
 * @return 当前面板的权威状态。
 */
internal fun RootComponent.paneState(paneId: PaneId): PaneState = paneComponent(paneId).state.value
