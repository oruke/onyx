package com.oruke.onyx

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.oruke.onyx.app.component.PaneChromeState
import com.oruke.onyx.app.component.PaneComponent
import com.oruke.onyx.app.component.PaneIntent
import com.oruke.onyx.app.component.PaneState
import com.oruke.onyx.app.component.SearchPanelMode
import com.oruke.onyx.app.component.createDefaultPaneTabState
import com.oruke.onyx.app.component.delegate.RootSearchDelegate
import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.ViewMode
import com.oruke.onyx.shared.usecase.FileCollectionUseCase
import com.oruke.onyx.shared.usecase.FileSearchUseCase
import com.oruke.onyx.shared.usecase.InMemoryFileCollectionRepository
import com.oruke.onyx.ui.SearchPanelActions
import com.oruke.onyx.ui.SearchPanelPresentation
import com.oruke.onyx.vfs.api.FileRepository
import com.oruke.onyx.vfs.local.JvmLocalFileProvider
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.intui.standalone.theme.default
import org.jetbrains.jewel.intui.standalone.theme.lightThemeDefinition
import org.jetbrains.jewel.intui.window.decoratedWindow
import org.jetbrains.jewel.intui.window.styling.lightWithLightHeader
import org.jetbrains.jewel.ui.ComponentStyling
import org.jetbrains.jewel.window.styling.TitleBarStyle

/**
 * 用真实本地搜索、集合仓储及生产委托构造窗口验收依赖。
 *
 * @param root JUnit 创建并负责回收的独立临时目录。
 * @param releaseNested 可选的子目录读取闸门，用于观察窗口隐藏期间的实际搜索进度。
 */
internal class SearchWindowFixture(root: Path, releaseNested: CompletableDeferred<Unit>? = null) {
    /** 搜索业务协程与组件状态更新使用的主线程作用域。 */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 测试可调整的真实窗口外观设置。 */
    val preferences = MutableStateFlow(OnyxSettings())

    /** 生产用例保存搜索结果快照的真实集合仓储。 */
    val collections = InMemoryFileCollectionRepository()

    /** 观察发往统一文件面板 API 的导航结果，不触发外部文件应用。 */
    val navigation = mutableListOf<PaneIntent>()

    /** 子目录实际开始读取时完成，保证生命周期断言覆盖运行中的任务。 */
    val nestedStarted = CompletableDeferred<Unit>()

    /** 本次搜索根的绝对位置，完全由临时目录动态派生。 */
    val rootLocation = root.toAbsolutePath().toString()

    /** 生产搜索业务委托，直接提供窗口状态与全部操作。 */
    val delegate: RootSearchDelegate

    /** 与生产窗口装配相同的状态动作映射。 */
    val actions: SearchPanelActions

    init {
        // 先准备真实目录及可识别结果，再将可控读取环节插入统一仓储边界。
        Files.writeString(root.resolve("report-alpha.txt"), "first searchable document")
        Files.writeString(root.resolve("report-beta-with-a-long-descriptive-name-for-window-layout.txt"), "second document")
        val nested = Files.createDirectory(root.resolve("nested"))
        Files.writeString(nested.resolve("report-gamma.txt"), "nested searchable document")
        val repository = WindowSearchRepository(nested.toString(), releaseNested, nestedStarted)
        val pane = recordingSearchPane(navigation)
        delegate = RootSearchDelegate(
            scope = scope,
            fileSearchUseCase = FileSearchUseCase(repository),
            fileCollectionUseCase = FileCollectionUseCase(collections),
            paneState = { searchWindowPaneState(rootLocation) },
            activatePane = {},
            paneComponent = { pane },
            getActivePane = { PaneId.PRIMARY },
            settings = { preferences.value },
            filesystemRoots = { listOf(rootLocation) },
            onRecordSearchHistory = {},
            mode = SearchPanelMode.SEARCH_PANEL,
        )
        actions = SearchPanelActions(
            onQueryChange = delegate::updateSearchQuery,
            onSearch = delegate::executeSearch,
            onCancel = delegate::cancelSearch,
            onClose = delegate::closeSearchPanel,
            onOpenResult = delegate::openSearchResult,
            onOpenResultInFolder = delegate::openSearchResultInFolder,
            onUpdateScope = delegate::updateSearchScope,
            onUpdateFilters = delegate::updateSearchFilters,
            onSelectIndex = delegate::setSelectedIndex,
            onSetAdvancedFiltersExpanded = delegate::setAdvancedFiltersExpanded,
            onSetIncludeRemoteLocations = delegate::setIncludeRemoteLocations,
            onUseCurrentDirectory = delegate::useCurrentDirectory,
            onOpenResultsAsCollection = delegate::openSearchResultsAsCollection,
            onClearHistory = {},
        )
    }
}

