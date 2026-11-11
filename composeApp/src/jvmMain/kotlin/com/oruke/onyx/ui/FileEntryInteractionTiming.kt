package com.oruke.onyx.ui

import java.awt.Toolkit

/** 详情和画廊确认慢速单击文件名后等待进入内联重命名的延迟（毫秒）。 */
internal const val FILE_ENTRY_RENAME_DELAY_MILLIS = 1_000L

/** 操作系统未暴露多击间隔属性时的桌面双击最大间隔默认值（毫秒，对齐桌面端标准 500ms）。 */
internal const val DEFAULT_DOUBLE_CLICK_INTERVAL_MILLIS = 500L

/** 系统双击间隔允许的下限（毫秒）。 */
private const val MIN_DOUBLE_CLICK_INTERVAL_MILLIS = 200L

/** 系统双击间隔允许的上限（毫秒）。 */
private const val MAX_DOUBLE_CLICK_INTERVAL_MILLIS = 1_000L

/**
 * 在最近一次点击之后的慢速重命名防误触隔离缓冲（毫秒）。
 *
 * 两次点击间隔处于 `(doubleClickInterval, doubleClickInterval + POST_SELECT_RENAME_GUARD_MILLIS)` 时，
 * 通常属于略慢半拍的失败双击，保持选中而不误触内联重命名。
 */
internal const val POST_SELECT_RENAME_GUARD_MILLIS = 200L

/** 允许触发慢速重命名的单次按下最长按住时长（毫秒），排除长按犹豫后原地松手。 */
internal const val MAX_RENAME_PRESS_HOLD_MILLIS = 350L

/** 文件条目指针开始拖拽或框选的移动阈值（dp，随屏幕缩放自动换算）。 */
internal const val ENTRY_DRAG_SLOP_DP = 8f

/** 同一条目两次点击识别为双击的最大位移容差（dp，随屏幕缩放自动换算）。 */
internal const val ENTRY_DOUBLE_CLICK_SLOP_DP = 12f

/**
 * 读取桌面操作系统配置的多击/双击时间间隔（毫秒）。
 *
 * @return 约束在合理范围内的系统双击间隔毫秒数。
 */
internal fun resolveSystemDoubleClickIntervalMillis(): Long {
    val desktopProperty = runCatching {
        Toolkit.getDefaultToolkit().getDesktopProperty("awt.multiClickInterval") as? Number
    }.getOrNull()?.toLong()
    return (desktopProperty ?: DEFAULT_DOUBLE_CLICK_INTERVAL_MILLIS)
        .coerceIn(MIN_DOUBLE_CLICK_INTERVAL_MILLIS, MAX_DOUBLE_CLICK_INTERVAL_MILLIS)
}
