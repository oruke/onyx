package com.oruke.onyx.core.model

import kotlinx.serialization.Serializable

/**
 * 双面板操作中的角色。
 */
@Serializable
enum class PaneTransferRole {
    SOURCE,
    DESTINATION,
}

/**
 * Directory Opus 风格的 Source / Destination 面板关系。
 *
 * @property sourcePaneId 当前作为源的面板。
 * @property destinationPaneId 当前作为目标的面板。
 */
@Serializable
data class PaneRoleState(
    val sourcePaneId: PaneId,
    val destinationPaneId: PaneId,
) {
    /**
     * 查询指定面板在当前传输上下文中的角色。
     *
     * @param paneId 待查询的面板 ID。
     * @return 源面板或目标面板角色。
     */
    fun roleFor(paneId: PaneId): PaneTransferRole {
        return if (paneId == sourcePaneId) PaneTransferRole.SOURCE else PaneTransferRole.DESTINATION
    }

    companion object {
        /**
         * 根据当前源面板构造双面板角色状态。
         *
         * @param sourcePaneId 作为 Source 的面板 ID。
         * @return Source / Destination 配对状态。
         */
        fun fromSource(sourcePaneId: PaneId): PaneRoleState {
            return PaneRoleState(
                sourcePaneId = sourcePaneId,
                destinationPaneId = sourcePaneId.oppositePane(),
            )
        }
    }
}

/**
 * 返回双面板中的另一个面板。
 *
 * @return 与当前面板相对的面板 ID。
 */
fun PaneId.oppositePane(): PaneId {
    return when (this) {
        PaneId.PRIMARY -> PaneId.SECONDARY
        PaneId.SECONDARY -> PaneId.PRIMARY
    }
}
