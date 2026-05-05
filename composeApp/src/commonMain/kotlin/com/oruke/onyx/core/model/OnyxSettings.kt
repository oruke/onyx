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
    // 动态外观系统持久化字段
    val listRowHeightDp: Int = 22,
    val listFontSizeSp: Int = 12,
    val zebraStripeEnabled: Boolean = true,
    // 列可见性控制
    val hiddenDetailsColumns: Set<DetailsColumn> = emptySet(),
    // 窗口大小记忆
    val mainWindowWidth: Int = 1200,
    val mainWindowHeight: Int = 800,
    val settingsWindowWidth: Int = 720,
    val settingsWindowHeight: Int = 520,
    val batchRenameWindowWidth: Int = 800,
    val batchRenameWindowHeight: Int = 620,
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
