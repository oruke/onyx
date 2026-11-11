package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.app.component.RootDialogState
import com.oruke.onyx.app.filesystem.FileHashRequest
import com.oruke.onyx.app.filesystem.FileHashResult
import com.oruke.onyx.app.filesystem.FileHashService
import com.oruke.onyx.app.filesystem.JvmFileHashService
import com.oruke.onyx.app.filesystem.JvmVfsPathService
import com.oruke.onyx.app.platform.SystemFileClipboardSnapshot
import com.oruke.onyx.core.model.BackgroundTaskStatus
import com.oruke.onyx.core.model.FileTransferOperation
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.LocalizedException
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.vfs.api.FileRepository
import com.oruke.onyx.vfs.api.ProviderBackedFileCommandService
import com.oruke.onyx.vfs.api.VfsProviderRegistry
import com.oruke.onyx.vfs.archive.ArchiveService
import com.oruke.onyx.vfs.local.JvmLocalFileProvider
import com.oruke.onyx.vfs.local.LocalVfsProvider
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 异步任务完成等待的超时上限（毫秒）。 */
private const val TASK_COMPLETION_TIMEOUT_MS = 10_000L

/** 用生产本地文件、摘要和历史入口验证复制撤销及内容保护，不使用 Mock。 */
class CopiedFilesHistoryFlowTest {
    /** 普通文件和包含子项的目录一起复制后，可以整体撤销并从完整源重做。 */
    @Test
    fun copyUndoRedoRestoresActualDirectoryAndFile() = runBlocking {
        withFixture { source, target, provider, history ->
            Files.writeString(source.resolve("note.txt"), "original-content")
            val folder = Files.createDirectory(source.resolve("folder"))
            Files.writeString(folder.resolve("nested.txt"), "nested-content")
            val entries = provider.list(source.toString()).getOrThrow()
            provider.copy(entries, target.toString()).getOrThrow()
            history.recordCopy(entries, target.toString()).getOrThrow()
            history.undoLast().getOrThrow()
            assertTrue(provider.list(target.toString()).getOrThrow().isEmpty())
            assertEquals("original-content", Files.readString(source.resolve("note.txt")))
            assertEquals("nested-content", Files.readString(folder.resolve("nested.txt")))
            history.redoLast().getOrThrow()
            assertEquals("original-content", Files.readString(target.resolve("note.txt")))
            assertEquals("nested-content", Files.readString(target.resolve("folder/nested.txt")))
            history.undoLast().getOrThrow()
            assertFalse(Files.exists(target.resolve("folder")))
        }
    }

    /** 用户修改副本后拒绝撤销，副本和源内容均保留。 */
    @Test
    fun modifiedCopyIsNeverDeletedByUndo() = runBlocking {
        withFixture { source, target, provider, history ->
            Files.writeString(source.resolve("note.txt"), "original-content")
            val entries = provider.list(source.toString()).getOrThrow()
            provider.copy(entries, target.toString()).getOrThrow()
            history.recordCopy(entries, target.toString()).getOrThrow()
            Files.writeString(target.resolve("note.txt"), "user-edited-content")
            assertTrue(history.undoLast().isFailure)
            assertEquals("user-edited-content", Files.readString(target.resolve("note.txt")))
            assertEquals("original-content", Files.readString(source.resolve("note.txt")))
            assertTrue(history.state.value.canUndo)
        }
    }

    /** 用户在副本目录新增文件后，不允许以目录递归删除丢失新增内容。 */
    @Test
    fun newlyAddedChildPreventsDirectoryUndo() = runBlocking {
        withFixture { source, target, provider, history ->
            val folder = Files.createDirectory(source.resolve("folder"))
            Files.writeString(folder.resolve("original.txt"), "original")
            val entries = provider.list(source.toString()).getOrThrow()
            provider.copy(entries, target.toString()).getOrThrow()
            history.recordCopy(entries, target.toString()).getOrThrow()
            Files.writeString(target.resolve("folder/new.txt"), "new-content")
            assertTrue(history.undoLast().isFailure)
            assertEquals("new-content", Files.readString(target.resolve("folder/new.txt")))
            assertEquals("original", Files.readString(target.resolve("folder/original.txt")))
        }
    }

