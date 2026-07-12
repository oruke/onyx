package com.oruke.onyx.vfs.api

import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileCapability
import com.oruke.onyx.core.model.VFileKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Provider 路由文件命令服务测试。
 */
class ProviderBackedFileCommandServiceTest {
    /**
     * 校验跨 Provider 内容流会把实际消费的字节增量传递给任务层。
     *
     * @return 无返回值。
     */
    @Test
    fun reportsBytesWhileCopyingAcrossProviders() = runBlocking {
        val sourceEntry = file(
            name = "payload.txt",
            location = "$SOURCE_PREFIX/payload.txt",
            content = "progress",
        )
        val sourceProvider = FakeProvider(
            protocol = VfsProtocol.LOCAL,
            locationPrefix = SOURCE_PREFIX,
            entries = emptyMap(),
        )
        val targetProvider = FakeProvider(
            protocol = VfsProtocol.WEBDAV,
            locationPrefix = TARGET_PREFIX,
            entries = emptyMap(),
        )
        val sourceContentService = FakeContentService(SOURCE_PREFIX)
        sourceContentService.seed(sourceEntry.location, "progress")
        val commandService = ProviderBackedFileCommandService(
            services = listOf(FakeCommandService(SOURCE_PREFIX), FakeCommandService(TARGET_PREFIX)),
            contentServices = listOf(sourceContentService, FakeContentService(TARGET_PREFIX)),
            providerRegistry = VfsProviderRegistry(listOf(sourceProvider, targetProvider)),
        )
        var reportedBytes = 0L

        val result = commandService.copyWithProgress(
            entries = listOf(sourceEntry),
            targetDirectoryLocation = "$TARGET_PREFIX/dest",
            conflictStrategy = TransferConflictStrategy.KEEP_BOTH,
            progressSink = { byteCount -> reportedBytes += byteCount },
        )

        assertTrue(result.isSuccess)
        assertEquals("progress".encodeToByteArray().size.toLong(), reportedBytes)
    }

    /**
     * 校验跨 provider 复制目录时会递归创建目录并写入子文件。
     *
     * @return 无返回值。
     */
    @Test
    fun copiesDirectoriesAcrossProvidersRecursively() = runBlocking {
        val sourceProvider = FakeProvider(
            protocol = VfsProtocol.LOCAL,
            locationPrefix = SOURCE_PREFIX,
            entries = mapOf(
                "$SOURCE_PREFIX/root" to listOf(
                    directory(name = "nested", location = "$SOURCE_PREFIX/root/nested"),
                    file(name = "a.txt", location = "$SOURCE_PREFIX/root/a.txt", content = "alpha"),
                ),
                "$SOURCE_PREFIX/root/nested" to listOf(
                    file(name = "b.txt", location = "$SOURCE_PREFIX/root/nested/b.txt", content = "beta"),
                ),
            ),
        )
        val targetProvider = FakeProvider(
            protocol = VfsProtocol.WEBDAV,
            locationPrefix = TARGET_PREFIX,
            entries = emptyMap(),
        )
        val sourceCommandService = FakeCommandService(SOURCE_PREFIX)
        val targetCommandService = FakeCommandService(TARGET_PREFIX)
        val sourceContentService = FakeContentService(SOURCE_PREFIX)
        sourceContentService.seed("$SOURCE_PREFIX/root/a.txt", "alpha")
        sourceContentService.seed("$SOURCE_PREFIX/root/nested/b.txt", "beta")
        val targetContentService = FakeContentService(TARGET_PREFIX)
        val commandService = ProviderBackedFileCommandService(
            services = listOf(sourceCommandService, targetCommandService),
            contentServices = listOf(sourceContentService, targetContentService),
            providerRegistry = VfsProviderRegistry(listOf(sourceProvider, targetProvider)),
        )

        val result = commandService.copy(
            entries = listOf(directory(name = "root", location = "$SOURCE_PREFIX/root")),
            targetDirectoryLocation = "$TARGET_PREFIX/dest",
        )

        assertTrue(result.isSuccess)
        assertEquals(listOf("$TARGET_PREFIX/dest/root", "$TARGET_PREFIX/dest/root/nested"), targetCommandService.createdDirectories)
        assertEquals("alpha", targetContentService.writtenText["$TARGET_PREFIX/dest/root/a.txt"])
        assertEquals("beta", targetContentService.writtenText["$TARGET_PREFIX/dest/root/nested/b.txt"])
    }

