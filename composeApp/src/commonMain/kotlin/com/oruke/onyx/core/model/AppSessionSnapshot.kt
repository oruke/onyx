package com.oruke.onyx.core.model

import kotlinx.serialization.Serializable

@Serializable
data class AppSessionSnapshot(
    val layoutMode: PaneLayoutMode,
    val paneSplitFraction: Float,
    val activePane: PaneId,
    val primaryPane: PaneSessionSnapshot,
    val secondaryPane: PaneSessionSnapshot,
)

@Serializable
data class PaneSessionSnapshot(
    val activeTabId: String,
    val tabs: List<TabSnapshot>,
)

@Serializable
data class TabSnapshot(
    val id: String,
    val location: String,
    val detailsColumns: List<DetailsColumn>,
    val detailsColumnWeights: Map<DetailsColumn, Float>,
    val detailsSort: DetailsSort,
    val showHiddenItems: Boolean,
    val viewMode: ViewMode,
    val filterQuery: String,
    val backStack: List<String>,
    val forwardStack: List<String>,
)

typealias TabSessionSnapshot = TabSnapshot
