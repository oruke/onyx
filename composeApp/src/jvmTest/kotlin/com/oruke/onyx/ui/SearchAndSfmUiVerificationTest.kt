package com.oruke.onyx.ui

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.oruke.onyx.App
import com.oruke.onyx.app.component.PaneEntriesState
import com.oruke.onyx.app.component.PaneIntent
import com.oruke.onyx.app.component.RootComponent
import com.oruke.onyx.app.component.RootIntent
import com.oruke.onyx.app.component.RootLaunchConfiguration
import com.oruke.onyx.app.component.SearchNavigationFeedback
import com.oruke.onyx.app.component.SearchNavigationKind
import com.oruke.onyx.app.component.SearchPanelState
import com.oruke.onyx.app.component.SearchStatus
import com.oruke.onyx.app.component.SessionRestoreState
import com.oruke.onyx.app.component.rememberRootApplicationRuntime
import com.oruke.onyx.app.component.rememberRootComponent
import com.oruke.onyx.app.platform.ExternalFileDragService
import com.oruke.onyx.app.platform.JvmApplicationLocaleController
import com.oruke.onyx.core.model.AppLocale
import com.oruke.onyx.core.model.AppSessionSnapshot
import com.oruke.onyx.core.model.BackgroundTask
import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.core.model.OnyxThemeMode
import com.oruke.onyx.core.model.PaneLayoutMode
import com.oruke.onyx.core.model.SearchScope
import com.oruke.onyx.core.model.StorageDevice
import com.oruke.onyx.core.model.StorageDeviceAction
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.VFileCapability
import com.oruke.onyx.core.model.VFileKind
import com.oruke.onyx.di.fileModule
import com.oruke.onyx.ui.theme.OnyxLocaleEnvironment
import com.oruke.onyx.ui.theme.OnyxWindowTheme
import com.oruke.onyx.vfs.api.SessionRepository
import com.oruke.onyx.vfs.api.SettingsRepository
import com.oruke.onyx.vfs.api.StorageDeviceService
import com.oruke.onyx.vfs.api.TaskPersistenceRepository
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.koin.compose.KoinIsolatedContext
import org.koin.compose.getKoin
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/** 验证全局搜索面板交互重设计、真实面板 SFM 文件名渲染、悬浮筛选按回车进目录自动收起及键盘焦点恢复。 */
@OptIn(ExperimentalTestApi::class)
class SearchAndSfmUiVerificationTest {
    @Test
    fun rendersRedesignedSearchPanelWithSfmDecodedNamesQuickActionsAndFeedback() = runDesktopComposeUiTest(
        width = 860,
        height = 560,
    ) {
        val preferences = MutableStateFlow(OnyxSettings(themeMode = OnyxThemeMode.DARK))
        val parentDir = """Z:\unsorted\video\里番\とるだ屋"""
        val dirCaps = setOf(VFileCapability.LIST_CHILDREN)
        val sfmFolder = VFile(
            id = "dir-sfm",
            name = "我说过要戴安全套吧..\uF029",
            location = """$parentDir\我说过要戴安全套吧..""" + "\uF029",
            parentLocation = parentDir,
            kind = VFileKind.DIRECTORY,
            sizeBytes = null,
            modifiedAtEpochMillis = 1_763_470_000_000L,
            hidden = false,
            capabilities = dirCaps,
        )
        val siblingQuestionFolder = VFile(
            id = "dir-question",
            name = "大哥哥...你是一个人吗\uF025",
            location = """$parentDir\大哥哥...你是一个人吗""" + "\uF025",
            parentLocation = parentDir,
            kind = VFileKind.DIRECTORY,
            sizeBytes = null,
            modifiedAtEpochMillis = 1_763_460_000_000L,
            hidden = false,
            capabilities = dirCaps,
        )
        val videoFile = VFile(
            id = "file-ep1",
            name = "安全套吧 第1話 [1080p].mp4",
            location = """${sfmFolder.location}\安全套吧 第1話 [1080p].mp4""",
            parentLocation = sfmFolder.location,
            kind = VFileKind.FILE,
            sizeBytes = 684_510_208L,
            modifiedAtEpochMillis = 1_763_472_000_000L,
            hidden = false,
            capabilities = emptySet(),
        )
        var focusMainClicks = 0
        var locatedEntry: VFile? = null

        setContent {
            val localeController = remember(preferences) { JvmApplicationLocaleController(preferences) }
            val localeScope = rememberCoroutineScope()
            DisposableEffect(localeController, localeScope) {
                val binding = localeController.bind(localeScope)
                onDispose(binding::cancel)
            }
            val currentSettings by preferences.collectAsState()
            var searchState by remember {
                mutableStateOf(
                    SearchPanelState(
                        visible = true,
                        query = "安全套",
                        rootLocation = parentDir,
                        scope = SearchScope.CURRENT_DIRECTORY,
                        status = SearchStatus.COMPLETED,
                        results = listOf(sfmFolder, siblingQuestionFolder, videoFile),
                        scannedEntryCount = 128,
                        selectedIndex = 0,
                        keepWindowOpen = true,
                        lastNavigationFeedback = SearchNavigationFeedback(
                            kind = SearchNavigationKind.OPENED_DIRECTORY,
                            targetLabel = "我说过要戴安全套吧...",
                        ),
                    )
                )
            }
            OnyxLocaleEnvironment(localeController.appliedLocale) {
                OnyxWindowTheme(currentSettings) {
                    OnyxTooltipHost {
                        SearchPanel(
                            state = searchState,
                            presentation = SearchPanelPresentation(
                                searchHistory = emptyList(),
                                locationLabel = { loc -> loc },
                            ),
                            actions = SearchPanelActions(
                                onQueryChange = { q -> searchState = searchState.copy(query = q) },
                                onSearch = {},
                                onCancel = {},
                                onClose = {},
                                onOpenResult = {},
                                onOpenResultInFolder = { entry -> locatedEntry = entry },
                                onUpdateScope = {},
                                onUpdateFilters = {},
                                onSelectIndex = { idx -> searchState = searchState.copy(selectedIndex = idx) },
                                onSetAdvancedFiltersExpanded = {},
                                onSetIncludeRemoteLocations = {},
                                onSetKeepWindowOpen = { keep -> searchState = searchState.copy(keepWindowOpen = keep) },
                                onFocusMainWindow = { focusMainClicks += 1 },
                                onUseCurrentDirectory = {},
                                onOpenResultsAsCollection = {},
                                onClearHistory = {},
                            ),
                            listState = rememberLazyListState(),
                        )
                    }
                }
            }
        }
        waitForIdle()

        onNodeWithText("我说过要戴安全套吧...").assertIsDisplayed()
        onNodeWithText("大哥哥...你是一个人吗?").assertIsDisplayed()
        onNodeWithTag("search-keep-open").assertIsDisplayed()
        onNodeWithTag("search-result-open-dir-sfm").assertIsDisplayed()
        onNodeWithTag("search-result-locate-dir-sfm").assertIsDisplayed()
        onNodeWithTag("search-navigation-feedback").assertIsDisplayed()

        saveOffscreenScreenshot("search-panel-redesigned-dark.png", onRoot().captureToImage().toAwtImage())

        onNodeWithTag("search-result-locate-dir-sfm").performClick()
        assertEquals(sfmFolder, locatedEntry)
        onNodeWithTag("search-focus-main-window").performClick()
        assertEquals(1, focusMainClicks)
    }

