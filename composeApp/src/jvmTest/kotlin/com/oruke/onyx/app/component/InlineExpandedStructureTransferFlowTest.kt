package com.oruke.onyx.app.component

import com.oruke.onyx.app.component.delegate.ClipboardManager
import com.oruke.onyx.app.component.delegate.FileOperationHistoryDelegate
import com.oruke.onyx.app.component.delegate.FileTransferDelegate
import com.oruke.onyx.app.component.delegate.TaskOrchestrator
import com.oruke.onyx.app.filesystem.JvmFileHashService
import com.oruke.onyx.app.filesystem.JvmVfsPathService
import com.oruke.onyx.core.model.BackgroundTaskStatus
import com.oruke.onyx.core.model.FileTransferOperation
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.ViewMode
import com.oruke.onyx.vfs.api.ProviderBackedFileCommandService
import com.oruke.onyx.vfs.api.TransferConflictStrategy
import com.oruke.onyx.vfs.api.VfsProviderRegistry
import com.oruke.onyx.vfs.archive.ArchiveService
import com.oruke.onyx.vfs.local.JvmLocalFileProvider
import com.oruke.onyx.vfs.local.LocalVfsProvider
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 验证内联展开目录的父子选择裁剪、相对目录结构保留传输以及撤销重做闭环。 */
class InlineExpandedStructureTransferFlowTest {
    /**
     * 当展开 `#` 目录并同时选中 `#` 与其中两个子目录时，应裁剪父目录 `#`，
     * 仅在目标创建 `#` 结构并复制选中的两个子目录，且支持完整撤销与重做。
     */
    @Test
    fun partialSelectionUnderExpandedFolderCopiesStructureAndSelectedItemsOnly() = runBlocking {
        withTransferFixture {
            val hashDir = createDirectoryWithChildren(
                parent = sourceTv,
                name = "#",
                children = listOf(
                    "2.5次元的诱惑 (2024)",
                    "4个人各自有着自己的秘密 (2022)",
                    "9-nine- 支配者的王冠 (2025)",
                    "16bit的感动 ANOTHER LAYER (2023)",
                    "86-不存在的战区- (2021)",
                ),
            )
            val topEntries = provider.list(sourceTv.toString()).getOrThrow()
            val hashChildren = provider.list(hashDir.toString()).getOrThrow()
            val hashVFile = topEntries.single { it.name == "#" }
            val selectedChild1 = hashChildren.single { it.name == "2.5次元的诱惑 (2024)" }
            val selectedChild2 = hashChildren.single { it.name == "4个人各自有着自己的秘密 (2022)" }

            val state = expandedPaneState(
                location = sourceTv.toString(),
                topEntries = topEntries,
                expandedDir = hashVFile,
                expandedChildren = hashChildren,
                selectedIds = setOf(hashVFile.id, selectedChild1.id, selectedChild2.id),
            )
            val effective = state.effectiveSelectedEntries()
            assertEquals(
                listOf("2.5次元的诱惑 (2024)", "4个人各自有着自己的秘密 (2022)"),
                effective.map { it.name },
            )

            transfer.requestTransferEntriesToDirectory(
                entries = effective,
                targetDirectoryLocation = targetTv.toString(),
                operation = FileTransferOperation.COPY,
                clearClipboardOnSuccess = false,
                sourceBaseLocation = sourceTv.toString(),
            )
            awaitTasksCompleted()

            val targetHash = targetTv.resolve("#")
            assertTrue(Files.isDirectory(targetHash))
            assertEquals(
                "episode-2.5次元的诱惑 (2024)",
                Files.readString(targetHash.resolve("2.5次元的诱惑 (2024)/ep01.txt")),
            )
            assertEquals(
                "episode-4个人各自有着自己的秘密 (2022)",
                Files.readString(targetHash.resolve("4个人各自有着自己的秘密 (2022)/ep01.txt")),
            )
            assertFalse(Files.exists(targetHash.resolve("9-nine- 支配者的王冠 (2025)")))
            assertFalse(Files.exists(targetHash.resolve("16bit的感动 ANOTHER LAYER (2023)")))
            assertFalse(Files.exists(targetHash.resolve("86-不存在的战区- (2021)")))

            history.undoLast().getOrThrow()
            assertFalse(Files.exists(targetHash))

            history.redoLast().getOrThrow()
            assertTrue(Files.isDirectory(targetHash.resolve("2.5次元的诱惑 (2024)")))
            assertTrue(Files.isDirectory(targetHash.resolve("4个人各自有着自己的秘密 (2022)")))
            assertFalse(Files.exists(targetHash.resolve("9-nine- 支配者的王冠 (2025)")))
        }
    }

