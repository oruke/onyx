package com.oruke.onyx.app.filesystem

import com.oruke.onyx.app.cache.PlatformMenuCacheScope
import com.oruke.onyx.app.cache.PlatformMenuCacheService
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.vfs.api.SystemFileMaterializer
import com.oruke.onyx.vfs.api.SystemMenuAction
import com.oruke.onyx.vfs.api.SystemMenuService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * JVM 系统菜单统一服务，负责平台路由、文件物化与菜单缓存协调。
 *
 * @param materializer 将 VFS 条目转换为系统可访问的本地文件。
 * @param menuCacheService 平台菜单缓存服务。
 */
internal class JvmSystemMenuService(
    private val materializer: SystemFileMaterializer,
    private val menuCacheService: PlatformMenuCacheService,
) : SystemMenuService {
    /** Windows Explorer COM 菜单桥接。 */
    private val windowsComProvider = JvmWindowsShellComMenuBridge()

    /** Windows 静态注册表菜单提供器。 */
    private val windowsRegistryProvider = JvmWindowsRegistryMenuProvider()

    /** Linux 桌面菜单提供器。 */
    private val linuxProvider = JvmLinuxSystemMenuProvider()

    /** macOS Services 菜单提供器。 */
    private val macProvider = JvmMacSystemMenuProvider(materializer)

    /**
     * 查询当前平台适用的系统菜单动作。
     *
     * @param entries 当前菜单目标。
     * @return 可展示的系统菜单动作。
     */
    override suspend fun listActions(entries: List<VFile>): List<SystemMenuAction> = withContext(Dispatchers.IO) {
        if (entries.isEmpty()) return@withContext emptyList()
        when (currentHostPlatform()) {
            HostPlatform.LINUX -> listCachedSystemActions(entries, "linux-services") {
                linuxProvider.listActions(entries)
            }
            HostPlatform.WINDOWS -> listWindowsShellActionsWithCache(entries)
            HostPlatform.MACOS -> listCachedSystemActions(entries, "macos-services") {
                macProvider.listActions(entries)
            }
            HostPlatform.OTHER -> emptyList()
        }
    }

    /**
     * 执行系统菜单叶子动作。
     *
     * @param action 待执行系统动作。
     * @param entries 当前菜单目标。
     * @return 执行结果。
     */
    override suspend fun execute(
        action: SystemMenuAction,
        entries: List<VFile>,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val targetEntries = checkNotNull(entries.materializeEntries()) {
                "System menu action requires materializable files"
            }
            when {
                action.children.isNotEmpty() -> throw UnsupportedOperationException(
                    "System menu group cannot be executed directly: ${action.id}"
                )
                action.id.startsWith(JvmLinuxSystemMenuProvider.SCRIPT_ACTION_PREFIX) -> {
                    linuxProvider.execute(action, targetEntries)
                }
                action.id.startsWith(JvmLinuxSystemMenuProvider.SERVICE_ACTION_PREFIX) -> {
                    linuxProvider.execute(action, targetEntries)
                }
                action.id.startsWith(JvmMacSystemMenuProvider.ACTION_PREFIX) -> {
                    macProvider.execute(action, targetEntries)
                }
                action.id.startsWith(JvmWindowsShellComMenuBridge.WINDOWS_COM_ACTION_PREFIX) -> {
                    windowsComProvider.execute(action, targetEntries).getOrThrow()
                }
                action.id.startsWith(JvmWindowsRegistryMenuProvider.ACTION_PREFIX) -> {
                    windowsRegistryProvider.execute(action, targetEntries)
                }
                else -> throw UnsupportedOperationException("Unsupported system menu action: ${action.id}")
            }
        }
    }

    /**
     * 将全部菜单目标物化为系统本地文件。
     *
     * @return 全部物化成功时返回本地条目，否则返回 `null`。
     */
    private suspend fun List<VFile>.materializeEntries(): List<VFile>? {
        if (any { entry -> !materializer.supports(entry) }) return null
        return map { entry -> materializer.materialize(entry).getOrElse { return null } }
    }

    /**
     * 通过缓存读取 Windows 静态注册表菜单。
     *
     * @param entries 当前菜单目标。
     * @return 可展示的 Windows 注册表动作。
     */
    private suspend fun listWindowsShellActionsWithCache(entries: List<VFile>): List<SystemMenuAction> {
        val targetEntries = entries.materializeEntries() ?: return emptyList()
        return listCachedSystemActions(targetEntries, "windows-registry-icon-v2") {
            windowsRegistryProvider.listActions(targetEntries)
        }
    }

    /**
     * 按平台菜单作用域读取缓存，缺失时同步调用真实提供器。
     *
     * @param entries 当前菜单目标。
     * @param sourceKey 菜单来源标识。
     * @param loader 缓存缺失时的平台扫描函数。
     * @return 可展示的系统菜单动作。
     */
    private suspend fun listCachedSystemActions(
        entries: List<VFile>,
        sourceKey: String,
        loader: suspend () -> List<SystemMenuAction>,
    ): List<SystemMenuAction> {
        return menuCacheService.cachedOrLoad(entries.toMenuCacheScope(sourceKey), loader)
    }
}

