package com.oruke.onyx.app.component

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.create
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import com.oruke.onyx.app.component.delegate.ClipboardManager
import com.oruke.onyx.app.component.delegate.ImageViewerController
import com.oruke.onyx.app.component.delegate.SessionManager
import com.oruke.onyx.app.component.delegate.TaskOrchestrator
import com.oruke.onyx.app.platform.ExternalFileDragService
import com.oruke.onyx.core.model.AppSessionSnapshot
import com.oruke.onyx.core.model.BackgroundTask
import com.oruke.onyx.core.model.BackgroundTaskStatus
import com.oruke.onyx.core.model.FileTransferOperation
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.PaneOperationFeedbackKind
import com.oruke.onyx.core.model.StorageDevice
import com.oruke.onyx.core.model.StorageDeviceAction
import com.oruke.onyx.core.model.TRASH_LOCATION
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.core.model.buildTrashItemLocation
import com.oruke.onyx.di.fileModule
import com.oruke.onyx.vfs.api.SessionRepository
import com.oruke.onyx.vfs.api.SettingsRepository
import com.oruke.onyx.vfs.api.StorageDeviceService
import com.oruke.onyx.vfs.api.TaskPersistenceRepository
import com.oruke.onyx.vfs.api.TextClipboardService
import com.oruke.onyx.vfs.api.TrashItem
import com.oruke.onyx.vfs.api.TrashMoveRecord
import com.oruke.onyx.vfs.api.TrashRestorationStatus
import com.oruke.onyx.vfs.api.TrashRestoreOutcome
import com.oruke.onyx.vfs.api.TrashService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 回收站浏览、移动、还原、永久删除与原位置跳转端到端流程测试。
 *
 * 采用完全基于临时目录隔离的测试回收站服务驱动，严禁触碰宿主机操作系统真实回收站。
 */
class RecycleBinBrowsingFlowTest {

    /**
     * 验证回收站地址别名规范化：输入 shell:RecycleBinFolder 或 system://trash 均能正确跳转并加载回收站视图。
     */
    @Test
    fun opensTrashAndNormalizesShellAlias() = runBlocking {
        withRecycleBinFixture {
            rootComponent.dispatch(RootIntent.OpenLocationInActivePane("shell:RecycleBinFolder"))
            awaitCondition {
                primaryPaneState.location == TRASH_LOCATION &&
                    primaryPaneState.entriesState is PaneEntriesState.Ready
            }
            assertEquals(TRASH_LOCATION, primaryPaneState.location)

            rootComponent.dispatch(RootIntent.OpenLocationInActivePane(TRASH_LOCATION))
            awaitCondition {
                primaryPaneState.location == TRASH_LOCATION
            }
            assertEquals(TRASH_LOCATION, primaryPaneState.location)
        }
    }

    /**
     * 验证将本地选定条目拖放移入回收站后原文件被移动且回收站内出现该条目。
     */
    @Test
    fun movesSelectedFileToRecycleBinViaDragTransfer() = runBlocking {
        withRecycleBinFixture {
            val sample = Files.writeString(localDir.resolve("drag-doc.txt"), "hello drag")
            openLocalDir()

            val entry = awaitPaneEntryNamed("drag-doc.txt")
            rootComponent.primaryPane.dispatch(PaneIntent.SelectEntry(entry.id))

            rootComponent.dispatch(
                RootIntent.RequestTransferSelectedToDirectory(
                    sourcePaneId = PaneId.PRIMARY,
                    targetDirectoryLocation = TRASH_LOCATION,
                    operation = FileTransferOperation.MOVE,
                ),
            )
            val confirmDialog = assertIs<RootDialogState.DeleteSelectionConfirmation>(
                rootComponent.state.value.dialogState,
            )
            assertTrue(confirmDialog.moveToTrash)
            rootComponent.dispatch(RootIntent.ConfirmDialog)

            awaitCondition {
                !Files.exists(sample) && trashService.listItems().getOrThrow().size == 1
            }
            awaitTasksCompleted()

            assertFalse(Files.exists(sample), "拖入回收站后原文件应不存在")
            val items = trashService.listItems().getOrThrow()
            assertEquals(1, items.size)
            assertEquals("drag-doc.txt", items.single().displayName)

            openTrash()
            val trashEntry = awaitPaneEntryNamed("drag-doc.txt")
            assertNotNull(trashEntry)
        }
    }

