package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.vfs.api.FileCommandService
import com.oruke.onyx.vfs.api.FileRepository
import com.oruke.onyx.vfs.api.TransferConflictStrategy
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 文件操作历史委托测试。
 *
 * 覆盖重命名、批量重命名和无冲突移动的撤销 / 重做路径，保证 UI 命令只是消费统一状态，
 * 真实文件变更仍通过 FileCommandService 与 FileRepository 执行。
 */
class FileOperationHistoryDelegateTest {
    /**
     * 验证单文件重命名可以撤销并重做。
     */
    @Test
    fun `rename can be undone and redone`() = runTest {
        val fileSystem = InMemoryFileSystem()
        val original = fileSystem.addFile(parentLocation = SOURCE_DIRECTORY, name = "old.txt")
        val renamed = fileSystem.rename(original, "new.txt").getOrThrow()
        val delegate = FileOperationHistoryDelegate(
            fileCommandService = fileSystem,
            fileRepository = fileSystem,
        )

        delegate.recordRename(original, renamed.name)

        assertTrue(delegate.state.value.canUndo)
        assertFalse(delegate.state.value.canRedo)

        delegate.undoLast().getOrThrow()
        assertTrue(fileSystem.exists(parentLocation = SOURCE_DIRECTORY, name = "old.txt"))
        assertFalse(fileSystem.exists(parentLocation = SOURCE_DIRECTORY, name = "new.txt"))
        assertFalse(delegate.state.value.canUndo)
        assertTrue(delegate.state.value.canRedo)

        delegate.redoLast().getOrThrow()
        assertFalse(fileSystem.exists(parentLocation = SOURCE_DIRECTORY, name = "old.txt"))
        assertTrue(fileSystem.exists(parentLocation = SOURCE_DIRECTORY, name = "new.txt"))
        assertTrue(delegate.state.value.canUndo)
        assertFalse(delegate.state.value.canRedo)
    }

    /**
     * 验证批量重命名会作为一个历史记录整体撤销和重做。
     */
    @Test
    fun `batch rename is undone and redone as one record`() = runTest {
        val fileSystem = InMemoryFileSystem()
        val first = fileSystem.addFile(parentLocation = SOURCE_DIRECTORY, name = "first.txt")
        val second = fileSystem.addFile(parentLocation = SOURCE_DIRECTORY, name = "second.txt")
        fileSystem.rename(first, "first-renamed.txt").getOrThrow()
        fileSystem.rename(second, "second-renamed.txt").getOrThrow()
        val delegate = FileOperationHistoryDelegate(
            fileCommandService = fileSystem,
            fileRepository = fileSystem,
        )

        delegate.recordBatchRename(
            listOf(
                first to "first-renamed.txt",
                second to "second-renamed.txt",
            )
        )

        delegate.undoLast().getOrThrow()
        assertTrue(fileSystem.exists(parentLocation = SOURCE_DIRECTORY, name = "first.txt"))
        assertTrue(fileSystem.exists(parentLocation = SOURCE_DIRECTORY, name = "second.txt"))

        delegate.redoLast().getOrThrow()
        assertTrue(fileSystem.exists(parentLocation = SOURCE_DIRECTORY, name = "first-renamed.txt"))
        assertTrue(fileSystem.exists(parentLocation = SOURCE_DIRECTORY, name = "second-renamed.txt"))
    }

    /**
     * 验证无冲突移动可以通过反向移动完成撤销和重做。
     */
    @Test
    fun `move can be undone and redone`() = runTest {
        val fileSystem = InMemoryFileSystem()
        val original = fileSystem.addFile(parentLocation = SOURCE_DIRECTORY, name = "moving.txt")
        fileSystem.move(listOf(original), TARGET_DIRECTORY).getOrThrow()
        val delegate = FileOperationHistoryDelegate(
            fileCommandService = fileSystem,
            fileRepository = fileSystem,
        )

        delegate.recordMove(listOf(original), TARGET_DIRECTORY)

        delegate.undoLast().getOrThrow()
        assertTrue(fileSystem.exists(parentLocation = SOURCE_DIRECTORY, name = "moving.txt"))
        assertFalse(fileSystem.exists(parentLocation = TARGET_DIRECTORY, name = "moving.txt"))

        delegate.redoLast().getOrThrow()
        assertFalse(fileSystem.exists(parentLocation = SOURCE_DIRECTORY, name = "moving.txt"))
        assertTrue(fileSystem.exists(parentLocation = TARGET_DIRECTORY, name = "moving.txt"))
    }

    /**
     * 测试用内存文件系统，同时实现列表与文件命令服务。
     */
    private class InMemoryFileSystem : FileRepository, FileCommandService {
        /** 以父目录为 key 保存当前目录下的文件条目。 */
        private val entriesByParent = mutableMapOf<String, MutableList<VFile>>()

        /**
         * 查询目录下的文件列表。
         *
         * @param location 需要列出的目录位置。
         * @return 当前目录下的文件条目。
         */
        override suspend fun list(location: String): Result<List<VFile>> {
            return Result.success(entriesByParent[location].orEmpty())
        }

        /**
         * 返回测试文件系统默认目录。
         *
         * @return 默认目录位置。
         */
        override fun defaultLocation(): String = SOURCE_DIRECTORY

