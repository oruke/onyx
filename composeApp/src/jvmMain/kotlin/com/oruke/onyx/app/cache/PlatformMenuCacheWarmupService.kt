package com.oruke.onyx.app.cache

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileCapability
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.vfs.api.SystemMenuService
import java.nio.file.Files
import java.nio.file.Path

/** 平台菜单缓存预热服务，负责在启动阶段填充常见菜单作用域。 */
internal class PlatformMenuCacheWarmupService(
    private val systemMenuService: SystemMenuService,
) {
    /**
     * 预热本机目录右键菜单缓存。
     *
     * @return 预热结果；失败时由调用方记录日志，不阻断应用启动。
     */
    suspend fun warmUp(): Result<Unit> {
        return runCatching {
            systemMenuService.listActions(listOf(homeDirectoryEntry()))
            Unit
        }
    }

    /**
     * 构造用户 home 目录对应的 VFile，用于生成稳定的目录菜单缓存作用域。
     *
     * @return 指向用户 home 目录的本地 VFile。
     */
    private fun homeDirectoryEntry(): VFile {
        val homePath = Path.of(System.getProperty("user.home"))
        return VFile(
            id = "warmup:${homePath.toAbsolutePath()}",
            name = homePath.fileName?.toString() ?: homePath.toString(),
            location = homePath.toAbsolutePath().toString(),
            parentLocation = homePath.parent?.toAbsolutePath()?.toString(),
            kind = VFileKind.DIRECTORY,
            sizeBytes = null,
            modifiedAtEpochMillis = runCatching {
                Files.getLastModifiedTime(homePath).toMillis()
            }.getOrNull(),
            hidden = false,
            capabilities = setOf(VFileCapability.LIST_CHILDREN, VFileCapability.READ_METADATA),
        )
    }
}
