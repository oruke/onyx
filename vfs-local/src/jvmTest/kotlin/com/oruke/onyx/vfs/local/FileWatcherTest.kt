package com.oruke.onyx.vfs.local

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 文件监听器生命周期与事件映射测试。
 */
class FileWatcherTest {
    /**
     * 创建文件后应收到完整路径事件，收集结束后监听任务应可正常取消。
     */
    @Test
    fun emitsChangedPathAndStopsAfterCollection() = runBlocking {
        val directory = Files.createTempDirectory("onyx-file-watcher-test")
        try {
            val expected = directory.resolve("created.txt")
            val event = async {
                withTimeout(WATCH_TEST_TIMEOUT_MILLIS) {
                    FileWatcher().watch(directory).first { candidate ->
                        candidate.path == expected
                    }
                }
            }
            delay(WATCH_REGISTRATION_DELAY_MILLIS)
            Files.writeString(expected, "content")

            assertEquals(expected, event.await().path)
        } finally {
            LocalPathTransferOperations.deletePathRecursively(directory)
        }
    }

    /** 测试等待监听器注册的毫秒数。 */
    private companion object {
        const val WATCH_REGISTRATION_DELAY_MILLIS = 150L
        const val WATCH_TEST_TIMEOUT_MILLIS = 5_000L
    }
}
