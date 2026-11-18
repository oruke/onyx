package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.app.filesystem.JvmVfsPathService
import com.oruke.onyx.core.model.BackgroundTaskStatus
import com.oruke.onyx.core.model.FileTransferOperation
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileCapability
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.vfs.api.FileTransferProgressSink
import com.oruke.onyx.vfs.api.FileTransferResult
import com.oruke.onyx.vfs.api.ResultAwareFileCommandService
import com.oruke.onyx.vfs.api.TransferConflictStrategy
import com.oruke.onyx.vfs.api.VfsProtocol
import com.oruke.onyx.vfs.api.VfsProvider
import com.oruke.onyx.vfs.api.VfsProviderCapability
import com.oruke.onyx.vfs.api.VfsProviderRegistry
import com.oruke.onyx.vfs.local.JvmLocalFileProvider
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 验证批量文件传输的单条目容错、精准错误归因、暂存路径脱敏以及按未完成项重试。 */
class FileTransferPartialFailureAndRetryTest {
    /**
     * 4 个文件中仅第 2 个因目标文件名非法而失败时：
     * 1) 第 1、3、4 个文件继续完成传输；
     * 2) 错误列表仅包含第 2 个文件（不连坐已成功文件），且剥离 `.onyx-transfer-*` 与重复长路径前缀；
     * 3) 点击重试时仅重传第 2 个未完成文件，不再重复传输已成功的 3 个文件。
     */
    @Test
    fun batchCopyIsolatesFailedEntryAndRetriesOnlyUnresolvedItems() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val targetDir = """Z:\unsorted\ebook\H漫"""
            val brokenName =
                "[Akisaka Yamoka] Jitsu no Imouto ga Ore ni Tsumetai node, Imouto no Tomodachi ni " +
                    "Nakadashi Shichaimashita [Chinese] [De.zip"
            val entries = listOf(
                syntheticZip("1.zip", 1_000L),
                syntheticZip(brokenName, 2_000L),
                syntheticZip("3.zip", 3_000L),
                syntheticZip("4.zip", 4_000L),
            )
            val failBrokenItem = AtomicBoolean(true)
            val transferredNames = CopyOnWriteArrayList<String>()
            val commandService = object : ResultAwareFileCommandService {
                override suspend fun transferEntry(
                    entry: VFile,
                    targetDirectoryLocation: String,
                    operation: FileTransferOperation,
                    conflictStrategy: TransferConflictStrategy,
                    progressSink: FileTransferProgressSink,
                ): Result<FileTransferResult> {
                    transferredNames += entry.name
                    if (entry.name == brokenName && failBrokenItem.get()) {
                        val rawStagingMessage =
                            """$targetDir\.onyx-transfer-8558666416063270573\content -> """ +
                                """$targetDir\$brokenName: 文件名、目录名或卷标语法不正确。"""
                        return Result.failure(IllegalStateException(rawStagingMessage))
                    }
                    progressSink.onBytesTransferred(entry.sizeBytes ?: 0L)
                    return Result.success(
                        FileTransferResult.Committed(
                            entry.copy(
                                id = """$targetDir\${entry.name}""",
                                location = """$targetDir\${entry.name}""",
                                parentLocation = targetDir,
                            )
                        )
                    )
                }

                override suspend fun copy(
                    entries: List<VFile>,
                    targetDirectoryLocation: String,
                    conflictStrategy: TransferConflictStrategy,
                ): Result<Unit> = Result.success(Unit)

                override suspend fun move(
                    entries: List<VFile>,
                    targetDirectoryLocation: String,
                    conflictStrategy: TransferConflictStrategy,
                ): Result<Unit> = Result.success(Unit)

                override suspend fun delete(entries: List<VFile>): Result<Unit> = Result.success(Unit)
                override suspend fun rename(entry: VFile, targetName: String): Result<VFile> = Result.success(entry)
                override suspend fun createFile(parentLocation: String, name: String): Result<VFile> =
                    Result.failure(UnsupportedOperationException())
                override suspend fun createDirectory(parentLocation: String, name: String): Result<VFile> =
                    Result.failure(UnsupportedOperationException())
            }