    /**
     * 校验跨 provider 移动目录时会在复制成功后删除源目录。
     *
     * @return 无返回值。
     */
    @Test
    fun movesDirectoriesAcrossProvidersAfterRecursiveCopy() = runBlocking {
        val sourceProvider = FakeProvider(
            protocol = VfsProtocol.LOCAL,
            locationPrefix = SOURCE_PREFIX,
            entries = mapOf(
                "$SOURCE_PREFIX/root" to listOf(
                    file(name = "a.txt", location = "$SOURCE_PREFIX/root/a.txt", content = "alpha"),
                ),
            ),
        )
        val targetProvider = FakeProvider(
            protocol = VfsProtocol.WEBDAV,
            locationPrefix = TARGET_PREFIX,
            entries = emptyMap(),
        )
        val sourceCommandService = FakeCommandService(SOURCE_PREFIX)
        val targetCommandService = FakeCommandService(TARGET_PREFIX)
        val sourceContentService = FakeContentService(SOURCE_PREFIX)
        sourceContentService.seed("$SOURCE_PREFIX/root/a.txt", "alpha")
        val targetContentService = FakeContentService(TARGET_PREFIX)
        val commandService = ProviderBackedFileCommandService(
            services = listOf(sourceCommandService, targetCommandService),
            contentServices = listOf(sourceContentService, targetContentService),
            providerRegistry = VfsProviderRegistry(listOf(sourceProvider, targetProvider)),
        )

        val result = commandService.move(
            entries = listOf(directory(name = "root", location = "$SOURCE_PREFIX/root")),
            targetDirectoryLocation = "$TARGET_PREFIX/dest",
        )

        assertTrue(result.isSuccess)
        assertEquals(listOf("$SOURCE_PREFIX/root"), sourceCommandService.deletedLocations)
        assertEquals("alpha", targetContentService.writtenText["$TARGET_PREFIX/dest/root/a.txt"])
    }

    /**
     * 校验跨 provider 复制会聚合子项失败，同时保留已成功写入的文件。
     *
     * @return 无返回值。
     */
    @Test
    fun aggregatesFailuresAcrossProviderDirectoryCopy() = runBlocking {
        val sourceProvider = FakeProvider(
            protocol = VfsProtocol.LOCAL,
            locationPrefix = SOURCE_PREFIX,
            entries = mapOf(
                "$SOURCE_PREFIX/root" to listOf(
                    file(name = "a.txt", location = "$SOURCE_PREFIX/root/a.txt", content = "alpha"),
                    file(name = "b.txt", location = "$SOURCE_PREFIX/root/b.txt", content = "beta"),
                ),
            ),
        )
        val targetProvider = FakeProvider(
            protocol = VfsProtocol.WEBDAV,
            locationPrefix = TARGET_PREFIX,
            entries = emptyMap(),
        )
        val sourceContentService = FakeContentService(SOURCE_PREFIX)
        sourceContentService.seed("$SOURCE_PREFIX/root/a.txt", "alpha")
        val targetContentService = FakeContentService(TARGET_PREFIX)
        val commandService = ProviderBackedFileCommandService(
            services = listOf(FakeCommandService(SOURCE_PREFIX), FakeCommandService(TARGET_PREFIX)),
            contentServices = listOf(sourceContentService, targetContentService),
            providerRegistry = VfsProviderRegistry(listOf(sourceProvider, targetProvider)),
        )

        val result = commandService.copy(
            entries = listOf(directory(name = "root", location = "$SOURCE_PREFIX/root")),
            targetDirectoryLocation = "$TARGET_PREFIX/dest",
        )

        val failure = result.exceptionOrNull()
        assertTrue(failure is CrossProviderTransferException)
        assertEquals(1, failure.report.failures.size)
        assertEquals("alpha", targetContentService.writtenText["$TARGET_PREFIX/dest/root/a.txt"])
    }

