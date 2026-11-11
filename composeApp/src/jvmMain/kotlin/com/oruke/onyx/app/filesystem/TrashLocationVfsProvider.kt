package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.isTrashItemLocation
import com.oruke.onyx.core.model.isTrashLocation
import com.oruke.onyx.vfs.api.TrashItem
import com.oruke.onyx.vfs.api.TrashService
import com.oruke.onyx.vfs.api.VfsProtocol
import com.oruke.onyx.vfs.api.VfsProvider
import com.oruke.onyx.vfs.api.VfsProviderCapability
import com.oruke.onyx.vfs.api.VfsProviderError
import com.oruke.onyx.vfs.api.VfsProviderException

/**
 * 将回收站容器（`system://trash`）及其单条目虚拟位置（`system://trash/item/...`）接入统一 VFS 路由。
 *
 * - 枚举 `system://trash` 时委托 [TrashService.listItems] 并转换为只读导航能力的 [VFile] 列表；
 * - 单条目虚拟位置同样由本 Provider 声明支持，保证 [VfsProviderRegistry.totalSizeBytes] 等按条目路由的操作
 *   不会抛出 [com.oruke.onyx.vfs.api.VfsProviderNotFoundException] 或误落入本地文件系统。
 *
 * @param trashService 跨平台回收站服务。
 */
internal class TrashLocationVfsProvider(
    private val trashService: TrashService,
) : VfsProvider {

    override val protocol: VfsProtocol = VfsProtocol.SYSTEM

    override val capabilities: Set<VfsProviderCapability> = emptySet()

    override fun supports(location: String): Boolean =
        isTrashLocation(location) || isTrashItemLocation(location)

    override suspend fun list(location: String): Result<List<VFile>> {
        if (!isTrashLocation(location)) {
            return Result.failure(
                VfsProviderException(
                    VfsProviderError.UnsupportedOperation(
                        protocol = VfsProtocol.SYSTEM,
                        location = location,
                    ),
                ),
            )
        }
        return trashService.listItems().map { items -> items.map(TrashItem::toVFile) }
    }

    override suspend fun totalSizeBytes(entries: List<VFile>): Result<Long> =
        Result.success(entries.sumOf { entry -> entry.sizeBytes ?: 0L })
}
