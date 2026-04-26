package com.oruke.onyx.app.component

import com.oruke.onyx.app.filesystem.TransferConflictStrategy
import com.oruke.onyx.core.model.BackgroundTask
import com.oruke.onyx.core.model.I18nMessage
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
    val sidebarTreeState: SidebarTreeState,
    val settings: OnyxSettings,
    val sessionRestoreState: SessionRestoreState,
    val dialogState: RootDialogState?,
    val canPaste: Boolean,
    val tasks: List<BackgroundTask>,
    val showPreviewPane: Boolean = false,
)

data class SidebarTreeState(
    val roots: List<SidebarTreeNode>,
)

data class SidebarTreeNode(
    val location: String,
    val label: String,
    val expanded: Boolean,
    val loadState: SidebarTreeNodeLoadState,
    val loadError: String? = null,
    val children: List<SidebarTreeNode> = emptyList(),
)

enum class SidebarTreeNodeLoadState {
    IDLE,
    LOADING,
    READY,
    FAILURE,
}

sealed interface SessionRestoreState {
    data object Loading : SessionRestoreState

    data object Ready : SessionRestoreState

    data class Failed(
        val reason: I18nMessage?,
    ) : SessionRestoreState
}

sealed interface RootDialogState {
    data class DeleteSelectionConfirmation(
        val moveToTrash: Boolean,
        val itemCount: Int,
        val trashUnavailable: Boolean,
    ) : RootDialogState

    data class ConflictResolution(
        val sourceName: String,
        val targetLocation: String,
        val operation: FileTransferOperation,
        val currentIndex: Int,
        val total: Int,
    ) : RootDialogState

    data class CreateDirectories(
        val paneId: PaneId,
        val location: String,
        val draft: String,
        val error: CreateDirectoriesDialogError? = null,
    ) : RootDialogState

    data class Settings(
        val draft: OnyxSettings,
    ) : RootDialogState
}

enum class CreateDirectoriesDialogError {
    EMPTY_INPUT,
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

    fun openSettings()

    fun updateSettingsDraft(draft: OnyxSettings)

    fun activatePane(paneId: PaneId)

    fun updateSettings(settings: OnyxSettings)

    fun openLocationInActivePane(location: String)

    fun toggleFavoriteLocation(location: String)

    fun toggleSidebarTreeNode(location: String)

    fun retrySidebarTreeNode(location: String)

    fun beginCreateDirectoriesInPane(paneId: PaneId)

    fun updateCreateDirectoriesDraft(draft: String)

    fun confirmDialog()

    fun dismissDialog()

    fun resolveConflict(
        strategy: TransferConflictStrategy,
        applyToAll: Boolean,
    )

    fun moveTab(
        sourcePaneId: PaneId,
        tabId: String,
        targetPaneId: PaneId,
        targetIndex: Int,
    )

    fun refreshActivePane()

    fun togglePreviewPane()

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

    fun pauseTask(taskId: String)

    fun resumeTask(taskId: String)

    fun clearAllTasks()
}