    /**
     * 挂载真实 App + DefaultPaneComponent + PaneSurface：
     * 1. 验证真实文件面板中 SFM 文件夹名称（`..▯` / `?`）无损可读还原；
     * 2. 打开悬浮筛选框输入“安全套”，验证列表真实过滤收敛为 1 项且计数“1/5”，无表头压盖并抓取真实截图；
     * 3. 在筛选输入框聚焦态直接按 Enter 进入该 SFM 目录，验证底层路径保留原始 U+F029、筛选浮层自动清空收起，
     *    且面板主列表立即恢复键盘焦点（方向键可直接选中目录内子文件）。
     */
    @Test
    fun realPaneSurfaceFiltersSfmDirectoriesEntersOnEnterClearsOverlayAndRestoresKeyboardFocus() {
        val tempRoot = Files.createTempDirectory("onyx-sfm-pane-ui-")
        try {
            val studioDir = Files.createDirectories(tempRoot.resolve("とるだ屋"))
            Files.createDirectories(studioDir.resolve("产子岛 每周7天都可生产的雌性们"))
            Files.createDirectories(studioDir.resolve("受孕屋"))
            Files.createDirectories(studioDir.resolve("大哥哥...你是一个人吗\uF025"))
            val sfmTargetDir = Files.createDirectories(studioDir.resolve("我说过要戴安全套吧..\uF029"))
            Files.createDirectories(studioDir.resolve("泡在我家的辣妹可以让我随意使用小穴"))
            Files.writeString(sfmTargetDir.resolve("第1話 [1080p].mp4"), "video-bytes")

            val settingsRepo = IsolatedSettingsRepo()
            val sessionRepo = IsolatedSessionRepo()
            val tasksRepo = IsolatedTasksRepo()

            runDesktopComposeUiTest(width = 920, height = 420) {
                var capturedRoot: RootComponent? = null
                setContent {
                    val isolatedKoin = remember {
                        koinApplication {
                            allowOverride(true)
                            modules(fileModule)
                            modules(
                                module {
                                    single<SettingsRepository> { settingsRepo }
                                    single<SessionRepository> { sessionRepo }
                                    single<TaskPersistenceRepository> { tasksRepo }
                                    single<StorageDeviceService> { EmptyStorageDevices }
                                },
                            )
                        }
                    }
                    DisposableEffect(isolatedKoin) { onDispose(isolatedKoin::close) }
                    KoinIsolatedContext(context = isolatedKoin) {
                        val koin = getKoin()
                        val runtime = rememberRootApplicationRuntime()
                        val drag = remember { koin.get<ExternalFileDragService>() }
                        val launch = remember {
                            RootLaunchConfiguration(
                                initialLocation = studioDir.toString(),
                                persistsMainSession = false,
                            )
                        }
                        val rootComponent = rememberRootComponent(runtime, drag, launch)
                        SideEffect { capturedRoot = rootComponent }
                        DisposableEffect(drag) { onDispose(drag::dispose) }
                        App(rootComponent, drag)
                    }
                }

                waitUntil(timeoutMillis = 15_000L) {
                    val ready = capturedRoot?.state?.value?.sessionRestoreState == SessionRestoreState.Ready
                    val entries = (capturedRoot?.primaryPane?.state?.value?.entriesState as? PaneEntriesState.Ready)?.entries
                    ready && entries?.size == 5
                }
                val root = checkNotNull(capturedRoot)
                runOnUiThread {
                    root.dispatch(
                        RootIntent.UpdateSettings(
                            OnyxSettings(
                                preferredLocale = AppLocale.SIMPLIFIED_CHINESE,
                                themeMode = OnyxThemeMode.DARK,
                                defaultLayoutMode = PaneLayoutMode.SINGLE,
                                sidebarVisible = false,
                            )
                        )
                    )
                    root.dispatch(RootIntent.SetLayoutMode(PaneLayoutMode.SINGLE))
                    root.secondaryPane.dispatch(PaneIntent.OpenDirectory(studioDir.toString()))
                }
                waitUntil(timeoutMillis = 5_000L) {
                    root.state.value.layoutMode == PaneLayoutMode.SINGLE &&
                        root.state.value.settings.themeMode == OnyxThemeMode.DARK &&
                        !root.state.value.settings.sidebarVisible
                }
                waitForIdle()

                // 初始列表同时验证两个 SFM 映射目录名均已还原为正常标点，无豆腐块乱码
                onNodeWithText("我说过要戴安全套吧...", useUnmergedTree = true).assertIsDisplayed()
                onNodeWithText("大哥哥...你是一个人吗?", useUnmergedTree = true).assertIsDisplayed()

                // 呼出右上角悬浮筛选框并真实键入“安全套”
                runOnUiThread { root.primaryPane.dispatch(PaneIntent.ShowFilterInput) }
                waitForIdle()
                onNodeWithTag("pane-filter-input", useUnmergedTree = true).performTextInput("安全套")
                waitUntil(timeoutMillis = 5_000L) {
                    val visible = (root.primaryPane.state.value.entriesState as? PaneEntriesState.Ready)?.entries
                    visible?.size == 1 && visible.single().name == "我说过要戴安全套吧..\uF029"
                }
                waitForIdle()

                onNodeWithTag("pane-filter-count", useUnmergedTree = true).assertTextEquals("1/5")
                onNodeWithText("我说过要戴安全套吧...", useUnmergedTree = true).assertIsDisplayed()
                onNodeWithText("受孕屋", useUnmergedTree = true).assertDoesNotExist()
                saveOffscreenScreenshot("pane-filter-and-sfm-dark.png", onRoot().captureToImage().toAwtImage())

                // 在筛选输入框持有焦点时直接按 Enter 进入匹配子目录
                onNodeWithTag("pane-filter-input", useUnmergedTree = true).performKeyInput { pressKey(Key.Enter) }
                waitUntil(timeoutMillis = 5_000L) {
                    val state = root.primaryPane.state.value
                    val entries = (state.entriesState as? PaneEntriesState.Ready)?.entries
                    state.location == sfmTargetDir.toString() && entries?.size == 1
                }
                waitForIdle()

                val enteredState = root.primaryPane.state.value
                assertTrue(enteredState.location.endsWith("\uF029"), "底层路径必须保留原始 U+F029 码位")
                assertFalse(enteredState.filterInputVisible)
                assertEquals("", enteredState.filterQuery)
                onNodeWithTag("pane-filter-overlay", useUnmergedTree = true).assertDoesNotExist()
                onNodeWithText("第1話 [1080p].mp4", useUnmergedTree = true).assertIsDisplayed()

                // 验证筛选浮层卸载后文件面板已取回键盘焦点：按方向下键可直接选中新目录内的视频条目
                onRoot().performKeyInput { pressKey(Key.DirectionDown) }
                waitUntil(timeoutMillis = 5_000L) {
                    root.primaryPane.state.value.selectedEntryIds.size == 1
                }
            }
        } finally {
            deleteTreeQuietly(tempRoot)
        }
    }

