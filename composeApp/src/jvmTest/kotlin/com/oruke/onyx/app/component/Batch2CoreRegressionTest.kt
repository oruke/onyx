package com.oruke.onyx.app.component

import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.oruke.onyx.app.component.delegate.EntrySorter
import com.oruke.onyx.app.component.delegate.TaskOrchestrator
import com.oruke.onyx.app.filesystem.suggestAddressBarLocations
import com.oruke.onyx.core.model.BackgroundTask
import com.oruke.onyx.core.model.BackgroundTaskKind
import com.oruke.onyx.core.model.BackgroundTaskStatus
import com.oruke.onyx.core.model.DetailsColumn
import com.oruke.onyx.core.model.DetailsSort
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.PaneSessionSnapshot
import com.oruke.onyx.core.model.SortDirection
import com.oruke.onyx.core.model.StorageDevice
import com.oruke.onyx.core.model.StorageDeviceKind
import com.oruke.onyx.core.model.StorageDeviceState
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.core.model.ViewMode
import com.oruke.onyx.vfs.api.TaskPersistenceRepository
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Batch 2 核心逻辑回归测试：覆盖键盘滚动、Boundary 跳转、任务可重试标记、
 * 命令上下文门禁、修改时间预设可执行性、快速筛选空格保留与地址栏路径补全。
 */
class Batch2CoreRegressionTest {
    /** 验证方向键与 Home/End 跳转均写入 `pendingScrollToEntryId` 驱动视口自动滚动。 */
    @Test
    fun keyboardSelectionMovementSetsPendingScrollAndSupportsBoundaries() {
        val entries = (1..25).map { idx ->
            VFile(
                id = "file-$idx",
                name = "file-$idx.txt",
                location = "/workspace/file-$idx.txt",
                parentLocation = "/workspace",
                kind = VFileKind.FILE,
                sizeBytes = idx.toLong(),
                modifiedAtEpochMillis = 0L,
                hidden = false,
                capabilities = emptySet(),
            )
        }
        val initial = createDefaultPaneTabState(
            id = "tab-1",
            title = "workspace",
            location = "/workspace",
            defaultViewMode = ViewMode.DETAILS,
        )

        val stepDown = initial.moveSelectionState(entries, offset = 1, extendSelection = false)
        assertEquals("file-1", stepDown.selectionFocusId)
        assertEquals("file-1", stepDown.pendingScrollToEntryId)

        val pageDown = stepDown.moveSelectionState(entries, offset = 10, extendSelection = false)
        assertEquals("file-11", pageDown.selectionFocusId)
        assertEquals("file-11", pageDown.pendingScrollToEntryId)

        val jumpEndWithShift = pageDown.moveSelectionToBoundaryState(entries, toEnd = true, extendSelection = true)
        assertEquals("file-25", jumpEndWithShift.selectionFocusId)
        assertEquals("file-25", jumpEndWithShift.pendingScrollToEntryId)
        assertEquals("file-11", jumpEndWithShift.selectionAnchorId)
        assertEquals(15, jumpEndWithShift.selectedEntryIds.size)

        val jumpHome = jumpEndWithShift.moveSelectionToBoundaryState(entries, toEnd = false, extendSelection = false)
        assertEquals("file-1", jumpHome.selectionFocusId)
        assertEquals("file-1", jumpHome.pendingScrollToEntryId)
        assertEquals(setOf("file-1"), jumpHome.selectedEntryIds)
    }

    /** 验证此电脑设备概览方向键与 Home/End 同样写入 `pendingScrollToEntryId`。 */
    @Test
    fun computerSelectionMovementSetsPendingScrollAndBoundaryTargets() {
        val devices = listOf("dev-c", "dev-d", "dev-e").mapIndexed { index, id ->
            StorageDevice(
                id = id,
                name = "Drive $index",
                kind = StorageDeviceKind.LOCAL,
                locations = listOf("${'C' + index}:\\"),
                state = StorageDeviceState.MOUNTED,
            )
        }
        val computerTab = createDefaultPaneTabState(
            id = "tab-computer",
            title = "computer://",
            location = "computer://",
            defaultViewMode = ViewMode.GALLERY,
        ).withTabState { tab ->
            tab.copy(
                entriesState = ComputerEntriesState(
                    devices = devices,
                    visibleDevices = devices,
                    loading = false,
                ),
            )
        }

        val moved = computerTab.moveComputerSelection(offset = 1, extendSelection = false)
        assertEquals("dev-c", moved.selectionFocusId)
        assertEquals("dev-c", moved.pendingScrollToEntryId)

        val toEnd = moved.moveComputerSelectionToBoundary(toEnd = true, extendSelection = false)
        assertEquals("dev-e", toEnd.selectionFocusId)
        assertEquals("dev-e", toEnd.pendingScrollToEntryId)
    }

