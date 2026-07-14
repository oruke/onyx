package com.oruke.onyx.app.platform

import com.oruke.onyx.core.model.SystemQuickAccessLocation
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 系统快速访问合并与桌面书签解析测试。 */
class SystemQuickAccessServiceTest {
    /** 主目录应立即可见，并与系统数据源中的重复位置合并。 */
    @Test
    fun mergesHomeWithSystemLocationsWithoutDuplicates() = runTest {
        val homeDirectory = createTempDirectory("onyx-quick-access-home")
        try {
            val childDirectory = homeDirectory.resolve("Documents")
            val service = JvmSystemQuickAccessService(
                operatingSystem = DesktopOperatingSystem.LINUX,
                homeDirectory = homeDirectory,
                source = SystemQuickAccessSource {
                    Result.success(
                        listOf(
                            SystemQuickAccessLocation("重复主目录", homeDirectory.toString()),
                            SystemQuickAccessLocation("文档", childDirectory.toString()),
                        )
                    )
                },
            )

            val locations = service.loadLocations().getOrThrow()

            assertEquals(2, locations.size)
            assertTrue(locations.first().isHome)
            assertEquals(childDirectory.toString(), locations.last().location)
        } finally {
            homeDirectory.deleteIfExists()
        }
    }

    /** GTK 书签应保留显示名称并把 DAV 协议映射到统一 WebDAV VFS。 */
    @Test
    fun parsesGtkBookmarksIntoSupportedVfsLocations() {
        val smbLocation = parseGtkBookmarkLine("smb://server/share 团队共享")
        val webDavLocation = parseGtkBookmarkLine("davs://example.test/files 云文档")

        assertEquals("团队共享", smbLocation?.displayName)
        assertEquals("smb://server/share", smbLocation?.location)
        assertEquals("云文档", webDavLocation?.displayName)
        assertEquals("webdavs://example.test/files", webDavLocation?.location)
    }

    /** XDG 用户目录只能按受限语法展开 `$HOME`，不能执行 Shell 表达式。 */
    @Test
    fun parsesRestrictedXdgUserDirectorySyntax() {
        val homeDirectory = createTempDirectory("onyx-xdg-home")
        try {
            val valid = parseXdgUserDirectoryLine(
                line = "XDG_DOCUMENTS_DIR=\"\$HOME/Documents\"",
                homeDirectory = homeDirectory,
            )
            val unsupported = parseXdgUserDirectoryLine(
                line = "XDG_DOCUMENTS_DIR=\$(echo unsafe)",
                homeDirectory = homeDirectory,
            )

            assertEquals(homeDirectory.resolve("Documents").toString(), valid?.location)
            assertEquals(null, unsupported)
        } finally {
            homeDirectory.deleteIfExists()
        }
    }

    /** KDE XBEL 书签应使用 XML 解析并保留 Unicode 标题。 */
    @Test
    fun parsesKdePlacesXbel() {
        val directory = createTempDirectory("onyx-kde-places")
        val targetDirectory = Files.createDirectory(directory.resolve("共享目录"))
        val placesFile = directory.resolve("user-places.xbel")
        try {
            Files.writeString(
                placesFile,
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <xbel xmlns="http://www.freedesktop.org/standards/desktop-bookmarks">
                  <bookmark href="${targetDirectory.toUri()}"><title>共享目录</title></bookmark>
                  <bookmark href="${targetDirectory.toUri()}">
                    <title>隐藏目录</title>
                    <info><metadata owner="http://freedesktop.org"><isHidden>true</isHidden></metadata></info>
                  </bookmark>
                </xbel>
                """.trimIndent(),
            )

            val locations = readKdePlaces(placesFile)

            assertEquals(1, locations.size)
            assertEquals("共享目录", locations.single().displayName)
            assertEquals(targetDirectory.toAbsolutePath().toString(), locations.single().location)
        } finally {
            placesFile.deleteIfExists()
            targetDirectory.deleteIfExists()
            directory.deleteIfExists()
        }
    }

    /** 操作系统名称识别应覆盖 Windows、macOS、Linux 与未知系统。 */
    @Test
    fun detectsDesktopOperatingSystems() {
        assertEquals(DesktopOperatingSystem.WINDOWS, currentDesktopOperatingSystem("Windows 11"))
        assertEquals(DesktopOperatingSystem.MAC_OS, currentDesktopOperatingSystem("Mac OS X"))
        assertEquals(DesktopOperatingSystem.LINUX, currentDesktopOperatingSystem("Linux"))
        assertEquals(DesktopOperatingSystem.OTHER, currentDesktopOperatingSystem("FreeBSD"))
    }

}
