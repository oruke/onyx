package com.oruke.onyx.core.model

import kotlinx.serialization.Serializable

/** 侧边栏可独立折叠的分组标识。 */
@Serializable
enum class SidebarSectionKey {
    QUICK_ACCESS,
    FAVORITES,
    CONNECTIONS,
    RECENT,
    TREE,
}
