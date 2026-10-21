package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.StorageDeviceState
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileCapability
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.vfs.api.StorageDeviceService
import com.oruke.onyx.vfs.api.VfsProtocol
import com.oruke.onyx.vfs.api.VfsProvider
import com.oruke.onyx.vfs.api.VfsProviderCapability

/**
 * 将系统概览接入统一 VFS 路由；概览只列举真实已挂载入口，未挂载设备由组件模型表达。
 *
 * @param devices 平台设备发现接口。
 */
internal class SystemLocationVfsProvider(
    /** 平台设备发现和操作服务。 */
    private val devices: StorageDeviceService
) : VfsProvider {
    /** 系统位置使用独立协议，不能落入本地路径写入服务。 */
    override val protocol = VfsProtocol.SYSTEM
    /** 概览是导航容器，没有新建、粘贴、删除或移动目标能力。 */
    override val capabilities = emptySet<VfsProviderCapability>()

    /**
     * 判断当前 Provider 是否处理该位置。
     *
     * @param location 标准 VFS 位置。
     * @return 此电脑概览返回 true。
     */
    override fun supports(location: String): Boolean = isComputerLocation(location)

    /**
     * 为统一仓库提供可浏览的真实挂载入口，不探测或遍历磁盘内容。
     *
     * @param location 系统概览位置。
     * @return 已挂载卷的真实目录条目。
     */
    override suspend fun list(location: String): Result<List<VFile>> =
        devices.snapshot().map { entries ->
            entries
                .filter { it.state == StorageDeviceState.MOUNTED }
                .flatMap { device ->
                    device.locations.map { path ->
                        VFile(
                            path,
                            device.name,
                            path,
                            null,
                            VFileKind.DIRECTORY,
                            null,
                            null,
                            false,
                            setOf(VFileCapability.LIST_CHILDREN, VFileCapability.READ_METADATA),
                            identityKey = device.id,
                        )
                    }
                }
        }

    /**
     * 系统位置不递归统计整块磁盘，目录本身没有文件长度。
     *
     * @param entries 当前系统位置的入口条目。
     * @return 概览条目的直接文件大小之和。
     */
    override suspend fun totalSizeBytes(entries: List<VFile>): Result<Long> =
        Result.success(entries.sumOf { it.sizeBytes ?: 0 })
}
