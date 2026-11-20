package com.oruke.onyx.app.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.create
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import com.oruke.onyx.app.component.delegate.ArchiveActionDelegate
import com.oruke.onyx.app.component.delegate.ClipboardManager
import com.oruke.onyx.app.component.delegate.FileOperationHistoryDelegate
import com.oruke.onyx.app.component.delegate.ImageViewerController
import com.oruke.onyx.app.component.delegate.PlannedTransferStructure
import com.oruke.onyx.app.component.delegate.SessionManager
import com.oruke.onyx.app.component.delegate.TaskOrchestrator
import com.oruke.onyx.app.component.delegate.TransferStructurePlanner
import com.oruke.onyx.app.component.delegate.TrashActionDelegate
import com.oruke.onyx.app.component.delegate.TrashActionDelegateCallbacks
import com.oruke.onyx.app.filesystem.JvmUserCommandLauncherService
import com.oruke.onyx.app.platform.ExternalFileDragService
import com.oruke.onyx.app.platform.OnyxSystemTrayController
import com.oruke.onyx.app.platform.TrayMenuLabels
import com.oruke.onyx.core.model.AppSessionSnapshot
import com.oruke.onyx.core.model.BackgroundTask
import com.oruke.onyx.core.model.BackgroundTaskStatus
import com.oruke.onyx.core.model.CustomThemePreset
import com.oruke.onyx.core.model.DetailsColumn
import com.oruke.onyx.core.model.LocalizedException
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.core.model.OnyxThemeColors
import com.oruke.onyx.core.model.OnyxThemePreset
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.RemoteConnectionProfile
import com.oruke.onyx.core.model.RemoteConnectionProtocol
import com.oruke.onyx.core.model.SidebarSectionKey
import com.oruke.onyx.core.model.StorageDevice
import com.oruke.onyx.core.model.StorageDeviceAction
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileCapability
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.di.fileModule
import com.oruke.onyx.shared.usecase.UserCommandInvocation
import com.oruke.onyx.ui.SettingsCategory
import com.oruke.onyx.ui.mergeEditableSettingsFrom
import com.oruke.onyx.ui.resetCategoryToDefaults
import com.oruke.onyx.ui.selectVisibleBreadcrumbIndices
import com.oruke.onyx.ui.theme.contrastTextOn
import com.oruke.onyx.ui.theme.resolveEffectiveDetailsColumnWidths
import com.oruke.onyx.ui.theme.resolveOnyxPalette
import com.oruke.onyx.vfs.api.FileCommandService
import com.oruke.onyx.vfs.api.FileRepository
import com.oruke.onyx.vfs.api.SessionRepository
import com.oruke.onyx.vfs.api.SettingsRepository
import com.oruke.onyx.vfs.api.StorageDeviceService
import com.oruke.onyx.vfs.api.TaskPersistenceRepository
import com.oruke.onyx.vfs.api.TextClipboardService
import com.oruke.onyx.vfs.api.TrashItem
import com.oruke.onyx.vfs.api.TrashMoveRecord
import com.oruke.onyx.vfs.api.TrashRestoreOutcome
import com.oruke.onyx.vfs.api.TrashService
import com.oruke.onyx.vfs.api.TransferConflictStrategy
import com.oruke.onyx.vfs.archive.ArchiveService
import com.oruke.onyx.vfs.local.JvmLocalFileProvider
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Batch 3 核心逻辑回归测试。
 *
 * 覆盖：
 * - `SET-03` / `SET-04`: 设置三路合并保护运行期状态与 `verboseLoggingEnabled` 草稿闭环
 * - `SET-06`: 远程工作目录启动用户命令返回 `MSG_USER_COMMAND_LOCAL_DIRECTORY_REQUIRED`
 * - `OPS-006`: 压缩包解压至远程 VFS 面板时经本地临时目录提取并回传上传且清理临时目录
 * - `OPS-007`: 历史撤销部分失败拆包不卡栈、过期记录自动移除释放早期历史
 * - `OPS-009`: 回收站批量还原/永久删除逐项容错并向 `BackgroundTask.errors` 写入单条失败详情
 * - `OPS-010`: Windows 本地与 SMB 冲突检测大小写不敏感，S3 保持大小写敏感
 * - `OPS-011`: 批量新建目录路径穿越、绝对路径、驱动器号与非法字符校验
 * - `PLAT-01`: 托盘左键单击/双击防抖窗口与本地化文案模型
 * - `REM-04`: `RemoteConnectionLocation` 特殊字符编解码且仍拒绝 S3 query
 * - `VIS-02`: 面包屑窄宽折叠计算与详情列视口自适应宽度收敛
 * - `THEME-01`: `contrastTextOn` / `OnyxPalette.onAccent` 与浅色次要文字高对比度
 */