    /**
     * 验证回收站内还原选定条目以及全部还原能够正确移回原目录并触发列表自动刷新。
     */
    @Test
    fun restoresSelectedAndAllItemsWithAutomaticParentRefresh() = runBlocking {
        withRecycleBinFixture {
            val fileA = Files.writeString(localDir.resolve("itemA.txt"), "contentA")
            val fileB = Files.writeString(localDir.resolve("itemB.txt"), "contentB")
            openLocalDir()

            val entryA = awaitPaneEntryNamed("itemA.txt")
            trashService.moveToTrash(listOf(entryA)).getOrThrow()
            openTrash()

            val trashedA = awaitPaneEntryNamed("itemA.txt")
            rootComponent.primaryPane.dispatch(PaneIntent.SelectEntry(trashedA.id))
            rootComponent.dispatch(RootIntent.RestoreSelectedTrashItems(PaneId.PRIMARY))
            awaitCondition {
                Files.exists(fileA) &&
                    (primaryPaneState.entriesState as? PaneEntriesState.Ready)?.entries?.isEmpty() == true
            }
            awaitTasksCompleted()

            assertTrue(Files.exists(fileA), "所选项还原后应重新出现在原目录")

            openLocalDir()
            val entryB = awaitPaneEntryNamed("itemB.txt")
            val entryARestored = awaitPaneEntryNamed("itemA.txt")
            trashService.moveToTrash(listOf(entryARestored, entryB)).getOrThrow()
            openTrash()

            awaitCondition {
                (primaryPaneState.entriesState as? PaneEntriesState.Ready)?.entries?.size == 2
            }
            rootComponent.dispatch(RootIntent.RestoreAllTrashItems(PaneId.PRIMARY))
            awaitCondition {
                Files.exists(fileA) &&
                    Files.exists(fileB) &&
                    trashService.listItems().getOrThrow().isEmpty()
            }
            awaitTasksCompleted()

            assertTrue(Files.exists(fileA))
            assertTrue(Files.exists(fileB))
            assertEquals(0, trashService.listItems().getOrThrow().size)
        }
    }

    /**
     * 验证即使关闭常规删除确认，永久删除与清空回收站依然强制弹出二次确认弹窗且确认后生效。
     */
    @Test
    fun permanentDeleteAndEmptyTrashEnforceConfirmationEvenWhenSettingDisabled() = runBlocking {
        withRecycleBinFixture {
            rootComponent.dispatch(
                RootIntent.UpdateSettings(
                    rootComponent.state.value.settings.copy(confirmBeforeDelete = false),
                ),
            )
            awaitCondition {
                !rootComponent.state.value.settings.confirmBeforeDelete
            }

            Files.writeString(localDir.resolve("delete-me.txt"), "delete-me")
            openLocalDir()
            val entry = awaitPaneEntryNamed("delete-me.txt")
            trashService.moveToTrash(listOf(entry)).getOrThrow()
            openTrash()

            val trashed = awaitPaneEntryNamed("delete-me.txt")
            rootComponent.primaryPane.dispatch(PaneIntent.SelectEntry(trashed.id))
            rootComponent.dispatch(RootIntent.RequestPermanentDeleteTrashItems(PaneId.PRIMARY))

            val deleteDialog = rootComponent.state.value.dialogState
            assertIs<RootDialogState.TrashPermanentDeleteConfirmation>(deleteDialog)
            assertEquals(1, deleteDialog.itemCount)

            rootComponent.dispatch(RootIntent.ConfirmDialog)
            awaitCondition {
                trashService.listItems().getOrThrow().isEmpty()
            }
            awaitTasksCompleted()
            assertEquals(0, trashService.listItems().getOrThrow().size)

            Files.writeString(localDir.resolve("bulk1.txt"), "bulk1")
            openLocalDir()
            val entry1 = awaitPaneEntryNamed("bulk1.txt")
            trashService.moveToTrash(listOf(entry1)).getOrThrow()
            openTrash()
            awaitPaneEntryNamed("bulk1.txt")

            rootComponent.dispatch(RootIntent.RequestEmptyTrash(PaneId.PRIMARY))
            val emptyDialog = rootComponent.state.value.dialogState
            assertIs<RootDialogState.EmptyTrashConfirmation>(emptyDialog)

            rootComponent.dispatch(RootIntent.ConfirmDialog)
            awaitCondition {
                trashService.listItems().getOrThrow().isEmpty()
            }
            awaitTasksCompleted()
            assertEquals(0, trashService.listItems().getOrThrow().size)
        }
    }

