package com.oruke.onyx.app.filesystem

/**
 * Windows Shell COM 菜单命令的可序列化描述。
 *
 * @property offset `IContextMenu.QueryContextMenu` 返回的命令偏移量。
 * @property menuPath 叶子命令所属级联菜单的父级索引路径。
 */
internal data class WindowsShellComCommand(
    val offset: Int,
    val menuPath: List<Int>,
) {
    /**
     * 序列化到 `SystemMenuAction.command`，供 UI 层无感传回执行服务。
     *
     * @return 可解析的命令描述字符串。
     */
    fun serialize(): String {
        if (menuPath.isEmpty()) return offset.toString()
        return "$offset@$COMMAND_PATH_PREFIX${menuPath.joinToString("/")}"
    }

    companion object {
        private const val COMMAND_PATH_PREFIX = "path:"

        /**
         * 从菜单动作命令字符串恢复 Shell COM 命令描述。
         *
         * @param value `SystemMenuAction.command` 中保存的命令字符串。
         * @return 可执行命令描述；格式无效时返回 `null`。
         */
        fun parse(value: String): WindowsShellComCommand? {
            val parts = value.split("@", limit = 2)
            val offset = parts.firstOrNull()
                ?.toIntOrNull()
                ?.takeIf { candidate -> candidate >= 0 }
            return when {
                offset == null -> null
                parts.size == 1 -> WindowsShellComCommand(offset = offset, menuPath = emptyList())
                else -> parseMenuPath(parts[1])?.let { path ->
                    WindowsShellComCommand(offset = offset, menuPath = path)
                }
            }
        }

        /**
         * 解析级联菜单路径。
         *
         * @param value `path:` 前缀开头的菜单索引路径。
         * @return 全部路径段合法时返回索引列表，否则返回 `null`。
         */
        private fun parseMenuPath(value: String): List<Int>? {
            val rawPath = value
                .takeIf { text -> text.startsWith(COMMAND_PATH_PREFIX) }
                ?.removePrefix(COMMAND_PATH_PREFIX)
                ?.takeIf { text -> text.isNotBlank() }
            val segments = rawPath?.split("/").orEmpty()
            val path = segments.mapNotNull { segment ->
                segment.toIntOrNull()?.takeIf { index -> index >= 0 }
            }
            return path.takeIf { parsedPath -> parsedPath.size == segments.size && segments.isNotEmpty() }
        }
    }
}