class Batch3CoreRegressionTest {
    /** 验证设置确认三路合并保留设置打开期间的后台运行期变更，并正确提交用户编辑项与详细日志开关（SET-03 / SET-04）。 */
    @Test
    fun mergeEditableSettingsPreservesLiveRuntimeFieldsAndAppliesVerboseLogging() {
        val initialDraft = OnyxSettings(
            uiScale = 100,
            verboseLoggingEnabled = false,
            autoCloseTaskCenterWhenFinished = true,
            recentLocations = listOf("C:\\a"),
            favoriteLocations = listOf("C:\\fav-old", "C:\\fav-keep"),
            searchHistory = listOf("old"),
            keepSearchWindowOpen = false,
            mainWindowWidth = 1280,
        )
        val liveLatest = initialDraft.copy(
            recentLocations = listOf("C:\\a", "C:\\newly-visited"),
            favoriteLocations = listOf("C:\\fav-old", "C:\\fav-keep", "C:\\fav-new"),
            searchHistory = listOf("latest-query", "old"),
            remoteConnections = listOf(
                RemoteConnectionProfile(
                    id = "r1",
                    name = "NAS",
                    protocol = RemoteConnectionProtocol.WEBDAV,
                    location = "webdav://nas.local/files/",
                ),
            ),
            collapsedSidebarSections = setOf(SidebarSectionKey.RECENT),
            keepSearchWindowOpen = true,
            autoCloseTaskCenterWhenFinished = false,
            mainWindowWidth = 1440,
        )
        val editedDraft = initialDraft.copy(
            uiScale = 125,
            verboseLoggingEnabled = true,
            favoriteLocations = listOf("C:\\fav-keep"),
        )

        val merged = liveLatest.mergeEditableSettingsFrom(draft = editedDraft, initialDraft = initialDraft)

        assertEquals(125, merged.uiScale)
        assertTrue(merged.verboseLoggingEnabled)
        assertEquals(listOf("C:\\a", "C:\\newly-visited"), merged.recentLocations)
        assertEquals(listOf("latest-query", "old"), merged.searchHistory)
        assertEquals(1, merged.remoteConnections.size)
        assertEquals(setOf(SidebarSectionKey.RECENT), merged.collapsedSidebarSections)
        assertTrue(merged.keepSearchWindowOpen)
        assertFalse(merged.autoCloseTaskCenterWhenFinished)
        assertEquals(1440, merged.mainWindowWidth)
        assertEquals(listOf("C:\\fav-keep", "C:\\fav-new"), merged.favoriteLocations)

        val withCustom = merged.copy(
            customThemes = listOf(
                CustomThemePreset(id = "c1", name = "MyTheme", basePreset = OnyxThemePreset.DEFAULT),
            ),
        )
        assertFalse(withCustom.resetCategoryToDefaults(SettingsCategory.SHORTCUTS_ADVANCED).verboseLoggingEnabled)
        assertEquals(1, withCustom.resetCategoryToDefaults(SettingsCategory.THEME_APPEARANCE).customThemes.size)
    }

    /** 验证批量新建目录严格拦截绝对路径、盘符、`.`/`..` 及非法字符，放行合法相对层级（OPS-011）。 */
    @Test
    fun validateCreateDirectoriesRejectsUnsafePathsAndAcceptsRelativeTrees() {
        assertEquals(
            CreateDirectoriesDialogError.EMPTY_INPUT,
            validateCreateDirectoriesDraft("   \n  ").error,
        )
        val unsafeInputs = listOf(
            "/etc/evil",
            "C:\\Windows\\System32",
            "docs/../escape",
            "docs/.",
            "bad:name",
            "bad?name",
            "bad*name",
            "bad<name>",
        )
        unsafeInputs.forEach { raw ->
            assertEquals(
                CreateDirectoriesDialogError.INVALID_PATH,
                validateCreateDirectoriesDraft(raw).error,
                "应拒绝不安全路径: $raw",
            )
        }

        val valid = validateCreateDirectoriesDraft("src/main/kotlin\nassets\\images\n")
        assertNull(valid.error)
        assertEquals(listOf("src/main/kotlin", "assets/images"), valid.paths)
    }

    /** 验证跨面板冲突检测在 Windows 本地与 SMB 下忽略大小写，在 S3 下保持大小写敏感（OPS-010）。 */
    @Test
    fun transferStructurePlannerMatchesCaseInsensitivelyOnWindowsAndSmbButNotS3() = runBlocking {
        assertTrue(isCaseInsensitiveVfsLocation("smb://nas.local/share/docs"))
        assertFalse(isCaseInsensitiveVfsLocation("s3://bucket/docs/"))

        val incoming = testFile("in-1", "README.TXT", "/src/README.TXT", "/src")
        val existingOnTarget = listOf(testFile("ex-1", "readme.txt", "smb://nas/share/readme.txt", "smb://nas/share"))
        val fakeRepo = object : FileRepository {
            override fun defaultLocation(): String = "/"
            override suspend fun list(location: String): Result<List<VFile>> = Result.success(existingOnTarget)
        }

        val smbConflicts = TransferStructurePlanner.detectConflictingEntries(
            plan = PlannedTransferStructure(
                entries = listOf(incoming),
                targetDirectoryLocation = "smb://nas/share",
            ),
            fileRepository = fakeRepo,
        )
        assertEquals(listOf("in-1"), smbConflicts.map { it.id })

        val s3Conflicts = TransferStructurePlanner.detectConflictingEntries(
            plan = PlannedTransferStructure(
                entries = listOf(incoming),
                targetDirectoryLocation = "s3://bucket/docs/",
            ),
            fileRepository = fakeRepo,
        )
        assertTrue(s3Conflicts.isEmpty(), "S3 应保持大小写敏感，不应将 README.TXT 误判为与 readme.txt 冲突")
    }