/**
 * 使用生产原生窗口、主题和真实业务状态构造等价的应用级集成入口。
 *
 * @param fixture 本次验收的真实搜索依赖和可观察状态。
 * @return 无返回值。
 */
@Composable
internal fun SearchWindowTestApplication(fixture: SearchWindowFixture) {
    val state by fixture.delegate.searchState.collectAsState()
    val settings by fixture.preferences.collectAsState()
    IntUiTheme(
        theme = JewelTheme.lightThemeDefinition(),
        styling = ComponentStyling.default().decoratedWindow(titleBarStyle = TitleBarStyle.lightWithLightHeader()),
    ) {
        SearchWindow(
            state = state,
            presentation = SearchPanelPresentation(emptyList()) { location ->
                if (location == fixture.rootLocation) "Search documents" else "Search documents/nested"
            },
            actions = fixture.actions,
            settings = settings,
        )
    }
}

/**
 * 在真实本地目录读取前插入可取消的挂起点，其余行为全部使用生产本地 Provider。
 */
private class WindowSearchRepository(
    /** 需要观察后台继续读取行为的子目录。 */
    private val nestedLocation: String,
    /** 可选继续读取信号；为空时立即读取。 */
    private val releaseNested: CompletableDeferred<Unit>?,
    /** 记录搜索已进入子目录的信号。 */
    private val nestedStarted: CompletableDeferred<Unit>,
) : FileRepository {
    /** 实际执行文件系统 I/O 的生产 Provider。 */
    private val localProvider = JvmLocalFileProvider()

    /**
     * 等待测试闸门后调用真实目录读取，保留协程取消语义。
     *
     * @param location 生产搜索用例请求读取的目录。
     * @return 真实文件系统中的文件条目或读取失败。
     */
    override suspend fun list(location: String): Result<List<VFile>> {
        if (location == nestedLocation) {
            nestedStarted.complete(Unit)
            releaseNested?.await()
        }
        return localProvider.list(location)
    }

    /**
     * 返回测试子目录，避免默认位置指向用户文件。
     *
     * @return 本次测试控制的目录。
     */
    override fun defaultLocation(): String = nestedLocation
}

/**
 * 记录统一面板意图作为导航边界的可观察业务结果。
 *
 * @param navigation 依次接收打开文件、定位文件及创建集合标签的意图。
 * @return 只允许调用 dispatch 的面板接口。
 */
private fun recordingSearchPane(navigation: MutableList<PaneIntent>): PaneComponent {
    return PaneComponent::class.java.cast(
        Proxy.newProxyInstance(PaneComponent::class.java.classLoader, arrayOf(PaneComponent::class.java)) { _, method, args ->
            check(method.name == "dispatch") { "Unexpected pane operation: ${method.name}" }
            navigation += args?.single() as PaneIntent
            null
        },
    )
}

/**
 * 提供绑定到临时目录的真实面板状态结构。
 *
 * @param location 本次测试的搜索来源目录。
 * @return 能被生产搜索委托读取的面板快照。
 */
private fun searchWindowPaneState(location: String): PaneState {
    val tab = createDefaultPaneTabState("search-window-test", "Documents", location, ViewMode.DETAILS)
    return PaneState(
        paneId = PaneId.PRIMARY,
        chromeState = PaneChromeState(activeTabId = "search-window-test"),
        activeTabState = tab.tabState,
    )
}
