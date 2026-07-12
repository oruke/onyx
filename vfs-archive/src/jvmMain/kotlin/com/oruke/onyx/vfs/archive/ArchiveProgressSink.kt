package com.oruke.onyx.vfs.archive

/** 归档解压字节进度接收器。 */
fun interface ArchiveProgressSink {
    /**
     * 接收当前解压操作的绝对字节进度。
     *
     * @param completedBytes 已完成解压字节数。
     * @param totalBytes 当前解压操作总字节数。
     */
    fun onProgress(
        completedBytes: Long,
        totalBytes: Long,
    )

    /** 不需要解压进度时使用的空实现。 */
    object NoOp : ArchiveProgressSink {
        /**
         * 忽略解压字节进度。
         *
         * @param completedBytes 已完成解压字节数。
         * @param totalBytes 当前解压操作总字节数。
         */
        override fun onProgress(
            completedBytes: Long,
            totalBytes: Long,
        ) = Unit
    }
}
