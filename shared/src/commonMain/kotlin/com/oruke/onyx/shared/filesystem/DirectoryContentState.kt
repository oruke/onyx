package com.oruke.onyx.shared.filesystem

import com.oruke.onyx.core.model.DirectoryChange
import com.oruke.onyx.core.model.DirectoryRevisionChange
import com.oruke.onyx.core.model.VFile
import kotlinx.coroutines.flow.StateFlow

/** 一个目录的共享内容和同步状态，不携带面板的选择、排序与滚动。 */
data class DirectoryContentState(
    /** 最近一次成功快照；null 表示尚未读取，空列表表示成功读取空目录。 */
    val entries: List<VFile>? = null,
    /** 是否正在执行需要前台提示的读取或刷新。 */
    val refreshing: Boolean = false,
    /** 最近读取失败原因，保留旧快照供用户恢复操作。 */
    val failure: Throwable? = null,
    /** 监听不可用原因，与目录读取失败分开表达。 */
    val observationFailure: Throwable? = null,
    /** 内容版本，仅真实内容变化时递增。 */
    val revision: Long = 0,
    /** 当前内容版本包含的已确认身份变化。 */
    val change: DirectoryChange? = null,
    /** 有界身份变化日志，订阅者按自身内容版本重放。 */
    val changeLog: List<DirectoryRevisionChange> = emptyList(),
)

/**
 * 目录订阅句柄，最后一个订阅释放时关闭底层观察资源。
 * @param state 共享目录状态流。
 * @param release 释放引用的动作，必须在所属作用域线程执行。
 */
class DirectorySubscription(
    /** 当前共享目录状态。 */
    val state: StateFlow<DirectoryContentState>,
    /** 释放当前订阅引用的动作。 */
    private val release: () -> Unit,
) {
    /** 句柄是否已经释放，避免重复关闭导致引用计数出错。 */
    private var closed = false

    /** 释放本次订阅；重复关闭不重复减少引用。 */
    fun close() {
        if (!closed) {
            closed = true
            release()
        }
    }
}
