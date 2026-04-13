package com.oruke.onyx.app.component

import com.oruke.onyx.app.filesystem.JvmLocalFileProvider
import com.oruke.onyx.core.model.DetailsColumn
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.file.Path
import kotlin.io.path.pathString

class DefaultPaneComponent(
    private val paneId: PaneId,
    initialLocation: String,
    private val localFileProvider: JvmLocalFileProvider,
    private val scope: CoroutineScope,
) : PaneComponent {
    private val backStack = ArrayDeque<String>()
    private val forwardStack = ArrayDeque<String>()

    private val mutableState = MutableStateFlow(
        PaneState(
            paneId = paneId,
            location = initialLocation,
            canGoBack = false,
            canGoForward = false,
            detailsColumns = listOf(
                DetailsColumn.NAME,
                DetailsColumn.TYPE,
                DetailsColumn.SIZE,
            ),
            entriesState = PaneEntriesState.Idle,
        )
    )

    override val state: StateFlow<PaneState> = mutableState.asStateFlow()

    init {
        refresh()
    }

    override fun refresh() {
        val location = mutableState.value.location
        mutableState.value = mutableState.value.copy(entriesState = PaneEntriesState.Loading)
        scope.launch {
            val result = localFileProvider.list(location)
            mutableState.value = result.fold(
                onSuccess = { entries ->
                    mutableState.value.copy(entriesState = PaneEntriesState.Ready(entries))
                },
                onFailure = { failure ->
                    mutableState.value.copy(entriesState = PaneEntriesState.Failure(failure.message))
                },
            )
        }
    }

    override fun goBack() {
        val previousLocation = backStack.removeLastOrNull() ?: return
        forwardStack.addLast(mutableState.value.location)
        navigateTo(
            location = previousLocation,
            recordHistory = false,
        )
    }

    override fun goForward() {
        val nextLocation = forwardStack.removeLastOrNull() ?: return
        backStack.addLast(mutableState.value.location)
        navigateTo(
            location = nextLocation,
            recordHistory = false,
        )
    }

    override fun goUp() {
        val parentLocation = Path.of(mutableState.value.location).parent?.pathString ?: return
        openDirectory(parentLocation)
    }

    override fun openDirectory(location: String) {
        navigateTo(
            location = location,
            recordHistory = true,
        )
    }

    override fun openEntry(entry: VFile) {
        if (entry.kind == VFileKind.DIRECTORY) {
            openDirectory(entry.location)
        }
    }

    private fun navigateTo(
        location: String,
        recordHistory: Boolean,
    ) {
        val normalizedLocation = Path.of(location).normalize().toAbsolutePath().pathString
        val currentLocation = mutableState.value.location
        if (normalizedLocation == currentLocation) {
            refresh()
            return
        }

        if (recordHistory) {
            backStack.addLast(currentLocation)
            forwardStack.clear()
        }

        mutableState.value = mutableState.value.copy(
            location = normalizedLocation,
            canGoBack = backStack.isNotEmpty(),
            canGoForward = forwardStack.isNotEmpty(),
            entriesState = PaneEntriesState.Loading,
        )
        refresh()
    }
}
