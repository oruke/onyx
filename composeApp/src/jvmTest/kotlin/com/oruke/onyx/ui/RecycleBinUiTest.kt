package com.oruke.onyx.ui

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.oruke.onyx.App
import com.oruke.onyx.app.component.PaneEntriesState
import com.oruke.onyx.app.component.PaneIntent
import com.oruke.onyx.app.component.RootComponent
import com.oruke.onyx.app.component.RootDialogState
import com.oruke.onyx.app.component.RootIntent
import com.oruke.onyx.app.component.RootLaunchConfiguration
import com.oruke.onyx.app.component.SessionRestoreState
import com.oruke.onyx.app.component.rememberRootApplicationRuntime
import com.oruke.onyx.app.component.rememberRootComponent
import com.oruke.onyx.app.platform.ExternalFileDragService
import com.oruke.onyx.core.model.AppLocale
import com.oruke.onyx.core.model.AppSessionSnapshot
import com.oruke.onyx.core.model.BackgroundTask
import com.oruke.onyx.core.model.OnyxSettings
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
import com.oruke.onyx.vfs.api.TrashItem
import com.oruke.onyx.vfs.api.TrashMoveRecord
import com.oruke.onyx.vfs.api.TrashRestorationStatus
import com.oruke.onyx.vfs.api.TrashRestoreOutcome
import com.oruke.onyx.vfs.api.TrashService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.koin.compose.KoinIsolatedContext
import org.koin.compose.getKoin
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 回收站桌面端 Compose 界面渲染与交互集成验收测试。
 *
 * 通过临时目录与 KoinIsolatedContext 完全隔离宿主机真实回收站，挂载真实 App 界面组件树，
 * 验证侧边栏导航、操作栏按钮、视觉截图留档、还原与清空回收站全链路交互。
 */
@OptIn(ExperimentalTestApi::class)
class RecycleBinUiTest {

    /**
     * 验证回收站完整界面流：侧栏进入、操作栏按钮断言、画面截图落盘、选中条目还原及清空确认。
     */
    @Test
    fun recycleBinUiAcceptanceFlow() {
        val tempDir = Files.createTempDirectory("onyx-recycle-bin-ui-test-")
        try {
            val localDir = Files.createDirectories(tempDir.resolve("local"))
            val trashStorageDir = Files.createDirectories(tempDir.resolve("isolated-trash"))
            val isolatedTrashService = IsolatedUiTrashService(trashStorageDir)
            val memorySettings = InMemoryUiSettingsRepository()
            val memorySession = InMemoryUiSessionRepository()
            val memoryTasks = InMemoryUiTaskPersistenceRepository()

            val sample1 = Files.writeString(localDir.resolve("sample-1.txt"), "sample 1 content")
            val sample2 = Files.writeString(localDir.resolve("sample-2.txt"), "sample 2 content")
            val vfile1 = VFile(
                id = sample1.toString(),
                name = "sample-1.txt",
                location = sample1.toString(),
                parentLocation = localDir.toString(),
                kind = VFileKind.FILE,
                sizeBytes = 16L,
                modifiedAtEpochMillis = 1000L,
                hidden = false,
                capabilities = emptySet(),
            )
            val vfile2 = VFile(
                id = sample2.toString(),
                name = "sample-2.txt",
                location = sample2.toString(),
                parentLocation = localDir.toString(),
                kind = VFileKind.FILE,
                sizeBytes = 16L,
                modifiedAtEpochMillis = 2000L,
                hidden = false,
                capabilities = emptySet(),
            )
            isolatedTrashService.addInitialItem(sample1, vfile1)
            isolatedTrashService.addInitialItem(sample2, vfile2)

            runDesktopComposeUiTest(width = 1280, height = 800) {
                var capturedRoot: RootComponent? = null
                setContent {
                    val isolatedKoinApplication = remember {
                        koinApplication {
                            allowOverride(true)
                            modules(fileModule)
                            modules(
                                module {
                                    single<TrashService> { isolatedTrashService }
                                    single<SettingsRepository> { memorySettings }
                                    single<SessionRepository> { memorySession }
                                    single<TaskPersistenceRepository> { memoryTasks }
                                    single<StorageDeviceService> { NoopUiStorageDeviceService }
                                },
                            )
                        }
                    }
                    DisposableEffect(isolatedKoinApplication) {
                        onDispose(isolatedKoinApplication::close)
                    }
                    KoinIsolatedContext(context = isolatedKoinApplication) {
                        val koin = getKoin()
                        val runtime = rememberRootApplicationRuntime()
                        val drag = remember { koin.get<ExternalFileDragService>() }
                        val launch = remember {
                            RootLaunchConfiguration(
                                initialLocation = localDir.toString(),
                                persistsMainSession = false,
                            )
                        }
                        val rootComponent = rememberRootComponent(runtime, drag, launch)
                        SideEffect {
                            capturedRoot = rootComponent
                        }
                        DisposableEffect(drag) {
                            onDispose(drag::dispose)
                        }
                        App(rootComponent, drag)
                    }
                }

                waitUntil(timeoutMillis = 15_000L) {
                    capturedRoot?.state?.value?.sessionRestoreState == SessionRestoreState.Ready
                }
                val root = checkNotNull(capturedRoot)

                onNodeWithTag("sidebar-trash", useUnmergedTree = true).performClick()
                waitUntil(timeoutMillis = 10_000L) {
                    root.primaryPane.state.value.location == TRASH_LOCATION &&
                        root.primaryPane.state.value.entriesState is PaneEntriesState.Ready &&
                        (root.primaryPane.state.value.entriesState as PaneEntriesState.Ready).entries.size == 2
                }
                waitForIdle()

                onNodeWithTag("trash-action-bar", useUnmergedTree = true).assertIsDisplayed()
                onNodeWithTag("trash-action-restore-selected", useUnmergedTree = true).assertIsDisplayed()
                onNodeWithTag("trash-action-restore-all", useUnmergedTree = true).assertIsDisplayed()
                onNodeWithTag("trash-action-delete-permanently", useUnmergedTree = true).assertIsDisplayed()
                onNodeWithTag("trash-action-empty", useUnmergedTree = true).assertIsDisplayed()

                val targetEntry = (root.primaryPane.state.value.entriesState as PaneEntriesState.Ready).entries
                    .first { it.name == "sample-1.txt" }
                runOnUiThread {
                    root.primaryPane.dispatch(PaneIntent.SelectEntry(targetEntry.id))
                }
                waitForIdle()

                val screenshot = onRoot().captureToImage().toAwtImage()
                val screenshotPath = Paths.get("build", "browsing-acceptance", "recycle-bin-ui.png")
                screenshotPath.parent?.let(Files::createDirectories)
                assertTrue(ImageIO.write(screenshot, "png", screenshotPath.toFile()))
                assertTrue(Files.exists(screenshotPath) && Files.size(screenshotPath) > 0L)

                onNodeWithTag("trash-action-restore-selected", useUnmergedTree = true).performClick()

                waitUntil(timeoutMillis = 10_000L) {
                    val ready = root.primaryPane.state.value.entriesState as? PaneEntriesState.Ready
                    ready != null && ready.entries.none { it.id == targetEntry.id }
                }
                waitForIdle()
                assertTrue(Files.exists(localDir.resolve("sample-1.txt")), "已还原项目应重新出现在原临时目录")

                onNodeWithTag("trash-action-empty", useUnmergedTree = true).performClick()
                waitUntil(timeoutMillis = 5_000L) {
                    root.state.value.dialogState is RootDialogState.EmptyTrashConfirmation
                }
                val dialog = root.state.value.dialogState
                assertIs<RootDialogState.EmptyTrashConfirmation>(dialog)

                runOnUiThread {
                    root.dispatch(RootIntent.ConfirmDialog)
                }
                waitUntil(timeoutMillis = 10_000L) {
                    val ready = root.primaryPane.state.value.entriesState as? PaneEntriesState.Ready
                    ready != null && ready.entries.isEmpty()
                }
                waitForIdle()
                assertEquals(
                    0,
                    kotlinx.coroutines.runBlocking { isolatedTrashService.listItems().getOrThrow().size },
                    "隔离回收站应被清空",
                )
            }
        } finally {
            deleteRecursivelyQuietly(tempDir)
        }
    }

