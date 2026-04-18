package com.oruke.onyx.core.model

import kotlinx.serialization.Serializable

@Serializable
data class OnyxSettings(
    val uiScale: Int = 100,
    val preferredLocale: AppLocale = AppLocale.SYSTEM,
    val defaultLayoutMode: PaneLayoutMode = PaneLayoutMode.DUAL_VERTICAL,
    val defaultViewMode: ViewMode = ViewMode.DETAILS,
    val deleteMode: DeleteMode = DeleteMode.MOVE_TO_TRASH_PREFERRED,
    val sidebarVisible: Boolean = true,
    val statusBarVisible: Boolean = true,
    val favoriteLocations: List<String> = emptyList(),
    val recentLocations: List<String> = emptyList(),
)

@Serializable
enum class AppLocale {
    SYSTEM,
    ENGLISH,
    SIMPLIFIED_CHINESE,
    JAPANESE,
}

@Serializable
enum class DeleteMode {
    MOVE_TO_TRASH_PREFERRED,
    PERMANENT,
}