    /** 验证批量重命名撤销部分失败时拆分已成功子集推入重做栈，且失败记录不再永久卡死更早历史（OPS-007）。 */
    @Test
    fun historyDelegateSplitsPartialBatchFailureAndUnblocksEarlierHistory() = runTest {
        val fs = PartialFailureHistoryFileSystem()
        val older = fs.addFile("/dir", "older.txt")
        val olderRenamed = fs.rename(older, "older-v2.txt").getOrThrow()

        val first = fs.addFile("/dir", "first.txt")
        val second = fs.addFile("/dir", "second.txt")
        fs.rename(first, "first-new.txt").getOrThrow()
        fs.rename(second, "second-new.txt").getOrThrow()

        val delegate = FileOperationHistoryDelegate(fileCommandService = fs, fileRepository = fs)
        delegate.recordRename(older, olderRenamed.name)
        delegate.recordBatchRename(listOf(first to "first-new.txt", second to "second-new.txt"))

        // RenameBatch 撤销按逆序执行（先 second-new.txt 再 first-new.txt）：
        // 让第二项 first-new.txt 失败，则 second.txt 已成功还原并进入重做栈，失败步被丢弃且更早历史不受阻塞。
        fs.failingRenameNames += "first-new.txt"
        val partialResult = delegate.undoLast()
        assertTrue(partialResult.isFailure)
        assertTrue(fs.exists("/dir", "second.txt"), "未失败的条目应已完成反向还原")
        assertTrue(delegate.state.value.canRedo, "已成功撤销的子集应进入重做栈")
        assertTrue(delegate.state.value.canUndo, "更早的合法历史记录应仍可继续撤销")

        val earlierResult = delegate.undoLast()
        assertTrue(earlierResult.isSuccess)
        assertTrue(fs.exists("/dir", "older.txt"))
        assertFalse(delegate.state.value.canUndo)
    }

