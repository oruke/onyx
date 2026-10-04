package com.oruke.onyx.app.component

/** 需要独立持久化尺寸的窗口，避免尺寸事件覆盖其它用户设置。 */
internal enum class RootWindowKind {
    /** 文件管理器主窗口。 */
    MAIN,

    /** 图片查看器。 */
    IMAGE_VIEWER,

    /** 应用设置窗口。 */
    SETTINGS,

    /** 批量重命名窗口。 */
    BATCH_RENAME,
}