    /** 撤销后用户改变源内容，重做不能复制不属于原记录的新内容。 */
    @Test
    fun changedSourcePreventsRedo() = runBlocking {
        withFixture { source, target, provider, history ->
            Files.writeString(source.resolve("note.txt"), "original")
            val entries = provider.list(source.toString()).getOrThrow()
            provider.copy(entries, target.toString()).getOrThrow()
            history.recordCopy(entries, target.toString()).getOrThrow()
            history.undoLast().getOrThrow()
            Files.writeString(source.resolve("note.txt"), "changed-source")
            assertTrue(history.redoLast().isFailure)
            assertTrue(provider.list(target.toString()).getOrThrow().isEmpty())
            assertEquals("changed-source", Files.readString(source.resolve("note.txt")))
        }
    }

    /** 真实副本摘要读取后取消快照任务，应传播取消、清空旧历史并保留所有文件。 */
    @Test
    fun cancelledCopySnapshotPreservesFilesAndClearsOldHistory() = runBlocking {
        withFixture { source, target, provider, _ ->
            Files.writeString(source.resolve("note.txt"), "original")
            val hasher = JvmFileHashService(ArchiveService())
            val cancellingHasher = object : FileHashService {
                /**
                 * 使用生产服务完成实际摘要，然后在登记边界注入协程取消。
                 * @param request 真实副本的摘要请求。
                 * @return 本场景不会正常返回，摘要确认后传播取消。
                 */
                override suspend fun readHash(request: FileHashRequest): FileHashResult {
                    val result = hasher.readHash(request)
                    assertTrue(result is FileHashResult.Hash)
                    currentCoroutineContext().cancel()
                    currentCoroutineContext().ensureActive()
                    return result
                }
            }
            val history = FileOperationHistoryDelegate(provider, provider, hashService = cancellingHasher)
            val original = provider.list(source.toString()).getOrThrow().single()
            provider.rename(original, "renamed.txt").getOrThrow()
            history.recordRename(original, "renamed.txt")
            assertTrue(history.state.value.canUndo)
            val entries = provider.list(source.toString()).getOrThrow()
            provider.copy(entries, target.toString()).getOrThrow()
            val capture = launch { history.recordCopy(entries, target.toString()).getOrThrow() }
            capture.join()
            assertTrue(capture.isCancelled)
            assertFalse(history.state.value.canUndo)
            assertFalse(history.state.value.canRedo)
            assertEquals("original", Files.readString(source.resolve("renamed.txt")))
            assertEquals("original", Files.readString(target.resolve("renamed.txt")))
        }
    }

    /** 大文件复制无需全量重读哈希即可立刻置为完成态，且支持安全撤销与修改保护。 */
    @Test
    fun largeFileCopyCompletesTaskImmediatelyWithoutFullFileHashingAndSupportsSafeUndoRedo() = runBlocking {
        val hashCallCount = AtomicInteger(0)
        val realHasher = JvmFileHashService(ArchiveService())
        val countingHasher = object : FileHashService {
            /** 统计哈希调用次数并转发给真实摘要服务。 */
            override suspend fun readHash(request: FileHashRequest): FileHashResult {
                hashCallCount.incrementAndGet()
                return realHasher.readHash(request)
            }
        }
        withTransferHarness(hashService = countingHasher) { source, target, provider, tasks, history, transfer ->
            val largeSize = MAX_COPY_HISTORY_HASH_FILE_BYTES + 4096L
            writeSparseFile(source.resolve("large1.bin"), largeSize)
            writeSparseFile(source.resolve("large2.bin"), largeSize)

            val entries = provider.list(source.toString()).getOrThrow()
            transfer.requestTransferEntriesToDirectory(
                entries = entries,
                targetDirectoryLocation = target.toString(),
                operation = FileTransferOperation.COPY,
                clearClipboardOnSuccess = false,
                sourceBaseLocation = source.toString(),
            )

            val completedTask = withTimeout(TASK_COMPLETION_TIMEOUT_MS) {
                tasks.tasks.first { list ->
                    list.isNotEmpty() && list.all { task -> task.status == BackgroundTaskStatus.SUCCEEDED }
                }.first()
            }
            assertEquals(2, completedTask.processedCount)
            assertEquals(1f, completedTask.progress)
            assertEquals(null, completedTask.bytesPerSecond)
            assertEquals(0, hashCallCount.get())

            history.undoLast().getOrThrow()
            assertFalse(Files.exists(target.resolve("large1.bin")))
            assertFalse(Files.exists(target.resolve("large2.bin")))

            history.redoLast().getOrThrow()
            assertTrue(Files.exists(target.resolve("large1.bin")))
            assertTrue(Files.exists(target.resolve("large2.bin")))

            Files.write(target.resolve("large1.bin"), byteArrayOf(1), StandardOpenOption.APPEND)
            assertTrue(history.undoLast().isFailure)
            assertTrue(Files.exists(target.resolve("large1.bin")))
            assertTrue(Files.exists(target.resolve("large2.bin")))
        }
    }