    /**
     * 验证打开原位置行为：原父目录存在时顺利导航，原父目录不存在时展示本地化操作失败反馈。
     */
    @Test
    fun openOriginalLocationNavigatesWhenPresentOrShowsFeedbackWhenMissing() = runBlocking {
        withRecycleBinFixture {
            val subDir = Files.createDirectories(localDir.resolve("nested-sub"))
            val targetFile = Files.writeString(subDir.resolve("inside.txt"), "nested content")
            openLocalDir()

            val subEntry = VFile(
                id = targetFile.toString(),
                name = "inside.txt",
                location = targetFile.toString(),
                parentLocation = subDir.toString(),
                kind = VFileKind.FILE,
                sizeBytes = 14L,
                modifiedAtEpochMillis = 1000L,
                hidden = false,
                capabilities = emptySet(),
            )
            trashService.moveToTrash(listOf(subEntry)).getOrThrow()
            openTrash()

            val trashedEntry = awaitPaneEntryNamed("inside.txt")
            rootComponent.dispatch(RootIntent.OpenTrashItemOriginalLocation(PaneId.PRIMARY, trashedEntry))
            awaitCondition {
                primaryPaneState.location == subDir.toString()
            }
            assertEquals(subDir.toString(), primaryPaneState.location)

            deleteRecursivelyQuietly(subDir)
            openTrash()
            val trashedAgain = awaitPaneEntryNamed("inside.txt")
            rootComponent.dispatch(RootIntent.OpenTrashItemOriginalLocation(PaneId.PRIMARY, trashedAgain))

            awaitCondition {
                val feedback = primaryPaneState.operationFeedback
                feedback?.kind == PaneOperationFeedbackKind.OPEN_FAILED &&
                    feedback.detail?.key == MessageKey.MSG_TRASH_ORIGINAL_LOCATION_UNAVAILABLE
            }
            val feedback = checkNotNull(primaryPaneState.operationFeedback)
            assertEquals(PaneOperationFeedbackKind.OPEN_FAILED, feedback.kind)
            assertEquals(MessageKey.MSG_TRASH_ORIGINAL_LOCATION_UNAVAILABLE, feedback.detail?.key)
        }
    }

    /**
     * 验证在回收站中复制原始路径能将准确路径写入系统文本剪贴板。
     */
    @Test
    fun copiesOriginalLocationPathsToClipboard() = runBlocking {
        withRecycleBinFixture {
            val sample = Files.writeString(localDir.resolve("copied-origin.txt"), "sample")
            openLocalDir()
            val entry = awaitPaneEntryNamed("copied-origin.txt")
            trashService.moveToTrash(listOf(entry)).getOrThrow()
            openTrash()

            val trashed = awaitPaneEntryNamed("copied-origin.txt")
            rootComponent.primaryPane.dispatch(PaneIntent.SelectEntry(trashed.id))
            rootComponent.dispatch(RootIntent.CopyTrashItemOriginalPaths(PaneId.PRIMARY))

            awaitCondition {
                clipboard.text != null
            }
            assertEquals(sample.toAbsolutePath().toString(), clipboard.text)
        }
    }

