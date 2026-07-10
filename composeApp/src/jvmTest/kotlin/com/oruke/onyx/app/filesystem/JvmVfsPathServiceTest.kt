package com.oruke.onyx.app.filesystem

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * JVM VFS 路径规范化测试。
 */
class JvmVfsPathServiceTest {
    /** 路径服务测试实例。 */
    private val service = JvmVfsPathService()

    /**
     * 校验 SMB 中文路径在规范化后仍保留 Unicode 目录名。
     *
     * @return 无返回值。
     */
    @Test
    fun preservesChineseSegmentsWhenNormalizingRemoteLocation() {
        assertEquals(
            "smb://host/share/综艺/",
            service.normalizeLocation("smb://host/share/综艺"),
        )
        assertEquals(
            "smb://host/share/综艺/",
            service.normalizeLocation("smb://host/share/%E7%BB%BC%E8%89%BA/"),
        )
    }

    /**
     * 校验远程中文路径的父目录和面包屑位置不会重新变成百分号文本。
     *
     * @return 无返回值。
     */
    @Test
    fun preservesChineseSegmentsInRemoteNavigationLocations() {
        val location = "smb://host/share/综艺/音乐/"

        assertEquals("smb://host/share/综艺/", service.parentLocation(location))
        assertEquals(
            listOf(
                VfsBreadcrumb(label = "host", location = "smb://host/"),
                VfsBreadcrumb(label = "share", location = "smb://host/share/"),
                VfsBreadcrumb(label = "综艺", location = "smb://host/share/综艺/"),
                VfsBreadcrumb(label = "音乐", location = "smb://host/share/综艺/音乐/"),
            ),
            service.buildBreadcrumbs(location),
        )
    }
}
