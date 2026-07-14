package com.oruke.onyx.core.model

/**
 * 操作系统文件选择器或资源管理器提供的快速访问位置。
 */
data class SystemQuickAccessLocation(
    /** 系统提供的显示名称；为空时由应用根据位置生成名称。 */
    val displayName: String?,
    /** 可交给统一 VFS 打开的本地路径或受支持的远程位置。 */
    val location: String,
    /** 是否为当前用户主目录，用于应用本地化名称和图标。 */
    val isHome: Boolean = false,
)
