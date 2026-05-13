package com.oruke.onyx.app.usecase

import com.oruke.onyx.app.filesystem.RoutableVfsContentService
import com.oruke.onyx.app.filesystem.TransferConflictStrategy
import com.oruke.onyx.app.filesystem.VfsContentSource
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileCapability
import com.oruke.onyx.core.model.VFileKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 统一 VFS 内容搜索服务测试。
 */
class JvmVfsFileContentSearchServiceTest {
    /**
     * 校验远程 provider 的内容服务也会参与 `content:` 搜索。
     *
     * @return 无返回值。
     */
    @Test
    fun matchesRemoteContentThroughRoutableContentService() = runBlocking {
        val remoteContentService = FakeContentService("webdav://")
        val entry = file("webdav://server/docs/readme.txt", sizeBytes = 18)
        remoteContentService.seed(entry.location, "Alpha Beta Content")
        val searchService = JvmVfsFileContentSearchService(listOf(remoteContentService))

        val result = searchService.contains(entry = entry, query = "beta", maxBytes = 1024)

        assertTrue(searchService.supportsLocation("webdav://server/docs"))
        assertTrue(searchService.supports(entry))
        assertTrue(result.getOrThrow())
    }

    /**
     * 校验目录条目不会被内容搜索服务误判为可读文件。
     *
     * @return 无返回值。
     */
    @Test
    fun doesNotSupportDirectoryContentSearch() {
        val remoteContentService = FakeContentService("s3://")
        val searchService = JvmVfsFileContentSearchService(listOf(remoteContentService))

        val supported = searchService.supports(directory("s3://bucket/folder"))

        assertFalse(supported)
    }

    /**
     * 校验内容大小超过限制时不会继续读取并匹配。
     *
     * @return 无返回值。
     */
    @Test
    fun returnsFalseWhenSourceExceedsMaxBytes() = runBlocking {
        val remoteContentService = FakeContentService("s3://")
        val entry = file("s3://bucket/large.txt", sizeBytes = null)
        remoteContentService.seed(entry.location, "0123456789", reportedSizeBytes = 10)
        val searchService = JvmVfsFileContentSearchService(listOf(remoteContentService))

        val result = searchService.contains(entry = entry, query = "789", maxBytes = 4)

        assertTrue(result.isSuccess)
        assertFalse(result.getOrThrow())
    }

    private companion object {
        /**
         * 构造测试文件条目。
         *
         * @param location 文件位置。
         * @param sizeBytes 文件大小。
         * @return 测试文件条目。
         */
        fun file(
            location: String,
            sizeBytes: Long?,
        ): VFile = VFile(
            id = location,
            name = location.substringAfterLast('/'),
            location = location,
            parentLocation = location.substringBeforeLast('/', missingDelimiterValue = ""),
            kind = VFileKind.FILE,
            sizeBytes = sizeBytes,
            modifiedAtEpochMillis = null,
            hidden = false,
            capabilities = setOf(VFileCapability.READ_CONTENT),
        )

        /**
         * 构造测试目录条目。
         *
         * @param location 目录位置。
         * @return 测试目录条目。
         */
        fun directory(location: String): VFile = VFile(
            id = location,
            name = location.substringAfterLast('/'),
            location = location,
            parentLocation = location.substringBeforeLast('/', missingDelimiterValue = ""),
            kind = VFileKind.DIRECTORY,
            sizeBytes = null,
            modifiedAtEpochMillis = null,
            hidden = false,
            capabilities = setOf(VFileCapability.LIST_CHILDREN),
        )
    }
}

/**
 * 测试用可路由内容服务。
 *
 * @property locationPrefix 支持的位置前缀。
 */
private class FakeContentService(
    private val locationPrefix: String,
) : RoutableVfsContentService {
    private val contents = mutableMapOf<String, ContentRecord>()

    /**
     * 写入测试内容。
     *
     * @param location 文件位置。
     * @param text 文件文本。
     * @param reportedSizeBytes 内容服务报告的大小。
     */
    fun seed(
        location: String,
        text: String,
        reportedSizeBytes: Long? = text.encodeToByteArray().size.toLong(),
    ) {
        contents[location] = ContentRecord(text = text, reportedSizeBytes = reportedSizeBytes)
    }

    /**
     * 判断位置是否由当前内容服务处理。
     *
     * @param location 待判断位置。
     * @return 是否支持。
     */
    override fun supports(location: String): Boolean = location.startsWith(locationPrefix)

    /**
     * 读取测试内容。
     *
     * @param entry 待读取文件条目。
     * @return 内容源。
     */
    override suspend fun readFile(entry: VFile): Result<VfsContentSource> {
        val record = contents[entry.location]
            ?: return Result.failure(IllegalStateException("Missing fake content: ${entry.location}"))
        return Result.success(
            VfsContentSource(
                name = entry.name,
                sizeBytes = record.reportedSizeBytes,
                chunks = flowOf(record.text.encodeToByteArray()),
            )
        )
    }

    /**
     * 测试不覆盖写入路径。
     *
     * @param parentLocation 父目录位置。
     * @param name 文件名。
     * @param chunks 内容块。
     * @param conflictStrategy 冲突策略。
     * @return 失败结果。
     */
    override suspend fun writeFile(
        parentLocation: String,
        name: String,
        chunks: Flow<ByteArray>,
        conflictStrategy: TransferConflictStrategy,
    ): Result<VFile?> = Result.failure(UnsupportedOperationException("write is not used"))
}

/**
 * 测试内容记录。
 *
 * @property text 文件文本。
 * @property reportedSizeBytes 内容服务报告的大小。
 */
private data class ContentRecord(
    val text: String,
    val reportedSizeBytes: Long?,
)