    /** 验证仅注册了运行时重试处理器的任务具备 `canRetry == true`，重启恢复或未注册任务一律为 `false`。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun taskOrchestratorTracksCanRetryAndClearsItOnPersistedRestore() = runTest {
        val persistedFailed = BackgroundTask(
            id = "restored-failed",
            kind = BackgroundTaskKind.COPY,
            title = I18nMessage(MessageKey.MSG_STRING_LITERAL, "old copy"),
            status = BackgroundTaskStatus.FAILED,
            canRetry = true,
            detail = I18nMessage(MessageKey.MSG_STRING_LITERAL, "failed"),
        )
        val repository = object : TaskPersistenceRepository {
            override suspend fun loadTasks(): Result<List<BackgroundTask>> = Result.success(listOf(persistedFailed))
            override suspend fun saveTasks(tasks: List<BackgroundTask>): Result<Unit> = Result.success(Unit)
        }
        val orchestrator = TaskOrchestrator(scope = this, taskRepository = repository)
        runCurrent()

        val restored = orchestrator.tasks.value.single { it.id == "restored-failed" }
        assertFalse(restored.canRetry, "持久化恢复的任务无运行时重试处理器，canRetry 必须收敛为 false")

        val deleteTask = BackgroundTask(
            id = "delete-failed",
            kind = BackgroundTaskKind.DELETE,
            title = I18nMessage(MessageKey.MSG_STRING_LITERAL, "delete"),
            status = BackgroundTaskStatus.FAILED,
            detail = I18nMessage(MessageKey.MSG_STRING_LITERAL, "denied"),
        )
        orchestrator.appendTask(deleteTask)
        assertFalse(orchestrator.tasks.value.first { it.id == "delete-failed" }.canRetry)

        val copyTask = BackgroundTask(
            id = "copy-failed",
            kind = BackgroundTaskKind.COPY,
            title = I18nMessage(MessageKey.MSG_STRING_LITERAL, "copy"),
            status = BackgroundTaskStatus.FAILED,
            detail = I18nMessage(MessageKey.MSG_STRING_LITERAL, "io error"),
        )
        orchestrator.appendTask(copyTask)
        var retried = false
        orchestrator.registerRetryHandler("copy-failed") { retried = true }
        assertTrue(orchestrator.tasks.value.first { it.id == "copy-failed" }.canRetry)

        orchestrator.retryTask("copy-failed")
        assertTrue(retried)
    }

    /** 验证只读位置与根位置禁用 `NEW_FILE`/`NEW_DIRECTORY`/`GO_UP` 且阻止派发。 */
    @Test
    fun paneCommandControllerRespectsReadOnlyAndRootExecutionContext() {
        val dispatched = mutableListOf<PaneIntent>()
        val stubComponent = object : PaneComponent {
            val initialTab = createDefaultPaneTabState("t1", "root", "s3://bucket", ViewMode.DETAILS)
            override val state: StateFlow<PaneState> = MutableStateFlow(
                initialTab.toPaneState(PaneId.PRIMARY, "t1"),
            )
            override val tabOrder: StateFlow<List<String>> = MutableStateFlow(listOf("t1"))
            override val tabStates: StateFlow<List<PaneTabState>> = MutableStateFlow(emptyList())
            override val tabStack: Value<ChildStack<TabConfig, TabComponent>> =
                MutableValue(ChildStack(initialTab.toTabSnapshot().toTabConfig(), FakeTabComponent))

            override fun dispatch(intent: PaneIntent) {
                dispatched += intent
            }

            override fun detachTab(tabId: String): PaneTabTransfer? = null
            override fun attachTab(tabSnapshot: PaneTabTransfer, targetIndex: Int) = Unit
            override fun restoreSession(snapshot: PaneSessionSnapshot) = Unit
            override fun captureRemoval(entries: List<VFile>): PaneRemovalContext? = null
            override fun completeRemoval(context: PaneRemovalContext, removedIds: Set<String>) = Unit
        }

        val controller = PaneCommandController(
            state = stubComponent.state.value,
            component = stubComponent,
            executionContext = PaneCommandExecutionContext(
                canPaste = false,
                canUndo = false,
                canRedo = false,
                canCreateEntries = false,
                canGoUp = false,
            ),
            externalActions = PaneCommandExternalActions(
                onBeginCreateDirectory = {},
                onCopySelection = {},
                onCutSelection = {},
                onPaste = {},
                onUndo = {},
                onRedo = {},
                onDeleteSelection = {},
                onToggleFavoriteLocation = {},
                onOpenSettings = {},
            ),
        )

        assertFalse(controller.isEnabled(PaneCommand.NEW_FILE))
        assertFalse(controller.isEnabled(PaneCommand.NEW_DIRECTORY))
        assertFalse(controller.isEnabled(PaneCommand.GO_UP))
        assertFalse(controller.execute(PaneCommand.GO_UP))
        assertFalse(controller.execute(PaneCommand.NEW_FILE))
        assertTrue(dispatched.isEmpty())
    }

