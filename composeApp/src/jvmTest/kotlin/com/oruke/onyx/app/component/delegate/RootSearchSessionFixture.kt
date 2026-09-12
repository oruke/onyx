package com.oruke.onyx.app.component.delegate

import com.oruke.onyx.app.component.PaneChromeState
import com.oruke.onyx.app.component.PaneComponent
import com.oruke.onyx.app.component.PaneIntent
import com.oruke.onyx.app.component.PaneState
import com.oruke.onyx.app.component.SearchPanelMode
import com.oruke.onyx.app.component.SearchPanelState
import com.oruke.onyx.app.component.createDefaultPaneTabState
import com.oruke.onyx.core.model.OnyxSettings
import com.oruke.onyx.core.model.PaneId
import com.oruke.onyx.core.model.VFile
import com.oruke.onyx.core.model.ViewMode
import com.oruke.onyx.shared.usecase.FileCollectionUseCase
import com.oruke.onyx.shared.usecase.FileSearchUseCase
import com.oruke.onyx.shared.usecase.InMemoryFileCollectionRepository
import com.oruke.onyx.vfs.api.FileRepository
import com.oruke.onyx.vfs.local.JvmLocalFileProvider
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/**
 * 两个真实目录、独立会话与可控读取闸门组成的搜索业务测试环境。
 *
 * @param owner 使用单线程事件循环驱动组件状态的测试作用域。
 */
internal class RootSearchSessionFixture(owner: CoroutineScope) {
    /** 隔离本场景创建的文件，清理仅作用于这个临时目录。 */
    private val workspace = Files.createTempDirectory("onyx-search-session-")
    /** 排在多根列表首位的真实目录。 */
    val firstRoot: String = Files.createDirectory(workspace.resolve("first")).toString()
    /** 排在多根列表后位的真实目录。 */
    val secondRoot: String = Files.createDirectory(workspace.resolve("second")).toString()
    /** 在释放测试文件前等待所有扫描子任务终止。 */
    private val job = SupervisorJob(owner.coroutineContext[Job])
    /** 与调用方共享单线程事件循环的组件作用域。 */
    private val scope = CoroutineScope(owner.coroutineContext + job)
    /** 通过统一仓储调用真实本地 Provider 的可控扫描入口。 */
    val repository = ControlledSessionSearchRepository(firstRoot)
    /** 两个委托共享的无状态搜索用例。 */
    private val searchUseCase = FileSearchUseCase(repository)
    /** 保存每次固定搜索结果的真实内存集合仓储。 */
    val collections = InMemoryFileCollectionRepository()
    /** 记录结果打开与创建集合标签的统一导航意图。 */
    val navigation = mutableListOf<PaneIntent>()
    /** 下一次显式绑定目录时使用的活动位置。 */
    var activeRoot = firstRoot
    /** 下一次显式绑定目录时使用的活动面板。 */
    var activePane = PaneId.PRIMARY

    init {
        listOf(firstRoot, secondRoot).forEach { location ->
            val root = java.nio.file.Path.of(location)
            Files.writeString(root.resolve("report-alpha.txt"), "first document")
            Files.writeString(root.resolve("report-beta.txt"), "second document")
        }
    }

    /**
     * 创建仅拥有自身状态和运行标识的搜索会话。
     *
     * @param mode 固定为完整搜索或快速打开。
     * @return 共享业务用例但独立运行的搜索委托。
     */
    fun delegate(mode: SearchPanelMode = SearchPanelMode.SEARCH_PANEL): RootSearchDelegate {
        val pane = recordingSessionPane(navigation)
        return RootSearchDelegate(
            scope = scope,
            fileSearchUseCase = searchUseCase,
            fileCollectionUseCase = FileCollectionUseCase(collections),
            paneState = { paneId -> sessionPaneState(paneId, activeRoot) },
            activatePane = {},
            paneComponent = { pane },
            getActivePane = { activePane },
            settings = { OnyxSettings() },
            filesystemRoots = { listOf(firstRoot, secondRoot) },
            onRecordSearchHistory = {},
            mode = mode,
        )
    }

    /** 先终止真实扫描，再逐项删除本测试专有的临时目录和文件。 */
    suspend fun dispose() {
        job.cancelAndJoin()
        Files.walk(workspace).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
    }
}

/** 记录指定真实目录读取的开始、放行及退出状态。 */
internal class SessionSearchReadGate {
    /** 仓储已进入目标目录时完成。 */
    val started = CompletableDeferred<Unit>()
    /** 测试允许真实目录读取继续时完成。 */
    val release = CompletableDeferred<Unit>()
    /** 读取正常结束或被取消后完成。 */
    val exited = CompletableDeferred<Unit>()
}

/**
 * 在真实文件读取边界提供取消闸门，并允许以本地目录替代网络测试环境。
 *
 * @param initialLocation 测试默认根，确保不访问用户默认目录。
 */
internal class ControlledSessionSearchRepository(private val initialLocation: String) : FileRepository {
    /** 实际列举测试文件的生产 Provider。 */
    private val provider = JvmLocalFileProvider()
    /** 按原请求位置设置的读取闸门。 */
    val gates = ConcurrentHashMap<String, SessionSearchReadGate>()
    /** 网络协议测试的受控本地替代位置。 */
    val aliases = ConcurrentHashMap<String, String>()
    /** 实际到达仓储的扫描请求，用于验证授权边界。 */
    val requestedLocations = CopyOnWriteArrayList<String>()

    /**
     * 记录扫描请求并等待可选闸门，随后读取真实文件系统。
     *
     * @param location 搜索用例请求的根位置。
     * @return 生产 Provider 返回的目录条目或错误。
     */
    override suspend fun list(location: String): Result<List<VFile>> {
        requestedLocations += location
        val gate = gates[location]
        gate?.started?.complete(Unit)
        return try {
            gate?.release?.await()
            provider.list(aliases[location] ?: location)
        } finally {
            gate?.exited?.complete(Unit)
        }
    }

    /** @return 测试创建的默认目录，不触达用户数据。 */
    override fun defaultLocation(): String = initialLocation
}

/**
 * 等待真实异步搜索发布满足条件的可观察状态。
 *
 * @param predicate 本场景要求的业务结果条件。
 * @return 首个满足条件的搜索状态。
 */
internal suspend fun RootSearchDelegate.awaitSessionState(predicate: (SearchPanelState) -> Boolean): SearchPanelState =
    withTimeout(5_000L) { searchState.first(predicate) }

/**
 * 使用实际面板状态结构提供当前目录。
 *
 * @param paneId 来源面板角色。
 * @param location 当前测试目录。
 * @return 可供生产搜索委托绑定的面板快照。
 */
private fun sessionPaneState(paneId: PaneId, location: String): PaneState {
    val tab = createDefaultPaneTabState("session-test", "Documents", location, ViewMode.DETAILS)
    return PaneState(
        paneId = paneId,
        chromeState = PaneChromeState(activeTabId = "session-test"),
        activeTabState = tab.tabState,
    )
}

/**
 * 记录统一面板导航接口的输出，不替代搜索或集合存储业务。
 *
 * @param navigation 接收搜索动作的真实意图列表。
 * @return 只允许分发意图的面板边界。
 */
private fun recordingSessionPane(navigation: MutableList<PaneIntent>): PaneComponent = PaneComponent::class.java.cast(
    Proxy.newProxyInstance(
        PaneComponent::class.java.classLoader, arrayOf(PaneComponent::class.java),
    ) { _, method, args ->
        check(method.name == "dispatch") { "Unexpected pane operation: ${method.name}" }
        navigation += args?.single() as PaneIntent
        null
    },
)
