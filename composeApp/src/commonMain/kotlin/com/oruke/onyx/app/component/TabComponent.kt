package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.TabSnapshot
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable
data class TabConfig(
    val snapshot: TabSnapshot,
)

interface TabComponent {
    val state: StateFlow<PaneTabState>
}
