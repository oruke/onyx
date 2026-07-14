package com.oruke.onyx.app.platform

import com.oruke.onyx.app.OnyxLogger
import com.oruke.onyx.core.model.SystemQuickAccessLocation
import java.nio.file.Files
import java.nio.file.Path

/**
 * 同步读取单个平台快速访问数据源的内部边界。
 */
internal fun interface SystemQuickAccessSource {
    /**
     * 读取当前数据源中的位置。
     *
     * @return 成功时返回位置列表，失败时返回保留原因的 [Result]。
     */
    fun loadLocations(): Result<List<SystemQuickAccessLocation>>
}

/**
 * 主数据源不可用或没有内容时切换到标准用户目录。
 *
 * @param sourceName 用于日志定位的系统数据源名称。
 * @param primary 系统原生快速访问数据源。
 * @param fallback 原生数据源不可用时使用的降级数据源。
 */
internal class FallbackSystemQuickAccessSource(
    private val sourceName: String,
    private val primary: SystemQuickAccessSource,
    private val fallback: SystemQuickAccessSource,
) : SystemQuickAccessSource {
    /**
     * 优先读取系统数据源，并在失败或空结果时读取降级目录。
     *
     * @return 原生数据或降级数据的读取结果。
     */
    override fun loadLocations(): Result<List<SystemQuickAccessLocation>> {
        val primaryResult = primary.loadLocations()
        val locations = primaryResult.getOrNull()
        if (!locations.isNullOrEmpty()) return Result.success(locations)

        primaryResult.exceptionOrNull()?.let { failure ->
            OnyxLogger.warn(
                tag = QUICK_ACCESS_LOG_TAG,
                message = "$sourceName 快速访问读取失败，改用标准用户目录",
                throwable = failure,
            )
        }
        return fallback.loadLocations()
    }
}

/**
 * 从用户主目录下的常见系统目录构造降级快速访问位置。
 *
 * @param homeDirectory 当前用户主目录。
 * @param directoryNames 按展示顺序排列的标准目录名称。
 */
internal class StandardUserDirectoryQuickAccessSource(
    private val homeDirectory: Path,
    private val directoryNames: List<String> = DEFAULT_STANDARD_DIRECTORY_NAMES,
) : SystemQuickAccessSource {
    /**
     * 读取当前真实存在的标准用户目录。
     *
     * @return 标准用户目录列表。
     */
    override fun loadLocations(): Result<List<SystemQuickAccessLocation>> = runCatching {
        directoryNames.mapNotNull { directoryName ->
            val directory = homeDirectory.resolve(directoryName).normalize()
            directory.takeIf(Files::isDirectory)?.let { existingDirectory ->
                SystemQuickAccessLocation(
                    displayName = null,
                    location = existingDirectory.toString(),
                )
            }
        }
    }
}

/** 快速访问平台读取日志标签。 */
internal const val QUICK_ACCESS_LOG_TAG = "SystemQuickAccess"

/** 标准用户目录的跨平台降级顺序。 */
private val DEFAULT_STANDARD_DIRECTORY_NAMES = listOf(
    "Desktop",
    "Documents",
    "Downloads",
    "Pictures",
    "Music",
    "Videos",
    "Movies",
)
