package com.oruke.onyx.app.filesystem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 压缩包服务基础行为测试。
 */
class ArchiveServiceTest {
    /**
     * 校验 zstd tar 复合扩展名能进入压缩包 provider。
     *
     * @return 无返回值。
     */
    @Test
    fun recognizesTarZstdArchives() {
        assertTrue(ArchiveService.isArchive("backup.tar.zst"))
        assertTrue(ArchiveService.isArchive("backup.TZST"))
    }

    /**
     * 校验 `archive://` 解析能识别 `.tar.zst` 路径边界。
     *
     * @return 无返回值。
     */
    @Test
    fun parsesTarZstdArchiveLocations() {
        val parsed = ArchiveService.parseArchiveLocation("archive://C:/tmp/backup.tar.zst!/src/main")

        assertEquals("C:/tmp/backup.tar.zst", parsed?.first)
        assertEquals("src/main", parsed?.second)
    }
}