    /** 提交落盘后的历史登记抛出异常时，任务依然切换为已完成且文件完好。 */
    @Test
    fun postCommitHistoryExceptionStillMarksTransferTaskSucceeded() = runBlocking {
        withTransferHarness(
            onCopySucceeded = { _, _ ->
                throw IllegalStateException("Simulated history failure after disk commit")
            },
        ) { source, target, provider, tasks, _, transfer ->
            Files.writeString(source.resolve("test.txt"), "committed-content")
            val entries = provider.list(source.toString()).getOrThrow()
            transfer.requestTransferEntriesToDirectory(
                entries = entries,
                targetDirectoryLocation = target.toString(),
                operation = FileTransferOperation.COPY,
                clearClipboardOnSuccess = false,
                sourceBaseLocation = source.toString(),
            )

            val completedTask = withTimeout(TASK_COMPLETION_TIMEOUT_MS) {
                tasks.tasks.first { list ->
                    list.isNotEmpty() && list.all { task -> task.status == BackgroundTaskStatus.SUCCEEDED }
                }.first()
            }
            assertEquals(BackgroundTaskStatus.SUCCEEDED, completedTask.status)
            assertTrue(Files.exists(target.resolve("test.txt")))
            assertEquals("committed-content", Files.readString(target.resolve("test.txt")))
        }
    }

    /** 提交落盘后的历史登记被协程取消时，任务依然切换为已完成、旧历史清除且文件完好。 */
    @Test
    fun postCommitHistoryCancellationStillMarksTransferTaskSucceeded() = runBlocking {
        withTransferHarness(
            onCopySucceeded = { _, _ ->
                currentCoroutineContext().cancel()
                currentCoroutineContext().ensureActive()
            },
        ) { source, target, provider, tasks, history, transfer ->
            Files.writeString(source.resolve("test.txt"), "committed-content")
            val entries = provider.list(source.toString()).getOrThrow()
            transfer.requestTransferEntriesToDirectory(
                entries = entries,
                targetDirectoryLocation = target.toString(),
                operation = FileTransferOperation.COPY,
                clearClipboardOnSuccess = false,
                sourceBaseLocation = source.toString(),
            )

            val completedTask = withTimeout(TASK_COMPLETION_TIMEOUT_MS) {
                tasks.tasks.first { list ->
                    list.isNotEmpty() && list.all { task -> task.status == BackgroundTaskStatus.SUCCEEDED }
                }.first()
            }
            assertEquals(BackgroundTaskStatus.SUCCEEDED, completedTask.status)
            assertFalse(history.state.value.canUndo)
            assertTrue(Files.exists(target.resolve("test.txt")))
            assertEquals("committed-content", Files.readString(target.resolve("test.txt")))
        }
    }

    /** 哈希服务不可用与读取失败时精确透传错误语义，且撤销读取失败时不删除目标文件。 */
    @Test
    fun unavailableAndFailedHashResultsPreserveExactErrorSemantics() = runBlocking {
        val realHasher = JvmFileHashService(ArchiveService())
        var sourcePath: String? = null
        var sourceResult: FileHashResult? = null
        var undoResult: FileHashResult? = null
        val testHasher = object : FileHashService {
            override suspend fun readHash(request: FileHashRequest): FileHashResult {
                val failure = undoResult
                if (failure != null) return failure
                val currentSource = sourcePath
                val forcedSource = sourceResult
                if (forcedSource != null && currentSource != null && request.entry.location.startsWith(currentSource)) {
                    return forcedSource
                }
                return realHasher.readHash(request)
            }
        }
        withFixture(hashService = testHasher) { source, target, provider, history ->
            sourcePath = source.toString()
            Files.writeString(source.resolve("note.txt"), "original-content")
            val entries = provider.list(source.toString()).getOrThrow()
            provider.copy(entries, target.toString()).getOrThrow()

            sourceResult = FileHashResult.Unavailable
            val recordFailure = history.recordCopy(entries, target.toString()).exceptionOrNull()
            assertTrue(recordFailure is LocalizedException)
            assertEquals(MessageKey.MSG_COPY_HISTORY_UNAVAILABLE, recordFailure.userMessage.key)

            sourceResult = null
            history.recordCopy(entries, target.toString()).getOrThrow()
            assertTrue(history.state.value.canUndo)

            val ioErrorReason = I18nMessage(MessageKey.MSG_STRING_LITERAL, "disk-io-error")
            undoResult = FileHashResult.Failed(ioErrorReason)
            val undoFailure = history.undoLast().exceptionOrNull()
            assertTrue(undoFailure is LocalizedException)
            assertEquals(ioErrorReason, undoFailure.userMessage)
            assertTrue(Files.exists(target.resolve("note.txt")))
            assertEquals("original-content", Files.readString(target.resolve("note.txt")))
        }
    }