    private fun saveOffscreenScreenshot(fileName: String, image: BufferedImage) {
        val output = Path.of("build", "reports", "search-sfm-offscreen", fileName)
        Files.createDirectories(output.parent)
        assertTrue(ImageIO.write(image, "png", output.toFile()))
    }

    private class IsolatedSettingsRepo : SettingsRepository {
        private var settings = OnyxSettings(
            preferredLocale = AppLocale.SIMPLIFIED_CHINESE,
            themeMode = OnyxThemeMode.DARK,
            defaultLayoutMode = PaneLayoutMode.SINGLE,
            sidebarVisible = false,
        )

        override suspend fun loadSettings(): Result<OnyxSettings?> = Result.success(settings)

        override suspend fun saveSettings(settings: OnyxSettings): Result<Unit> {
            this.settings = settings
            return Result.success(Unit)
        }
    }

    private class IsolatedSessionRepo : SessionRepository {
        override suspend fun loadSession(): Result<AppSessionSnapshot?> = Result.success(null)
        override suspend fun saveSession(snapshot: AppSessionSnapshot): Result<Unit> = Result.success(Unit)
    }

    private class IsolatedTasksRepo : TaskPersistenceRepository {
        override suspend fun loadTasks(): Result<List<BackgroundTask>> = Result.success(emptyList())
        override suspend fun saveTasks(tasks: List<BackgroundTask>): Result<Unit> = Result.success(Unit)
    }

    private object EmptyStorageDevices : StorageDeviceService {
        override fun cachedSnapshot(): List<StorageDevice> = emptyList()
        override fun observe(): Flow<Result<List<StorageDevice>>> = flowOf(Result.success(emptyList()))
        override suspend fun snapshot(): Result<List<StorageDevice>> = Result.success(emptyList())
        override suspend fun execute(deviceId: String, action: StorageDeviceAction): Result<String?> =
            Result.success(null)
    }

    private companion object {
        fun deleteTreeQuietly(root: Path) {
            runCatching {
                if (!Files.exists(root)) return
                Files.walk(root).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }
}