    /** 验证回收站批量还原与永久删除在单条失败时继续完成其余条目并收集结构化错误（OPS-009）。 */
    @Test
    fun trashActionDelegateProcessesPerItemAndRecordsStructuredErrorsOnPartialFailure() = runBlocking {
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val orchestrator = TaskOrchestrator(scope = ioScope)
            val refreshed = mutableSetOf<String>()
            val okEntry = testFile("t-ok", "ok.txt", "trash://t-ok", "C:\\docs")
            val badEntry = testFile("t-bad", "bad.txt", "trash://t-bad", "C:\\docs")
            val fakeTrash = object : TrashService {
                override val isSupported: Boolean = true
                override suspend fun moveToTrash(entries: List<VFile>): Result<List<TrashMoveRecord>> = Result.success(emptyList())
                override suspend fun restoreFromTrash(records: List<TrashMoveRecord>): Result<Unit> = Result.success(Unit)
                override suspend fun restoreItems(entryIds: Set<String>): Result<TrashRestoreOutcome> {
                    val id = entryIds.single()
                    return if (id == "t-bad") {
                        Result.failure(IllegalStateException("Target exists: bad.txt"))
                    } else {
                        Result.success(
                            TrashRestoreOutcome(
                                restoredItems = listOf(
                                    TrashItem(
                                        id = id,
                                        displayName = "ok.txt",
                                        kind = VFileKind.FILE,
                                        originalLocation = "C:\\docs\\ok.txt",
                                        originalParentLocation = "C:\\docs",
                                        sizeBytes = 10L,
                                        deletedAtEpochMillis = 0L,
                                    ),
                                ),
                                affectedParentLocations = setOf("C:\\docs"),
                            ),
                        )
                    }
                }

                override suspend fun deletePermanently(entryIds: Set<String>): Result<Int> {
                    val id = entryIds.single()
                    return if (id == "t-bad") {
                        Result.failure(IllegalStateException("Access denied: bad.txt"))
                    } else {
                        Result.success(1)
                    }
                }
            }
            val removedCapture = mutableListOf<Set<String>>()
            val delegate = TrashActionDelegate(
                trashService = fakeTrash,
                taskOrchestrator = orchestrator,
                callbacks = TrashActionDelegateCallbacks(
                    onRefreshDirectories = { refreshed += it },
                    onRemovalFinished = { _, removed, _ -> removedCapture += removed },
                ),
            )

            delegate.restoreItems(PaneId.PRIMARY, listOf(okEntry, badEntry), setOf("t-ok", "t-bad"))
            val restoreTask = withTimeout(5_000L) {
                orchestrator.tasks.first { list ->
                    list.firstOrNull()?.status in setOf(BackgroundTaskStatus.SUCCEEDED, BackgroundTaskStatus.FAILED)
                }.first()
            }
            assertEquals(BackgroundTaskStatus.FAILED, restoreTask.status)
            assertEquals(MessageKey.MSG_TRASH_PARTIAL_RESTORE, restoreTask.detail.key)
            assertEquals(1, restoreTask.processedCount)
            assertEquals(1, restoreTask.errors.size)
            assertEquals("bad.txt", restoreTask.errors.single().fileName)
            assertEquals(setOf("t-ok"), removedCapture.last())

            delegate.deletePermanently(PaneId.PRIMARY, listOf(okEntry, badEntry), setOf("t-ok", "t-bad"))
            val deleteTask = withTimeout(5_000L) {
                orchestrator.tasks.first { list ->
                    list.size >= 2 && list.first().status in setOf(BackgroundTaskStatus.SUCCEEDED, BackgroundTaskStatus.FAILED)
                }.first()
            }
            assertEquals(BackgroundTaskStatus.FAILED, deleteTask.status)
            assertEquals(MessageKey.MSG_TRASH_PARTIAL_DELETE, deleteTask.detail.key)
            assertEquals(1, deleteTask.processedCount)
            assertEquals(1, deleteTask.errors.size)
            assertEquals("bad.txt", deleteTask.errors.single().fileName)

            // 验证回收站返回空集（目标已被外部清空）时产出本地化 MSG_TRASH_ITEM_UNAVAILABLE，无硬编码英文（REV-07）
            val emptyTrash = object : TrashService {
                override val isSupported: Boolean = true
                override suspend fun moveToTrash(entries: List<VFile>) = Result.success(emptyList<TrashMoveRecord>())
                override suspend fun restoreFromTrash(records: List<TrashMoveRecord>) = Result.success(Unit)
                override suspend fun restoreItems(entryIds: Set<String>) =
                    Result.success(TrashRestoreOutcome(emptyList(), emptySet()))

                override suspend fun deletePermanently(entryIds: Set<String>) = Result.success(0)
            }
            val missingDelegate = TrashActionDelegate(
                trashService = emptyTrash,
                taskOrchestrator = orchestrator,
                callbacks = TrashActionDelegateCallbacks(onRefreshDirectories = {}),
            )
            missingDelegate.restoreItems(PaneId.PRIMARY, listOf(okEntry), setOf("t-ok"))
            val missingTask = withTimeout(5_000L) {
                orchestrator.tasks.first { list ->
                    list.size >= 3 && list.first().status == BackgroundTaskStatus.FAILED
                }.first()
            }
            assertEquals(MessageKey.MSG_TRASH_ITEM_UNAVAILABLE, missingTask.errors.single().detail?.key)
        } finally {
            ioScope.cancel()
        }
    }

    /** 验证远程解压在上传阶段被取消时经 NonCancellable 仍彻底删除临时中转目录（REV-04）。 */
    @Test
    fun archiveRemoteExtractCancelStillCleansUpTempDirectory() = runBlocking {
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val archiveFile = Files.createTempFile("onyx-remote-cancel-src-", ".zip")
        try {
            val zipBytes = ByteArrayOutputStream().use { out ->
                ZipOutputStream(out).use { zip ->
                    zip.putNextEntry(ZipEntry("big.bin"))
                    zip.write("cancel-me".toByteArray())
                    zip.closeEntry()
                }
                out.toByteArray()
            }
            Files.write(archiveFile, zipBytes)

            val uploadEntered = CompletableDeferred<Path>()
            val neverFinishes = CompletableDeferred<Unit>()
            val stallingCommandService = object : FileCommandService {
                override suspend fun createFile(parentLocation: String, name: String) =
                    Result.failure<VFile>(UnsupportedOperationException())

                override suspend fun createDirectory(parentLocation: String, name: String) =
                    Result.failure<VFile>(UnsupportedOperationException())

                override suspend fun rename(entry: VFile, targetName: String) =
                    Result.failure<VFile>(UnsupportedOperationException())

                override suspend fun delete(entries: List<VFile>) = Result.success(Unit)
                override suspend fun move(
                    entries: List<VFile>,
                    targetDirectoryLocation: String,
                    conflictStrategy: TransferConflictStrategy,
                ) = Result.success(Unit)

                override suspend fun copy(
                    entries: List<VFile>,
                    targetDirectoryLocation: String,
                    conflictStrategy: TransferConflictStrategy,
                ): Result<Unit> {
                    uploadEntered.complete(Path.of(entries.first().location).parent)
                    neverFinishes.await()
                    return Result.success(Unit)
                }
            }
            val orchestrator = TaskOrchestrator(scope = ioScope)
            val delegate = ArchiveActionDelegate(
                archiveService = ArchiveService(),
                taskOrchestrator = orchestrator,
                dialogState = MutableStateFlow(null),
                onRefreshDirectories = {},
                fileCommandService = stallingCommandService,
                fileRepository = JvmLocalFileProvider(),
            )
            val archiveEntry = testFile(
                id = "arch-cancel",
                name = "big.bin",
                location = "archive://${archiveFile}!/big.bin",
                parent = "archive://${archiveFile}!/",
            )
            delegate.launchArchiveExtractToDirectory(
                entries = listOf(archiveEntry),
                targetDirectoryLocation = "webdav://nas.local/uploads/",
            )

            val stagingDir = withTimeout(5_000L) { uploadEntered.await() }
            assertTrue(Files.exists(stagingDir))

            val runningTaskId = orchestrator.tasks.value.single().id
            orchestrator.cancelTask(runningTaskId)
            val cancelledTask = withTimeout(5_000L) {
                orchestrator.tasks.first { list -> list.single().status == BackgroundTaskStatus.CANCELLED }.single()
            }
            assertEquals(BackgroundTaskStatus.CANCELLED, cancelledTask.status)
            // 等待 finally 中 NonCancellable 清理收尾
            withTimeout(5_000L) {
                while (Files.exists(stagingDir)) {
                    kotlinx.coroutines.delay(20L)
                }
            }
            assertFalse(Files.exists(stagingDir), "取消远程解压任务后临时目录必须已被 NonCancellable 彻底删除")
        } finally {
            ioScope.cancel()
            Files.deleteIfExists(archiveFile)
        }
    }

    /** 验证并发归档密码队列在队首取消时自动推进至下一项，非密码弹窗关闭后恢复展示，且任务取消时自动关闭弹窗（REV-03）。 */
    @Test
    fun archivePasswordQueueAdvancesOnDismissRestoresAfterOtherDialogAndClosesOnTaskCancel() = runBlocking {
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val orchestrator = TaskOrchestrator(scope = ioScope)
            val dialogState = MutableStateFlow<RootDialogState?>(null)
            val delegate = ArchiveActionDelegate(
                archiveService = ArchiveService(),
                taskOrchestrator = orchestrator,
                dialogState = dialogState,
                onRefreshDirectories = {},
            )
            val reqA = ioScope.launch {
                runCatching { delegate.requestArchivePassword("task-a", "/tmp/a.7z", "a.7z") }
            }
            val firstDialog = withTimeout(5_000L) {
                dialogState.first { it is RootDialogState.ArchivePassword } as RootDialogState.ArchivePassword
            }
            assertEquals("a.7z", firstDialog.archiveName)

            val reqB = ioScope.launch {
                runCatching { delegate.requestArchivePassword("task-b", "/tmp/b.7z", "b.7z") }
            }
            // 等待 reqB 入队
            kotlinx.coroutines.delay(50L)

            // 取消队首 a.7z -> 必须自动推进至 b.7z，而不是被置空死锁
            delegate.clearPending()
            val secondDialog = withTimeout(5_000L) {
                dialogState.first { (it as? RootDialogState.ArchivePassword)?.archiveName == "b.7z" }
                    as RootDialogState.ArchivePassword
            }
            assertEquals("b.7z", secondDialog.archiveName)
            withTimeout(5_000L) { reqA.join() }

            // 若其他无关弹窗临时关闭置空 dialogState，restorePendingPasswordDialogIfIdle 必须重新恢复 b.7z
            dialogState.value = null
            delegate.restorePendingPasswordDialogIfIdle()
            assertEquals("b.7z", (dialogState.value as? RootDialogState.ArchivePassword)?.archiveName)

            // 后台取消 task-b -> 自动关闭密码弹窗并取消挂起协程
            delegate.cancelPasswordRequestsForTask("task-b")
            assertNull(dialogState.value)
            withTimeout(5_000L) { reqB.join() }
        } finally {
            ioScope.cancel()
        }
    }

    /** 验证窄视口自适应模式下首次拖拽列宽以视觉列宽为起点平滑变化，不发生向 300dp 跳变（REV-06）。 */
    @Test
    fun adaptiveColumnResizeUsesEffectiveBaselineWithoutJump() {
        val tab = createDefaultPaneTabState(
            id = "tab-1",
            title = "work",
            location = "C:\\work",
            defaultViewMode = com.oruke.onyx.core.model.ViewMode.DETAILS,
        )
        val effective = resolveEffectiveDetailsColumnWidths(
            columns = defaultDetailsColumns(),
            columnWeights = tab.detailsColumnWeights,
            hiddenColumns = emptySet(),
            viewportWidthDp = 540f,
        )
        val visualNameWidth = effective.getValue(DetailsColumn.NAME)
        assertTrue(visualNameWidth < 260f)

        val resized = tab.withResizedDetailsColumnState(
            column = DetailsColumn.NAME,
            deltaWeight = 4f,
            baseWidths = effective,
        )
        assertEquals(visualNameWidth + 4f, resized.detailsColumnWeights.getValue(DetailsColumn.NAME), 0.1f)
        assertEquals(effective.getValue(DetailsColumn.MODIFIED), resized.detailsColumnWeights.getValue(DetailsColumn.MODIFIED), 0.1f)
    }

    /** 验证归档文件解压至远程 VFS（如 `webdav://`）时经本地临时目录中转上传并自动清理临时目录（OPS-006）。 */
    @Test
    fun archiveActionDelegateExtractsToRemoteTargetViaTempStagingAndCleansUp() = runBlocking {
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val archiveFile = Files.createTempFile("onyx-remote-extract-src-", ".zip")
        try {
            val zipBytes = ByteArrayOutputStream().use { out ->
                ZipOutputStream(out).use { zip ->
                    zip.putNextEntry(ZipEntry("hello.txt"))
                    zip.write("remote-payload".toByteArray())
                    zip.closeEntry()
                }
                out.toByteArray()
            }
            Files.write(archiveFile, zipBytes)

            val uploadedNames = mutableListOf<String>()
            var uploadedTarget: String? = null
            var observedStagingDir: Path? = null
            val localRepo = JvmLocalFileProvider()
            val capturingCommandService = object : FileCommandService {
                override suspend fun createFile(parentLocation: String, name: String) =
                    Result.failure<VFile>(UnsupportedOperationException())

                override suspend fun createDirectory(parentLocation: String, name: String) =
                    Result.failure<VFile>(UnsupportedOperationException())

                override suspend fun rename(entry: VFile, targetName: String) =
                    Result.failure<VFile>(UnsupportedOperationException())

                override suspend fun delete(entries: List<VFile>) = Result.success(Unit)
                override suspend fun move(
                    entries: List<VFile>,
                    targetDirectoryLocation: String,
                    conflictStrategy: TransferConflictStrategy,
                ) = Result.success(Unit)

                override suspend fun copy(
                    entries: List<VFile>,
                    targetDirectoryLocation: String,
                    conflictStrategy: TransferConflictStrategy,
                ): Result<Unit> {
                    uploadedTarget = targetDirectoryLocation
                    entries.forEach { entry ->
                        uploadedNames += entry.name
                        observedStagingDir = Path.of(entry.location).parent
                    }
                    return Result.success(Unit)
                }
            }
            val orchestrator = TaskOrchestrator(scope = ioScope)
            val dialogState = MutableStateFlow<RootDialogState?>(null)
            val refreshed = mutableSetOf<String>()
            val delegate = ArchiveActionDelegate(
                archiveService = ArchiveService(),
                taskOrchestrator = orchestrator,
                dialogState = dialogState,
                onRefreshDirectories = { refreshed += it },
                fileCommandService = capturingCommandService,
                fileRepository = localRepo,
            )

            val archiveEntry = testFile(
                id = "arch-entry",
                name = "hello.txt",
                location = "archive://${archiveFile}!/hello.txt",
                parent = "archive://${archiveFile}!/",
            )
            delegate.launchArchiveExtractToDirectory(
                entries = listOf(archiveEntry),
                targetDirectoryLocation = "webdav://nas.local/uploads/",
            )
            val task = withTimeout(10_000L) {
                orchestrator.tasks.first { list ->
                    list.singleOrNull()?.status in setOf(BackgroundTaskStatus.SUCCEEDED, BackgroundTaskStatus.FAILED)
                }.single()
            }
            assertEquals(BackgroundTaskStatus.SUCCEEDED, task.status)
            assertEquals("webdav://nas.local/uploads/", uploadedTarget)
            assertEquals(listOf("hello.txt"), uploadedNames)
            assertTrue("webdav://nas.local/uploads/" in refreshed)
            val stagingDir = observedStagingDir
            assertTrue(stagingDir != null && !Files.exists(stagingDir), "临时中转目录必须在任务结束后清理")
        } finally {
            ioScope.cancel()
            Files.deleteIfExists(archiveFile)
        }
    }

    /** 验证远程工作目录启动用户自定义命令时返回精准本地化错误 `MSG_USER_COMMAND_LOCAL_DIRECTORY_REQUIRED`（SET-06）。 */
    @Test
    fun userCommandLauncherRejectsRemoteWorkingDirectoriesWithSpecificMessageKey() = runBlocking {
        val launcher = JvmUserCommandLauncherService()
        listOf("webdav://nas/docs", "s3://bucket/prefix/", "smb://server/share").forEach { remoteDir ->
            val failure = launcher.execute(
                UserCommandInvocation(
                    executable = "echo",
                    arguments = listOf("hi"),
                    workingDirectory = remoteDir,
                ),
            ).exceptionOrNull()
            assertTrue(failure is LocalizedException, "远程路径 $remoteDir 应返回 LocalizedException")
            assertEquals(MessageKey.MSG_USER_COMMAND_LOCAL_DIRECTORY_REQUIRED, failure.userMessage.key)
        }
    }

    /** 验证托盘左键单击/双击联合触发在 300ms 窗口内防抖去重，超时后正常再次响应（PLAT-01）。 */
    @Test
    fun systemTrayDebouncesRapidDoubleClicksAndSupportsLocalizedLabels() {
        var now = 1_000L
        var openCount = 0
        val trigger = OnyxSystemTrayController.debouncedOpenTrigger(
            nowMillis = { now },
            debounceMs = OnyxSystemTrayController.OPEN_WINDOW_DEBOUNCE_MS,
            onOpenWindow = { openCount += 1 },
        )

        trigger()
        now += 40L
        trigger()
        assertEquals(1, openCount)

        now += OnyxSystemTrayController.OPEN_WINDOW_DEBOUNCE_MS
        trigger()
        assertEquals(2, openCount)

        val labels = TrayMenuLabels(openLabel = "打开 Onyx", exitLabel = "退出 Onyx")
        assertEquals("打开 Onyx", labels.openLabel)
        assertEquals("退出 Onyx", labels.exitLabel)
    }

    /** 验证 `RemoteConnectionLocation` 正确保留 `#`、`+`、`%`、`[`、`]`、中文并仍拒绝 S3 `?query`（REM-04）。 */
    @Test
    fun remoteConnectionLocationHandlesSpecialCharsAndRejectsS3Query() {
        val normalizedDav = RemoteConnectionLocation.normalize(
            RemoteConnectionProtocol.WEBDAV,
            "webdav://nas.local/team+docs/release#2/中文 目录",
        )
        assertTrue(RemoteConnectionLocation.isValid(RemoteConnectionProtocol.WEBDAV, normalizedDav))
        assertTrue("team+docs" in normalizedDav)
        assertTrue("%232" in normalizedDav)

        val normalizedS3 = RemoteConnectionLocation.normalize(
            RemoteConnectionProtocol.S3,
            "s3://media-bucket/folder+1/album#3",
        )
        assertTrue(RemoteConnectionLocation.isValid(RemoteConnectionProtocol.S3, normalizedS3))
        assertFalse(RemoteConnectionLocation.isValid(RemoteConnectionProtocol.S3, "s3://bucket/prefix?key=value"))
    }

    /** 验证面包屑窄宽折叠算法与明细列视口自适应宽度收敛（VIS-02）。 */
    @Test
    fun breadcrumbCollapseAndAdaptiveDetailsColumnWidthsBehaveDeterministically() {
        val (collapsed, visible) = selectVisibleBreadcrumbIndices(
            labelWidthsPx = listOf(60f, 90f, 110f, 100f, 120f),
            availableWidthPx = 280f,
            separatorWidthPx = 18f,
            overflowChipWidthPx = 42f,
        )
        assertEquals(listOf(0, 1, 2), collapsed)
        assertEquals(listOf(3, 4), visible)

        val cols = defaultDetailsColumns()
        val defaults = defaultDetailsColumnWeights()
        val dual100 = resolveEffectiveDetailsColumnWidths(cols, defaults, emptySet(), viewportWidthDp = 540f)
        assertTrue(dual100.getValue(DetailsColumn.NAME) in 168f..260f)
        assertEquals(132f, dual100.getValue(DetailsColumn.MODIFIED))

        val wide = resolveEffectiveDetailsColumnWidths(cols, defaults, emptySet(), viewportWidthDp = 1100f)
        assertEquals(defaults, wide)

        val manual = defaults + (DetailsColumn.NAME to 420f)
        assertEquals(manual, resolveEffectiveDetailsColumnWidths(cols, manual, emptySet(), viewportWidthDp = 540f))
    }

    /** 验证亮暗强调色自适应前景色与浅色模式次要文字具备高对比度（THEME-01）。 */
    @Test
    fun themeContrastHelpersEnsureReadableForegroundsOnLightAndDarkAccents() {
        assertEquals(Color(0xFF14171F), contrastTextOn(Color(0xFFFACC15)))
        assertEquals(Color.White, contrastTextOn(Color(0xFF1D4ED8)))

        val lightPalette = resolveOnyxPalette(
            themePreset = OnyxThemePreset.DEFAULT,
            dark = false,
            overrides = OnyxThemeColors(accentArgb = 0xFF7DD3FCL),
        )
        assertEquals(Color(0xFF14171F), lightPalette.onAccent)
        assertTrue(lightPalette.surface.luminance() - lightPalette.mutedForeground.luminance() > 0.45f)
        assertTrue(lightPalette.surface.luminance() - lightPalette.disabledForeground.luminance() > 0.30f)
    }

    /**
     * 直接验证真实 [DefaultRootComponent]：
     * 1. `RootIntent.FocusActivePaneAddressBar` 仅递增当前活动面板专属计数，切面板绝不修改计数（REV-02 / R2-REV-02）；
     * 2. `confirmDialog()` 关闭无关设置弹窗置空 `dialogState` 后，自动恢复后台排队的归档密码弹窗（OPS-005 加固）。
     */
    @Test
    fun defaultRootComponentFocusActivePaneAddressBarIncrementsOnlyActivePaneAndRestoresQueuedPassword() = runBlocking {
        withContext(Dispatchers.Main) {
            val tempDir = Files.createTempDirectory("onyx-batch3-root-test-").normalize().toAbsolutePath()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val lifecycle = LifecycleRegistry()
            var externalDrag: ExternalFileDragService? = null
            var runtime: RootApplicationRuntime? = null
            var koinClose: (() -> Unit)? = null
            try {
                val memorySettings = object : SettingsRepository {
                    var current: OnyxSettings = OnyxSettings()
                    override suspend fun loadSettings(): Result<OnyxSettings?> = Result.success(current)
                    override suspend fun saveSettings(settings: OnyxSettings): Result<Unit> {
                        current = settings
                        return Result.success(Unit)
                    }
                }
                val memorySession = object : SessionRepository {
                    var current: AppSessionSnapshot? = null
                    override suspend fun loadSession(): Result<AppSessionSnapshot?> = Result.success(current)
                    override suspend fun saveSession(snapshot: AppSessionSnapshot): Result<Unit> {
                        current = snapshot
                        return Result.success(Unit)
                    }
                }
                val memoryTasks = object : TaskPersistenceRepository {
                    override suspend fun loadTasks(): Result<List<BackgroundTask>> = Result.success(emptyList())
                    override suspend fun saveTasks(tasks: List<BackgroundTask>): Result<Unit> = Result.success(Unit)
                }
                val noopDevices = object : StorageDeviceService {
                    override fun cachedSnapshot(): List<StorageDevice> = emptyList()
                    override fun observe(): Flow<Result<List<StorageDevice>>> = flowOf(Result.success(emptyList()))
                    override suspend fun snapshot(): Result<List<StorageDevice>> = Result.success(emptyList())
                    override suspend fun execute(deviceId: String, action: StorageDeviceAction): Result<String?> =
                        Result.success(null)
                }
                val memoryClipboard = object : TextClipboardService {
                    override suspend fun copyText(text: String): Result<Unit> = Result.success(Unit)
                }
                val koinApp = koinApplication {
                    allowOverride(true)
                    modules(fileModule)
                    modules(
                        module {
                            single<SettingsRepository> { memorySettings }
                            single<SessionRepository> { memorySession }
                            single<TaskPersistenceRepository> { memoryTasks }
                            single<StorageDeviceService> { noopDevices }
                            single<TextClipboardService> { memoryClipboard }
                        },
                    )
                }
                koinClose = koinApp::close
                val koin = koinApp.koin
                val dragService = koin.get<ExternalFileDragService>()
                externalDrag = dragService
                val appRuntime = RootApplicationRuntime(
                    scope = scope,
                    taskOrchestrator = TaskOrchestrator(scope = scope, taskRepository = memoryTasks),
                    clipboardManager = ClipboardManager(koin.get(), koin.get()),
                    sessionManager = SessionManager(
                        settingsRepository = memorySettings,
                        sessionRepository = memorySession,
                    ),
                    settings = MutableStateFlow(OnyxSettings()),
                )
                runtime = appRuntime
                val root = DefaultRootComponent(
                    componentContext = DefaultComponentContext(lifecycle = lifecycle),
                    dependencies = DefaultRootDependencies(
                        files = RootFileDependencies(
                            fileRepository = koin.get(),
                            fileCommandService = koin.get(),
                            textClipboardService = memoryClipboard,
                            trashService = koin.get(),
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
                            storageDevices = noopDevices,
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
                            imageViewerController = ImageViewerController(),
                            sessionManager = appRuntime.sessionManager,
                            settings = appRuntime.settings,
                            taskCenter = appRuntime.taskCenter,
                            resourceLocale = appRuntime.resourceLocale,
                        ),
                    ),
                    launchConfiguration = RootLaunchConfiguration(
                        initialLocation = tempDir.toString(),
                        persistsMainSession = false,
                    ),
                )
                lifecycle.create()
                lifecycle.resume()
                withTimeout(5_000L) {
                    while (root.state.value.sessionRestoreState != SessionRestoreState.Ready) {
                        delay(20L)
                    }
                }

                // 1. 主面板请求聚焦地址栏 -> 仅 PRIMARY 计数递增
                assertEquals(PaneId.PRIMARY, root.state.value.activePane)
                root.dispatch(RootIntent.FocusActivePaneAddressBar)
                assertEquals(mapOf(PaneId.PRIMARY to 1), root.state.value.addressBarFocusRequests)

                // 2. 切换至次面板 -> 聚焦请求映射保持不变，不向 SECONDARY 注入计数
                root.dispatch(RootIntent.ActivatePane(PaneId.SECONDARY))
                assertEquals(PaneId.SECONDARY, root.state.value.activePane)
                assertEquals(mapOf(PaneId.PRIMARY to 1), root.state.value.addressBarFocusRequests)

                // 3. 在次面板请求聚焦地址栏 -> 仅 SECONDARY 计数递增
                root.dispatch(RootIntent.FocusActivePaneAddressBar)
                assertEquals(
                    mapOf(PaneId.PRIMARY to 1, PaneId.SECONDARY to 1),
                    root.state.value.addressBarFocusRequests,
                )

                // 4. 后台存在排队归档密码请求时，确认设置弹窗关闭后必须自动恢复密码弹窗
                val pendingJob = scope.launch {
                    runCatching {
                        root.archiveActionDelegate.requestArchivePassword("task-pwd", "/tmp/secret.7z", "secret.7z")
                    }
                }
                withTimeout(5_000L) {
                    root.dialogState.first { it is RootDialogState.ArchivePassword }
                }
                root.dispatch(RootIntent.OpenSettings)
                assertTrue(root.state.value.dialogState is RootDialogState.Settings)
                root.dispatch(RootIntent.ConfirmDialog)
                val restored = root.state.value.dialogState as? RootDialogState.ArchivePassword
                assertEquals("secret.7z", restored?.archiveName, "确认非密码弹窗后必须恢复后台排队中的归档密码弹窗")

                root.archiveActionDelegate.cancelPasswordRequestsForTask("task-pwd")
                withTimeout(5_000L) { pendingJob.join() }
            } finally {
                runCatching { lifecycle.destroy() }
                runCatching { externalDrag?.dispose() }
                runCatching { runtime?.close() }
                runCatching { koinClose?.invoke() }
                runCatching {
                    Files.walk(tempDir).use { stream ->
                        stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                    }
                }
            }
        }
    }

    private fun testFile(id: String, name: String, location: String, parent: String?): VFile {
        return VFile(
            id = id,
            name = name,
            location = location,
            parentLocation = parent,
            kind = VFileKind.FILE,
            sizeBytes = 64L,
            modifiedAtEpochMillis = 0L,
            hidden = false,
            capabilities = setOf(VFileCapability.RENAME, VFileCapability.DELETE),
        )
    }
}

