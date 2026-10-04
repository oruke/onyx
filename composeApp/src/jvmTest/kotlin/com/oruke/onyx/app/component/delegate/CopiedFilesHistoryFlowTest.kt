package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.app.filesystem.JvmFileHashService
import com.oruke.onyx.app.filesystem.FileHashRequest
import com.oruke.onyx.app.filesystem.FileHashResult
import com.oruke.onyx.app.filesystem.FileHashService
import com.oruke.onyx.vfs.archive.ArchiveService
import com.oruke.onyx.vfs.local.JvmLocalFileProvider
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    /**
     * 在独占临时目录装配真实依赖并在验证后清理样本。
     * @param block 源目录、目标目录、本地 Provider 和历史组件上的完整验证流程。
     */
    private suspend fun withFixture(
        block: suspend (Path, Path, JvmLocalFileProvider, FileOperationHistoryDelegate) -> Unit,
    ) {
        val root = Files.createTempDirectory("onyx-copy-history-")
        val provider = JvmLocalFileProvider()
        try {
            val source = Files.createDirectory(root.resolve("source"))
            val target = Files.createDirectory(root.resolve("target"))
            val history =
                FileOperationHistoryDelegate(provider, provider, hashService = JvmFileHashService(ArchiveService()))
            block(source, target, provider, history)
        } finally {
            provider.delete(provider.list(root.toString()).getOrThrow()).getOrThrow()
            Files.delete(root)
        }
    }
}