/**
 * 生成平台菜单缓存作用域。
 *
 * @param sourceKey 菜单来源标识。
 * @return 由平台、来源、选择数量与文件类型组成的作用域。
 */
private fun List<VFile>.toMenuCacheScope(sourceKey: String): PlatformMenuCacheScope {
    val entrySignature = joinToString(separator = "|") { entry ->
        val extension = entry.name.substringAfterLast('.', "")
            .takeIf { value -> value.isNotBlank() }
            ?.lowercase(Locale.ROOT)
            .orEmpty()
        val mimeType = if (entry.kind == VFileKind.DIRECTORY) {
            "inode/directory"
        } else {
            entry.guessSystemMimeType().orEmpty()
        }
        "${entry.kind.name}:$extension:$mimeType"
    }
    return PlatformMenuCacheScope(
        platform = currentHostPlatform().cacheKey,
        scopeKey = "$sourceKey;count=$size;entries=$entrySignature",
    )
}

/** 当前 JVM 主机平台。 */
private enum class HostPlatform(val cacheKey: String) {
    WINDOWS("windows"),
    MACOS("macos"),
    LINUX("linux"),
    OTHER("other"),
}

/**
 * 识别当前 JVM 主机平台。
 *
 * @return 当前主机平台。
 */
private fun currentHostPlatform(): HostPlatform {
    val osName = System.getProperty("os.name").lowercase(Locale.ROOT)
    return when {
        osName.contains("mac") || osName.contains("darwin") -> HostPlatform.MACOS
        osName.contains("win") -> HostPlatform.WINDOWS
        osName.contains("nux") || osName.contains("nix") || osName.contains("linux") -> HostPlatform.LINUX
        else -> HostPlatform.OTHER
    }
}

/** 已解析 desktop 文件的分组字段。 */
internal data class DesktopEntryFile(
    /** 分组名称到键值字段的映射。 */
    val groups: Map<String, Map<String, String>>,
)

/**
 * 读取 Freedesktop desktop 文件。
 *
 * @return 已解析分组；读取失败时返回空分组。
 */
internal fun Path.readDesktopEntry(): DesktopEntryFile {
    return runCatching {
        val groups = linkedMapOf<String, MutableMap<String, String>>()
        var currentGroup: MutableMap<String, String>? = null
        Files.newBufferedReader(this).useLines { lines ->
            lines.forEach { rawLine ->
                val line = rawLine.trim()
                when {
                    line.isBlank() || line.startsWith("#") -> Unit
                    line.startsWith("[") && line.endsWith("]") -> {
                        val groupName = line.removePrefix("[").removeSuffix("]")
                        currentGroup = groups.getOrPut(groupName) { linkedMapOf() }
                    }
                    else -> {
                        val separatorIndex = line.indexOf('=')
                        if (separatorIndex > 0) {
                            currentGroup?.put(line.substring(0, separatorIndex), line.substring(separatorIndex + 1))
                        }
                    }
                }
            }
        }
        DesktopEntryFile(groups)
    }.getOrDefault(DesktopEntryFile(emptyMap()))
}

/**
 * 按系统区域读取 desktop 本地化字段。
 *
 * @param fields desktop 字段。
 * @param key 基础字段名。
 * @return 最匹配的本地化字段值。
 */
internal fun localizedValue(fields: Map<String, String>, key: String): String? {
    val locale = Locale.getDefault()
    val keys = buildList {
        if (locale.language.isNotBlank() && locale.country.isNotBlank()) {
            add("$key[${locale.language}_${locale.country}]")
        }
        if (locale.language.isNotBlank()) add("$key[${locale.language}]")
        add(key)
    }
    return keys.firstNotNullOfOrNull { candidate ->
        fields[candidate]?.takeIf { value -> value.isNotBlank() }
    }
}

/**
 * 解析 desktop 布尔字段。
 *
 * @return 字段值为 true 时返回 `true`。
 */
internal fun String?.isTrue(): Boolean = equals("true", ignoreCase = true)

/**
 * 返回 Linux desktop application 搜索目录。
 *
 * @return application 目录候选。
 */
internal fun desktopApplicationDirs(): List<Path> {
    val home = System.getProperty("user.home")
    return listOf(
        Path.of(home, ".local/share/applications"),
        Path.of(home, ".local/share/flatpak/exports/share/applications"),
        Path.of("/usr/local/share/applications"),
        Path.of("/usr/share/applications"),
        Path.of("/var/lib/flatpak/exports/share/applications"),
    )
}

/**
 * 执行短生命周期命令并读取标准输出。
 *
 * @param command 命令及参数。
 * @return 退出码为零时返回输出，否则返回 `null`。
 */
internal fun commandOutput(vararg command: String): String? {
    return runCatching {
        val process = ProcessBuilder(*command)
            .redirectErrorStream(true)
            .apply { environment()["LC_ALL"] = "C" }
            .start()
        val output = process.inputStream.readBytes().decodePlatformProcessOutput().trim()
        if (process.waitFor() == 0) output else null
    }.getOrNull()
}