    /** 仅选中内联展开目录中的子项（未勾选父目录 `#`）时，跨目录复制同样保留 `#` 相对目录结构。 */
    @Test
    fun selectingOnlyExpandedChildrenStillPreservesRelativeParentStructure() = runBlocking {
        withTransferFixture {
            val hashDir = createDirectoryWithChildren(
                parent = sourceTv,
                name = "#",
                children = listOf("2.5次元的诱惑 (2024)", "4个人各自有着自己的秘密 (2022)", "86-不存在的战区- (2021)"),
            )
            val topEntries = provider.list(sourceTv.toString()).getOrThrow()
            val hashChildren = provider.list(hashDir.toString()).getOrThrow()
            val hashVFile = topEntries.single { it.name == "#" }
            val selectedIds = hashChildren
                .filter { it.name != "86-不存在的战区- (2021)" }
                .mapTo(linkedSetOf()) { it.id }

            val state = expandedPaneState(
                location = sourceTv.toString(),
                topEntries = topEntries,
                expandedDir = hashVFile,
                expandedChildren = hashChildren,
                selectedIds = selectedIds,
            )
            val effective = state.effectiveSelectedEntries()
            assertEquals(2, effective.size)

            transfer.requestTransferEntriesToDirectory(
                entries = effective,
                targetDirectoryLocation = targetTv.toString(),
                operation = FileTransferOperation.COPY,
                clearClipboardOnSuccess = false,
                sourceBaseLocation = sourceTv.toString(),
            )
            awaitTasksCompleted()

            assertTrue(Files.isDirectory(targetTv.resolve("#/2.5次元的诱惑 (2024)")))
            assertTrue(Files.isDirectory(targetTv.resolve("#/4个人各自有着自己的秘密 (2022)")))
            assertFalse(Files.exists(targetTv.resolve("#/86-不存在的战区- (2021)")))
        }
    }

    /** 单独选中已展开父目录或同时全选父目录与全部子项时，保留整目录语义。 */
    @Test
    fun selectingExpandedParentAloneOrWithAllChildrenKeepsWholeDirectory() = runBlocking {
        withTransferFixture {
            val hashDir = createDirectoryWithChildren(
                parent = sourceTv,
                name = "#",
                children = listOf("A1", "A2"),
            )
            val topEntries = provider.list(sourceTv.toString()).getOrThrow()
            val hashChildren = provider.list(hashDir.toString()).getOrThrow()
            val hashVFile = topEntries.single { it.name == "#" }

            val parentOnlyState = expandedPaneState(
                location = sourceTv.toString(),
                topEntries = topEntries,
                expandedDir = hashVFile,
                expandedChildren = hashChildren,
                selectedIds = setOf(hashVFile.id),
            )
            assertEquals(listOf(hashVFile), parentOnlyState.effectiveSelectedEntries())

            val fullSubtreeState = expandedPaneState(
                location = sourceTv.toString(),
                topEntries = topEntries,
                expandedDir = hashVFile,
                expandedChildren = hashChildren,
                selectedIds = setOf(hashVFile.id) + hashChildren.map { it.id },
            )
            assertEquals(listOf(hashVFile), fullSubtreeState.effectiveSelectedEntries())
        }
    }

