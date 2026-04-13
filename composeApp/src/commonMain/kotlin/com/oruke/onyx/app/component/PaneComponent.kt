package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.VFile
import kotlinx.coroutines.flow.StateFlow

data class PaneState(
    val paneId: PaneId,
    val location: String,
    val entriesState: PaneEntriesState,
)

sealed interface PaneEntriesState {
    data object Idle : PaneEntriesState

    data object Loading : PaneEntriesState

    data class Ready(
        val entries: List<VFile>,
    ) : PaneEntriesState

    data class Failure(
        val reason: String?,
    ) : PaneEntriesState
}

interface PaneComponent {
    val state: StateFlow<PaneState>

    fun refresh()

    fun goUp()

    fun openDirectory(location: String)

    fun openEntry(entry: VFile)
}
