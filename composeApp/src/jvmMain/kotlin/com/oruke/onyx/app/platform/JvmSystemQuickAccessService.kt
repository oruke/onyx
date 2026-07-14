package com.oruke.onyx.app.platform

import com.oruke.onyx.core.model.SystemQuickAccessLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.nio.file.Path
import java.util.Locale

/**
 * 根据桌面操作系统选择原生数据源并输出统一快速访问位置。
 *
 * @param operatingSystem 当前桌面操作系统。
 * @param homeDirectory 当前用户主目录。
 * @param source 当前平台快速访问数据源，允许测试注入确定性实现。
 */
internal class JvmSystemQuickAccessService(
    private val operatingSystem: DesktopOperatingSystem = currentDesktopOperatingSystem(),
    private val homeDirectory: Path = currentHomeDirectory(),
    private val source: SystemQuickAccessSource = defaultSystemQuickAccessSource(operatingSystem, homeDirectory),
) : SystemQuickAccessService {
    /**
     * 构造不依赖系统 I/O 的主目录入口。
     *
     * @return 仅包含当前用户主目录的初始位置。
     */
    override fun initialLocations(): List<SystemQuickAccessLocation> {
        return listOf(
            SystemQuickAccessLocation(
                displayName = null,
                location = homeDirectory.toString(),
                isHome = true,
            )
        )
    }

    /**
     * 在 I/O 调度器读取并合并系统快速访问位置。
     *
     * @return 主目录与平台位置去重、限量后的结果。
     */
    override suspend fun loadLocations(): Result<List<SystemQuickAccessLocation>> = withContext(Dispatchers.IO) {
        source.loadLocations().map { platformLocations ->
            (initialLocations() + platformLocations)
                .asSequence()
                .filter { quickAccessLocation -> quickAccessLocation.location.isNotBlank() }
                .distinctBy { quickAccessLocation -> locationIdentity(quickAccessLocation.location) }
                .take(MAX_QUICK_ACCESS_LOCATION_COUNT)
                .toList()
        }
    }

    /**
     * 生成符合宿主文件系统大小写规则的位置去重键。
     *
     * @param location 本地路径或远程位置。
     * @return 规范化后的比较键。
     */
    private fun locationIdentity(location: String): String {
        val trimmedLocation = location.trim()
        if (VFS_URI_SEPARATOR in trimmedLocation) {
            return runCatching { URI(trimmedLocation).normalize().toString().trimEnd('/') }
                .getOrDefault(trimmedLocation)
        }
        val normalizedLocation = runCatching {
            Path.of(trimmedLocation).normalize().toAbsolutePath().toString()
        }.getOrDefault(trimmedLocation)
        return if (operatingSystem == DesktopOperatingSystem.WINDOWS) {
            normalizedLocation.lowercase(Locale.ROOT)
        } else {
            normalizedLocation
        }
    }
}

/** 桌面宿主操作系统分类。 */
internal enum class DesktopOperatingSystem {
    WINDOWS,
    MAC_OS,
    LINUX,
    OTHER,
}

/**
 * 根据 JVM 系统属性识别桌面操作系统。
 *
 * @param osName JVM 报告的操作系统名称。
 * @return 对应的桌面操作系统分类。
 */
internal fun currentDesktopOperatingSystem(
    osName: String = System.getProperty("os.name").orEmpty(),
): DesktopOperatingSystem {
    val normalizedName = osName.lowercase(Locale.ROOT)
    return when {
        "win" in normalizedName -> DesktopOperatingSystem.WINDOWS
        "mac" in normalizedName || "darwin" in normalizedName -> DesktopOperatingSystem.MAC_OS
        "linux" in normalizedName -> DesktopOperatingSystem.LINUX
        else -> DesktopOperatingSystem.OTHER
    }
}

/**
 * 读取并规范化当前用户主目录。
 *
 * @param userHome JVM 用户主目录属性。
 * @return 可用于 VFS 的绝对主目录路径。
 */
internal fun currentHomeDirectory(
    userHome: String = System.getProperty("user.home").orEmpty(),
): Path {
    return Path.of(userHome.ifBlank { "." }).normalize().toAbsolutePath()
}

/**
 * 为宿主平台创建原生快速访问数据源及标准目录降级链。
 *
 * @param operatingSystem 当前桌面操作系统。
 * @param homeDirectory 当前用户主目录。
 * @return 可直接读取的快速访问数据源。
 */
private fun defaultSystemQuickAccessSource(
    operatingSystem: DesktopOperatingSystem,
    homeDirectory: Path,
): SystemQuickAccessSource {
    val fallback = StandardUserDirectoryQuickAccessSource(homeDirectory)
    val primary = when (operatingSystem) {
        DesktopOperatingSystem.WINDOWS -> WindowsShellQuickAccessSource()
        DesktopOperatingSystem.MAC_OS -> MacFinderQuickAccessSource()
        DesktopOperatingSystem.LINUX -> LinuxPlacesQuickAccessSource(homeDirectory)
        DesktopOperatingSystem.OTHER -> return fallback
    }
    return FallbackSystemQuickAccessSource(
        sourceName = operatingSystem.name,
        primary = primary,
        fallback = fallback,
    )
}

/** 侧栏最多展示的系统快速访问位置数。 */
private const val MAX_QUICK_ACCESS_LOCATION_COUNT = 24

/** VFS 位置与普通本地路径的最小判别标记。 */
private const val VFS_URI_SEPARATOR = "://"
