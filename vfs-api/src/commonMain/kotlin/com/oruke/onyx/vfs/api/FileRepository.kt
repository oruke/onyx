package com.oruke.onyx.vfs.api

import com.oruke.onyx.core.model.VFile

/**
 * 文件列表仓储，负责按 VFS 位置读取目录内容并提供默认入口位置。
 */
interface FileRepository {
    /**
     * 读取指定位置的直接子项。
     *
     * @param location 需要列出的 VFS 位置。
     * @return 目录子项列表，失败时返回明确异常。
     */
    suspend fun list(location: String): Result<List<VFile>>

    /**
     * 获取当前平台或配置下的默认文件入口。
     *
     * @return 默认 VFS 位置。
     */
    fun defaultLocation(): String
}
