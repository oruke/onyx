package com.oruke.onyx.app.platform

import com.oruke.onyx.app.OnyxLogger
import com.oruke.onyx.core.model.SystemQuickAccessLocation
import com.sun.jna.platform.win32.COM.util.Factory
import com.sun.jna.platform.win32.COM.util.annotation.ComMethod
import com.sun.jna.platform.win32.COM.util.annotation.ComObject
import com.sun.jna.platform.win32.COM.util.annotation.ComProperty
import java.nio.file.Files
import java.nio.file.Path

/**
 * 通过 Windows Shell COM 读取资源管理器 Home/快速访问中的文件夹。
 */
internal class WindowsShellQuickAccessSource : SystemQuickAccessSource {
    /**
     * 枚举 Windows Shell 快速访问命名空间中的真实目录。
     *
     * @return 可访问文件夹的位置列表。
     */
    override fun loadLocations(): Result<List<SystemQuickAccessLocation>> = runCatching {
        val factory = Factory()
        try {
            val shell = factory.createObject(WindowsShellApplication::class.java)
            val folder = shell.nameSpace(WINDOWS_QUICK_ACCESS_NAMESPACE) ?: return@runCatching emptyList()
            val items = folder.items()
            buildList {
                repeat(items.count().coerceAtLeast(0)) { index ->
                    items.item(index)?.toQuickAccessLocation()?.let(::add)
                }
            }
        } finally {
            disposeFactory(factory)
        }
    }

    /**
     * 将 Shell 条目转换为应用快速访问位置，并排除最近文件和压缩包伪文件夹。
     *
     * @return 条目指向真实目录时返回位置，否则返回 null。
     */
    private fun WindowsShellFolderItem.toQuickAccessLocation(): SystemQuickAccessLocation? {
        if (!isFolder() || !isFileSystem()) return null
        val shellPath = path().trim().takeIf(String::isNotEmpty) ?: return null
        val directory = runCatching { Path.of(shellPath).normalize().toAbsolutePath() }.getOrNull() ?: return null
        if (!Files.isDirectory(directory)) return null
        return SystemQuickAccessLocation(
            displayName = name().trim().takeIf(String::isNotEmpty),
            location = directory.toString(),
        )
    }

    /**
     * 释放 Factory 持有的全部 COM 代理，并记录清理异常。
     *
     * @param factory 本次枚举创建的 COM 工厂。
     */
    private fun disposeFactory(factory: Factory) {
        runCatching(factory::disposeAll).onFailure { failure ->
            OnyxLogger.warn(
                tag = QUICK_ACCESS_LOG_TAG,
                message = "Windows Shell 快速访问 COM 资源释放失败",
                throwable = failure,
            )
        }
    }
}

/** Windows Shell.Application 自动化对象。 */
@ComObject(progId = "Shell.Application")
internal interface WindowsShellApplication {
    /**
     * 打开指定 Shell 命名空间。
     *
     * @param directory Shell 路径或命名空间标识。
     * @return 对应 Shell 文件夹；不存在时返回 null。
     */
    @ComMethod(name = "NameSpace")
    fun nameSpace(directory: String): WindowsShellFolder?
}

/** Windows Shell 文件夹自动化对象。 */
internal interface WindowsShellFolder {
    /**
     * 读取文件夹中的 Shell 条目集合。
     *
     * @return Shell 条目集合。
     */
    @ComMethod(name = "Items")
    fun items(): WindowsShellFolderItems
}

/** Windows Shell 文件夹条目集合。 */
internal interface WindowsShellFolderItems {
    /**
     * 读取条目数量。
     *
     * @return 当前集合大小。
     */
    @ComProperty(name = "Count")
    fun count(): Int

    /**
     * 按索引读取 Shell 条目。
     *
     * @param index 从零开始的条目索引。
     * @return 对应条目；索引无效时返回 null。
     */
    @ComMethod(name = "Item")
    fun item(index: Int): WindowsShellFolderItem?
}

/** Windows Shell 单个文件夹条目。 */
internal interface WindowsShellFolderItem {
    /**
     * 读取资源管理器显示名称。
     *
     * @return 当前条目的系统显示名称。
     */
    @ComProperty(name = "Name")
    fun name(): String

    /**
     * 读取文件系统路径。
     *
     * @return 当前条目的文件系统路径。
     */
    @ComProperty(name = "Path")
    fun path(): String

    /**
     * 判断 Shell 条目是否表现为文件夹。
     *
     * @return 文件夹条目返回 true。
     */
    @ComProperty(name = "IsFolder")
    fun isFolder(): Boolean

    /**
     * 判断 Shell 条目是否映射到文件系统。
     *
     * @return 文件系统条目返回 true。
     */
    @ComProperty(name = "IsFileSystem")
    fun isFileSystem(): Boolean
}

/** Windows 10/11 Home 与快速访问共用的 Shell 命名空间。 */
private const val WINDOWS_QUICK_ACCESS_NAMESPACE = "shell:::{679f85cb-0220-4080-b29b-5540cc05aab6}"
