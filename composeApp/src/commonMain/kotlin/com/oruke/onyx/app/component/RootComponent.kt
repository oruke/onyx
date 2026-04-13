package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.BackgroundTask
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.PaneLayoutMode
import kotlinx.coroutines.flow.StateFlow

data class RootState(
    val layoutMode: PaneLayoutMode,
    val activePane: PaneId,
    val primaryPane: PaneState,
    val secondaryPane: PaneState,
    val canPaste: Boolean,
    val tasks: List<BackgroundTask>,
)

interface RootComponent {
    val state: StateFlow<RootState>
    val primaryPane: PaneComponent
    val secondaryPane: PaneComponent

    fun setLayoutMode(mode: PaneLayoutMode)

    fun activatePane(paneId: PaneId)

    fun refreshActivePane()

    fun stageCopySelectedInPane(paneId: PaneId)

    fun stageCutSelectedInPane(paneId: PaneId)

    fun requestPasteIntoPane(paneId: PaneId)

    fun requestDeleteSelectedInPane(paneId: PaneId)

    fun dismissTask(taskId: String)
}
