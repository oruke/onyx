package com.oruke.onyx.app.filesystem

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileCapability
import com.oruke.onyx.core.model.VFileKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * JVM 系统文件物化服务测试。
 */
class JvmSystemFileMaterializerTest {
    /**
     * 校验远程内容文件会导出为可交给系统 API 的本地临时文件。
     *
     * @return 无返回值。
     */
    @Test
    fun materializesRemoteContentToSystemFile() = runBlocking {
        val contentService = FakeContentService("s3://")
        val entry = file(location = "s3://bucket/docs/note.txt", name = "note.txt")
        contentService.seed(entry.location, "hello remote")
        val materializer = JvmSystemFileMaterializer(
            archiveService = ArchiveService(),
            contentServices = listOf(contentService),
        )

        val materialized = materializer.materialize(entry).getOrThrow()
        val path = Path.of(materialized.location)

        assertTrue(Files.isRegularFile(path))
        assertEquals("hello remote", Files.readString(path))
        assertEquals("note.txt", path.fileName.toString())
    }

    /**
     * 校验同一个远程条目会复用已经物化成功的临时文件。
     *
     * @return 无返回值。
     */
    @Test
    fun reusesCachedMaterializedFile() = runBlocking {
        val contentService = FakeContentService("webdav://")
        val entry = file(location = "webdav://server/docs/readme.md", name = "readme.md")
        contentService.seed(entry.location, "cached")
        val materializer = JvmSystemFileMaterializer(
            archiveService = ArchiveService(),
            contentServices = listOf(contentService),
        )

        val first = materializer.materialize(entry).getOrThrow()
        val second = materializer.materialize(entry).getOrThrow()

        assertEquals(first.location, second.location)
        assertEquals(1, contentService.readCount)
    }

    /**
     * 校验远程目录不会被作为系统文件物化。
     *
     * @return 无返回值。
     */
    @Test
    fun doesNotSupportRemoteDirectory() {
        val contentService = FakeContentService("s3://")
        val materializer = JvmSystemFileMaterializer(
            archiveService = ArchiveService(),
            contentServices = listOf(contentService),
        )

        val supported = materializer.supports(directory("s3://bucket/docs/"))

        assertFalse(supported)
    }

    private companion object {
        /**
         * 构造测试文件条目。
         *
         * @param location 文件位置。
         * @param name 文件名。
         * @return 测试文件条目。
         */
        fun file(
            location: String,
            name: String,
        ): VFile = VFile(
            id = location,
            name = name,
            location = location,
            parentLocation = location.substringBeforeLast('/', missingDelimiterValue = ""),
            kind = VFileKind.FILE,
            sizeBytes = null,
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
            name = location.trimEnd('/').substringAfterLast('/'),
            location = location,
            parentLocation = location.trimEnd('/').substringBeforeLast('/', missingDelimiterValue = ""),
            kind = VFileKind.DIRECTORY,
            sizeBytes = null,
            modifiedAtEpochMillis = null,
            hidden = false,
            capabilities = setOf(VFileCapability.LIST_CHILDREN),
        )
    }
}

/**
 * 测试用内容读取服务。
 *
 * @property locationPrefix 支持的位置前缀。
 */
private class FakeContentService(
    private val locationPrefix: String,
) : RoutableVfsContentService {
    private val contents = mutableMapOf<String, String>()
    var readCount: Int = 0
        private set

    /**
     * 写入可读测试内容。
     *
     * @param location 文件位置。
     * @param text 文件文本。
     */
    fun seed(
        location: String,
        text: String,
    ) {
        contents[location] = text
    }

    /**
     * 判断位置是否由当前测试服务处理。
     *
     * @param location 待判断位置。
     * @return 是否支持。
     */
    override fun supports(location: String): Boolean = location.startsWith(locationPrefix)

    /**
     * 读取测试内容。
     *
     * @param entry 待读取条目。
     * @return 内容源。
     */
    override suspend fun readFile(entry: VFile): Result<VfsContentSource> {
        readCount += 1
        val text = contents[entry.location]
            ?: return Result.failure(IllegalStateException("Missing fake content: ${entry.location}"))
        return Result.success(
            VfsContentSource(
                name = entry.name,
                sizeBytes = text.encodeToByteArray().size.toLong(),
                chunks = flowOf(text.encodeToByteArray()),
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
