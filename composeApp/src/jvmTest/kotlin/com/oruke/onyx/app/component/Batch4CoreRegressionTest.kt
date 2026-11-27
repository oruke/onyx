package com.oruke.onyx.app.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import com.oruke.onyx.app.component.delegate.FileOperationHistoryDelegate
import com.oruke.onyx.app.component.delegate.RootRemoteConnectionManager
import com.oruke.onyx.app.component.delegate.TaskOrchestrator
import com.oruke.onyx.app.filesystem.ImageLoadOutcome
import com.oruke.onyx.app.filesystem.JvmImageMetadataService
import com.oruke.onyx.app.filesystem.JvmThumbnailService
import com.oruke.onyx.core.model.BackgroundTask
import com.oruke.onyx.core.model.BackgroundTaskKind
import com.oruke.onyx.core.model.BackgroundTaskStatus
import com.oruke.onyx.core.model.I18nMessage
import com.oruke.onyx.core.model.MessageKey
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.RemoteConnectionProfile
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileCapability
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.core.model.ViewMode
import com.oruke.onyx.ui.OnyxCommand
import com.oruke.onyx.ui.WINDOW_GLOBAL_SHORTCUT_COMMANDS
import com.oruke.onyx.ui.extractQuickOpenHighlightTokens
import com.oruke.onyx.ui.formatSizeBytesToMbText
import com.oruke.onyx.ui.highlightMatchText
import com.oruke.onyx.ui.parseMbTextToBytes
import com.oruke.onyx.ui.resolveActualSizeTargetScale
import com.oruke.onyx.ui.resolveDisplayZoomPercent
import com.oruke.onyx.ui.resolveNextStepZoomScale
import com.oruke.onyx.ui.resolveSwatchBorderColor
import com.oruke.onyx.ui.theme.contrastTextOn
import com.oruke.onyx.ui.theme.contrastTextOnComposite
import com.oruke.onyx.ui.theme.resolveLabelAutoPrimaryTextColor
import com.oruke.onyx.ui.theme.resolveLabelAutoSecondaryTextColor
import com.oruke.onyx.vfs.api.FileCommandService
import com.oruke.onyx.vfs.api.FileRepository
import com.oruke.onyx.vfs.api.InMemoryRemoteAuthStore
import com.oruke.onyx.vfs.api.TransferConflictStrategy
import com.oruke.onyx.vfs.api.VfsAuthContext
import com.oruke.onyx.vfs.api.VfsConnectionTestRequest
import com.oruke.onyx.vfs.api.VfsConnectionTestResult
import com.oruke.onyx.vfs.api.VfsConnectionTestService
import com.oruke.onyx.vfs.api.VfsProtocol
import com.oruke.onyx.vfs.api.VfsProviderError
import com.oruke.onyx.vfs.archive.ArchiveService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Batch 4 核心逻辑与服务层回归测试（覆盖 F-07/F-08/F-10/F-13/F-15/F-16/OPS-014/OPS-015/PLAT-03/SEARCH-006/SEARCH-008/THEME-02）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Batch4CoreRegressionTest {
    /**
     * F-13 + F-10: 校验缩略图同步内存缓存命中与文件覆写后按 mtime/size 失效刷新，
     * 同时验证 `loadViewerImage` 返回结构化结果（Loaded / NotFound / DecodeFailed）。
     */
    @Test
    fun thumbnailCachePeeksSynchronouslyInvalidatesOnOverwriteAndReturnsStructuredViewerOutcomes(): Unit = runBlocking {
        val tempDir = Files.createTempDirectory("onyx-b4-thumb-")
        try {
            val imagePath = tempDir.resolve("sample.png")
            val invalidPath = tempDir.resolve("corrupt.png")
            val missingPath = tempDir.resolve("missing.png")

            writeSolidPng(imagePath, width = 64, height = 48, rgb = 0xFF3366)
            Files.write(invalidPath, "not-a-valid-png-payload".encodeToByteArray())

            val service = JvmThumbnailService()
            val location = imagePath.toString()
            val mtime1 = Files.getLastModifiedTime(imagePath).toMillis()
            val size1 = Files.size(imagePath)

            // 首次未加载前 peek 必须返回 null
            assertNull(service.peekCachedThumbnail(location, 256, mtime1, size1))

            // 异步加载后可同步命中零闪烁缓存，且单次写入仅占 1 条缓存、不双倍扣减字节预算
            val firstBitmap = service.loadThumbnail(location, 256, mtime1, size1)
            assertNotNull(firstBitmap)
            assertEquals(64, firstBitmap.width)
            assertEquals(48, firstBitmap.height)
            assertEquals(1, service.thumbnailCache.size())
            assertEquals(64L * 48L * 4L, service.thumbnailCache.currentBytes())
            assertNotNull(service.peekCachedThumbnail(location, 256, mtime1, size1))
            // 无元数据入参禁止走无版本脏缓存键，且传入 (null, null) 加载同样仅单键入池
            assertNull(service.peekCachedThumbnail(location, 256, null, null))
            val noMetaLoaded = service.loadThumbnail(location, 256, null, null)
            assertNotNull(noMetaLoaded)
            assertEquals(1, service.thumbnailCache.size(), "无元数据调用应命中同一磁盘版本键而非双写 -1#-1 键")

            // 覆写同名图片为不同尺寸并更新 mtime -> 旧版本缓存失效，同步 peek 返回 null，重新加载得到新尺寸
            writeSolidPng(imagePath, width = 96, height = 32, rgb = 0x22AA55)
            val mtime2 = mtime1 + 5_000L
            Files.setLastModifiedTime(imagePath, FileTime.fromMillis(mtime2))
            val size2 = Files.size(imagePath)
            assertNull(service.peekCachedThumbnail(location, 256, mtime2, size2))
            assertNull(service.peekCachedThumbnail(location, 256, null, null))

            val refreshedBitmap = service.loadThumbnail(location, 256, null, null)
            assertNotNull(refreshedBitmap)
            assertEquals(96, refreshedBitmap.width)
            assertEquals(32, refreshedBitmap.height)

            // 结构化查看器加载结果校验
            val loaded = service.loadViewerImage(
                sampleFile(location, "sample.png", tempDir.toString()).copy(
                    modifiedAtEpochMillis = mtime2,
                    sizeBytes = size2,
                ),
                4096,
            )
            assertIs<ImageLoadOutcome.Loaded>(loaded)
            assertEquals(96, loaded.bitmap.width)

            val notFound = service.loadViewerImage(sampleFile(missingPath.toString(), "missing.png", tempDir.toString()), 4096)
            assertIs<ImageLoadOutcome.NotFound>(notFound)

            val decodeFailed = service.loadViewerImage(sampleFile(invalidPath.toString(), "corrupt.png", tempDir.toString()), 4096)
            assertIs<ImageLoadOutcome.DecodeFailed>(decodeFailed)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    /**
     * F-10: 校验 `JvmImageMetadataService.readImageSize` 在标准 `ImageIO` 不支持 WebP 格式时回退到 Skia 正确读取原图宽高。
     */
    @Test
    fun imageMetadataServiceFallsBackToSkiaForWebpDimensions() = runBlocking {
        val tempDir = Files.createTempDirectory("onyx-b4-webp-")
        try {
            val webpPath = tempDir.resolve("banner.webp")
            val surface = Surface.makeRasterN32Premul(140, 88)
            val encoded = try {
                surface.makeImageSnapshot().use { snapshot ->
                    snapshot.encodeToData(EncodedImageFormat.WEBP, 90)?.bytes
                }
            } finally {
                surface.close()
            }
            assertNotNull(encoded, "Skia 应能编码测试用 WebP 图像")
            Files.write(webpPath, encoded)

            val service = JvmImageMetadataService(ArchiveService())
            val entry = VFile(
                id = webpPath.toString(),
                name = "banner.webp",
                location = webpPath.toString(),
                parentLocation = tempDir.toString(),
                kind = VFileKind.FILE,
                sizeBytes = encoded.size.toLong(),
                modifiedAtEpochMillis = Files.getLastModifiedTime(webpPath).toMillis(),
                hidden = false,
                capabilities = setOf(VFileCapability.READ_CONTENT),
            )
            val size = service.readImageSize(entry)
            assertEquals(IntSize(140, 88), size)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    /**
     * F-08 + F-10: 校验图片查看器真实 1:1 像素倍率、百分比换算与防漂移连续缩放计算。
     */
    @Test
    fun imageViewerScaleMathComputesTrueActualSizeAndAntiDriftSteps() {
        // 情况 A: ZoomImage 将 Fit 状态映射为 minScale = 1.0f（3200x1800 图像在 800x600 容器中，fitScale = 0.25）
        val actualInNormalizedSpace = resolveActualSizeTargetScale(
            nativeSize = IntSize(3200, 1800),
            bitmapSize = IntSize(3200, 1800),
            containerSize = IntSize(800, 600),
            minScale = 1f,
            maxScale = 16f,
            rotationDegrees = 0,
        )
        assertEquals(4f, actualInNormalizedSpace, 0.01f)
        assertEquals(
            25,
            resolveDisplayZoomPercent(
                currentScale = 1f,
                nativeSize = IntSize(3200, 1800),
                bitmapSize = IntSize(3200, 1800),
                containerSize = IntSize(800, 600),
                minScale = 1f,
            ),
        )
        assertEquals(
            100,
            resolveDisplayZoomPercent(
                currentScale = actualInNormalizedSpace,
                nativeSize = IntSize(3200, 1800),
                bitmapSize = IntSize(3200, 1800),
                containerSize = IntSize(800, 600),
                minScale = 1f,
            ),
        )

        // 情况 B: 超大图（8192x4096）降采样到 4096x2048，minScale 等于 fitScale 时应返回 downsampleRatio=2.0
        val actualDownsampled = resolveActualSizeTargetScale(
            nativeSize = IntSize(8192, 4096),
            bitmapSize = IntSize(4096, 2048),
            containerSize = IntSize(1024, 512),
            minScale = 0.125f,
            maxScale = 8f,
        )
        assertEquals(2f, actualDownsampled, 0.01f)

        // 连续快捷缩放防漂移：即使动画仍停在 currentScale=1.0，连续按两次 + 应基于 pendingTargetScale 累乘
        val firstStep = resolveNextStepZoomScale(
            currentScale = 1f,
            pendingTargetScale = null,
            factor = 1.25f,
            minScale = 0.25f,
            maxScale = 8f,
        )
        val secondStep = resolveNextStepZoomScale(
            currentScale = 1f,
            pendingTargetScale = firstStep,
            factor = 1.25f,
            minScale = 0.25f,
            maxScale = 8f,
        )
        assertEquals(1.25f, firstStep, 0.001f)
        assertEquals(1.5625f, secondStep, 0.001f)
    }

    /**
     * F-15: 校验关闭其他标签与关闭右侧标签的状态流转逻辑。
     */
    @Test
    fun tabStackExtensionsCloseOtherTabsAndTabsToRightCorrectly() {
        val t1 = createDefaultPaneTabState("t1", "Alpha", "C:\\Alpha", ViewMode.DETAILS)
        val t2 = createDefaultPaneTabState("t2", "Beta", "C:\\Beta", ViewMode.DETAILS)
        val t3 = createDefaultPaneTabState("t3", "Gamma", "C:\\Gamma", ViewMode.DETAILS)
        val tabs = listOf(t1, t2, t3)

        val closeOthers = requireNotNull(tabs.withClosedOtherTabs("t2"))
        assertEquals(listOf("t2"), closeOthers.tabs.map { it.id })
        assertEquals("t2", closeOthers.activeTab.id)
        assertNull(listOf(t1).withClosedOtherTabs("t1"))

        val closeRightFromT1 = requireNotNull(tabs.withClosedTabsToRight(activeTabId = "t3", tabId = "t1"))
        assertEquals(listOf("t1"), closeRightFromT1.tabs.map { it.id })
        assertEquals("t1", closeRightFromT1.activeTab.id, "原活动标签被关闭时应激活基准标签")

        val closeRightPreserveActive = requireNotNull(tabs.withClosedTabsToRight(activeTabId = "t1", tabId = "t2"))
        assertEquals(listOf("t1", "t2"), closeRightPreserveActive.tabs.map { it.id })
        assertEquals("t1", closeRightPreserveActive.activeTab.id, "原活动标签仍在左侧时应保持活动")
        assertNull(tabs.withClosedTabsToRight(activeTabId = "t1", tabId = "t3"))
    }

    /**
     * F-16: 校验内联新建文件将碰撞编号插入扩展名之前并遵守大小写不敏感位置（如 SMB）碰撞检测。
     */
    @Test
    fun nextCreateNamePreservesExtensionAndRespectsCaseInsensitiveCollisions() {
        val existing = listOf(
            sampleFile("smb://nas/share/work/new file.txt", "new file.txt", parent = "smb://nas/share/work"),
            sampleFile("smb://nas/share/work/New File (1).TXT", "New File (1).TXT", parent = "smb://nas/share/work"),
            sampleFile("smb://nas/share/work/New Folder", "New Folder", parent = "smb://nas/share/work"),
        )
        val tab = createDefaultPaneTabState("t1", "work", "smb://nas/share/work", ViewMode.DETAILS).copy(
            allEntries = existing,
        )
        assertEquals("New File (2).txt", tab.nextCreateName("New File.txt", preserveExtension = true))
        assertEquals("New Folder (1)", tab.nextCreateName("New Folder", preserveExtension = false))
    }

    /**
     * OPS-014: 校验大小写不敏感目录（如 SMB）下撤销移动操作遇到仅大小写不同的同名占位文件时拒绝覆盖。
     */
    @Test
    fun undoMoveDetectsCaseInsensitiveNameCollisionInSourceDirectory() = runBlocking {
        val movedEntry = sampleFile("smb://nas/share/src/Report.txt", "Report.txt", parent = "smb://nas/share/src")
        val inTargetDir = sampleFile("smb://nas/share/dst/Report.txt", "Report.txt", parent = "smb://nas/share/dst")
        val caseCollidingPlaceholder = sampleFile("smb://nas/share/src/report.txt", "report.txt", parent = "smb://nas/share/src")

        var moveInvocations = 0
        val repo = object : FileRepository {
            override suspend fun list(location: String): Result<List<VFile>> = when (location) {
                "smb://nas/share/src" -> Result.success(listOf(caseCollidingPlaceholder))
                "smb://nas/share/dst" -> Result.success(listOf(inTargetDir))
                else -> Result.success(emptyList())
            }

            override fun defaultLocation(): String = "smb://nas/share/src"
        }
        val commands = object : FileCommandService {
            override suspend fun createDirectory(parentLocation: String, name: String) = Result.failure<VFile>(UnsupportedOperationException())
            override suspend fun createFile(parentLocation: String, name: String) = Result.failure<VFile>(UnsupportedOperationException())
            override suspend fun rename(entry: VFile, targetName: String) = Result.failure<VFile>(UnsupportedOperationException())
            override suspend fun delete(entries: List<VFile>) = Result.failure<Unit>(UnsupportedOperationException())
            override suspend fun copy(entries: List<VFile>, targetDirectoryLocation: String, conflictStrategy: TransferConflictStrategy) =
                Result.failure<Unit>(UnsupportedOperationException())

            override suspend fun move(
                entries: List<VFile>,
                targetDirectoryLocation: String,
                conflictStrategy: TransferConflictStrategy,
            ): Result<Unit> {
                moveInvocations += 1
                return Result.success(Unit)
            }
        }

        val delegate = FileOperationHistoryDelegate(commands, repo)
        delegate.recordMove(listOf(movedEntry), "smb://nas/share/dst")

        val undoResult = delegate.undoLast()
        assertTrue(undoResult.isFailure, "存在仅大小写不同的占位文件时撤销移动必须中止并报错")
        assertEquals(0, moveInvocations, "检测到大小写冲突后不得发出真实移动覆盖指令")
    }

    /**
     * OPS-015: 校验排队中的可暂停任务在暂停后恢复为 QUEUED、在拿到许可前保持 PAUSED 且不进入业务体闪成 RUNNING，
     * 同时运行中迟到的 `updateTask(RUNNING)` 不会覆写已暂停状态。
     */
    @Test
    fun taskOrchestratorKeepsPausedQueuedTaskFromFlashingRunningAndResumesToQueued() = runTest {
        val orchestrator = TaskOrchestrator(this)
        val hold1 = CompletableDeferred<Unit>()
        val hold2 = CompletableDeferred<Unit>()
        var task3Entered = false

        orchestrator.appendTask(sampleTask("t1", BackgroundTaskStatus.RUNNING))
        orchestrator.appendTask(sampleTask("t2", BackgroundTaskStatus.RUNNING))
        orchestrator.appendTask(sampleTask("t3", BackgroundTaskStatus.QUEUED, canPause = true))

        val j1 = orchestrator.launchQueuedTask("t1") {
            hold1.await()
            orchestrator.updateTaskFields("t1") { it.copy(status = BackgroundTaskStatus.SUCCEEDED) }
            orchestrator.unregisterJob("t1")
        }
        val j2 = orchestrator.launchQueuedTask("t2") {
            hold2.await()
            orchestrator.updateTaskFields("t2") { it.copy(status = BackgroundTaskStatus.SUCCEEDED) }
            orchestrator.unregisterJob("t2")
        }
        val j3 = orchestrator.launchQueuedTask("t3") {
            task3Entered = true
            orchestrator.updateTask("t3", BackgroundTaskStatus.RUNNING, I18nMessage(MessageKey.MSG_COPY_ITEMS, 1))
            orchestrator.updateTaskFields("t3") { it.copy(status = BackgroundTaskStatus.SUCCEEDED) }
            orchestrator.unregisterJob("t3")
        }
        runCurrent()

        // 1. t3 排队期间被暂停，随后在仍排队时恢复 -> 应恢复为 QUEUED 而非 RUNNING
        orchestrator.pauseTask("t3")
        assertEquals(BackgroundTaskStatus.PAUSED, orchestrator.tasks.value.first { it.id == "t3" }.status)
        // 即使期间收到迟到的 RUNNING 上报，也应维持 PAUSED
        orchestrator.updateTask("t3", BackgroundTaskStatus.RUNNING, I18nMessage(MessageKey.MSG_COPY_ITEMS, 1))
        assertEquals(BackgroundTaskStatus.PAUSED, orchestrator.tasks.value.first { it.id == "t3" }.status)

        orchestrator.resumeTask("t3")
        assertEquals(BackgroundTaskStatus.QUEUED, orchestrator.tasks.value.first { it.id == "t3" }.status)

        // 2. 再次在排队期间暂停 t3，然后释放 t1 让出许可 -> t3 不得进入 block，也不得闪成 RUNNING
        orchestrator.pauseTask("t3")
        hold1.complete(Unit)
        runCurrent()
        assertFalse(task3Entered, "排队期已暂停的任务拿到许可后不应直接进入业务体")
        assertEquals(BackgroundTaskStatus.PAUSED, orchestrator.tasks.value.first { it.id == "t3" }.status)

        // 3. 此时恢复 t3 -> 立即进入业务体完成
        orchestrator.resumeTask("t3")
        hold2.complete(Unit)
        runCurrent()
        assertTrue(task3Entered)
        assertTrue(listOf(j1, j2, j3).all { it.isCompleted && !it.isCancelled })
        assertEquals(BackgroundTaskStatus.SUCCEEDED, orchestrator.tasks.value.first { it.id == "t3" }.status)
    }

    /**
     * REV-02 / OPS-015: 校验多个排队任务经由 TaskCenterIntent 暂停后绝不占用全局信号量导致后续正常任务饥饿，
     * 且已结束（SUCCEEDED / FAILED / CANCELLED）的终态任务不可被 `pauseTask` / `resumeTask` 复活。
     */
    @Test
    fun pausedQueuedTasksDoNotStarveFollowingTasksAndTerminalTasksNeverRevive() = runTest {
        val orchestrator = TaskOrchestrator(this)
        val hold1 = CompletableDeferred<Unit>()
        val hold2 = CompletableDeferred<Unit>()
        var q1Entered = false
        var q2Entered = false
        var q3Completed = false

        orchestrator.appendTask(sampleTask("r1", BackgroundTaskStatus.RUNNING))
        orchestrator.appendTask(sampleTask("r2", BackgroundTaskStatus.RUNNING))
        orchestrator.appendTask(sampleTask("q1", BackgroundTaskStatus.QUEUED, canPause = true))
        orchestrator.appendTask(sampleTask("q2", BackgroundTaskStatus.QUEUED, canPause = true))
        orchestrator.appendTask(sampleTask("q3", BackgroundTaskStatus.QUEUED, canPause = true))
        orchestrator.appendTask(sampleTask("done", BackgroundTaskStatus.SUCCEEDED, canPause = true))
        orchestrator.appendTask(sampleTask("failed", BackgroundTaskStatus.FAILED, canPause = true))

        val r1Job = orchestrator.launchQueuedTask("r1") {
            hold1.await()
            orchestrator.updateTaskFields("r1") { it.copy(status = BackgroundTaskStatus.SUCCEEDED) }
            orchestrator.unregisterJob("r1")
        }
        val r2Job = orchestrator.launchQueuedTask("r2") {
            hold2.await()
            orchestrator.updateTaskFields("r2") { it.copy(status = BackgroundTaskStatus.SUCCEEDED) }
            orchestrator.unregisterJob("r2")
        }
        val q1Job = orchestrator.launchQueuedTask("q1") {
            q1Entered = true
            orchestrator.updateTaskFields("q1") { it.copy(status = BackgroundTaskStatus.SUCCEEDED) }
            orchestrator.unregisterJob("q1")
        }
        val q2Job = orchestrator.launchQueuedTask("q2") {
            q2Entered = true
            orchestrator.updateTaskFields("q2") { it.copy(status = BackgroundTaskStatus.SUCCEEDED) }
            orchestrator.unregisterJob("q2")
        }
        val q3Job = orchestrator.launchQueuedTask("q3") {
            q3Completed = true
            orchestrator.updateTaskFields("q3") { it.copy(status = BackgroundTaskStatus.SUCCEEDED) }
            orchestrator.unregisterJob("q3")
        }
        runCurrent()

        // 通过真实任务中心 Intent 暂停排队中的 q1 和 q2
        orchestrator.dispatchTaskCenterAction(TaskCenterIntent.Pause("q1"))
        orchestrator.dispatchTaskCenterAction(TaskCenterIntent.Pause("q2"))
        assertEquals(BackgroundTaskStatus.PAUSED, orchestrator.tasks.value.first { it.id == "q1" }.status)
        assertEquals(BackgroundTaskStatus.PAUSED, orchestrator.tasks.value.first { it.id == "q2" }.status)

        // 释放 r1 让出 1 个并发槽位 -> q1/q2 处于排队暂停不得占位，后续正常的 q3 必须立刻跑完！
        hold1.complete(Unit)
        runCurrent()
        assertFalse(q1Entered, "排队暂停的 q1 不得进入执行体")
        assertFalse(q2Entered, "排队暂停的 q2 不得进入执行体")
        assertTrue(q3Completed, "2 个排队暂停任务不得饿死后续正常排队的 q3")
        assertTrue(q3Job.isCompleted && !q3Job.isCancelled)
        assertEquals(BackgroundTaskStatus.SUCCEEDED, orchestrator.tasks.value.first { it.id == "q3" }.status)

        // 终态任务调用 pauseTask / resumeTask 绝不可复活
        orchestrator.pauseTask("done")
        orchestrator.pauseTask("failed")
        orchestrator.resumeTask("done")
        orchestrator.resumeTask("r2")
        assertEquals(BackgroundTaskStatus.SUCCEEDED, orchestrator.tasks.value.first { it.id == "done" }.status)
        assertEquals(BackgroundTaskStatus.FAILED, orchestrator.tasks.value.first { it.id == "failed" }.status)
        assertEquals(BackgroundTaskStatus.RUNNING, orchestrator.tasks.value.first { it.id == "r2" }.status)

        // 恢复 q1/q2 并释放 r2 收尾清理协程
        orchestrator.dispatchTaskCenterAction(TaskCenterIntent.Resume("q1"))
        orchestrator.dispatchTaskCenterAction(TaskCenterIntent.Resume("q2"))
        hold2.complete(Unit)
        runCurrent()
        assertTrue(listOf(r1Job, r2Job, q1Job, q2Job, q3Job).all { it.isCompleted && !it.isCancelled })
    }

    /**
     * REV-01 / PLAT-02: 校验阻塞型落盘超过时限时 `flushPersistedStateWithBoundedTimeout` 准时放行退出，
     * 不再被 `withContext(NonCancellable)` + 阻塞 SQLite I/O 永久卡死主线程，同时允许后台写入安全结束。
     */
    @Test
    fun flushPersistedStateWithBoundedTimeoutUnblocksPromptlyWhenStorageBlocks() {
        val backendFinished = java.util.concurrent.CountDownLatch(1)
        val startNanos = System.nanoTime()

        val completedInTime = flushPersistedStateWithBoundedTimeout(timeoutMillis = 250L) {
            Thread.sleep(900L)
            backendFinished.countDown()
        }
        val elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L

        assertFalse(completedInTime, "耗时 900ms 的阻塞写入在 250ms 超时窗口下必须返回 false 及时放行")
        assertTrue(
            elapsedMillis < 750L,
            "调用线程应在接近 250ms 时返回而非死等 900ms 阻塞结束，实际耗时 ${elapsedMillis}ms",
        )
        assertTrue(
            backendFinished.await(3, java.util.concurrent.TimeUnit.SECONDS),
            "超时放行后后台 NonCancellable 写入应安全收尾完成",
        )
    }

    /**
     * PLAT-03: 校验 S3 认证错误会弹出即时凭据对话框，且提交后写入 `VfsAuthContext.AwsCredentials`。
     */
    @Test
    fun remoteConnectionManagerPromptsAndStoresAwsCredentialsForS3AuthErrors() = runBlocking {
        val store = InMemoryRemoteAuthStore()
        val dialogState = MutableStateFlow<RootDialogState?>(null)
        var refreshCalled = false
        val fakePaneState = createDefaultPaneTabState("t1", "bucket", "s3://demo-bucket/", ViewMode.DETAILS)
            .toPaneState(PaneId.PRIMARY, "t1")
        val fakePane = object : PaneComponent {
            override val state = MutableStateFlow(fakePaneState)
            override val tabOrder = MutableStateFlow(listOf("t1"))
            override val tabStates = MutableStateFlow(listOf(createDefaultPaneTabState("t1", "bucket", "s3://demo-bucket/", ViewMode.DETAILS)))
            override val tabStack: com.arkivanov.decompose.value.Value<com.arkivanov.decompose.router.stack.ChildStack<TabConfig, TabComponent>>
                get() = error("unused")

            override fun dispatch(intent: PaneIntent) {
                if (intent is PaneIntent.Refresh) refreshCalled = true
            }

            override fun detachTab(tabId: String): PaneTabTransfer? = null
            override fun attachTab(tabSnapshot: PaneTabTransfer, targetIndex: Int) = Unit
            override fun restoreSession(snapshot: com.oruke.onyx.core.model.PaneSessionSnapshot) = Unit
            override fun captureRemoval(entries: List<VFile>): PaneRemovalContext? = null
            override fun completeRemoval(context: PaneRemovalContext, removedIds: Set<String>) = Unit
        }
        val manager = RootRemoteConnectionManager(
            scope = this,
            connectionTestService = object : VfsConnectionTestService {
                override suspend fun testConnection(request: VfsConnectionTestRequest): VfsConnectionTestResult =
                    error("unused")
            },
            remoteAuthStore = store,
            dialogState = dialogState,
            remoteConnections = { emptyList<RemoteConnectionProfile>() },
            replaceRemoteConnections = {},
            paneState = { fakePaneState },
            paneComponent = { fakePane },
            openLocationInActivePane = {},
        )

        manager.requestRemoteCredentials(
            paneId = PaneId.PRIMARY,
            error = VfsProviderError.AuthenticationRequired(
                protocol = VfsProtocol.S3,
                location = "s3://demo-bucket/",
            ),
        )
        val opened = assertIs<RootDialogState.RemoteCredentials>(dialogState.value)
        assertEquals(VfsProtocol.S3, opened.protocol)

        manager.updateRemoteCredentialsDraft(
            opened.draft.copy(
                username = "AKIA-TEST-KEY",
                password = "SECRET-TEST-VALUE",
                domain = "SESSION-TOKEN-01",
            ),
        )
        manager.submitRemoteCredentials()
        withTimeout(5_000L) {
            while (dialogState.value != null) {
                delay(10L)
            }
        }

        assertNull(dialogState.value)
        assertTrue(refreshCalled)
        val stored = assertIs<VfsAuthContext.AwsCredentials>(store.peekAuthContext(VfsProtocol.S3, "s3://demo-bucket/"))
        assertEquals("AKIA-TEST-KEY", stored.accessKeyId)
        assertEquals("SECRET-TEST-VALUE", stored.secretAccessKey)
        assertEquals("SESSION-TOKEN-01", stored.sessionToken)
    }

    /**
     * SEARCH-006 + SEARCH-008 + F-07: 校验多词/扩展名/通配符高亮分词、结构化前缀忽略、自定义 MB 解析以及全局预览栏命令注册。
     */
    @Test
    fun highlightMatchTextSupportsMultiTokenExtensionsAndGlobalCommandsIncludeTogglePreview() {
        val tokens = extractQuickOpenHighlightTokens("type:file size:>1mb \"annual report\" *.pdf ext:pdf")
        assertEquals(listOf("annual report", ".pdf", "pdf"), tokens)

        val quotedStructured = extractQuickOpenHighlightTokens("content:\"hello world\" ext:\"tar.gz\" report \"my file\"")
        assertEquals(listOf("tar.gz", "report", "my file"), quotedStructured)

        val annotated = highlightMatchText(
            text = "2026_Annual Report_final.pdf",
            query = "type:file annual .pdf",
            normalColor = Color.White,
            highlightColor = Color.Cyan,
        )
        val highlightedSegments = annotated.spanStyles
            .filter { it.item.color == Color.Cyan }
            .map { annotated.text.substring(it.start, it.end) }
        assertEquals(listOf("Annual", ".pdf"), highlightedSegments)

        assertEquals(524_288L, parseMbTextToBytes("0.5"))
        assertEquals(10_485_760L, parseMbTextToBytes("10"))
        assertNull(parseMbTextToBytes("abc"))
        assertEquals("10", formatSizeBytesToMbText(10_485_760L))
        assertEquals("0.5", formatSizeBytesToMbText(524_288L))

        assertTrue(OnyxCommand.TogglePreviewPane in WINDOW_GLOBAL_SHORTCUT_COMMANDS)
    }

    /**
     * THEME-02 + VIS-03: 校验半透明文件标签背景合成自动对比度文字色与浅色/深色低对比色块边框增强。
     */
    @Test
    fun labelAutoContrastHandlesCompositeAlphaAcrossThemesAndEnhancesLightSwatchBorder() {
        val darkSurface = Color(0xFF1E1F22)
        val lightSurface = Color(0xFFFFFFFF)
        val paleYellowLabel = 0xFFFEF08AL
        val deepNavyLabel = 0xFF1E3A8AL
        val semiTransparentYellow = 0xCCFEF08AL

        // 浅黄底在深色模式下必须自动切深字，深蓝底在浅色模式下必须自动切白字
        assertEquals(
            Color(0xFF14171F),
            resolveLabelAutoPrimaryTextColor(paleYellowLabel, null, darkSurface),
        )
        assertEquals(
            Color.White,
            resolveLabelAutoPrimaryTextColor(deepNavyLabel, null, lightSurface),
        )
        // 半透明浅黄叠加暗底合成后仍是高亮度，自动选用深色可读文字
        assertEquals(
            Color(0xFF14171F),
            contrastTextOnComposite(Color(semiTransparentYellow), darkSurface),
        )
        assertNotNull(resolveLabelAutoSecondaryTextColor(paleYellowLabel, darkSurface))
        // 用户显式指定文字色时保持优先
        assertEquals(
            Color(0xFFEF4444),
            resolveLabelAutoPrimaryTextColor(paleYellowLabel, 0xFFEF4444L, darkSurface),
        )
        // 浅色白底上的纯白色块应增强描边（不等于原本淡边框）
        val subtleOutline = Color(0xFFE2E8F0)
        val enhanced = resolveSwatchBorderColor(Color(0xFFF8FAFC), lightSurface, subtleOutline)
        assertNotEquals(subtleOutline, enhanced)
        assertEquals(Color(0xFF14171F), contrastTextOn(Color(0xFFF8FAFC)))
    }

    private fun writeSolidPng(path: java.nio.file.Path, width: Int, height: Int, rgb: Int) {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) {
            for (x in 0 until width) {
                image.setRGB(x, y, rgb)
            }
        }
        ImageIO.write(image, "png", path.toFile())
    }

    private fun sampleFile(location: String, name: String, parent: String = "C:\\work"): VFile = VFile(
        id = location,
        name = name,
        location = location,
        parentLocation = parent,
        kind = VFileKind.FILE,
        sizeBytes = 1024L,
        modifiedAtEpochMillis = 1_794_996_000_000L,
        hidden = false,
        capabilities = setOf(VFileCapability.READ_CONTENT),
    )

    private fun sampleTask(
        id: String,
        status: BackgroundTaskStatus,
        canPause: Boolean = false,
    ): BackgroundTask = BackgroundTask(
        id = id,
        kind = BackgroundTaskKind.COPY,
        title = I18nMessage(MessageKey.MSG_COPY_ITEMS, 1),
        detail = I18nMessage(MessageKey.MSG_COPY_ITEMS, 1),
        status = status,
        progress = 0f,
        canPause = canPause,
    )
}
