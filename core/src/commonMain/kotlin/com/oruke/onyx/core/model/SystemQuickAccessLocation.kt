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
    /** 标准系统目录的业务分类；用户自定义书签没有该分类。 */
    val standardDirectory: StandardUserDirectory? = null,
)

/** 标准用户目录语义，位置由操作系统解析，名称由应用资源本地化。 */
enum class StandardUserDirectory {
    /** 当前用户的系统桌面目录。 */
    DESKTOP,
    /** 当前用户的系统文档目录。 */
    DOCUMENTS,
    /** 当前用户的系统下载目录。 */
    DOWNLOADS,
    /** 当前用户的系统图片目录。 */
    PICTURES,
    /** 当前用户的系统音乐目录。 */
    MUSIC,
    /** 当前用户的系统视频目录。 */
    VIDEOS,
}
