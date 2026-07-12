package com.oruke.onyx.shared.usecase

import com.oruke.onyx.core.model.FileTransferOperation
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileCapability
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.vfs.api.FileTransferProgressSink
import com.oruke.onyx.vfs.api.ProgressAwareFileCommandService
import com.oruke.onyx.vfs.api.TransferConflictStrategy
import com.oruke.onyx.vfs.api.VfsProvider
import com.oruke.onyx.vfs.api.VfsProviderCapability
import com.oruke.onyx.vfs.api.VfsProviderRegistry
import com.oruke.onyx.vfs.api.VfsProtocol
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 文件传输任务字节进度测试。 */
class FileTransferUseCaseTest {
    /**
     * 支持进度的命令服务应让大文件产生 0 与 100 之间的进度、速度和 ETA。
     */
    @Test
    fun emitsIntermediateByteProgressAndSpeed() = runBlocking {
        var nowMillis = 0L
        val entry = testFile(sizeBytes = TEST_FILE_SIZE)
        val commandService = ReportingFileCommandService(
            onChunk = { nowMillis += CHUNK_DURATION_MILLIS },
        )
        val useCase = FileTransferUseCase(
            fileCommandService = commandService,
            providerRegistry = VfsProviderRegistry(listOf(TestProvider)),
            clockMillis = { nowMillis },
        )

        val events = useCase.execute(
            request = FileTransferUseCase.FileTransferRequest(
                entries = listOf(entry),
                targetDirectoryLocation = TEST_TARGET,
                operation = FileTransferOperation.COPY,
                conflictStrategies = emptyMap(),
            ),
            awaitReady = {},
        ).toList()

        val intermediate = events.firstOrNull { progress ->
            val processedBytes = progress.processedBytes ?: return@firstOrNull false
            processedBytes in 1 until TEST_FILE_SIZE
        }
        assertNotNull(intermediate)
        assertTrue(intermediate.progress != null && intermediate.progress in 0f..1f)
        assertTrue((intermediate.bytesPerSecond ?: 0L) > 0L)
        assertTrue((intermediate.estimatedRemainingSeconds ?: 0L) > 0L)
        assertEquals(TEST_FILE_SIZE, events.last().processedBytes)
        assertEquals(1f, events.last().progress)
    }

    private companion object {
        /** 测试文件总字节数。 */
        const val TEST_FILE_SIZE = 1_000L
        /** 每次模拟传输的字节数。 */
        const val CHUNK_SIZE = 250L
        /** 每个模拟数据块耗时。 */
        const val CHUNK_DURATION_MILLIS = 250L
        /** 测试源文件位置。 */
        const val TEST_SOURCE = "test://source.bin"
        /** 测试目标目录。 */
        const val TEST_TARGET = "test://target"

        /**
         * 构造带确定大小的测试文件。
         *
         * @param sizeBytes 文件大小。
         * @return 测试 VFile。
         */
        fun testFile(sizeBytes: Long): VFile = VFile(
            id = TEST_SOURCE,
            name = "source.bin",
            location = TEST_SOURCE,
            parentLocation = "test://",
            kind = VFileKind.FILE,
            sizeBytes = sizeBytes,
            modifiedAtEpochMillis = null,
            hidden = false,
            capabilities = setOf(VFileCapability.READ_CONTENT),
        )
    }

    /** 测试 VFS Provider，仅用于总大小路由。 */
    private object TestProvider : VfsProvider {
        override val protocol: VfsProtocol = VfsProtocol.LOCAL
        override val capabilities: Set<VfsProviderCapability> = VfsProviderCapability.entries.toSet()

        /**
         * 判断测试协议位置。
         *
         * @param location 待判断位置。
         * @return 测试协议统一返回 true。
         */
        override fun supports(location: String): Boolean = true

        /**
         * 测试文件不是目录，无需列出子项。
         *
         * @param location 待列出位置。
         * @return 空列表。
         */
        override suspend fun list(location: String): Result<List<VFile>> = Result.success(emptyList())
    }

    /**
     * 按固定数据块上报进度的测试命令服务。
     *
     * @property onChunk 每个数据块发送前推进测试时钟的回调。
     */
    private class ReportingFileCommandService(
        private val onChunk: () -> Unit,
    ) : ProgressAwareFileCommandService {
        /**
         * 模拟复制并逐块上报字节。
         *
         * @param entries 待复制条目。
         * @param targetDirectoryLocation 目标目录。
         * @param conflictStrategy 冲突策略。
         * @param progressSink 字节进度接收器。
         * @return 成功结果。
         */
        override suspend fun copyWithProgress(
            entries: List<VFile>,
            targetDirectoryLocation: String,
            conflictStrategy: TransferConflictStrategy,
            progressSink: FileTransferProgressSink,
        ): Result<Unit> {
            repeat((TEST_FILE_SIZE / CHUNK_SIZE).toInt()) {
                onChunk()
                progressSink.onBytesTransferred(CHUNK_SIZE)
            }
            return Result.success(Unit)
        }

        /**
         * 测试移动复用复制字节行为。
         *
         * @param entries 待移动条目。
         * @param targetDirectoryLocation 目标目录。
         * @param conflictStrategy 冲突策略。
         * @param progressSink 字节进度接收器。
         * @return 成功结果。
         */
        override suspend fun moveWithProgress(
            entries: List<VFile>,
            targetDirectoryLocation: String,
            conflictStrategy: TransferConflictStrategy,
            progressSink: FileTransferProgressSink,
        ): Result<Unit> = copyWithProgress(
            entries = entries,
            targetDirectoryLocation = targetDirectoryLocation,
            conflictStrategy = conflictStrategy,
            progressSink = progressSink,
        )

        /** @param entries 待删除条目。 @return 成功结果。 */
        override suspend fun delete(entries: List<VFile>): Result<Unit> = Result.success(Unit)

        /** @param entry 待重命名条目。 @param targetName 新名称。 @return 原条目。 */
        override suspend fun rename(entry: VFile, targetName: String): Result<VFile> = Result.success(entry)

        /** @param parentLocation 父目录。 @param name 文件名。 @return 不支持结果。 */
        override suspend fun createFile(parentLocation: String, name: String): Result<VFile> =
            Result.failure(UnsupportedOperationException())

        /** @param parentLocation 父目录。 @param name 目录名。 @return 不支持结果。 */
        override suspend fun createDirectory(parentLocation: String, name: String): Result<VFile> =
            Result.failure(UnsupportedOperationException())
    }
}
