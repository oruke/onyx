package com.oruke.onyx.app.platform

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.oruke.onyx.app.component.delegate.ClipboardManager
import com.oruke.onyx.app.component.sanitizeRootSettings
import com.oruke.onyx.app.filesystem.DirectoryPollingPolicy
import com.oruke.onyx.app.filesystem.JvmDirectoryObservationService
import com.oruke.onyx.app.filesystem.JvmMemoryStatus
import com.oruke.onyx.app.filesystem.JvmSystemFileMaterializer
import com.oruke.onyx.app.filesystem.JvmVfsPathService
import com.oruke.onyx.app.platform.storage.JvmStorageDeviceService
import com.oruke.onyx.app.platform.storage.StorageDeviceBackend
import com.oruke.onyx.core.model.FileCollection
import com.oruke.onyx.core.model.FileCollectionKind
import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.core.model.StorageDevice
import com.oruke.onyx.core.model.StorageDeviceAction
import com.oruke.onyx.core.model.StorageDeviceKind
import com.oruke.onyx.core.model.StorageDeviceState
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileCapability
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.shared.filesystem.DirectoryContentStore
import com.oruke.onyx.shared.usecase.InMemoryFileCollectionRepository
import com.oruke.onyx.ui.SettingsCategory
import com.oruke.onyx.ui.SettingsDialogLayout
import com.oruke.onyx.ui.mergeEditableSettingsFrom
import com.oruke.onyx.ui.resetCategoryToDefaults
import com.oruke.onyx.ui.theme.LocalOnyxAppearance
import com.oruke.onyx.ui.theme.OnyxWindowTheme
import com.oruke.onyx.vfs.api.DirectoryWatchEvent
import com.oruke.onyx.vfs.api.RoutableVfsContentService
import com.oruke.onyx.vfs.api.TransferConflictStrategy
import com.oruke.onyx.vfs.api.VfsContentSource
import com.oruke.onyx.vfs.archive.ArchiveService
import com.oruke.onyx.vfs.local.JvmLocalFileProvider
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * 验证 JVM 最大堆手动配置（`Onyx.cfg` + 设置 UI）、无锁剪贴板跳过拷贝、后台托盘暂停轮询与有界缓存清理闭环。
 */
class MemoryLeakAndXmxConfigTest {

    /**
     * 校验 `Onyx.cfg` 解析与原子写回会精准替换或追加 `-Xmx`，同时保留原始换行与其余 JVM 选项。
     */
    @Test
    fun memoryConfigServiceParsesUpdatesAndClampsOnyxCfgAtomically() = runBlocking {
        val tempDir = Files.createTempDirectory("onyx-cfg-test-")
        val cfgPath = tempDir.resolve("Onyx.cfg")
        try {
            val initialCfg = listOf(
                "[Application]",
                "app.mainclass=com.oruke.onyx.MainKt",
                "",
                "[JavaOptions]",
                "java-options=-Djpackage.app-version=1.4.19",
                "java-options=-XX:+UseG1GC",
            ).joinToString("\r\n") + "\r\n"
            Files.writeString(cfgPath, initialCfg, StandardCharsets.UTF_8)

            val service = JvmMemoryConfigService(
                launcherConfigPathProvider = { cfgPath },
                runtimeMemoryProvider = { 1024L * 1024L * 1024L to 256L * 1024L * 1024L },
            )
            val before = service.readStatus()
            assertEquals(1024, before.activeMaxHeapMb)
            assertEquals(256, before.usedHeapMb)
            assertNull(before.configuredMaxHeapMb)
            assertTrue(before.launcherConfigWritable)

            val afterAppend = service.applyMaxHeapMemoryMb(2048).getOrThrow()
            assertEquals(2048, afterAppend.configuredMaxHeapMb)
            val writtenOnce = Files.readString(cfgPath, StandardCharsets.UTF_8)
            assertTrue("java-options=-Xmx2048m" in writtenOnce)
            assertTrue("\r\n" in writtenOnce)

            // 模拟手动编辑为 GB 单位及重复声明，确认解析与后续更新能正确归一且去重。
            val manuallyEdited = writtenOnce + "java-options=-Xmx3g\r\n"
            Files.writeString(cfgPath, manuallyEdited, StandardCharsets.UTF_8)
            assertEquals(3072, service.readStatus().configuredMaxHeapMb)

            // 越界值应被夹紧到安全范围内。
            val clamped = service.applyMaxHeapMemoryMb(999_999).getOrThrow()
            assertEquals(OnyxSettings.MAX_MAX_HEAP_MEMORY_MB, clamped.configuredMaxHeapMb)
            val finalContent = Files.readString(cfgPath, StandardCharsets.UTF_8)
            val xmxOccurrences = finalContent.lineSequence().count { it.trim().startsWith("java-options=-Xmx") }
            assertEquals(1, xmxOccurrences)
            assertTrue("java-options=-XX:+UseG1GC" in finalContent)
        } finally {
            Files.deleteIfExists(cfgPath)
            Files.deleteIfExists(tempDir)
        }
    }

