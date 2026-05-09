package com.oruke.onyx.app.component

import com.arkivanov.decompose.ComponentContext
import com.oruke.onyx.app.filesystem.VfsPathService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DefaultTabComponent(
    componentContext: ComponentContext,
    config: TabConfig,
    pathService: VfsPathService,
) : TabComponent, ComponentContext by componentContext {
    private val mutableState = MutableStateFlow(config.snapshot.toPaneTabState(pathService))

    override val state: StateFlow<PaneTabState> = mutableState.asStateFlow()

    override fun updateState(state: PaneTabState) {
        mutableState.value = state
    }
}