    private class InMemoryUiSettingsRepository : SettingsRepository {
        var current: OnyxSettings = OnyxSettings(preferredLocale = AppLocale.SIMPLIFIED_CHINESE)
        override suspend fun loadSettings(): Result<OnyxSettings?> = Result.success(current)
        override suspend fun saveSettings(settings: OnyxSettings): Result<Unit> {
            current = settings
            return Result.success(Unit)
        }
    }

    private class InMemoryUiSessionRepository : SessionRepository {
        var current: AppSessionSnapshot? = null
        override suspend fun loadSession(): Result<AppSessionSnapshot?> = Result.success(current)
        override suspend fun saveSession(snapshot: AppSessionSnapshot): Result<Unit> {
            current = snapshot
            return Result.success(Unit)
        }
    }

    private class InMemoryUiTaskPersistenceRepository : TaskPersistenceRepository {
        override suspend fun loadTasks(): Result<List<BackgroundTask>> = Result.success(emptyList())
        override suspend fun saveTasks(tasks: List<BackgroundTask>): Result<Unit> = Result.success(Unit)
    }

    private object NoopUiStorageDeviceService : StorageDeviceService {
        override fun cachedSnapshot(): List<StorageDevice> = emptyList()
        override fun observe(): Flow<Result<List<StorageDevice>>> = flowOf(Result.success(emptyList()))
        override suspend fun snapshot(): Result<List<StorageDevice>> = Result.success(emptyList())
        override suspend fun execute(deviceId: String, action: StorageDeviceAction): Result<String?> = Result.success(null)
    }

    private class IsolatedUiTrashService(
        private val trashDir: Path,
    ) : TrashService {
        override val isSupported: Boolean = true

        private data class StoredItem(
            val item: TrashItem,
            val trashedPath: Path,
        )

        private val lock = Any()
        private val items = LinkedHashMap<String, StoredItem>()

        fun addInitialItem(sourcePath: Path, vfile: VFile) = synchronized(lock) {
            val normalizedSource = sourcePath.normalize().toAbsolutePath()
            val target = trashDir.resolve("${System.nanoTime()}-${normalizedSource.fileName}")
            Files.move(normalizedSource, target)
            val id = buildTrashItemLocation(target.fileName.toString())
            val trashItem = TrashItem(
                id = id,
                displayName = vfile.name,
                originalLocation = normalizedSource.toString(),
                originalParentLocation = normalizedSource.parent?.toString(),
                kind = vfile.kind,
                sizeBytes = vfile.sizeBytes,
                deletedAtEpochMillis = System.currentTimeMillis(),
            )
            items[id] = StoredItem(trashItem, target)
        }

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