    /**
     * 校验剪贴板无锁探测器在序号未变或剪贴板不含文件时直接短路，不触碰 AWT 大数据拷贝。
     */
    @Test
    fun clipboardServiceSkipsReadWhenSequenceUnchangedOrNoNativeFiles() = runBlocking {
        val probe = FakeClipboardChangeProbe(sequence = 10L, hasFiles = false)
        val service = JvmSystemFileClipboardService(changeProbe = probe)

        // 首次检测：不含原生文件，零拷贝返回空快照并记住序号 10。
        val first = service.readIfChanged().getOrThrow()
        assertNotNull(first)
        assertTrue(first.locations.isEmpty())
        assertEquals(1, probe.sequenceCalls.get())
        assertEquals(1, probe.formatCalls.get())

        // 序号未变化：直接返回 null，不再探测格式也不访问 AWT。
        val unchanged = service.readIfChanged().getOrThrow()
        assertNull(unchanged)
        assertEquals(2, probe.sequenceCalls.get())
        assertEquals(1, probe.formatCalls.get())

        // 外部复制截图导致序号递增但依然无 CF_HDROP：返回空快照，绝不触发 AWT 拷贝。
        probe.sequence = 11L
        val screenshot = service.readIfChanged().getOrThrow()
        assertNotNull(screenshot)
        assertTrue(screenshot.locations.isEmpty())
        assertEquals(2, probe.formatCalls.get())
    }