    /**
     * 校验跨 provider 目录复制会按 SKIP 策略跳过已存在目录并上报进度。
     *
     * @return 无返回值。
     */
    @Test
    fun skipsExistingDirectoryAndReportsProgress() = runBlocking {
        val sourceProvider = FakeProvider(
            protocol = VfsProtocol.LOCAL,
            locationPrefix = SOURCE_PREFIX,
            entries = mapOf(
                "$SOURCE_PREFIX/root" to listOf(
                    file(name = "a.txt", location = "$SOURCE_PREFIX/root/a.txt", content = "alpha"),
                ),
            ),
        )
        val targetProvider = FakeProvider(
            protocol = VfsProtocol.WEBDAV,
            locationPrefix = TARGET_PREFIX,
            entries = mapOf(
                "$TARGET_PREFIX/dest" to listOf(directory(name = "root", location = "$TARGET_PREFIX/dest/root")),
            ),
        )
        val targetCommandService = FakeCommandService(TARGET_PREFIX)
        val progressEvents = mutableListOf<CrossProviderTransferProgress>()
        val commandService = ProviderBackedFileCommandService(
            services = listOf(FakeCommandService(SOURCE_PREFIX), targetCommandService),
            contentServices = listOf(FakeContentService(SOURCE_PREFIX), FakeContentService(TARGET_PREFIX)),
            providerRegistry = VfsProviderRegistry(listOf(sourceProvider, targetProvider)),
            progressSink = CrossProviderTransferProgressSink { progress -> progressEvents += progress },
        )

        val result = commandService.copy(
            entries = listOf(directory(name = "root", location = "$SOURCE_PREFIX/root")),
            targetDirectoryLocation = "$TARGET_PREFIX/dest",
            conflictStrategy = TransferConflictStrategy.SKIP,
        )

        assertTrue(result.isSuccess)
        assertTrue(targetCommandService.createdDirectories.isEmpty())
        assertEquals(CrossProviderTransferStage.ENTRY_SKIPPED, progressEvents.single().stage)
    }

    private companion object {
        const val SOURCE_PREFIX = "source://"
        const val TARGET_PREFIX = "target://"

        /**
         * 构造测试目录条目。
         *
         * @param name 文件名。
         * @param location 条目位置。
         * @return 测试目录。
         */
        fun directory(
            name: String,
            location: String,
        ): VFile = VFile(
            id = location,
            name = name,
            location = location,
            parentLocation = location.substringBeforeLast('/', missingDelimiterValue = ""),
            kind = VFileKind.DIRECTORY,
            sizeBytes = null,
            modifiedAtEpochMillis = null,
            hidden = false,
            capabilities = setOf(VFileCapability.LIST_CHILDREN),
        )

        /**
         * 构造测试文件条目。
         *
         * @param name 文件名。
         * @param location 条目位置。
         * @param content 文件内容，仅用于表达测试语义。
         * @return 测试文件。
         */
        fun file(
            name: String,
            location: String,
            content: String,
        ): VFile = VFile(
            id = location,
            name = name,
            location = location,
            parentLocation = location.substringBeforeLast('/', missingDelimiterValue = ""),
            kind = VFileKind.FILE,
            sizeBytes = content.encodeToByteArray().size.toLong(),
            modifiedAtEpochMillis = null,
            hidden = false,
            capabilities = setOf(VFileCapability.READ_CONTENT),
        )
    }
}

/**
 * 测试用 VFS provider。
 *
 * @property protocol provider 协议。
 * @property locationPrefix 支持的位置前缀。
 * @property entries 目录到子条目的映射。
 */
private class FakeProvider(
    override val protocol: VfsProtocol,
    private val locationPrefix: String,
    private val entries: Map<String, List<VFile>>,
) : VfsProvider {
    override val capabilities: Set<VfsProviderCapability> = VfsProviderCapability.entries.toSet()

    /**
     * 判断位置是否由当前 provider 处理。
     *
     * @param location 待检查位置。
     * @return 是否支持。
     */
    override fun supports(location: String): Boolean = location.startsWith(locationPrefix)

    /**
     * 列出目录子条目。
     *
     * @param location 目录位置。
     * @return 子条目列表。
     */
    override suspend fun list(location: String): Result<List<VFile>> {
        return Result.success(entries[location].orEmpty())
    }
}

/**
 * 测试用文件命令服务。
 *
 * @property locationPrefix 支持的位置前缀。
 */
