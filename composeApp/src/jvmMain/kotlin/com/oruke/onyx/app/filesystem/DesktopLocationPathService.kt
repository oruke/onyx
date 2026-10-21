package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.COMPUTER_LOCATION
import com.oruke.onyx.core.model.storageDriveDesignator
import com.oruke.onyx.core.model.volumeLabel
import com.oruke.onyx.vfs.api.StorageDeviceService

/**
 * 在现有路径服务边界增加桌面虚拟位置，物理路径关系仍由原服务负责。
 *
 * @param paths 本地、归档与远程文件系统的原有路径服务。
 * @param storageDevices 已发现的设备名称，不在同步路径解析中执行设备查询。
 */
internal class DesktopLocationPathService(
    /** 原有路径解析服务，保留所有协议的物理语义。 */
    private val paths: VfsPathService = JvmVfsPathService(),
    /** 最近成功的设备名称快照，与异步设备操作边界分开使用。 */
    private val storageDevices: StorageDeviceService? = null,
) : VfsPathService by paths {
    /**
     * 识别稳定虚拟位置及 Windows 此电脑的标准 Shell 别名。
     *
     * @param location 用户输入或持久化位置。
     * @return 规范化虚拟位置或原有文件系统位置。
     */
    override fun normalizeLocation(location: String): String =
        if (isComputerLocation(location)) COMPUTER_LOCATION else paths.normalizeLocation(location)

    /**
     * 虚拟概览没有物理父目录，不能参与文件传输的祖先关系判断。
     *
     * @param location 当前位置。
     * @return 文件系统物理父级；概览为空。
     */
    override fun parentLocation(location: String): String? =
        if (isComputerLocation(location)) null else paths.parentLocation(location)

    /**
     * 本地卷和 UNC 共享根目录向上返回概览，远程协议保留自己的根层级。
     *
     * @param location 当前浏览位置。
     * @return 界面导航父级。
     */
    override fun navigationParent(location: String): String? =
        when {
            isComputerLocation(location) -> null
            paths.parentLocation(location) != null -> paths.parentLocation(location)
            !location.contains("://") -> COMPUTER_LOCATION
            else -> null
        }

    /**
     * 虚拟位置标题以稳定位置作为标识，最终显示名称在 UI 资源层本地化。
     *
     * @param location 当前标签位置。
     * @return 标题标识或文件系统标题。
     */
    override fun title(location: String): String =
        if (isComputerLocation(location)) COMPUTER_LOCATION else paths.title(location)

    /**
     * 为收藏、最近位置提供可本地化的稳定标题。
     *
     * @param location 待显示位置。
     * @return 虚拟位置标识或原有位置名称。
     */
    override fun label(location: String): String =
        if (isComputerLocation(location)) COMPUTER_LOCATION else paths.label(location)

    /**
     * 系统概览不是文件系统目录，不能用于猜测直接子项名称。
     *
     * @param ancestor 物理祖先位置。
     * @param descendant 物理后代位置。
     * @return 普通文件系统中的直接子项名称，涉及概览时为空。
     */
    override fun directChildName(ancestor: String, descendant: String): String? =
        if (isComputerLocation(ancestor) || isComputerLocation(descendant)) null
        else paths.directChildName(ancestor, descendant)

    /**
     * 保持文件操作的物理父级关系，设备概览没有文件系统子项。
     *
     * @param parent 物理父位置。
     * @param child 物理子位置。
     * @return 确认存在物理直接父子关系时返回 true。
     */
    override fun isDirectParent(parent: String, child: String): Boolean =
        !isComputerLocation(parent) &&
            !isComputerLocation(child) &&
            paths.isDirectParent(parent, child)

    /**
     * 不将导航聚合关系解释为物理目录祖先关系。
     *
     * @param location 待判断位置。
     * @param parentLocation 物理祖先位置。
     * @return 普通文件系统中的相同或后代位置返回 true。
     */
    override fun isSameOrChildOf(location: String, parentLocation: String): Boolean =
        !isComputerLocation(location) &&
            !isComputerLocation(parentLocation) &&
            paths.isSameOrChildOf(location, parentLocation)

    /**
     * 在统一路径边界拒绝把系统概览当作文件传输源或目标。
     *
     * @param sourceLocation 源位置。
     * @param targetLocation 目标位置。
     * @return 普通存储位置的传输策略；概览位置返回本地化失败。
     */
    override fun resolveTransferOperation(
        sourceLocation: String,
        targetLocation: String,
    ): com.oruke.onyx.core.model.FileTransferOperation {
        val computer = listOf(sourceLocation, targetLocation).firstOrNull(::isComputerLocation)
        if (computer != null)
            throw com.oruke.onyx.core.model.LocalizedException(
                com.oruke.onyx.core.model.I18nMessage(
                    com.oruke.onyx.core.model.MessageKey.MSG_LOCATION_NOT_DIRECTORY,
                    computer,
                )
            )
        return paths.resolveTransferOperation(sourceLocation, targetLocation)
    }

    /**
     * 概览始终是有效的应用内入口，与设备是否在线分开判断。
     *
     * @param location 会话或导航需要验证的位置。
     * @return 虚拟概览或当前文件系统位置可用时返回 true。
     */
    override fun isLocationAvailable(location: String): Boolean =
        isComputerLocation(location) || paths.isLocationAvailable(location)

    /**
     * 为本地路径添加设备概览面包屑，远程和归档沿用原有层级。
     *
     * @param location 当前浏览位置。
     * @return 导航面包屑，虚拟位置名称由渲染层翻译。
     */
    override fun buildBreadcrumbs(location: String): List<VfsBreadcrumb> {
        val computer = VfsBreadcrumb(COMPUTER_LOCATION, COMPUTER_LOCATION)
        return when {
            isComputerLocation(location) -> listOf(computer)
            !location.contains("://") -> {
                val breadcrumbs = paths.buildBreadcrumbs(location)
                val root = breadcrumbs.firstOrNull() ?: return listOf(computer)
                val device = storageDevices?.cachedSnapshot()?.firstOrNull { item ->
                    item.locations.any { it.equals(root.location, ignoreCase = true) }
                }
                listOf(computer, root.copy(
                    label = storageDriveDesignator(root.location) ?: root.label,
                    storageLabel = device?.volumeLabel(),
                )) + breadcrumbs.drop(1)
            }
            else -> paths.buildBreadcrumbs(location)
        }
    }
}

/**
 * 识别统一概览位置与 Windows 规范 Shell 入口，不依赖系统语言或用户目录。
 *
 * @param location 原始位置文本。
 * @return 表示此电脑或电脑概览时返回 true。
 */
internal fun isComputerLocation(location: String): Boolean =
    location.trim().trimEnd('/').let { value ->
        value.equals(COMPUTER_LOCATION, ignoreCase = true) ||
            value.equals("shell:MyComputerFolder", ignoreCase = true) ||
            value.equals("shell:::{20D04FE0-3AEA-1069-A2D8-08002B30309D}", ignoreCase = true)
    }
