package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.VFile

/**
 * 外部打开服务，负责把文件交给系统默认应用或平台打开通道。
 */
interface ExternalOpenService {
    /**
     * 打开指定文件条目。
     *
     * @param entry 待打开条目。
     * @return 操作结果。
     */
    suspend fun open(entry: VFile): Result<Unit>
}

/**
 * 文本剪贴板服务，隔离平台剪贴板实现。
 */
interface TextClipboardService {
    /**
     * 复制文本到系统剪贴板。
     *
     * @param text 待复制文本。
     * @return 操作结果。
     */
    suspend fun copyText(text: String): Result<Unit>
}

/**
 * 回收站服务，负责平台可恢复删除能力。
 */
interface TrashService {
    /** 当前平台是否支持移动到回收站。 */
    val isSupported: Boolean

    /**
     * 将条目移动到回收站。
     *
     * @param entries 待处理条目。
     * @return 操作结果。
     */
    suspend fun moveToTrash(entries: List<VFile>): Result<Unit>
}

/**
 * 系统文件物化服务，用于把非本地 VFS 条目导出为可交给外部系统的本地文件。
 */
interface SystemFileMaterializer {
    /**
     * 判断条目是否支持物化。
     *
     * @param entry 待检查条目。
     * @return 支持时返回 true。
     */
    fun supports(entry: VFile): Boolean

    /**
     * 将条目物化为系统本地文件。
     *
     * @param entry 待物化条目。
     * @return 物化后的本地条目。
     */
    suspend fun materialize(entry: VFile): Result<VFile>
}