        /**
         * 测试中不需要复制命令。
         *
         * @param entries 待复制条目。
         * @param targetDirectoryLocation 目标目录位置。
         * @param conflictStrategy 冲突处理策略。
         * @return 复制结果。
         */
        override suspend fun copy(
            entries: List<VFile>,
            targetDirectoryLocation: String,
            conflictStrategy: TransferConflictStrategy,
        ): Result<Unit> = Result.failure(UnsupportedOperationException("copy is not used in this test"))

        /**
         * 移动文件条目到目标目录。
         *
         * @param entries 待移动条目。
         * @param targetDirectoryLocation 目标目录位置。
         * @param conflictStrategy 冲突处理策略；测试仅接受无同名冲突的移动。
         * @return 移动结果。
         */
        override suspend fun move(
            entries: List<VFile>,
            targetDirectoryLocation: String,
            conflictStrategy: TransferConflictStrategy,
        ): Result<Unit> = runCatching {
            entries.forEach { entry ->
                require(!exists(targetDirectoryLocation, entry.name)) {
                    "Target already contains ${entry.name}"
                }
                removeEntry(entry.parentLocation, entry.name)
                addEntry(entry.withParent(targetDirectoryLocation))
            }
        }

        /**
         * 测试中不需要删除命令。
         *
         * @param entries 待删除条目。
         * @return 删除结果。
         */
        override suspend fun delete(entries: List<VFile>): Result<Unit> {
            return Result.failure(UnsupportedOperationException("delete is not used in this test"))
        }

        /**
         * 重命名当前目录内的文件条目。
         *
         * @param entry 待重命名条目。
         * @param targetName 目标名称。
         * @return 重命名后的文件条目。
         */
        override suspend fun rename(
            entry: VFile,
            targetName: String,
        ): Result<VFile> = runCatching {
            val parentLocation = requireNotNull(entry.parentLocation)
            require(!exists(parentLocation, targetName)) {
                "Target already contains $targetName"
            }
            removeEntry(parentLocation, entry.name)
            val renamed = entry.copy(
                id = "$parentLocation/$targetName",
                name = targetName,
                location = "$parentLocation/$targetName",
            )
            addEntry(renamed)
            renamed
        }

        /**
         * 测试中不需要创建文件命令。
         *
         * @param parentLocation 父目录位置。
         * @param name 文件名称。
         * @return 创建结果。
         */
        override suspend fun createFile(
            parentLocation: String,
            name: String,
        ): Result<VFile> = Result.failure(UnsupportedOperationException("createFile is not used in this test"))

        /**
         * 测试中不需要创建目录命令。
         *
         * @param parentLocation 父目录位置。
         * @param name 目录名称。
         * @return 创建结果。
         */
        override suspend fun createDirectory(
            parentLocation: String,
            name: String,
        ): Result<VFile> = Result.failure(UnsupportedOperationException("createDirectory is not used in this test"))

        /**
         * 添加测试文件。
         *
         * @param parentLocation 父目录位置。
         * @param name 文件名称。
         * @return 新增的文件条目。
         */
        fun addFile(
            parentLocation: String,
            name: String,
        ): VFile {
            val entry = VFile(
                id = "$parentLocation/$name",
                name = name,
                location = "$parentLocation/$name",
                parentLocation = parentLocation,
                kind = VFileKind.FILE,
                sizeBytes = null,
                modifiedAtEpochMillis = null,
                hidden = false,
                capabilities = emptySet(),
            )
            addEntry(entry)
            return entry
        }

        /**
         * 判断目录内是否存在指定名称的条目。
         *
         * @param parentLocation 父目录位置。
         * @param name 文件名称。
         * @return 存在返回 `true`。
         */
        fun exists(
            parentLocation: String,
            name: String,
        ): Boolean {
            return entriesByParent[parentLocation].orEmpty().any { entry -> entry.name == name }
        }

        /**
         * 将文件条目加入对应目录。
         *
         * @param entry 待加入的文件条目。
         * @return 无返回值。
         */
        private fun addEntry(entry: VFile) {
            val parentLocation = requireNotNull(entry.parentLocation)
            entriesByParent.getOrPut(parentLocation) { mutableListOf() } += entry
        }

        /**
         * 从目录中移除指定名称的条目。
         *
         * @param parentLocation 父目录位置。
         * @param name 文件名称。
         * @return 无返回值。
         */
        private fun removeEntry(
            parentLocation: String?,
            name: String,
        ) {
            val entries = entriesByParent[requireNotNull(parentLocation)]
                ?: error("Parent directory is missing")
            val removed = entries.removeIf { entry -> entry.name == name }
            check(removed) { "Entry is missing: $name" }
        }

        /**
         * 生成位于新父目录下的文件条目。
         *
         * @param parentLocation 新父目录位置。
         * @return 更新父目录和位置后的文件条目。
         */
        private fun VFile.withParent(parentLocation: String): VFile {
            return copy(
                id = "$parentLocation/$name",
                location = "$parentLocation/$name",
                parentLocation = parentLocation,
            )
        }
    }

    private companion object {
        const val SOURCE_DIRECTORY = "mem:/source"
        const val TARGET_DIRECTORY = "mem:/target"
    }
}