            val tasks = TaskOrchestrator(scope)
            val pathService = JvmVfsPathService()
            val clipboard = ClipboardManager(JvmLocalFileProvider(), pathService)
            val registry = VfsProviderRegistry(listOf(StubProvider))
            var completionSignal = CompletableDeferred<Unit>()
            var historyInvalidatedCount = 0

            val delegate = FileTransferTaskDelegate(
                fileCommandService = commandService,
                taskOrchestrator = tasks,
                clipboardManager = clipboard,
                providerRegistry = registry,
                onDirectoryChange = {},
                onRefreshDirectories = {},
                onRemovalStarted = { null },
                onRemovalFinished = { _, _, _ -> completionSignal.complete(Unit) },
                onMoveSucceeded = {},
                onCopySucceeded = {},
                onHistoryInvalidated = { historyInvalidatedCount += 1 },
            )

            val structure = PlannedTransferStructure(entries = entries, targetDirectoryLocation = targetDir)
            delegate.launch(
                structure = structure,
                operation = FileTransferOperation.COPY,
                clipboardToClear = null,
                conflictStrategies = emptyMap(),
            )

            withTimeout(5_000L) { completionSignal.await() }

            assertEquals(listOf("1.zip", brokenName, "3.zip", "4.zip"), transferredNames.toList())
            val firstTask = tasks.tasks.value.single()
            assertEquals(BackgroundTaskStatus.FAILED, firstTask.status)
            assertEquals(3, firstTask.processedCount)
            assertEquals(4, firstTask.totalCount)
            assertEquals(8_000L, firstTask.processedBytes)
            assertEquals(10_000L, firstTask.totalBytes)
            assertEquals(
                I18nMessage(MessageKey.MSG_COPY_PARTIAL_FAILED, 3, targetDir, 1),
                firstTask.detail,
            )
            assertEquals(1, firstTask.errors.size)
            val recordedError = firstTask.errors.single()
            assertEquals(brokenName, recordedError.fileName)
            assertFalse(recordedError.message.contains(".onyx-transfer-"))
            assertEquals(
                I18nMessage(MessageKey.MSG_STRING_LITERAL, "文件名、目录名或卷标语法不正确。"),
                recordedError.detail,
            )
            assertTrue(historyInvalidatedCount >= 1)

            // 解除第 2 个文件的故障并触发重试，验证只重试该未完成条目。
            transferredNames.clear()
            failBrokenItem.set(false)
            completionSignal = CompletableDeferred()
            tasks.retryTask(firstTask.id)

            withTimeout(5_000L) { completionSignal.await() }

            assertEquals(listOf(brokenName), transferredNames.toList())
            val retriedTask = tasks.tasks.value.single()
            assertEquals(BackgroundTaskStatus.SUCCEEDED, retriedTask.status)
            assertEquals(1, retriedTask.totalCount)
            assertEquals(1, retriedTask.processedCount)
            assertTrue(retriedTask.errors.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    private fun syntheticZip(name: String, sizeBytes: Long): VFile = VFile(
        id = """D:\downloads\$name""",
        name = name,
        location = """D:\downloads\$name""",
        parentLocation = """D:\downloads""",
        kind = VFileKind.FILE,
        sizeBytes = sizeBytes,
        modifiedAtEpochMillis = null,
        hidden = false,
        capabilities = setOf(VFileCapability.READ_CONTENT),
    )

    private object StubProvider : VfsProvider {
        override val protocol: VfsProtocol = VfsProtocol.LOCAL
        override val capabilities: Set<VfsProviderCapability> = VfsProviderCapability.entries.toSet()
        override fun supports(location: String): Boolean = true
        override suspend fun list(location: String): Result<List<VFile>> = Result.success(emptyList())
    }
}