private class FakeCommandService(
    private val locationPrefix: String,
) : RoutableFileCommandService {
    val createdDirectories = mutableListOf<String>()
    val deletedLocations = mutableListOf<String>()

    /**
     * 判断位置是否由当前服务处理。
     *
     * @param location 待检查位置。
     * @return 是否支持。
     */
    override fun supports(location: String): Boolean = location.startsWith(locationPrefix)

    /**
     * 测试不覆盖同 provider 复制。
     *
     * @param entries 源条目。
     * @param targetDirectoryLocation 目标目录。
     * @param conflictStrategy 冲突策略。
     * @return 失败结果。
     */
    override suspend fun copy(
        entries: List<VFile>,
        targetDirectoryLocation: String,
        conflictStrategy: TransferConflictStrategy,
    ): Result<Unit> = Result.failure(UnsupportedOperationException("same-provider copy is not used"))

    /**
     * 测试不覆盖同 provider 移动。
     *
     * @param entries 源条目。
     * @param targetDirectoryLocation 目标目录。
     * @param conflictStrategy 冲突策略。
     * @return 失败结果。
     */
    override suspend fun move(
        entries: List<VFile>,
        targetDirectoryLocation: String,
        conflictStrategy: TransferConflictStrategy,
    ): Result<Unit> = Result.failure(UnsupportedOperationException("same-provider move is not used"))

    /**
     * 记录删除位置。
     *
     * @param entries 需要删除的条目。
     * @return 成功结果。
     */
    override suspend fun delete(entries: List<VFile>): Result<Unit> {
        deletedLocations += entries.map { entry -> entry.location }
        return Result.success(Unit)
    }

    /**
     * 测试不覆盖重命名。
     *
     * @param entry 源条目。
     * @param targetName 目标名称。
     * @return 失败结果。
     */
    override suspend fun rename(
        entry: VFile,
        targetName: String,
    ): Result<VFile> = Result.failure(UnsupportedOperationException("rename is not used"))

    /**
     * 测试不覆盖创建文件。
     *
     * @param parentLocation 父目录位置。
     * @param name 文件名。
     * @return 失败结果。
     */
    override suspend fun createFile(
        parentLocation: String,
        name: String,
    ): Result<VFile> = Result.failure(UnsupportedOperationException("createFile is not used"))

    /**
     * 创建目录并返回目标目录条目。
     *
     * @param parentLocation 父目录位置。
     * @param name 目录名。
     * @return 目标目录条目。
     */
    override suspend fun createDirectory(
        parentLocation: String,
        name: String,
    ): Result<VFile> {
        val location = "${parentLocation.trimEnd('/')}/$name"
        createdDirectories += location
        return Result.success(
            VFile(
                id = location,
                name = name,
                location = location,
                parentLocation = parentLocation,
                kind = VFileKind.DIRECTORY,
                sizeBytes = null,
                modifiedAtEpochMillis = null,
                hidden = false,
                capabilities = setOf(VFileCapability.LIST_CHILDREN),
            )
        )
    }
}

/**
 * 测试用内容服务。
 *
 * @property locationPrefix 支持的位置前缀。
 */
private class FakeContentService(
    private val locationPrefix: String,
) : RoutableVfsContentService {
    private val readableText = mutableMapOf<String, String>()
    val writtenText = mutableMapOf<String, String>()

    /**
     * 写入可读取的源内容。
     *
     * @param location 文件位置。
     * @param text 文件文本。
     */
    fun seed(
        location: String,
        text: String,
    ) {
        readableText[location] = text
    }

    /**
     * 判断位置是否由当前服务处理。
     *
     * @param location 待检查位置。
     * @return 是否支持。
     */
    override fun supports(location: String): Boolean = location.startsWith(locationPrefix)

    /**
     * 读取测试内容。
     *
     * @param entry 需要读取的条目。
     * @return 内容源。
     */
    override suspend fun readFile(entry: VFile): Result<VfsContentSource> {
        val text = readableText[entry.location] ?: return Result.failure(IllegalStateException("Missing content"))
        return Result.success(
            VfsContentSource(
                name = entry.name,
                sizeBytes = text.encodeToByteArray().size.toLong(),
                chunks = flowOf(text.encodeToByteArray()),
            )
        )
    }

    /**
     * 写入测试内容。
     *
     * @param parentLocation 父目录位置。
     * @param name 文件名。
     * @param chunks 内容块。
     * @param conflictStrategy 冲突策略。
     * @return 写入后的文件条目。
     */
    override suspend fun writeFile(
        parentLocation: String,
        name: String,
        chunks: Flow<ByteArray>,
        conflictStrategy: TransferConflictStrategy,
    ): Result<VFile?> {
        val location = "${parentLocation.trimEnd('/')}/$name"
        val bytes = chunks.toList().flatMap { chunk -> chunk.asIterable() }.toByteArray()
        val text = bytes.decodeToString()
        writtenText[location] = text
        return Result.success(
            VFile(
                id = location,
                name = name,
                location = location,
                parentLocation = parentLocation,
                kind = VFileKind.FILE,
                sizeBytes = bytes.size.toLong(),
                modifiedAtEpochMillis = null,
                hidden = false,
                capabilities = setOf(VFileCapability.READ_CONTENT),
            )
        )
    }
}
