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
            selectedEntryIds = emptySet(),
            selectionAnchorId = null,
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
                    val nextSelectedEntryIds = mutableState.value.selectedEntryIds
                        .intersect(sortedEntries.mapTo(mutableSetOf()) { it.id })
                        .ifEmpty {
                            sortedEntries.firstOrNull()?.let { setOf(it.id) } ?: emptySet()
                        }
                    val nextSelectionAnchorId = mutableState.value.selectionAnchorId
                        ?.takeIf { anchorId -> nextSelectedEntryIds.contains(anchorId) }
                        ?: nextSelectedEntryIds.firstOrNull()
                    mutableState.value.copy(
                        selectedEntryIds = nextSelectedEntryIds,
                        selectionAnchorId = nextSelectionAnchorId,
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
            selectedEntryIds = mutableState.value.selectedEntryIds
                .intersect(sortEntries(lastLoadedEntries, nextSort).mapTo(mutableSetOf()) { it.id })
                .ifEmpty {
                    sortEntries(lastLoadedEntries, nextSort).firstOrNull()?.let { setOf(it.id) } ?: emptySet()
                },
            selectionAnchorId = mutableState.value.selectionAnchorId
                ?.takeIf { anchorId ->
                    sortEntries(lastLoadedEntries, nextSort).any { it.id == anchorId }
                }
                ?: sortEntries(lastLoadedEntries, nextSort).firstOrNull()?.id,
            entriesState = when (val currentEntriesState = mutableState.value.entriesState) {
                is PaneEntriesState.Ready -> PaneEntriesState.Ready(sortEntries(lastLoadedEntries, nextSort))
                else -> currentEntriesState
            },
        )
    }

    override fun selectEntry(
        entryId: String,
        additive: Boolean,
        range: Boolean,
    ) {
        val entries = currentVisibleEntries()
        if (entries.none { it.id == entryId }) {
            return
        }

        val currentSelected = mutableState.value.selectedEntryIds
        val currentAnchor = mutableState.value.selectionAnchorId
        val nextSelection = when {
            range -> {
                val anchorId = currentAnchor ?: currentSelected.firstOrNull() ?: entryId
                buildRangeSelection(
                    entries = entries,
                    anchorId = anchorId,
                    targetId = entryId,
                    additive = additive,
                    existingSelection = currentSelected,
                )
            }

            additive -> {
                if (currentSelected.contains(entryId)) {
                    currentSelected - entryId
                } else {
                    currentSelected + entryId
                }
            }

            else -> setOf(entryId)
        }

        val finalSelection = nextSelection.ifEmpty { setOf(entryId) }
        mutableState.value = mutableState.value.copy(
            selectedEntryIds = finalSelection,
            selectionAnchorId = entryId,
        )
    }

    override fun moveSelection(
        offset: Int,
        extendSelection: Boolean,
    ) {
        val entries = currentVisibleEntries()
        if (entries.isEmpty()) {
            return
        }

        val currentIndex = entries.indexOfFirst { it.id == currentSelectionFocusId(entries) }
        val fallbackIndex = if (offset >= 0) 0 else entries.lastIndex
        val baseIndex = if (currentIndex == -1) fallbackIndex else currentIndex
        val nextIndex = (baseIndex + offset).coerceIn(0, entries.lastIndex)
        val nextEntryId = entries[nextIndex].id

        if (extendSelection) {
            val anchorId = mutableState.value.selectionAnchorId ?: currentSelectionFocusId(entries) ?: nextEntryId
            mutableState.value = mutableState.value.copy(
                selectedEntryIds = buildRangeSelection(
                    entries = entries,
                    anchorId = anchorId,
                    targetId = nextEntryId,
                    additive = false,
                    existingSelection = emptySet(),
                ),
                selectionAnchorId = anchorId,
            )
        } else {
            mutableState.value = mutableState.value.copy(
                selectedEntryIds = setOf(nextEntryId),
                selectionAnchorId = nextEntryId,
            )
        }
    }

    override fun openSelectedEntry() {
        val selectedEntry =
            currentVisibleEntries().firstOrNull { it.id == currentSelectionFocusId(currentVisibleEntries()) } ?: return
        openEntry(selectedEntry)
    }

    override fun selectAll() {
        val entries = currentVisibleEntries()
        if (entries.isEmpty()) {
            return
        }
        mutableState.value = mutableState.value.copy(
            selectedEntryIds = entries.mapTo(linkedSetOf()) { it.id },
            selectionAnchorId = entries.first().id,
        )
    }

    override fun clearSelection() {
        mutableState.value = mutableState.value.copy(
            selectedEntryIds = emptySet(),
            selectionAnchorId = null,
        )
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
            selectedEntryIds = emptySet(),
            selectionAnchorId = null,
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

    private fun currentSelectionFocusId(entries: List<VFile>): String? {
        return mutableState.value.selectionAnchorId
            ?.takeIf { anchorId -> entries.any { it.id == anchorId } }
            ?: mutableState.value.selectedEntryIds.firstOrNull()
    }

    private fun buildRangeSelection(
        entries: List<VFile>,
        anchorId: String,
        targetId: String,
        additive: Boolean,
        existingSelection: Set<String>,
    ): Set<String> {
        val anchorIndex = entries.indexOfFirst { it.id == anchorId }.takeIf { it >= 0 } ?: return setOf(targetId)
        val targetIndex = entries.indexOfFirst { it.id == targetId }.takeIf { it >= 0 } ?: return setOf(anchorId)
        val range = entries
            .subList(minOf(anchorIndex, targetIndex), maxOf(anchorIndex, targetIndex) + 1)
            .mapTo(linkedSetOf()) { it.id }
        return if (additive) existingSelection + range else range
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
