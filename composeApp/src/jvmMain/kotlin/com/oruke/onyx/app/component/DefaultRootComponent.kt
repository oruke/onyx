package com.oruke.onyx.app.component

import com.oruke.onyx.app.filesystem.JvmLocalFileProvider
import com.oruke.onyx.core.model.BackgroundTask
import com.oruke.onyx.core.model.BackgroundTaskStatus
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.PaneLayoutMode
import com.oruke.onyx.core.model.VFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.nio.file.Path
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
    private val paneSplitFraction = MutableStateFlow(0.5f)
    private val activePane = MutableStateFlow(PaneId.PRIMARY)
    private val clipboard = MutableStateFlow<ClipboardPayload?>(null)
    private val tasks = MutableStateFlow<List<BackgroundTask>>(emptyList())
    private val taskJobs = mutableMapOf<String, Job>()
    private val mutableState = MutableStateFlow(
        RootState(
            layoutMode = layoutMode.value,
            paneSplitFraction = paneSplitFraction.value,
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
                paneSplitFraction,
                activePane,
                primaryPane.state,
                secondaryPane.state,
                clipboard,
                tasks,
            ) { values ->
                val currentLayoutMode = values[0] as PaneLayoutMode
                val currentPaneSplitFraction = values[1] as Float
                val currentActivePane = values[2] as PaneId
                val primaryState = values[3] as PaneState
                val secondaryState = values[4] as PaneState
                val currentClipboard = values[5] as ClipboardPayload?

                @Suppress("UNCHECKED_CAST")
                val currentTasks = values[6] as List<BackgroundTask>
                RootState(
                    layoutMode = currentLayoutMode,
                    paneSplitFraction = currentPaneSplitFraction,
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

    override fun setPaneSplitFraction(fraction: Float) {
        paneSplitFraction.value = fraction.coerceIn(0.18f, 0.82f)
    }

    override fun activatePane(paneId: PaneId) {
        activePane.value = paneId
    }

    override fun moveTab(
        sourcePaneId: PaneId,
        tabId: String,
        targetPaneId: PaneId,
        targetIndex: Int,
    ) {
        if (sourcePaneId == targetPaneId) {
            paneComponent(sourcePaneId).moveTab(
                tabId = tabId,
                targetIndex = targetIndex,
            )
            activatePane(targetPaneId)
            return
        }

        val tab = paneComponent(sourcePaneId).detachTab(tabId) ?: return
        paneComponent(targetPaneId).attachTab(
            tab = tab,
            targetIndex = targetIndex,
        )
        activatePane(targetPaneId)
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
        requestTransferEntriesToDirectory(
            entries = clipboardPayload.entries,
            targetDirectoryLocation = targetLocation,
            operation = when (clipboardPayload.operation) {
                ClipboardOperation.COPY -> FileTransferOperation.COPY
                ClipboardOperation.CUT -> FileTransferOperation.MOVE
            },
            clearClipboardOnSuccess = clipboardPayload.operation == ClipboardOperation.CUT,
        )
    }

    override fun requestTransferSelectedToDirectory(
        sourcePaneId: PaneId,
        targetDirectoryLocation: String,
        operation: FileTransferOperation,
    ) {
        val entries = selectedEntriesInPane(sourcePaneId)
        if (entries.isEmpty()) {
            return
        }
        requestTransferEntriesToDirectory(
            entries = entries,
            targetDirectoryLocation = targetDirectoryLocation,
            operation = operation,
            clearClipboardOnSuccess = false,
        )
    }

    private fun requestTransferEntriesToDirectory(
        entries: List<VFile>,
        targetDirectoryLocation: String,
        operation: FileTransferOperation,
        clearClipboardOnSuccess: Boolean,
    ) {
        if (operation == FileTransferOperation.MOVE && entries.all { it.parentLocation == targetDirectoryLocation }) {
            return
        }
        if (entries.any { entry -> targetDirectoryLocation.isSameOrChildOf(entry.location) }) {
            return
        }
        val taskId = UUID.randomUUID().toString()
        appendTask(
            BackgroundTask(
                id = taskId,
                title = when (operation) {
                    FileTransferOperation.COPY -> "Copy ${entries.size} item(s)"
                    FileTransferOperation.MOVE -> "Move ${entries.size} item(s)"
                },
                status = BackgroundTaskStatus.QUEUED,
                detail = targetDirectoryLocation,
                progress = 0f,
            )
        )

        val job = scope.launch {
            try {
                updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.RUNNING,
                    detail = buildTransferTaskDetail(
                        entries = entries,
                        targetLocation = targetDirectoryLocation,
                    ),
                    progress = 0f,
                )

                entries.forEachIndexed { index, entry ->
                    ensureActive()
                    val result = when (operation) {
                        FileTransferOperation.COPY -> {
                            localFileProvider.copy(
                                entries = listOf(entry),
                                targetDirectoryLocation = targetDirectoryLocation,
                            )
                        }

                        FileTransferOperation.MOVE -> {
                            localFileProvider.move(
                                entries = listOf(entry),
                                targetDirectoryLocation = targetDirectoryLocation,
                            )
                        }
                    }
                    result.getOrThrow()
                    updateTask(
                        taskId = taskId,
                        status = BackgroundTaskStatus.RUNNING,
                        detail = "${entry.name} -> $targetDirectoryLocation",
                        progress = (index + 1).toFloat() / entries.size,
                    )
                }

                taskJobs.remove(taskId)
                updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.SUCCEEDED,
                    detail = when (operation) {
                        FileTransferOperation.COPY ->
                            "Copied ${entries.size} item(s) to $targetDirectoryLocation"

                        FileTransferOperation.MOVE ->
                            "Moved ${entries.size} item(s) to $targetDirectoryLocation"
                    },
                    progress = 1f,
                )
                if (clearClipboardOnSuccess) {
                    clipboard.value = null
                }
                refreshAllPanes()
            } catch (_: CancellationException) {
                taskJobs.remove(taskId)
                updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.CANCELLED,
                    detail = "Cancelled",
                    progress = null,
                )
                refreshAllPanes()
            } catch (failure: Throwable) {
                taskJobs.remove(taskId)
                updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.FAILED,
                    detail = failure.message ?: when (operation) {
                        FileTransferOperation.COPY -> "Copy failed"
                        FileTransferOperation.MOVE -> "Move failed"
                    },
                    progress = null,
                )
                refreshAllPanes()
            }
        }
        taskJobs[taskId] = job
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
                progress = 0f,
            )
        )

        val job = scope.launch {
            try {
                updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.RUNNING,
                    detail = buildTaskDetail(selectedEntries),
                    progress = 0f,
                )

                selectedEntries.forEachIndexed { index, entry ->
                    ensureActive()
                    localFileProvider.delete(listOf(entry)).getOrThrow()
                    updateTask(
                        taskId = taskId,
                        status = BackgroundTaskStatus.RUNNING,
                        detail = entry.name,
                        progress = (index + 1).toFloat() / selectedEntries.size,
                    )
                }

                taskJobs.remove(taskId)
                updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.SUCCEEDED,
                    detail = "Deleted ${selectedEntries.size} item(s)",
                    progress = 1f,
                )
                refreshAllPanes()
            } catch (_: CancellationException) {
                taskJobs.remove(taskId)
                updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.CANCELLED,
                    detail = "Cancelled",
                    progress = null,
                )
                refreshAllPanes()
            } catch (failure: Throwable) {
                taskJobs.remove(taskId)
                updateTask(
                    taskId = taskId,
                    status = BackgroundTaskStatus.FAILED,
                    detail = failure.message ?: "Delete failed",
                    progress = null,
                )
                refreshAllPanes()
            }
        }
        taskJobs[taskId] = job
    }

    override fun dismissTask(taskId: String) {
        taskJobs.remove(taskId)?.cancel()
        tasks.value = tasks.value.filterNot { task -> task.id == taskId }
    }

    override fun cancelTask(taskId: String) {
        taskJobs[taskId]?.cancel()
    }

    override fun clearAllTasks() {
        taskJobs.values.forEach { job -> job.cancel() }
        taskJobs.clear()
        tasks.value = emptyList()
    }

    private fun appendTask(task: BackgroundTask) {
        tasks.value = listOf(task) + tasks.value
    }

    private fun updateTask(
        taskId: String,
        status: BackgroundTaskStatus,
        detail: String,
        progress: Float? = null,
    ) {
        tasks.value = tasks.value.map { task ->
            if (task.id == taskId) {
                task.copy(
                    status = status,
                    detail = detail,
                    progress = progress,
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

    private fun paneComponent(paneId: PaneId): PaneComponent {
        return when (paneId) {
            PaneId.PRIMARY -> primaryPane
            PaneId.SECONDARY -> secondaryPane
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

private fun String.isSameOrChildOf(parentLocation: String): Boolean {
    val target = Path.of(this).normalize().toAbsolutePath()
    val parent = Path.of(parentLocation).normalize().toAbsolutePath()
    return target == parent || target.startsWith(parent)
}