    /** 验证修改时间预设使搜索面板判定为具备可执行条件。 */
    @Test
    fun modifiedPresetCountsAsExecutableSearchCriteria() {
        val state = SearchPanelState(
            filters = SearchFilters(modifiedPreset = SearchModifiedPreset.LAST_7_DAYS),
        )
        assertTrue(state.hasExecutableSearchCriteria())
        assertFalse(state.shouldShowSearchHistory())
    }

    /** 验证快速筛选保留尾随空格以允许键入多词文件名，同时匹配时正确忽略首尾空白。 */
    @Test
    fun filterQueryPreservesTrailingSpacesWhileMatchingTrimmedKeyword() {
        val tab = createDefaultPaneTabState("t1", "docs", "/docs", ViewMode.DETAILS)
        val typingMidPhrase = tab.withFilterQueryState("my ")
        assertEquals("my ", typingMidPhrase.filterQuery)

        val entries = listOf(
            VFile("1", "my file.txt", "/docs/my file.txt", "/docs", VFileKind.FILE, 10, 0, false, emptySet()),
            VFile("2", "other.txt", "/docs/other.txt", "/docs", VFileKind.FILE, 10, 0, false, emptySet()),
        )
        val sort = DetailsSort(DetailsColumn.NAME, SortDirection.ASCENDING)

        assertEquals(
            listOf("my file.txt"),
            EntrySorter.visibleSortedEntries(entries, true, sort, "my ").map { it.name },
        )
        assertEquals(
            listOf("my file.txt"),
            EntrySorter.visibleSortedEntries(entries, true, sort, "my file").map { it.name },
        )
    }

    /** 验证地址栏路径自动补全能枚举真实本地子目录并忽略远程 URI 避免慢 I/O。 */
    @Test
    fun addressBarSuggestsMatchingLocalSubdirectoriesAndKnownLocations(): Unit = runBlocking {
        val tempRoot = Files.createTempDirectory("onyx-addr-suggest-")
        try {
            val alphaDir = Files.createDirectory(tempRoot.resolve("AlphaDocs"))
            val betaDir = Files.createDirectory(tempRoot.resolve("BetaMedia"))
            Files.createFile(tempRoot.resolve("AlphaNote.txt"))

            val prefixInput = tempRoot.toString() + java.io.File.separator + "alp"
            val suggestions = suggestAddressBarLocations(
                rawInput = prefixInput,
                knownLocations = listOf(betaDir.toString(), "s3://archive/alpha"),
            )
            assertEquals(listOf(alphaDir.toString()), suggestions)

            val remoteSuggestions = suggestAddressBarLocations(
                rawInput = "s3://arc",
                knownLocations = listOf("s3://archive/alpha"),
            )
            assertEquals(listOf("s3://archive/alpha"), remoteSuggestions)
        } finally {
            Files.walk(tempRoot)
                .sorted(Comparator.reverseOrder())
                .forEach { path -> Files.deleteIfExists(path) }
        }
    }

    /** 测试用空标签组件占位实现。 */
    private object FakeTabComponent : TabComponent {
        override val state: StateFlow<PaneTabState> = MutableStateFlow(
            createDefaultPaneTabState("t1", "root", "s3://bucket", ViewMode.DETAILS),
        )
        override fun updateState(state: PaneTabState) = Unit
    }
}