    /** 剪切部分已展开子项并粘贴到目标目录时，仅移动选中子项并保留源目录中未选中的兄弟项。 */
    @Test
    fun movingPartialExpandedSelectionPreservesUnselectedSourceSiblingsAndSupportsUndo() = runBlocking {
        withTransferFixture {
            val hashDir = createDirectoryWithChildren(
                parent = sourceTv,
                name = "#",
                children = listOf("MoveA", "MoveB", "KeepC"),
            )
            val topEntries = provider.list(sourceTv.toString()).getOrThrow()
            val hashChildren = provider.list(hashDir.toString()).getOrThrow()
            val hashVFile = topEntries.single { it.name == "#" }
            val moveA = hashChildren.single { it.name == "MoveA" }
            val moveB = hashChildren.single { it.name == "MoveB" }

            val effective = expandedPaneState(
                location = sourceTv.toString(),
                topEntries = topEntries,
                expandedDir = hashVFile,
                expandedChildren = hashChildren,
                selectedIds = setOf(hashVFile.id, moveA.id, moveB.id),
            ).effectiveSelectedEntries()

            transfer.requestTransferEntriesToDirectory(
                entries = effective,
                targetDirectoryLocation = targetTv.toString(),
                operation = FileTransferOperation.MOVE,
                clearClipboardOnSuccess = false,
                sourceBaseLocation = sourceTv.toString(),
            )
            awaitTasksCompleted()

            assertTrue(Files.isDirectory(targetTv.resolve("#/MoveA")))
            assertTrue(Files.isDirectory(targetTv.resolve("#/MoveB")))
            assertFalse(Files.exists(hashDir.resolve("MoveA")))
            assertFalse(Files.exists(hashDir.resolve("MoveB")))
            assertTrue(Files.isDirectory(hashDir.resolve("KeepC")))

            history.undoLast().getOrThrow()
            assertTrue(Files.isDirectory(hashDir.resolve("MoveA")))
            assertTrue(Files.isDirectory(hashDir.resolve("MoveB")))
            assertTrue(Files.isDirectory(hashDir.resolve("KeepC")))
            assertFalse(Files.exists(targetTv.resolve("#")))
        }
    }

    /** 目标中已存在同名父目录 `#` 时，自动合并并在 `#` 内部精准识别同名子项冲突。 */
    @Test
    fun existingTargetIntermediateDirectoryMergesAndDetectsNestedConflict() = runBlocking {
        withTransferFixture {
            val sourceHash = createDirectoryWithChildren(
                parent = sourceTv,
                name = "#",
                children = listOf("ExistingShow", "NewShow"),
            )
            val targetHash = createDirectoryWithChildren(
                parent = targetTv,
                name = "#",
                children = listOf("ExistingShow", "UnrelatedTargetShow"),
            )
            Files.writeString(targetHash.resolve("ExistingShow/ep01.txt"), "old-target-version")

            val hashChildren = provider.list(sourceHash.toString()).getOrThrow()
            transfer.requestTransferEntriesToDirectory(
                entries = hashChildren,
                targetDirectoryLocation = targetTv.toString(),
                operation = FileTransferOperation.COPY,
                clearClipboardOnSuccess = false,
                sourceBaseLocation = sourceTv.toString(),
            )

            val conflictDialog = withTimeout(5_000L) {
                dialogState.first { it is RootDialogState.ConflictResolution }
            }
            val resolution = assertIs<RootDialogState.ConflictResolution>(conflictDialog)
            assertEquals("ExistingShow", resolution.sourceName)
            assertEquals(targetHash.toString(), resolution.targetLocation)
            assertEquals(1, resolution.total)

            transfer.resolveConflict(TransferConflictStrategy.SKIP, applyToAll = false)
            awaitTasksCompleted()

            assertEquals("old-target-version", Files.readString(targetHash.resolve("ExistingShow/ep01.txt")))
            assertEquals("episode-NewShow", Files.readString(targetHash.resolve("NewShow/ep01.txt")))
            assertTrue(Files.isDirectory(targetHash.resolve("UnrelatedTargetShow")))
        }
    }

