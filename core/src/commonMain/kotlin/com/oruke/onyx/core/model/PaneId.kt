package com.oruke.onyx.core.model

import kotlinx.serialization.Serializable

@Serializable
/** 双面板中的稳定面板标识。 */
enum class PaneId {
    PRIMARY,
    SECONDARY,
}
