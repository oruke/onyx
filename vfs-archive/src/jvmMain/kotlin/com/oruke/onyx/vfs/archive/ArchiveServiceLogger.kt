package com.oruke.onyx.vfs.archive

/**
 * 压缩包服务的轻量日志出口。
 */
interface ArchiveServiceLogger {
    /**
     * 记录可恢复的压缩包处理警告。
     *
     * @param tag 日志来源标签。
     * @param message 警告内容。
     * @param throwable 触发警告的异常。
     */
    fun warn(
        tag: String,
        message: String,
        throwable: Throwable,
    )

    /**
     * 记录不可恢复或影响用户操作结果的压缩包错误。
     *
     * @param tag 日志来源标签。
     * @param message 错误内容。
     * @param throwable 触发错误的异常。
     */
    fun error(
        tag: String,
        message: String,
        throwable: Throwable,
    )

    /**
     * 默认空实现，用于未接入日志系统的调用路径。
     */
    data object NoOp : ArchiveServiceLogger {
        override fun warn(
            tag: String,
            message: String,
            throwable: Throwable,
        ) = Unit

        override fun error(
            tag: String,
            message: String,
            throwable: Throwable,
        ) = Unit
    }
}