    private fun expandedPaneState(
        location: String,
        topEntries: List<VFile>,
        expandedDir: VFile,
        expandedChildren: List<VFile>,
        selectedIds: Set<String>,
    ): PaneState {
        val expandedLocations = setOf(expandedDir.location)
        val expandedEntries = mapOf(
            expandedDir.location to InlineExpandedEntry(
                parentLocation = expandedDir.location,
                depth = 1,
                entries = expandedChildren,
            ),
        )
        val tab = createDefaultPaneTabState(
            id = "tab-1",
            title = "TV",
            location = location,
            defaultViewMode = ViewMode.DETAILS,
        )
            .withLoadedEntries(topEntries, focusEntry = null)
            .withSelectedEntryIds(selectedIds)
            .withDerivedState(expandedLocations, expandedEntries)
        return tab.toPaneState(
            paneId = PaneId.PRIMARY,
            activeTabId = tab.id,
            inlineExpandedLocations = expandedLocations,
            inlineExpandedEntries = expandedEntries,
        )
    }

    private fun createDirectoryWithChildren(
        parent: Path,
        name: String,
        children: List<String>,
    ): Path {
        val dir = Files.createDirectories(parent.resolve(name))
        children.forEach { childName ->
            val childDir = Files.createDirectories(dir.resolve(childName))
            Files.writeString(childDir.resolve("ep01.txt"), "episode-$childName")
        }
        return dir
    }

    private class TransferFixtureContext(
        val sourceTv: Path,
        val targetTv: Path,
        val provider: JvmLocalFileProvider,
        val tasks: TaskOrchestrator,
        val dialogState: MutableStateFlow<RootDialogState?>,
        val history: FileOperationHistoryDelegate,
        val transfer: FileTransferDelegate,
    ) {
        suspend fun awaitTasksCompleted() {
            withTimeout(10_000L) {
                tasks.tasks.first { list ->
                    list.isNotEmpty() && list.all { task -> task.status == BackgroundTaskStatus.SUCCEEDED }
                }
            }
        }
    }

    private suspend fun withTransferFixture(block: suspend TransferFixtureContext.() -> Unit) {
        val root = Files.createTempDirectory("onyx-inline-structure-test-")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val sourceTv = Files.createDirectories(root.resolve("source/TV"))
            val targetTv = Files.createDirectories(root.resolve("target/TV"))
            val provider = JvmLocalFileProvider()
            val commandService = ProviderBackedFileCommandService(listOf(provider))
            val pathService = JvmVfsPathService()
            val registry = VfsProviderRegistry(listOf(LocalVfsProvider(provider)))
            val tasks = TaskOrchestrator(scope)
            val clipboard = ClipboardManager(provider, pathService)
            val dialogState = MutableStateFlow<RootDialogState?>(null)
            val history = FileOperationHistoryDelegate(
                fileCommandService = commandService,
                fileRepository = provider,
                hashService = JvmFileHashService(ArchiveService()),
            )
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
                onMoveSucceeded = { completed ->
                    history.recordMove(
                        entries = completed.entries,
                        targetDirectoryLocation = completed.targetDirectoryLocation,
                        entryTargetDirectories = completed.entryTargetDirectories,
                        directorySteps = completed.directorySteps,
                        createdDirectories = completed.createdDirectories,
                    )
                },
                onCopySucceeded = { completed ->
                    history.recordCopy(
                        entries = completed.entries,
                        targetDirectoryLocation = completed.targetDirectoryLocation,
                        entryTargetDirectories = completed.entryTargetDirectories,
                        directorySteps = completed.directorySteps,
                        createdDirectories = completed.createdDirectories,
                    ).getOrThrow()
                },
                onHistoryInvalidated = { history.clear() },
            )
            TransferFixtureContext(sourceTv, targetTv, provider, tasks, dialogState, history, transfer).block()
        } finally {
            scope.cancel()
            deleteRecursivelyQuietly(root)
        }
    }

    private fun deleteRecursivelyQuietly(root: Path) {
        runCatching {
            Files.walk(root)
                .sorted(Comparator.reverseOrder())
                .forEach { path -> Files.deleteIfExists(path) }
        }
    }
}
