package com.oruke.onyx.app.component

import com.oruke.onyx.core.model.PaneId

/** 删除或移动开始时的访问上下文，仅用于一次命令最终完成后的选择续接。 */
internal data class PaneRemovalContext(
    /** 发起面板。 */
    val paneId: PaneId,
    /** 发起标签。 */
    val tabId: String,
    /** 发起访问，防止完成回调影响其他目录。 */
    val visitId: Long,
    /** 用户交互版本，完成时必须仍然匹配。 */
    val interactionRevision: Long,
    /** 发起目录。 */
    val location: String,
    /** 命令实际目标身份。 */
    val targetIds: Set<String>,
    /** 发起时选择，后续内容更新不能抹掉命令上下文。 */
    val selectedIds: Set<String>,
    /** 发起时键盘焦点。 */
    val focusId: String?,
    /** 发起时的可见顺序，用于寻找最近存活邻项。 */
    val visibleIds: List<String>,
)
