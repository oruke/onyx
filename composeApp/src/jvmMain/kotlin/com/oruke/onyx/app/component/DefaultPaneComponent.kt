package com.oruke.onyx.app.component

import com.oruke.onyx.app.filesystem.JvmLocalFileProvider
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
    private val mutableState = MutableStateFlow(
        PaneState(
            paneId = paneId,
            location = initialLocation,
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

    override fun goUp() {
        val parentLocation = Path.of(mutableState.value.location).parent?.pathString ?: return
        openDirectory(parentLocation)
    }

    override fun openDirectory(location: String) {
        mutableState.value = mutableState.value.copy(
            location = location,
            entriesState = PaneEntriesState.Loading,
        )
        refresh()
    }

    override fun openEntry(entry: VFile) {
        if (entry.kind == VFileKind.DIRECTORY) {
            openDirectory(entry.location)
        }
    }
}
