package com.oruke.onyx.vfs.local

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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
            val registered = CompletableDeferred<Unit>()
            val event = async {
                withTimeout(WATCH_TEST_TIMEOUT_MILLIS) {
                    FileWatcher().watch(directory) { registered.complete(Unit) }.first { candidate ->
                        candidate.path == expected
                    }
                }
            }
            withTimeout(WATCH_TEST_TIMEOUT_MILLIS) { registered.await() }
            Files.writeString(expected, "content")

            assertEquals(expected, event.await().path)
        } finally {
            LocalPathTransferOperations.deletePathRecursively(directory)
        }
    }

    /** 测试等待监听器注册和文件事件的最大毫秒数。 */
    private companion object {
        const val WATCH_TEST_TIMEOUT_MILLIS = 5_000L
    }
}
