package com.oruke.onyx.app.component

import com.oruke.onyx.app.filesystem.JvmLocalFileProvider
import com.oruke.onyx.core.model.DetailsColumn
import com.oruke.onyx.core.model.DetailsSort
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.SortDirection
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
    private var lastLoadedEntries: List<VFile> = emptyList()

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
                DetailsColumn.MODIFIED,
            ),
            detailsSort = DetailsSort(
                column = DetailsColumn.NAME,
                direction = SortDirection.ASCENDING,
            ),
            selectedEntryId = null,
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
                    lastLoadedEntries = entries
                    val sortedEntries = sortEntries(entries, mutableState.value.detailsSort)
                    val nextSelectedEntryId = sortedEntries
                        .firstOrNull { it.id == mutableState.value.selectedEntryId }
                        ?.id
                        ?: sortedEntries.firstOrNull()?.id
                    mutableState.value.copy(
                        selectedEntryId = nextSelectedEntryId,
                        entriesState = PaneEntriesState.Ready(sortedEntries)
                    )
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

    override fun toggleSort(column: DetailsColumn) {
        val currentSort = mutableState.value.detailsSort
        val nextSort = if (currentSort.column == column) {
            currentSort.copy(
                direction = when (currentSort.direction) {
                    SortDirection.ASCENDING -> SortDirection.DESCENDING
                    SortDirection.DESCENDING -> SortDirection.ASCENDING
                }
            )
        } else {
            DetailsSort(
                column = column,
                direction = SortDirection.ASCENDING,
            )
        }

        mutableState.value = mutableState.value.copy(
            detailsSort = nextSort,
            selectedEntryId = currentVisibleEntries()
                .firstOrNull { it.id == mutableState.value.selectedEntryId }
                ?.id
                ?: sortEntries(lastLoadedEntries, nextSort).firstOrNull()?.id,
            entriesState = when (val currentEntriesState = mutableState.value.entriesState) {
                is PaneEntriesState.Ready -> PaneEntriesState.Ready(sortEntries(lastLoadedEntries, nextSort))
                else -> currentEntriesState
            },
        )
    }

    override fun selectEntry(entryId: String) {
        if (currentVisibleEntries().none { it.id == entryId }) {
            return
        }
        mutableState.value = mutableState.value.copy(selectedEntryId = entryId)
    }

    override fun moveSelection(offset: Int) {
        val entries = currentVisibleEntries()
        if (entries.isEmpty()) {
            return
        }

        val currentIndex = entries.indexOfFirst { it.id == mutableState.value.selectedEntryId }
        val fallbackIndex = if (offset >= 0) 0 else entries.lastIndex
        val baseIndex = if (currentIndex == -1) fallbackIndex else currentIndex
        val nextIndex = (baseIndex + offset).coerceIn(0, entries.lastIndex)
        mutableState.value = mutableState.value.copy(selectedEntryId = entries[nextIndex].id)
    }

    override fun openSelectedEntry() {
        val selectedEntry =
            currentVisibleEntries().firstOrNull { it.id == mutableState.value.selectedEntryId } ?: return
        openEntry(selectedEntry)
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
            selectedEntryId = null,
            entriesState = PaneEntriesState.Loading,
        )
        refresh()
    }

    private fun currentVisibleEntries(): List<VFile> {
        return when (val entriesState = mutableState.value.entriesState) {
            is PaneEntriesState.Ready -> entriesState.entries
            else -> emptyList()
        }
    }

    private fun sortEntries(
        entries: List<VFile>,
        sort: DetailsSort,
    ): List<VFile> {
        val comparator = when (sort.column) {
            DetailsColumn.NAME -> compareBy<VFile> { it.name.lowercase() }
            DetailsColumn.TYPE -> compareBy<VFile> { it.kind.name }
            DetailsColumn.SIZE -> compareBy<VFile> { it.sizeBytes ?: -1L }
            DetailsColumn.MODIFIED -> compareBy<VFile> { it.modifiedAtEpochMillis ?: Long.MIN_VALUE }
        }

        val sorted = entries.sortedWith(
            compareByDescending<VFile> { it.kind == VFileKind.DIRECTORY }
                .then(comparator)
        )

        return when (sort.direction) {
            SortDirection.ASCENDING -> sorted
            SortDirection.DESCENDING -> {
                val directories = sorted.filter { it.kind == VFileKind.DIRECTORY }.reversed()
                val files = sorted.filter { it.kind == VFileKind.FILE }.reversed()
                directories + files
            }
        }
    }
}