    /**
     * 校验应用进入后台托盘（`activeFlow == false`）时剪贴板、目录轮询与设备轮询均彻底暂停，唤醒后恢复。
     */
    @Test
    fun backgroundModePausesClipboardDirectoryAndStoragePollingAndTrimsInactiveCaches() = runBlocking {
        val foreground = MutableStateFlow(false)
        val probe = FakeClipboardChangeProbe(sequence = 1L, hasFiles = false)
        val clipboardScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val manager = ClipboardManager(
            fileRepository = JvmLocalFileProvider(),
            pathService = JvmVfsPathService(),
            systemClipboard = JvmSystemFileClipboardService(probe),
        )
        manager.bind(clipboardScope, foreground)

        val dirObserver = JvmDirectoryObservationService(
            pathService = JvmVfsPathService(),
            pollingPolicy = DirectoryPollingPolicy(activeIntervalMillis = 30, idleIntervalMillis = 30, failureIntervalMillis = 30),
            isForeground = foreground,
        )
        val verifyCount = AtomicInteger(0)
        val dirJob = launch {
            dirObserver.observe("test://remote-dir").collect { event ->
                if (event == DirectoryWatchEvent.Verify) verifyCount.incrementAndGet()
            }
        }

        val storageDiscoverCount = AtomicInteger(0)
        val storageService = JvmStorageDeviceService(FakeCountingStorageBackend(storageDiscoverCount))
        storageService.setForegroundActive(false)
        val storageJob = launch { storageService.observe().collect {} }

        try {
            delay(250)
            assertEquals(0, probe.sequenceCalls.get(), "后台托盘期间不应轮询剪贴板")
            assertEquals(0, verifyCount.get(), "后台托盘期间不应触发目录 Verify 轮询")
            assertEquals(0, storageDiscoverCount.get(), "后台托盘期间不应执行盘符周期发现")

            // 模拟窗口从托盘唤醒回前台，各项监控应立即恢复。
            foreground.value = true
            storageService.setForegroundActive(true)
            withTimeout(3_000) {
                while (probe.sequenceCalls.get() == 0 || verifyCount.get() == 0 || storageDiscoverCount.get() == 0) {
                    delay(20)
                }
            }
            assertTrue(probe.sequenceCalls.get() >= 1)
            assertTrue(verifyCount.get() >= 1)
            assertTrue(storageDiscoverCount.get() >= 1)
        } finally {
            dirJob.cancelAndJoin()
            storageJob.cancelAndJoin()
            storageService.close()
            clipboardScope.cancel()
        }

        // 校验 DirectoryContentStore.trimInactiveCaches 仅释放非活动历史目录，保留当前活动目录。
        val root = Files.createTempDirectory("onyx-trim-cache-")
        val activeDir = Files.createDirectory(root.resolve("active"))
        val inactiveDir = Files.createDirectory(root.resolve("inactive"))
        val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val store = DirectoryContentStore(JvmLocalFileProvider(), storeScope)
            val activeSub = store.subscribe(activeDir.toString())
            val inactiveSub = store.subscribe(inactiveDir.toString())
            store.settled(activeDir.toString())
            store.settled(inactiveDir.toString())
            inactiveSub.close()
            assertNotNull(store.cached(inactiveDir.toString()))

            store.trimInactiveCaches()
            assertNotNull(store.cached(activeDir.toString()))
            assertNull(store.cached(inactiveDir.toString()))
            activeSub.close()
        } finally {
            storeScope.cancel()
            Files.deleteIfExists(activeDir)
            Files.deleteIfExists(inactiveDir)
            Files.deleteIfExists(root)
        }
    }

    /**
     * 校验内存文件集合仓库与系统文件物化器按有界 LRU 淘汰并清理磁盘临时目录。
     */
    @Test
    fun collectionRepositoryAndMaterializerEnforceBoundedLruAndCleanupTempDirs() = runBlocking {
        val repo = InMemoryFileCollectionRepository(maxCollections = 2)
        repo.save(FileCollection("c1", "First", FileCollectionKind.SEARCH_RESULT, emptyList()))
        repo.save(FileCollection("c2", "Second", FileCollectionKind.SEARCH_RESULT, emptyList()))
        // 访问 c1 刷新 LRU，随后写入 c3 应淘汰 c2。
        assertNotNull(repo.findById("c1"))
        repo.save(FileCollection("c3", "Third", FileCollectionKind.SEARCH_RESULT, emptyList()))
        assertNotNull(repo.findById("c1"))
        assertNull(repo.findById("c2"))
        assertNotNull(repo.findById("c3"))

        val contentService = FakeBoundedContentService()
        val materializer = JvmSystemFileMaterializer(
            archiveService = ArchiveService(),
            contentServices = listOf(contentService),
            maxCachedEntries = 2,
        )
        try {
            val f1 = materializer.materialize(remoteFile("s3://bucket/a.txt", "a.txt")).getOrThrow()
            val f2 = materializer.materialize(remoteFile("s3://bucket/b.txt", "b.txt")).getOrThrow()
            val path1 = Path.of(f1.location)
            val path2 = Path.of(f2.location)
            assertTrue(Files.isRegularFile(path1))
            assertTrue(Files.isRegularFile(path2))

            // 写入第 3 个文件时应立即淘汰并清理最早的临时文件目录。
            val f3 = materializer.materialize(remoteFile("s3://bucket/c.txt", "c.txt")).getOrThrow()
            val path3 = Path.of(f3.location)
            assertFalse(Files.exists(path1), "超容淘汰的物化临时文件应立即删除")
            assertTrue(Files.isRegularFile(path2))
            assertTrue(Files.isRegularFile(path3))

            materializer.close()
            assertFalse(Files.exists(path2), "close() 后应清理剩余临时文件")
            assertFalse(Files.exists(path3), "close() 后应清理剩余临时文件")
        } finally {
            materializer.close()
        }
    }

    /**
     * 校验 `maxHeapMemoryMb` 设置字段的规范化、重置、三路合并及设置页手动输入交互。
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun settingsMaxHeapMemorySupportsManualInputSanitizeMergeAndReset() =
        runDesktopComposeUiTest(width = 880, height = 680) {
            assertEquals(
                OnyxSettings.MIN_MAX_HEAP_MEMORY_MB,
                OnyxSettings(maxHeapMemoryMb = 16).sanitizeRootSettings().maxHeapMemoryMb,
            )
            assertEquals(
                OnyxSettings.MAX_MAX_HEAP_MEMORY_MB,
                OnyxSettings(maxHeapMemoryMb = 99_999).sanitizeRootSettings().maxHeapMemoryMb,
            )
            val custom = OnyxSettings(maxHeapMemoryMb = 2048)
            assertEquals(
                OnyxSettings.DEFAULT_MAX_HEAP_MEMORY_MB,
                custom.resetCategoryToDefaults(SettingsCategory.SHORTCUTS_ADVANCED).maxHeapMemoryMb,
            )
            val merged = OnyxSettings(maxHeapMemoryMb = 1024).mergeEditableSettingsFrom(
                draft = OnyxSettings(maxHeapMemoryMb = 3072),
                initialDraft = OnyxSettings(maxHeapMemoryMb = 1024),
            )
            assertEquals(3072, merged.maxHeapMemoryMb)

            var latestSettings = OnyxSettings(maxHeapMemoryMb = 1024)
            setContent {
                var draft by remember { mutableStateOf(latestSettings) }
                latestSettings = draft
                OnyxWindowTheme(settings = draft) {
                    val appearance = LocalOnyxAppearance.current
                    SettingsDialogLayout(
                        title = "设置",
                        draft = draft,
                        category = SettingsCategory.SHORTCUTS_ADVANCED,
                        onCategoryChange = {},
                        onDraftChange = { next -> draft = next },
                        onCleanupInvalidLocations = {},
                        onConfirm = {},
                        onDismiss = {},
                        bodyFontSize = appearance.listFontSize,
                        labelFontSize = appearance.headerFontSize,
                        memoryStatus = JvmMemoryStatus(
                            activeMaxHeapMb = 1024,
                            usedHeapMb = 180,
                            configuredMaxHeapMb = 1024,
                            launcherConfigWritable = true,
                        ),
                    )
                }
            }

            waitForIdle()
            onNodeWithTag("settings-xmx-input").performTextClearance()
            onNodeWithTag("settings-xmx-input").performTextInput("2560")
            waitForIdle()
            assertEquals(2560, latestSettings.maxHeapMemoryMb)
        }

    private companion object {
        /** 构造远程测试文件条目。 */
        fun remoteFile(location: String, name: String): VFile = VFile(
            id = location,
            name = name,
            location = location,
            parentLocation = location.substringBeforeLast('/'),
            kind = VFileKind.FILE,
            sizeBytes = 5L,
            modifiedAtEpochMillis = 0L,
            hidden = false,
            capabilities = setOf(VFileCapability.READ_CONTENT),
        )
    }
}

