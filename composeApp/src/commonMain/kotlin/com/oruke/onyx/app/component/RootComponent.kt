package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.BackgroundTask
import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.PaneLayoutMode
import kotlinx.coroutines.flow.StateFlow

data class RootState(
    val layoutMode: PaneLayoutMode,
    val paneSplitFraction: Float,
    val activePane: PaneId,
    val primaryPane: PaneState,
    val secondaryPane: PaneState,
    val settings: OnyxSettings,
    val sessionRestoreState: SessionRestoreState,
    val dialogState: RootDialogState?,
    val canPaste: Boolean,
    val tasks: List<BackgroundTask>,
)

sealed interface SessionRestoreState {
    data object Loading : SessionRestoreState

    data object Ready : SessionRestoreState

    data class Failed(
        val reason: String?,
    ) : SessionRestoreState
}

sealed interface RootDialogState {
    data class Confirmation(
        val title: String,
        val message: String,
    ) : RootDialogState

    data class ConflictResolution(
        val sourceName: String,
        val targetLocation: String,
    ) : RootDialogState
}

enum class FileTransferOperation {
    COPY,
    MOVE,
}

interface RootComponent {
    val state: StateFlow<RootState>
    val primaryPane: PaneComponent
    val secondaryPane: PaneComponent

    fun setLayoutMode(mode: PaneLayoutMode)

    fun setPaneSplitFraction(fraction: Float)

    fun activatePane(paneId: PaneId)

    fun updateSettings(settings: OnyxSettings)

    fun moveTab(
        sourcePaneId: PaneId,
        tabId: String,
        targetPaneId: PaneId,
        targetIndex: Int,
    )

    fun refreshActivePane()

    fun stageCopySelectedInPane(paneId: PaneId)

    fun stageCutSelectedInPane(paneId: PaneId)

    fun requestPasteIntoPane(paneId: PaneId)

    fun requestTransferSelectedToDirectory(
        sourcePaneId: PaneId,
        targetDirectoryLocation: String,
        operation: FileTransferOperation,
    )

    fun requestDeleteSelectedInPane(paneId: PaneId)

    fun dismissTask(taskId: String)

    fun cancelTask(taskId: String)

    fun clearAllTasks()
}