/**
 * 支持按名称注入单条重命名失败的内存文件系统桩，用于验证部分失败历史拆包与防卡死逻辑。
 */
private class PartialFailureHistoryFileSystem : FileCommandService, FileRepository {
    private val entriesByParent = linkedMapOf<String, MutableList<VFile>>()
    val failingRenameNames = mutableSetOf<String>()

    fun addFile(parentLocation: String, name: String): VFile {
        val file = VFile(
            id = "$parentLocation/$name",
            name = name,
            location = "$parentLocation/$name",
            parentLocation = parentLocation,
            kind = VFileKind.FILE,
            sizeBytes = 12L,
            modifiedAtEpochMillis = 0L,
            hidden = false,
            capabilities = setOf(VFileCapability.RENAME),
        )
        entriesByParent.getOrPut(parentLocation) { mutableListOf() }.add(file)
        return file
    }

    fun exists(parentLocation: String, name: String): Boolean {
        return entriesByParent[parentLocation].orEmpty().any { it.name == name }
    }

    override fun defaultLocation(): String = "/"

    override suspend fun list(location: String): Result<List<VFile>> {
        return Result.success(entriesByParent[location].orEmpty().toList())
    }

    override suspend fun rename(entry: VFile, targetName: String): Result<VFile> {
        if (entry.name in failingRenameNames) {
            return Result.failure(IllegalStateException("Simulated lock: ${entry.name}"))
        }
        val parent = entry.parentLocation ?: return Result.failure(IllegalArgumentException("Missing parent"))
        val list = entriesByParent[parent] ?: return Result.failure(IllegalStateException("Missing directory"))
        val index = list.indexOfFirst { it.name == entry.name }
        if (index < 0) return Result.failure(IllegalStateException("Missing source: ${entry.name}"))
        val updated = entry.copy(id = "$parent/$targetName", name = targetName, location = "$parent/$targetName")
        list[index] = updated
        return Result.success(updated)
    }

    override suspend fun createFile(parentLocation: String, name: String) =
        Result.failure<VFile>(UnsupportedOperationException())

    override suspend fun createDirectory(parentLocation: String, name: String) =
        Result.failure<VFile>(UnsupportedOperationException())

    override suspend fun delete(entries: List<VFile>) = Result.failure<Unit>(UnsupportedOperationException())
    override suspend fun copy(
        entries: List<VFile>,
        targetDirectoryLocation: String,
        conflictStrategy: TransferConflictStrategy,
    ) = Result.failure<Unit>(UnsupportedOperationException())

    override suspend fun move(
        entries: List<VFile>,
        targetDirectoryLocation: String,
        conflictStrategy: TransferConflictStrategy,
    ) = Result.failure<Unit>(UnsupportedOperationException())
}
