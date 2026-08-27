package com.oruke.onyx.core.model

import kotlinx.serialization.Serializable

/** 全局搜索的扫描根范围。 */
@Serializable
enum class SearchScope {
    /** 仅扫描当前活动面板所在目录。 */
    CURRENT_DIRECTORY,

    /** 仅扫描用户收藏的位置；收藏为空时回退到当前目录。 */
    FAVORITES,

    /** 扫描全部文件系统根。 */
    ALL_ROOTS,
}
