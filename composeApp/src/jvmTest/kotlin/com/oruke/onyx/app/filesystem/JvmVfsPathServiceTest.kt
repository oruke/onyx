package com.oruke.onyx.app.filesystem

import com.oruke.onyx.vfs.archive.ArchiveService
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

    /**
     * 校验空格与方括号目录名会被规范化为可导航 URI，而不会回退成本地路径。
     *
     * @return 无返回值。
     */
    @Test
    fun normalizesReservedCharactersInRemoteDirectoryNames() {
        val rawLocation =
            "smb://example-host/share/动画/[Release Group] Example Series [1080p]/"
        val encodedLocation = rawLocation
            .replace("[", "%5B")
            .replace("]", "%5D")
            .replace(" ", "%20")

        assertEquals(rawLocation, service.normalizeLocation(rawLocation))
        assertEquals(rawLocation, service.normalizeLocation(encodedLocation))
        assertEquals("smb://example-host/share/动画/", service.parentLocation(rawLocation))
        assertEquals("[Release Group] Example Series [1080p]", service.title(rawLocation))
    }

    /**
     * 校验仅 SMB 保留原始路径字符，标准 URI provider 继续输出百分号编码。
     *
     * @return 无返回值。
     */
    @Test
    fun preservesProviderSpecificRemoteLocationFormats() {
        assertEquals(
            "webdav://host/share/%5Bfolder%5D%20name/",
            service.normalizeLocation("webdav://host/share/[folder] name"),
        )
        assertEquals(
            "s3://bucket/prefix/%5Bfolder%5D%20name/",
            service.normalizeLocation("s3://bucket/prefix/[folder] name"),
        )
    }

    /**
     * 校验远程压缩包及内部路径面包屑不会交给本地 Path 解析。
     *
     * @return 无返回值。
     */
    @Test
    fun buildsRemoteArchiveBreadcrumbsWithoutLocalPathParsing() {
        val archivePath = "smb://host/share/电子书/示例 03.epub"
        val location = ArchiveService.archiveLocation(archivePath, "OEBPS/章节")

        assertEquals(
            listOf(
                VfsBreadcrumb(label = "host", location = "smb://host/"),
                VfsBreadcrumb(label = "share", location = "smb://host/share/"),
                VfsBreadcrumb(label = "电子书", location = "smb://host/share/电子书/"),
                VfsBreadcrumb(
                    label = "示例 03.epub",
                    location = ArchiveService.archiveLocation(archivePath),
                ),
                VfsBreadcrumb(
                    label = "OEBPS",
                    location = ArchiveService.archiveLocation(archivePath, "OEBPS"),
                ),
                VfsBreadcrumb(
                    label = "章节",
                    location = ArchiveService.archiveLocation(archivePath, "OEBPS/章节"),
                ),
            ),
            service.buildBreadcrumbs(location),
        )
    }
}
