package com.oruke.onyx.ui

import org.jetbrains.jewel.ui.icon.IconKey

/**
 * 命令面板中已经完成本地化和可用性计算的展示条目。
 */
internal data class CommandPaletteItem(
    /** 点击后需要执行的应用命令。 */
    val command: OnyxCommand,
    /** 已本地化的命令名称。 */
    val label: String,
    /** 可选的快捷键说明。 */
    val shortcut: String?,
    /** 命令对应的 Jewel 图标。 */
    val iconKey: IconKey,
    /** 当前上下文中是否允许执行。 */
    val enabled: Boolean,
)
