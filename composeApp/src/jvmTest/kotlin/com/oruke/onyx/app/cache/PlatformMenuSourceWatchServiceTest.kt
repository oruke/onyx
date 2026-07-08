package com.oruke.onyx.app.cache

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 平台菜单来源监听服务测试，验证真实目录事件和超时行为。 */
class PlatformMenuSourceWatchServiceTest {
    /**
     * 验证监听目录发生文件创建时会返回变化事件。
     */
    @Test
    fun awaitChangeReturnsTrueWhenDirectoryChanges() = runBlocking {
        val sourceDirectory = createTempDirectory(prefix = "onyx-menu-watch-")
        val service = PlatformMenuSourceWatchService { listOf(sourceDirectory) }

        val watchResult = async {
            service.awaitChange(timeoutMillis = 5_000L)
        }
        delay(250L)
        Files.writeString(sourceDirectory.resolve("demo.desktop"), "[Desktop Entry]")

        assertTrue(withTimeout(5_000L) { watchResult.await() })
    }

    /**
     * 验证没有文件事件时监听会按超时时间返回 `false`。
     */
    @Test
    fun awaitChangeReturnsFalseWhenNoEventArrives() = runBlocking {
        val sourceDirectory = createTempDirectory(prefix = "onyx-menu-watch-timeout-")
        val service = PlatformMenuSourceWatchService { listOf(sourceDirectory) }

        assertFalse(service.awaitChange(timeoutMillis = 100L))
    }
}
