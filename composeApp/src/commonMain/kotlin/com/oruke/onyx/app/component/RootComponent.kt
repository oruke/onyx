package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.PaneLayoutMode
import kotlinx.coroutines.flow.StateFlow

data class RootState(
    val layoutMode: PaneLayoutMode,
    val activePane: PaneId,
    val primaryPane: PaneState,
    val secondaryPane: PaneState,
)

interface RootComponent {
    val state: StateFlow<RootState>
    val primaryPane: PaneComponent
    val secondaryPane: PaneComponent

    fun setLayoutMode(mode: PaneLayoutMode)

    fun activatePane(paneId: PaneId)

    fun refreshActivePane()
}
