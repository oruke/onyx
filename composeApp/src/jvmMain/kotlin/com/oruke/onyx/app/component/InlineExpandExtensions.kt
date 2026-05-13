package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind

internal data class InlineExpandToggleResult(
    val state: PaneState,
    val loadRequest: InlineExpandLoadRequest?,
)

internal data class InlineExpandLoadRequest(
    val location: String,
    val depth: Int,
)

internal fun PaneState.toggleInlineExpandState(directoryLocation: String): InlineExpandToggleResult {
    if (directoryLocation in inlineExpandedLocations) {
        val toRemove = inlineExpandedLocations.filter { location ->
            location == directoryLocation || location.startsWith("$directoryLocation/")
        }.toSet()
        return InlineExpandToggleResult(
            state = copy(
                chromeState = chromeState.copy(
                    inlineExpandedLocations = inlineExpandedLocations - toRemove,
                    inlineExpandedEntries = inlineExpandedEntries - toRemove,
                ),
            ),
            loadRequest = null,
        )
    }

    val depth = inlineExpandedEntries.values
        .firstOrNull { expanded ->
            expanded.entries?.any { it.location == directoryLocation } == true
        }?.depth?.plus(1) ?: 1
    val loading = InlineExpandedEntry(
        parentLocation = directoryLocation,
        depth = depth,
        entries = null,
    )
    return InlineExpandToggleResult(
        state = copy(
            chromeState = chromeState.copy(
                inlineExpandedLocations = inlineExpandedLocations + directoryLocation,
                inlineExpandedEntries = inlineExpandedEntries + (directoryLocation to loading),
            ),
        ),
        loadRequest = InlineExpandLoadRequest(
            location = directoryLocation,
            depth = depth,
        ),
    )
}

internal fun PaneState.withInlineExpandChildren(
    location: String,
    depth: Int,
    entries: List<VFile>,
): PaneState {
    if (location !in inlineExpandedLocations) return this
    return copy(
        chromeState = chromeState.copy(
            inlineExpandedEntries = inlineExpandedEntries + (
                location to InlineExpandedEntry(
                    parentLocation = location,
                    depth = depth,
                    entries = entries.sortedForInlineExpand(),
                )
            ),
        ),
    )
}

internal fun PaneState.withInlineExpandFailure(
    location: String,
    depth: Int,
): PaneState {
    if (location !in inlineExpandedLocations) return this
    return copy(
        chromeState = chromeState.copy(
            inlineExpandedEntries = inlineExpandedEntries + (
                location to InlineExpandedEntry(
                    parentLocation = location,
                    depth = depth,
                    entries = emptyList(),
                    error = true,
                )
            ),
        ),
    )
}

internal fun PaneState.clearInlineExpandState(): PaneState {
    return copy(
        chromeState = chromeState.copy(
            inlineExpandedLocations = emptySet(),
            inlineExpandedEntries = emptyMap(),
        ),
    )
}

private fun List<VFile>.sortedForInlineExpand(): List<VFile> {
    return sortedWith(
        compareBy<VFile> { it.kind != VFileKind.DIRECTORY }
            .thenBy { it.name.lowercase() },
    )
}
