package com.oruke.onyx.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 桌面文件管理器窗口管理器测试。
 */
class DesktopWindowManagerTest {
    /**
     * 校验目录请求会创建具有独立 ID 的临时窗口。
     *
     * @return 无返回值。
     */
    @Test
    fun createsIndependentDirectoryWindows() {
        val manager = DesktopWindowManager()

        val first = requireNotNull(manager.openDirectory("test://local/workspace"))
        val second = requireNotNull(manager.openDirectory("smb://server/share"))

        assertEquals(3, manager.windows.size)
        assertEquals("test://local/workspace", first.initialLocation)
        assertEquals("smb://server/share", second.initialLocation)
        assertFalse(first.persistsMainSession)
        assertNotEquals(first.id, second.id)
    }

    /**
     * 校验空目录请求不会生成无效窗口。
     *
     * @return 无返回值。
     */
    @Test
    fun rejectsBlankDirectoryLocation() {
        val manager = DesktopWindowManager()

        assertNull(manager.openDirectory("   "))
        assertEquals(1, manager.windows.size)
    }

    /**
     * 校验只有关闭最后一个活动窗口时才请求退出应用。
     *
     * @return 无返回值。
     */
    @Test
    fun exitsOnlyAfterLastWindowCloses() {
        val manager = DesktopWindowManager()
        val secondary = requireNotNull(manager.openDirectory("test://local/workspace"))
        val primaryId = requireNotNull(manager.windows.firstOrNull { request -> request.persistsMainSession }).id

        assertFalse(manager.closeWindow(primaryId))
        assertTrue(manager.closeWindow(secondary.id))
        assertFalse(manager.closeWindow(secondary.id))
    }
}
