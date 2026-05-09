package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.TabSnapshot
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable
class TabConfig(
    val id: String,
    val snapshot: TabSnapshot,
) {
    override fun equals(other: Any?): Boolean {
        return other is TabConfig && id == other.id
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }

    override fun toString(): String {
        return "TabConfig(id=$id, location=${snapshot.location})"
    }
}

fun TabSnapshot.toTabConfig(): TabConfig {
    return TabConfig(
        id = id,
        snapshot = this,
    )
}

interface TabComponent {
    val state: StateFlow<PaneTabState>

    fun updateState(state: PaneTabState)
}
