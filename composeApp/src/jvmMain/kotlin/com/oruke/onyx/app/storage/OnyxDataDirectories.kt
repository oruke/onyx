package com.oruke.onyx.app.storage

import java.nio.file.Path

/** Onyx 桌面端本地数据目录解析器，统一约束配置、状态与缓存文件的落盘位置。 */
internal object OnyxDataDirectories {
    /** 当前宿主系统是否为 Windows，用于选择符合平台习惯的用户数据目录。 */
    private val isWindows: Boolean = System.getProperty("os.name").lowercase().contains("win")

    /** 当前宿主系统是否为 macOS，用于选择符合平台习惯的用户数据目录。 */
    private val isMac: Boolean = System.getProperty("os.name").lowercase().contains("mac")

    /**
     * 返回配置目录，承载用户希望长期保留且可迁移的设置文件。
     *
     * @return Onyx 配置目录路径；调用方负责在写入前创建目录。
     */
    fun configDirectory(): Path {
        val xdgConfigHome = System.getenv("XDG_CONFIG_HOME")?.takeIf { value -> value.isNotBlank() }
        val userHome = System.getProperty("user.home")
        return when {
            isWindows -> {
                val appData = System.getenv("APPDATA")?.takeIf { value -> value.isNotBlank() }
                    ?: Path.of(userHome, "AppData", "Roaming").toString()
                Path.of(appData).resolve("Onyx")
            }

            xdgConfigHome != null -> Path.of(xdgConfigHome).resolve("onyx")
            isMac -> Path.of(userHome, "Library", "Application Support", "Onyx")
            else -> Path.of(userHome, ".config", "onyx")
        }
    }

    /**
     * 返回状态目录，承载会随本机运行状态变化但不应频繁清理的数据。
     * Windows 将状态目录放在 `%LOCALAPPDATA%\\OnyxData`，与 MSI 安装目录隔离，避免升级清理安装目录时删除用户数据。
     *
     * @return Onyx 状态目录路径；调用方负责在写入前创建目录。
     */
    fun stateDirectory(): Path {
        val xdgStateHome = System.getenv("XDG_STATE_HOME")?.takeIf { value -> value.isNotBlank() }
        val userHome = System.getProperty("user.home")
        return when {
            isWindows -> {
                val localAppData = System.getenv("LOCALAPPDATA")?.takeIf { value -> value.isNotBlank() }
                    ?: Path.of(userHome, "AppData", "Local").toString()
                Path.of(localAppData, "OnyxData")
            }

            xdgStateHome != null -> Path.of(xdgStateHome).resolve("onyx")
            isMac -> Path.of(userHome, "Library", "Caches", "Onyx")
            else -> Path.of(userHome, ".local", "state", "onyx")
        }
    }

    /**
     * 返回缓存目录，承载可重建的菜单索引、缩略图索引等派生数据。
     *
     * @return Onyx 缓存目录路径；调用方负责在写入前创建目录。
     */
    fun cacheDirectory(): Path {
        val xdgCacheHome = System.getenv("XDG_CACHE_HOME")?.takeIf { value -> value.isNotBlank() }
        val userHome = System.getProperty("user.home")
        return when {
            isWindows -> {
                val localAppData = System.getenv("LOCALAPPDATA")?.takeIf { value -> value.isNotBlank() }
                    ?: Path.of(userHome, "AppData", "Local").toString()
                Path.of(localAppData, "OnyxData", "Cache")
            }

            xdgCacheHome != null -> Path.of(xdgCacheHome).resolve("onyx")
            isMac -> Path.of(userHome, "Library", "Caches", "Onyx")
            else -> Path.of(userHome, ".cache", "onyx")
        }
    }
}
