package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.VFile

/**
 * 组合式 FileRepository — 根据 location 前缀路由到不同的文件提供者。
 *
 * 当 location 以 "archive://" 开头时，委托给 ArchiveService；
 * 否则委托给 JvmLocalFileProvider。
 */
class CompositeFileRepository(
    private val localProvider: JvmLocalFileProvider,
    private val archiveService: ArchiveService,
) : FileRepository {

    override suspend fun list(location: String): Result<List<VFile>> {
        val parsed = ArchiveService.parseArchiveLocation(location)
        return if (parsed != null) {
            archiveService.list(parsed.first, parsed.second)
        } else {
            localProvider.list(location)
        }
    }

    override fun defaultLocation(): String = localProvider.defaultLocation()
}