    /**
     * 验证在回收站虚拟位置内禁用新建文件、新建目录、粘贴与重命名等命令。
     */
    @Test
    fun disablesModificationCommandsInsideRecycleBin() = runBlocking {
        withRecycleBinFixture {
            openTrash()
            val commands = PaneCommandController(
                state = primaryPaneState,
                component = rootComponent.primaryPane,
                executionContext = PaneCommandExecutionContext(
                    canPaste = true,
                    canUndo = true,
                    canRedo = true,
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
            assertFalse(commands.isEnabled(PaneCommand.NEW_FILE), "回收站内禁止新建文件")
            assertFalse(commands.isEnabled(PaneCommand.NEW_DIRECTORY), "回收站内禁止新建目录")
            assertFalse(commands.isEnabled(PaneCommand.PASTE), "回收站内禁止粘贴")
            assertFalse(commands.isEnabled(PaneCommand.RENAME_SELECTION), "回收站内禁止重命名")
            assertFalse(commands.isEnabled(PaneCommand.CREATE_DIRECTORIES), "回收站内禁止批量建目录")
            assertTrue(commands.isEnabled(PaneCommand.REFRESH), "回收站内允许刷新")
        }
    }

    private class RecycleBinFixtureContext(
        val tempDir: Path,
        val localDir: Path,
        val trashService: IsolatedTempTrashService,
        val clipboard: InMemoryClipboardService,
        val rootComponent: DefaultRootComponent,
    ) {
        val primaryPaneState: PaneState get() = rootComponent.primaryPane.state.value

        suspend fun openLocalDir() {
            rootComponent.dispatch(RootIntent.OpenLocationInActivePane(localDir.toString()))
            awaitCondition {
                primaryPaneState.location == localDir.toString() &&
                    primaryPaneState.entriesState is PaneEntriesState.Ready
            }
        }

        suspend fun openTrash() {
            rootComponent.dispatch(RootIntent.OpenLocationInActivePane(TRASH_LOCATION))
            awaitCondition {
                primaryPaneState.location == TRASH_LOCATION &&
                    primaryPaneState.entriesState is PaneEntriesState.Ready
            }
        }

        suspend fun awaitPaneEntryNamed(name: String): VFile {
            var found: VFile? = null
            awaitCondition {
                val ready = primaryPaneState.entriesState as? PaneEntriesState.Ready
                found = ready?.entries?.firstOrNull { it.name == name }
                found != null
            }
            return checkNotNull(found)
        }

        suspend fun awaitTasksCompleted() {
            withTimeout(10_000L) {
                while (rootComponent.state.value.tasks.any {
                    it.status == BackgroundTaskStatus.RUNNING || it.status == BackgroundTaskStatus.QUEUED
                }) {
                    delay(30)
                }
            }
        }

        suspend fun awaitCondition(timeoutMs: Long = 10_000L, condition: suspend () -> Boolean) {
            withTimeout(timeoutMs) {
                while (!condition()) {
                    delay(30)
                }
            }
        }
    }

    private suspend fun withRecycleBinFixture(block: suspend RecycleBinFixtureContext.() -> Unit) {
        withContext(Dispatchers.Main) {
            val tempDir = Files.createTempDirectory("onyx-recycle-bin-test-").normalize().toAbsolutePath()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val lifecycle = LifecycleRegistry()
            var externalDrag: ExternalFileDragService? = null
            var runtime: RootApplicationRuntime? = null
            var koinClose: (() -> Unit)? = null
            try {
                val localDir = Files.createDirectories(tempDir.resolve("local"))
                val trashStorageDir = Files.createDirectories(tempDir.resolve("isolated-trash"))
                val testTrashService = IsolatedTempTrashService(trashStorageDir)
                val clipboard = InMemoryClipboardService()
                val memorySettings = InMemorySettingsRepository()
                val memorySession = InMemorySessionRepository()
                val memoryTasks = InMemoryTaskPersistenceRepository()

                val koinApp = koinApplication {
                    allowOverride(true)
                    modules(fileModule)
                    modules(
                        module {
                            single<TrashService> { testTrashService }
                            single<SettingsRepository> { memorySettings }
                            single<SessionRepository> { memorySession }
                            single<TaskPersistenceRepository> { memoryTasks }
                            single<StorageDeviceService> { NoopStorageDeviceService }
                            single<TextClipboardService> { clipboard }
                        },
                    )
                }
                koinClose = koinApp::close
                val koin = koinApp.koin
                val dragService = koin.get<ExternalFileDragService>()
                externalDrag = dragService
                val taskOrchestrator = TaskOrchestrator(
                    scope = scope,
                    taskRepository = memoryTasks,
                )
                val clipboardManager = ClipboardManager(koin.get(), koin.get())
                val sessionManager = SessionManager(
                    settingsRepository = memorySettings,
                    sessionRepository = memorySession,
                )
                val settingsState = MutableStateFlow(OnyxSettings())
                val appRuntime = RootApplicationRuntime(
                    scope = scope,
                    taskOrchestrator = taskOrchestrator,
                    clipboardManager = clipboardManager,
                    sessionManager = sessionManager,
                    settings = settingsState,
                )
                runtime = appRuntime
                val imageViewerController = ImageViewerController()
                val componentContext = DefaultComponentContext(lifecycle = lifecycle)
                val launchConfig = RootLaunchConfiguration(
                    initialLocation = localDir.toString(),
                    persistsMainSession = false,
                )
                val root = DefaultRootComponent(
                    componentContext = componentContext,
                    dependencies = DefaultRootDependencies(
                        files = RootFileDependencies(
                            fileRepository = koin.get(),
                            fileCommandService = koin.get(),
                            textClipboardService = clipboard,
                            trashService = testTrashService,
                            externalOpenService = koin.get(),
                            archiveService = koin.get(),
                            zipArchiveCreationService = koin.get(),
                            fileTypeService = koin.get(),
                            archiveEntryOpenService = koin.get(),
                            entryNameSuggestionService = koin.get(),
                        ),
                        platform = RootPlatformDependencies(
                            settingsRepository = memorySettings,
                            sessionRepository = memorySession,
                            openWithService = koin.get(),
                            systemMenuService = koin.get(),
                            fileContextMenuService = koin.get(),
                            externalFileDragService = dragService,
                            pathService = koin.get(),
                            providerRegistry = koin.get(),
                            terminalLauncherService = koin.get(),
                            systemQuickAccessService = koin.get(),
                            storageDevices = NoopStorageDeviceService,
                        ),
                        inspection = RootInspectionDependencies(
                            previewService = koin.get(),
                            fileHashService = koin.get(),
                            archiveInfoService = koin.get(),
                            thumbnailService = koin.get(),
                            imageMetadataService = koin.get(),
                            connectionTestService = koin.get(),
                            remoteAuthStore = koin.get(),
                            s3ConnectionRepository = koin.get(),
                        ),
                        search = RootSearchDependencies(
                            fileCollectionUseCase = koin.get(),
                            fileContentSearchService = koin.get(),
                        ),
                        customization = RootCustomizationDependencies(
                            labels = koin.get(),
                            commands = koin.get(),
                            commandLauncher = koin.get(),
                        ),
                        delegates = RootRuntimeDelegates(
                            directoryStore = appRuntime.directories(koin.get(), koin.get()),
                            taskOrchestrator = appRuntime.taskOrchestrator,
                            clipboardManager = appRuntime.clipboardManager,
                            imageViewerController = imageViewerController,
                            sessionManager = appRuntime.sessionManager,
                            settings = appRuntime.settings,
                            taskCenter = appRuntime.taskCenter,
                            resourceLocale = appRuntime.resourceLocale,
                        ),
                    ),
                    launchConfiguration = launchConfig,
                )
                lifecycle.create()
                lifecycle.resume()

                val context = RecycleBinFixtureContext(
                    tempDir = tempDir,
                    localDir = localDir,
                    trashService = testTrashService,
                    clipboard = clipboard,
                    rootComponent = root,
                )
                context.awaitCondition {
                    root.state.value.sessionRestoreState == SessionRestoreState.Ready
                }
                context.block()
            } finally {
                runCatching { lifecycle.destroy() }
                runCatching { externalDrag?.dispose() }
                runCatching { runtime?.close() }
                runCatching { koinClose?.invoke() }
                deleteRecursivelyQuietly(tempDir)
            }
        }
    }

    private class InMemoryClipboardService : TextClipboardService {
        var text: String? = null
        override suspend fun copyText(text: String): Result<Unit> {
            this.text = text
            return Result.success(Unit)
        }
    }

    private class InMemorySettingsRepository : SettingsRepository {
        var current: OnyxSettings = OnyxSettings()
        override suspend fun loadSettings(): Result<OnyxSettings?> = Result.success(current)
        override suspend fun saveSettings(settings: OnyxSettings): Result<Unit> {
            current = settings
            return Result.success(Unit)
        }
    }

    private class InMemorySessionRepository : SessionRepository {
        var current: AppSessionSnapshot? = null
        override suspend fun loadSession(): Result<AppSessionSnapshot?> = Result.success(current)
        override suspend fun saveSession(snapshot: AppSessionSnapshot): Result<Unit> {
            current = snapshot
            return Result.success(Unit)
        }
    }

    private class InMemoryTaskPersistenceRepository : TaskPersistenceRepository {
        override suspend fun loadTasks(): Result<List<BackgroundTask>> = Result.success(emptyList())
        override suspend fun saveTasks(tasks: List<BackgroundTask>): Result<Unit> = Result.success(Unit)
    }

    private object NoopStorageDeviceService : StorageDeviceService {
        override fun cachedSnapshot(): List<StorageDevice> = emptyList()
        override fun observe(): Flow<Result<List<StorageDevice>>> = flowOf(Result.success(emptyList()))
        override suspend fun snapshot(): Result<List<StorageDevice>> = Result.success(emptyList())
        override suspend fun execute(deviceId: String, action: StorageDeviceAction): Result<String?> = Result.success(null)
    }

    private class IsolatedTempTrashService(
        private val trashDir: Path,
    ) : TrashService {
        override val isSupported: Boolean = true

        private data class StoredItem(
            val item: TrashItem,
            val trashedPath: Path,
        )

        private val lock = Any()
        private val items = LinkedHashMap<String, StoredItem>()

        override suspend fun moveToTrash(entries: List<VFile>): Result<List<TrashMoveRecord>> =
            synchronized(lock) {
                runCatching {
                    Files.createDirectories(trashDir)
                    entries.map { entry ->
                        val source = Path.of(entry.location).normalize().toAbsolutePath()
                        val target = trashDir.resolve("${System.nanoTime()}-${source.fileName}")
                        Files.move(source, target)
                        val id = buildTrashItemLocation(target.fileName.toString())
                        val trashItem = TrashItem(
                            id = id,
                            displayName = entry.name,
                            originalLocation = source.toString(),
                            originalParentLocation = source.parent?.toString(),
                            kind = entry.kind,
                            sizeBytes = entry.sizeBytes,
                            deletedAtEpochMillis = System.currentTimeMillis(),
                        )
                        items[id] = StoredItem(trashItem, target)
                        TrashMoveRecord(
                            originalEntry = entry,
                            trashedLocation = target.toString(),
                            metadataLocation = null,
                            restorationStatus = TrashRestorationStatus.AVAILABLE,
                        )
                    }
                }
            }

        override suspend fun restoreFromTrash(records: List<TrashMoveRecord>): Result<Unit> =
            synchronized(lock) {
                runCatching {
                    records.forEach { record ->
                        val match = items.entries.firstOrNull {
                            it.value.trashedPath.toString() == record.trashedLocation
                        }
                        if (match != null) {
                            restoreItemsLocked(setOf(match.key))
                        }
                    }
                }
            }

        override suspend fun listItems(): Result<List<TrashItem>> =
            synchronized(lock) {
                Result.success(items.values.map { it.item })
            }

        override suspend fun restoreItems(entryIds: Set<String>): Result<TrashRestoreOutcome> =
            synchronized(lock) {
                runCatching { restoreItemsLocked(entryIds) }
            }

        private fun restoreItemsLocked(entryIds: Set<String>): TrashRestoreOutcome {
            val restored = mutableListOf<TrashItem>()
            val affectedParents = linkedSetOf<String>()
            entryIds.forEach { id ->
                val stored = items[id] ?: return@forEach
                val originalPath = stored.item.originalLocation?.let(Path::of) ?: return@forEach
                val parent = originalPath.parent
                if (parent != null && !Files.exists(parent)) {
                    Files.createDirectories(parent)
                }
                if (parent != null) affectedParents += parent.toString()
                Files.move(stored.trashedPath, originalPath)
                restored += stored.item
                items.remove(id)
            }
            return TrashRestoreOutcome(restored, affectedParents)
        }

        override suspend fun deletePermanently(entryIds: Set<String>): Result<Int> =
            synchronized(lock) {
                runCatching {
                    var deleted = 0
                    entryIds.forEach { id ->
                        val stored = items.remove(id) ?: return@forEach
                        deleteRecursivelyQuietly(stored.trashedPath)
                        deleted++
                    }
                    deleted
                }
            }

        override suspend fun emptyTrash(): Result<Int> =
            synchronized(lock) {
                runCatching {
                    val count = items.size
                    items.values.forEach { stored ->
                        deleteRecursivelyQuietly(stored.trashedPath)
                    }
                    items.clear()
                    count
                }
            }
    }

    private companion object {
        fun deleteRecursivelyQuietly(path: Path) {
            runCatching {
                if (!Files.exists(path)) return
                Files.walk(path)
                    .sorted(Comparator.reverseOrder())
                    .forEach { child -> Files.deleteIfExists(child) }
            }
        }
    }
}
