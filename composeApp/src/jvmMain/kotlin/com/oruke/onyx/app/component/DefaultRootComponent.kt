package com.oruke.onyx.app.component

import com.oruke.onyx.app.filesystem.JvmLocalFileProvider
import com.oruke.onyx.core.model.BackgroundTask
import com.oruke.onyx.core.model.BackgroundTaskStatus
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.PaneLayoutMode
import com.oruke.onyx.core.model.VFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.*

class DefaultRootComponent(
    private val scope: CoroutineScope,
    private val localFileProvider: JvmLocalFileProvider,
) : RootComponent {
    override val primaryPane: PaneComponent = DefaultPaneComponent(
        paneId = PaneId.PRIMARY,
        initialLocation = localFileProvider.defaultLocation(),
        localFileProvider = localFileProvider,
        scope = scope,
    )
    override val secondaryPane: PaneComponent = DefaultPaneComponent(
        paneId = PaneId.SECONDARY,
        initialLocation = localFileProvider.defaultLocation(),
        localFileProvider = localFileProvider,
        scope = scope,
    )

    private val layoutMode = MutableStateFlow(PaneLayoutMode.DUAL_VERTICAL)
    private val activePane = MutableStateFlow(PaneId.PRIMARY)
    private val clipboard = MutableStateFlow<ClipboardPayload?>(null)
    private val tasks = MutableStateFlow<List<BackgroundTask>>(emptyList())
    private val mutableState = MutableStateFlow(
        RootState(
            layoutMode = layoutMode.value,
            activePane = activePane.value,
            primaryPane = primaryPane.state.value,
            secondaryPane = secondaryPane.state.value,
            canPaste = clipboard.value != null,
            tasks = tasks.value,
        )
    )

    override val state: StateFlow<RootState> = mutableState.asStateFlow()

    init {
        scope.launch {
            combine(
                layoutMode,
                activePane,
                primaryPane.state,
                secondaryPane.state,
                clipboard,
                tasks,
            ) { values ->
                val currentLayoutMode = values[0] as PaneLayoutMode
                val currentActivePane = values[1] as PaneId
                val primaryState = values[2] as PaneState
                val secondaryState = values[3] as PaneState
                val currentClipboard = values[4] as ClipboardPayload?

                @Suppress("UNCHECKED_CAST")
                val currentTasks = values[5] as List<BackgroundTask>
                RootState(
                    layoutMode = currentLayoutMode,
                    activePane = currentActivePane,
                    primaryPane = primaryState,
                    secondaryPane = secondaryState,
                    canPaste = currentClipboard != null,
                    tasks = currentTasks,
                )
            }.collect { combinedState ->
                mutableState.value = combinedState
            }
        }
    }

    override fun setLayoutMode(mode: PaneLayoutMode) {
        layoutMode.value = mode
    }

    override fun activatePane(paneId: PaneId) {
        activePane.value = paneId
    }

    override fun refreshActivePane() {
        when (activePane.value) {
            PaneId.PRIMARY -> primaryPane.refresh()
            PaneId.SECONDARY -> secondaryPane.refresh()
        }
    }

    override fun stageCopySelectedInPane(paneId: PaneId) {
        val entries = selectedEntriesInPane(paneId)
        if (entries.isEmpty()) {
            return
        }
        clipboard.value = ClipboardPayload(
            operation = ClipboardOperation.COPY,
            entries = entries,
        )
    }

    override fun stageCutSelectedInPane(paneId: PaneId) {
        val entries = selectedEntriesInPane(paneId)
        if (entries.isEmpty()) {
            return
        }
        clipboard.value = ClipboardPayload(
            operation = ClipboardOperation.CUT,
            entries = entries,
        )
    }

    override fun requestPasteIntoPane(paneId: PaneId) {
        val clipboardPayload = clipboard.value ?: return
        val targetLocation = paneState(paneId).location
        val taskId = UUID.randomUUID().toString()
        appendTask(
            BackgroundTask(
                id = taskId,
                title = when (clipboardPayload.operation) {
                    ClipboardOperation.COPY -> "Copy ${clipboardPayload.entries.size} item(s)"
                    ClipboardOperation.CUT -> "Move ${clipboardPayload.entries.size} item(s)"
                },
                status = BackgroundTaskStatus.QUEUED,
                detail = targetLocation,
            )
        )

        scope.launch {
            updateTask(
                taskId = taskId,
                status = BackgroundTaskStatus.RUNNING,
                detail = buildTransferTaskDetail(
                    entries = clipboardPayload.entries,
                    targetLocation = targetLocation,
                ),
            )

            val result = when (clipboardPayload.operation) {
                ClipboardOperation.COPY -> {
                    localFileProvider.copy(
                        entries = clipboardPayload.entries,
                        targetDirectoryLocation = targetLocation,
                    )
                }

                ClipboardOperation.CUT -> {
                    localFileProvider.move(
                        entries = clipboardPayload.entries,
                        targetDirectoryLocation = targetLocation,
                    )
                }
            }

            result.fold(
                onSuccess = {
                    updateTask(
                        taskId = taskId,
                        status = BackgroundTaskStatus.SUCCEEDED,
                        detail = when (clipboardPayload.operation) {
                            ClipboardOperation.COPY ->
                                "Copied ${clipboardPayload.entries.size} item(s) to $targetLocation"

                            ClipboardOperation.CUT ->
                                "Moved ${clipboardPayload.entries.size} item(s) to $targetLocation"
                        },
                    )
                    if (clipboardPayload.operation == ClipboardOperation.CUT) {
                        clipboard.value = null
                    }
                    refreshAllPanes()
                },
                onFailure = { failure ->
                    updateTask(
                        taskId = taskId,
                        status = BackgroundTaskStatus.FAILED,
                        detail = failure.message ?: when (clipboardPayload.operation) {
                            ClipboardOperation.COPY -> "Copy failed"
                            ClipboardOperation.CUT -> "Move failed"
                        },
                    )
                },
            )
        }
    }

    override fun requestDeleteSelectedInPane(paneId: PaneId) {
        val selectedEntries = selectedEntriesInPane(paneId)
        if (selectedEntries.isEmpty()) {
            return
        }

        val taskId = UUID.randomUUID().toString()
        appendTask(
            BackgroundTask(
                id = taskId,
                title = "Delete ${selectedEntries.size} item(s)",
                status = BackgroundTaskStatus.QUEUED,
                detail = paneState(paneId).location,
            )
        )

        scope.launch {
            updateTask(
                taskId = taskId,
                status = BackgroundTaskStatus.RUNNING,
                detail = buildTaskDetail(selectedEntries),
            )

            val result = localFileProvider.delete(selectedEntries)
            result.fold(
                onSuccess = {
                    updateTask(
                        taskId = taskId,
                        status = BackgroundTaskStatus.SUCCEEDED,
                        detail = "Deleted ${selectedEntries.size} item(s)",
                    )
                    refreshAllPanes()
                },
                onFailure = { failure ->
                    updateTask(
                        taskId = taskId,
                        status = BackgroundTaskStatus.FAILED,
                        detail = failure.message ?: "Delete failed",
                    )
                },
            )
        }
    }

    override fun dismissTask(taskId: String) {
        tasks.value = tasks.value.filterNot { task -> task.id == taskId }
    }

    private fun appendTask(task: BackgroundTask) {
        tasks.value = listOf(task) + tasks.value
    }

    private fun updateTask(
        taskId: String,
        status: BackgroundTaskStatus,
        detail: String,
    ) {
        tasks.value = tasks.value.map { task ->
            if (task.id == taskId) {
                task.copy(
                    status = status,
                    detail = detail,
                )
            } else {
                task
            }
        }
    }

    private fun paneState(paneId: PaneId): PaneState {
        return when (paneId) {
            PaneId.PRIMARY -> primaryPane.state.value
            PaneId.SECONDARY -> secondaryPane.state.value
        }
    }

    private fun selectedEntriesInPane(paneId: PaneId): List<VFile> {
        val paneState = paneState(paneId)
        val entries = (paneState.entriesState as? PaneEntriesState.Ready)?.entries.orEmpty()
        return entries.filter { paneState.selectedEntryIds.contains(it.id) }
    }

    private fun buildTransferTaskDetail(
        entries: List<VFile>,
        targetLocation: String,
    ): String {
        return "${buildTaskDetail(entries)} -> $targetLocation"
    }

    private fun buildTaskDetail(entries: List<VFile>): String {
        val preview = entries.take(3).joinToString { it.name }
        return if (entries.size <= 3) preview else "$preview ..."
    }

    private fun refreshAllPanes() {
        primaryPane.refresh()
        secondaryPane.refresh()
    }

    private data class ClipboardPayload(
        val operation: ClipboardOperation,
        val entries: List<VFile>,
    )

    private enum class ClipboardOperation {
        COPY,
        CUT,
    }
}