    /** 修改时间戳为 0L 的大文件元数据指纹不可信，拒绝纳入历史记录以防撤销误删。 */
    @Test
    fun zeroTimestampLargeFileRejectsUnsafeSnapshot() = runBlocking {
        withFixture { source, target, provider, _ ->
            val largeSize = MAX_COPY_HISTORY_HASH_FILE_BYTES + 4096L
            writeSparseFile(source.resolve("large.bin"), largeSize)
            val entries = provider.list(source.toString()).getOrThrow()
            provider.copy(entries, target.toString()).getOrThrow()

            val zeroTimestampRepo = object : FileRepository by provider {
                override suspend fun list(location: String): Result<List<VFile>> {
                    return provider.list(location).map { list ->
                        list.map { entry ->
                            if (entry.location.startsWith(target.toString())) {
                                entry.copy(modifiedAtEpochMillis = 0L)
                            } else {
                                entry
                            }
                        }
                    }
                }
            }
            val history = FileOperationHistoryDelegate(
                fileCommandService = provider,
                fileRepository = zeroTimestampRepo,
                hashService = JvmFileHashService(ArchiveService()),
            )

            val failure = history.recordCopy(entries, target.toString()).exceptionOrNull()
            assertTrue(failure is LocalizedException)
            assertEquals(MessageKey.MSG_COPY_HISTORY_UNAVAILABLE, failure.userMessage.key)
        }
    }

