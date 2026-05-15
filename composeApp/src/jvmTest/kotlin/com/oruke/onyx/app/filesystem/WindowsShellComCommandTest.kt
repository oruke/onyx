package com.oruke.onyx.app.filesystem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Windows Shell COM 命令描述序列化测试。
 */
class WindowsShellComCommandTest {
    /**
     * 校验根菜单命令只保存命令偏移量。
     *
     * @return 无返回值。
     */
    @Test
    fun serializesRootMenuCommand() {
        val command = WindowsShellComCommand(offset = 12, menuPath = emptyList())

        val serialized = command.serialize()
        val parsed = WindowsShellComCommand.parse(serialized)

        assertEquals("12", serialized)
        assertEquals(command, parsed)
    }

    /**
     * 校验级联菜单命令会保存父级菜单路径。
     *
     * @return 无返回值。
     */
    @Test
    fun serializesSubmenuCommandPath() {
        val command = WindowsShellComCommand(offset = 5, menuPath = listOf(0, 2, 4))

        val serialized = command.serialize()
        val parsed = WindowsShellComCommand.parse(serialized)

        assertEquals("5@path:0/2/4", serialized)
        assertEquals(command, parsed)
    }

    /**
     * 校验无效命令字符串不会被误解析成可执行命令。
     *
     * @return 无返回值。
     */
    @Test
    fun rejectsInvalidCommandPayloads() {
        assertNull(WindowsShellComCommand.parse(""))
        assertNull(WindowsShellComCommand.parse("-1"))
        assertNull(WindowsShellComCommand.parse("abc"))
        assertNull(WindowsShellComCommand.parse("5@other:0/1"))
        assertNull(WindowsShellComCommand.parse("5@path:"))
        assertNull(WindowsShellComCommand.parse("5@path:0/x"))
        assertNull(WindowsShellComCommand.parse("5@path:0/-1"))
    }
}
