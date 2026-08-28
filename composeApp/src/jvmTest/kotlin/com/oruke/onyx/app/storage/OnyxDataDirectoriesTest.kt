package com.oruke.onyx.app.storage

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Onyx 本地数据目录测试，验证持久化状态与 MSI 安装目录隔离。 */
class OnyxDataDirectoriesTest {
    /** 验证 Windows 状态和缓存目录不会落在应用安装目录内，避免升级清理用户数据。 */
    @Test
    fun windowsPersistentDirectoriesAreOutsideInstallDirectory() {
        if (!System.getProperty("os.name").contains("win", ignoreCase = true)) return

        val userHome = System.getProperty("user.home")
        val localAppData = System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)
            ?: Path.of(userHome, "AppData", "Local").toString()
        val expectedStateDirectory = Path.of(localAppData, "OnyxData").toAbsolutePath().normalize()
        val legacyInstallDirectory = Path.of(localAppData, "Onyx").toAbsolutePath().normalize()

        assertEquals(expectedStateDirectory, OnyxDataDirectories.stateDirectory().toAbsolutePath().normalize())
        assertEquals(
            expectedStateDirectory.resolve("Cache"),
            OnyxDataDirectories.cacheDirectory().toAbsolutePath().normalize(),
        )
        assertFalse(
            OnyxDataDirectories.stateDirectory().toAbsolutePath().normalize().startsWith(legacyInstallDirectory),
        )
        assertFalse(
            OnyxDataDirectories.cacheDirectory().toAbsolutePath().normalize().startsWith(legacyInstallDirectory),
        )
    }
}