    /** 剪切移动传输提交后在剪贴板清理阶段遭遇取消，仍应保持成功态与可撤销移动历史。 */
    @Test
    fun postCommitCancellationDuringClipboardCleanupPreservesSucceededStatusAndMoveHistory() = runBlocking {
        val root = Files.createTempDirectory("onyx-move-cleanup-")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val source = Files.createDirectory(root.resolve("source"))
            val target = Files.createDirectory(root.resolve("target"))
            Files.writeString(source.resolve("test.txt"), "move-content")

            val provider = JvmLocalFileProvider()
            val commandService = ProviderBackedFileCommandService(listOf(provider))
            val pathService = JvmVfsPathService()
            val registry = VfsProviderRegistry(listOf(LocalVfsProvider(provider)))
            val tasks = TaskOrchestrator(scope)
            val clipboard = ClipboardManager(provider, pathService)
            val history = FileOperationHistoryDelegate(
                commandService,
                provider,
                hashService = JvmFileHashService(ArchiveService()),
            )

            val transferFinished = CompletableDeferred<Unit>()
            val transferTasks = FileTransferTaskDelegate(
                fileCommandService = commandService,
                taskOrchestrator = tasks,
                clipboardManager = clipboard,
                providerRegistry = registry,
                onDirectoryChange = {},
                onRefreshDirectories = {},
                onRemovalStarted = { null },
                onRemovalFinished = { _, _, _ -> transferFinished.complete(Unit) },
                onMoveSucceeded = { completed ->
                    history.recordMove(
                        entries = completed.entries,
                        targetDirectoryLocation = completed.targetDirectoryLocation,
                        entryTargetDirectories = completed.entryTargetDirectories,
                        directorySteps = completed.directorySteps,
                        createdDirectories = completed.createdDirectories,
                    )
                    tasks.cancelTask(tasks.tasks.value.first().id)
                },
                onCopySucceeded = {},
                onHistoryInvalidated = { history.clear() },
            )

            val entries = provider.list(source.toString()).getOrThrow()
            val cutPayload = ClipboardManager.ClipboardPayload(
                operation = ClipboardManager.ClipboardOperation.CUT,
                entries = entries,
                snapshot = SystemFileClipboardSnapshot(
                    locations = entries.map { it.location },
                    isCut = true,
                    ownerId = "isolated-test-cut-owner",
                ),
            )
            val structure = TransferStructurePlanner.plan(entries, target.toString(), null, pathService)
            transferTasks.launch(
                structure = structure,
                operation = FileTransferOperation.MOVE,
                clipboardToClear = cutPayload,
                conflictStrategies = emptyMap(),
            )

            withTimeout(TASK_COMPLETION_TIMEOUT_MS) {
                transferFinished.await()
            }
            assertEquals(BackgroundTaskStatus.SUCCEEDED, tasks.tasks.value.single().status)
            assertTrue(history.state.value.canUndo)
            history.undoLast().getOrThrow()
            assertTrue(Files.exists(source.resolve("test.txt")))
            assertFalse(Files.exists(target.resolve("test.txt")))
            assertEquals("move-content", Files.readString(source.resolve("test.txt")))
        } finally {
            scope.cancel()
            deleteRecursivelyQuietly(root)
        }
    }

    /**
     * 在独占临时目录装配真实依赖并在验证后清理样本。
     * @param hashService 历史记录使用的摘要服务，默认使用本地文件哈希服务。
     * @param block 源目录、目标目录、本地 Provider 和历史组件上的完整验证流程。
     */
    private suspend fun withFixture(
        hashService: FileHashService = JvmFileHashService(ArchiveService()),
        block: suspend (Path, Path, JvmLocalFileProvider, FileOperationHistoryDelegate) -> Unit,
    ) {
        val root = Files.createTempDirectory("onyx-copy-history-")
        val provider = JvmLocalFileProvider()
        try {
            val source = Files.createDirectory(root.resolve("source"))
            val target = Files.createDirectory(root.resolve("target"))
            val history =
                FileOperationHistoryDelegate(provider, provider, hashService = hashService)
            block(source, target, provider, history)
        } finally {
            provider.delete(provider.list(root.toString()).getOrThrow()).getOrThrow()
            Files.delete(root)
        }
    }

    /**
     * 装配真实文件传输运行环境并在执行完毕后统一清理资源。
     *
     * @param hashService 历史记录使用的摘要服务。
     * @param onCopySucceeded 复制成功后的历史回调，默认登记可逆记录。
     * @param block 在准备就绪的环境下执行测试逻辑。
     */
    private suspend fun withTransferHarness(
        hashService: FileHashService = JvmFileHashService(ArchiveService()),
        onCopySucceeded: suspend (CompletedTransferHistory, FileOperationHistoryDelegate) -> Unit =
            { completed, history ->
                history.recordCopy(
                    entries = completed.entries,
                    targetDirectoryLocation = completed.targetDirectoryLocation,
                    entryTargetDirectories = completed.entryTargetDirectories,
                    directorySteps = completed.directorySteps,
                    createdDirectories = completed.createdDirectories,
                ).getOrThrow()
            },
        block: suspend (
            source: Path,
            target: Path,
            provider: JvmLocalFileProvider,
            tasks: TaskOrchestrator,
            history: FileOperationHistoryDelegate,
            transfer: FileTransferDelegate,
        ) -> Unit,
    ) {
        val root = Files.createTempDirectory("onyx-copy-flow-")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val source = Files.createDirectory(root.resolve("source"))
            val target = Files.createDirectory(root.resolve("target"))
            val provider = JvmLocalFileProvider()
            val commandService = ProviderBackedFileCommandService(listOf(provider))
            val pathService = JvmVfsPathService()
            val registry = VfsProviderRegistry(listOf(LocalVfsProvider(provider)))
            val tasks = TaskOrchestrator(scope)
            val clipboard = ClipboardManager(provider, pathService)
            val dialogState = MutableStateFlow<RootDialogState?>(null)
            val history = FileOperationHistoryDelegate(commandService, provider, hashService = hashService)
            val transfer = FileTransferDelegate(
                scope = scope,
                fileCommandService = commandService,
                fileRepository = provider,
                taskOrchestrator = tasks,
                clipboardManager = clipboard,
                dialogState = dialogState,
                pathService = pathService,
                providerRegistry = registry,
                onDirectoryChange = {},
                onCopySucceeded = { completed -> onCopySucceeded(completed, history) },
                onHistoryInvalidated = { history.clear() },
            )
            block(source, target, provider, tasks, history, transfer)
        } finally {
            scope.cancel()
            deleteRecursivelyQuietly(root)
        }
    }

    /**
     * 在指定路径创建指定字节大小的文件，末尾写入单字节以确保元数据大小准确。
     *
     * @param path 文件目标路径。
     * @param size 文件总字节数。
     */
    private fun writeSparseFile(path: Path, size: Long) {
        Files.newByteChannel(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
            channel.position(size - 1)
            channel.write(ByteBuffer.wrap(byteArrayOf(1)))
        }
    }

    /** 递归静默删除临时目录及其所有子项。 */
    private fun deleteRecursivelyQuietly(root: Path) {
        runCatching {
            Files.walk(root)
                .sorted(Comparator.reverseOrder())
                .forEach { path -> Files.deleteIfExists(path) }
        }
    }
}