/** 可控剪贴板无锁探测测试桩，用于验证序号未变或无文件格式时的零拷贝短路。 */
private class FakeClipboardChangeProbe(
    /** 当前模拟的系统剪贴板序号。 */
    @Volatile var sequence: Long?,
    /** 当前模拟的 `CF_HDROP` 可用状态。 */
    @Volatile var hasFiles: Boolean?,
) : ClipboardChangeProbe {
    /** 序号探测被调用的次数。 */
    val sequenceCalls = AtomicInteger(0)

    /** 文件格式探测被调用的次数。 */
    val formatCalls = AtomicInteger(0)

    override fun sequenceNumber(): Long? {
        sequenceCalls.incrementAndGet()
        return sequence
    }

    override fun hasNativeFileList(): Boolean? {
        formatCalls.incrementAndGet()
        return hasFiles
    }
}

/** 计数型存储设备后端测试桩，验证托盘后台期间停止周期发现。 */
private class FakeCountingStorageBackend(
    /** `discover()` 被调用的累计次数。 */
    private val discoverCount: AtomicInteger,
) : StorageDeviceBackend {
    override fun discover(): List<StorageDevice> {
        discoverCount.incrementAndGet()
        return listOf(
            StorageDevice(
                id = "test-disk",
                name = "Test Disk",
                kind = StorageDeviceKind.LOCAL,
                locations = emptyList(),
                state = StorageDeviceState.MOUNTED,
                actions = emptySet(),
            ),
        )
    }

    override fun execute(device: StorageDevice, action: StorageDeviceAction): String? = null

    override fun changes(): Flow<Unit> = emptyFlow()
}

/** 最小远程内容测试桩，供物化器 LRU 淘汰验证使用。 */
private class FakeBoundedContentService : RoutableVfsContentService {
    override fun supports(location: String): Boolean = location.startsWith("s3://")

    override suspend fun readFile(entry: VFile): Result<VfsContentSource> {
        val bytes = "hello".encodeToByteArray()
        return Result.success(
            VfsContentSource(
                name = entry.name,
                sizeBytes = bytes.size.toLong(),
                chunks = flowOf(bytes),
            ),
        )
    }

    override suspend fun writeFile(
        parentLocation: String,
        name: String,
        chunks: Flow<ByteArray>,
        conflictStrategy: TransferConflictStrategy,
    ): Result<VFile?> = Result.failure(UnsupportedOperationException())
}
