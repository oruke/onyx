package com.oruke.onyx.app.component

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import com.oruke.onyx.app.component.delegate.ClipboardManager
import com.oruke.onyx.app.component.delegate.FileActionDelegate
import com.oruke.onyx.app.component.delegate.FileActionDelegateCallbacks
import com.oruke.onyx.app.component.delegate.FileTransferDelegate
import com.oruke.onyx.app.component.delegate.TaskOrchestrator
import com.oruke.onyx.app.filesystem.*
import com.oruke.onyx.core.model.*
import com.oruke.onyx.shared.filesystem.DirectoryContentStore
import com.oruke.onyx.ui.ResourceEntryNameSuggestionService
import com.oruke.onyx.vfs.api.*
import com.oruke.onyx.vfs.archive.ArchiveService
import com.oruke.onyx.vfs.local.JvmLocalFileProvider
import com.oruke.onyx.vfs.local.LocalVfsProvider
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlin.test.assertIs

/** 真实文件读取的确定性闸门，用于验证迟到快照与任务取消。 */
internal class BrowsingReadGate(
    /** 是否在放行前取得旧快照。 */
    val captureBeforeRelease: Boolean = false,
) {
    /** 读取已经进入仓储。 */
    val started = CompletableDeferred<Unit>()
    /** 测试允许读取返回。 */
    val release = CompletableDeferred<Unit>()
}

/**
 * 包装生产本地仓储，只提供计数、失败和延迟控制，真实目录结果仍来自 NIO Provider。
 * @param root 隔离的默认目录。
 * @param provider 真实文件查询实现。
 */
internal class BrowsingRepository(private val root: String, private val provider: JvmLocalFileProvider) : FileRepository {
    /** 每个目录的实际列举请求数。 */
    val reads = ConcurrentHashMap<String, AtomicInteger>()
    /** 下一次列举使用的一次性闸门。 */
    val gates = ConcurrentHashMap<String, BrowsingReadGate>()
    /** 需要返回明确错误的目录。 */
    val failing = ConcurrentHashMap.newKeySet<String>()

    /**
     * 计数并经过闸门读取真实目录。
     * @param location 请求位置。
     * @return 真实目录快照或本场景指定的读取失败。
     */
    override suspend fun list(location: String): Result<List<VFile>> {
        reads.computeIfAbsent(location) { AtomicInteger() }.incrementAndGet()
        val gate = gates.remove(location)
        val captured = if (gate?.captureBeforeRelease == true) provider.list(location) else null
        gate?.started?.complete(Unit)
        gate?.release?.await()
        return if (location in failing) Result.failure(IOException("controlled read failure"))
            else captured ?: provider.list(location)
    }

    /**
     * 直接查询生产文件元数据。
     * @param location 请求条目。
     * @return 真实条目查询结果。
     */
    override suspend fun query(location: String): Result<FileEntryLookup> = provider.query(location)

    /** 返回隔离样本目录，禁止测试打开用户默认目录。 */
    override fun defaultLocation(): String = root
}

/**
 * 真实双面板、统一文件命令、后台任务和共享目录存储的业务集成环境。
 */
internal class DirectoryBrowsingFixture(
    /** 是否注册真实系统目录观察。 */
    observe: Boolean = false,
    /** 由设备场景管理生命周期的共享生产服务。 */
    private val storageDevices: StorageDeviceService? = null,
    /** 生产路径服务，设备场景使用桌面虚拟位置装饰器。 */
    pathService: VfsPathService = JvmVfsPathService(),
    /** 控制通知先后次序的观察边界；文件查询和命令仍使用生产 Provider。 */
    observationService: DirectoryObservationService? = null,
) {
    /** 本场景唯一拥有的可回收样本根。 */
    val workspace: Path = Files.createTempDirectory("onyx-browsing-")
    /** 源目录。 */
    val source: Path = Files.createDirectory(workspace.resolve("source"))
    /** 目标及无关面板目录。 */
    val target: Path = Files.createDirectory(workspace.resolve("target"))
    /** 真实本地能力。 */
    val provider = JvmLocalFileProvider()
    /** 可统计真实列举的仓储。 */
    val repository = BrowsingRepository(source.toString(), provider)
    /** 与测试主线程共用的作用域。 */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** 主面板生命周期。 */
    private val primaryLifecycle = LifecycleRegistry()
    /** 次面板生命周期。 */
    private val secondaryLifecycle = LifecycleRegistry()
    /** 生产统一路径服务。 */
    val paths = pathService
    /** 两面板共享的内容与观察存储。 */
    val store = DirectoryContentStore(repository, scope,
        observationService ?: if (observe) JvmDirectoryObservationService(paths) else null)
    /** 生产后台任务状态。 */
    val tasks = TaskOrchestrator(scope)
    /** 不触发系统剪贴板写入的内部业务剪贴板。 */
    private val clipboard = ClipboardManager(repository, paths)
    /** 文件操作对话框状态。 */
    val dialog = MutableStateFlow<RootDialogState?>(null)
    /** 源面板。 */
    val primary = pane(PaneId.PRIMARY, source, primaryLifecycle)
    /** 目标面板。 */
    val secondary = pane(PaneId.SECONDARY, target, secondaryLifecycle)

    init { primaryLifecycle.resume(); secondaryLifecycle.resume() }

    /**
     * 装配使用真实生产 Provider 的面板。
     * @param id 面板角色。
     * @param location 隔离目录。
     * @param lifecycle 独立生命周期。
     * @return 真实组件。
     */
    private fun pane(id: PaneId, location: Path, lifecycle: LifecycleRegistry): DefaultPaneComponent {
        val external = JvmDesktopExternalOpenService(fileOpenLauncher =
            SystemFileOpenLauncher { error("Unexpected external open") })
        return DefaultPaneComponent(DefaultComponentContext(lifecycle), id, location.toString(), repository, provider,
            JvmTextClipboardService(), external, paths, ResourceEntryNameSuggestionService(), JvmFileTypeService(),
            JvmArchiveEntryOpenService(ArchiveService(), external), directoryStore = store,
            storageDevices = storageDevices)
    }

    /**
     * 创建生产删除委托，允许在真实命令边界注入部分失败或取消。
     * @param commands 真实命令或带可控闸门的生产命令委托。
     * @return 同步真实文件和组件结果的业务入口。
     */
    fun actions(commands: FileCommandService = provider): FileActionDelegate = FileActionDelegate(
        commands, JvmDesktopTrashService(), tasks, dialog, FileActionDelegateCallbacks(
            onDirectoryChange = store::apply, onRefreshDirectories = store::invalidate,
            getPaneState = { if (it == PaneId.PRIMARY) primary.state.value else secondary.state.value },
            onRemovalStarted = primary::captureRemoval, onRemovalFinished = ::finishRemoval))

    /** 创建真实传输任务委托，包括冲突预检、实际传输及最终选择续接。 */
    fun transfers(): FileTransferDelegate = FileTransferDelegate(scope,
        ProviderBackedFileCommandService(listOf(provider)), repository, tasks, clipboard, dialog, paths,
        VfsProviderRegistry(listOf(LocalVfsProvider(provider))), onDirectoryChange = store::apply,
        onRefreshDirectories = store::invalidate, onRemovalStarted = primary::captureRemoval,
        onRemovalFinished = ::finishRemoval)

    /**
     * 等待真实校验再把确认消失的结果交给生产选择续接逻辑。
     * @param context 发起访问。
     * @param ids 已确认移除身份。
     * @param verify 是否需要底层目录校验。
     */
    private fun finishRemoval(context: PaneRemovalContext?, ids: Set<String>, verify: Boolean) {
        if (context == null) return
        scope.launch {
            val removed = ids.toMutableSet()
            if (verify) {
                val settled = store.settled(context.location)
                if (settled.failure == null) removed += context.targetIds - settled.entries.orEmpty().map { it.id }.toSet()
            }
            primary.completeRemoval(context, removed)
        }
    }

    /**
     * 创建真实样本并等待列表与磁盘相符。
     * @param count 顺序文件数。
     * @return 已加载的源条目。
     */
    suspend fun populate(count: Int = 80): List<VFile> {
        withContext(Dispatchers.IO) {
            repeat(count) { Files.writeString(source.resolve("item-${it.toString().padStart(4, '0')}.txt"), "sample-$it") }
        }
        primary.dispatch(PaneIntent.Refresh)
        secondary.awaitBrowsing { it.entriesState is PaneEntriesState.Ready }
        return primary.awaitBrowsing { (it.entriesState as? PaneEntriesState.Ready)?.entries?.size == count }
            .let { assertIs<PaneEntriesState.Ready>(it.entriesState).entries }
    }

    /** 先释放所有观察和任务，再清理本测试创建的唯一样本目录。 */
    suspend fun close() {
        primaryLifecycle.destroy(); secondaryLifecycle.destroy()
        scope.coroutineContext[Job]?.cancelAndJoin()
        withContext(Dispatchers.IO) {
            Files.walk(workspace).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}

/**
 * 等待组件可观察的最终状态，不能用命令返回代替业务验证。
 * @param predicate 必须成立的业务状态。
 * @return 满足断言条件的真实面板状态。
 */
internal suspend fun PaneComponent.awaitBrowsing(predicate: (PaneState) -> Boolean): PaneState =
    withTimeout(15_000) { state.first(predicate) }

/**
 * 在真实桌面主线程运行一次隔离业务场景，异常时同样回收任务和样本。
 * @param observe 是否注册真实系统观察。
 * @param scenario 完整业务流程与最终磁盘断言。
 */
internal fun browsingScenario(observe: Boolean = false, scenario: suspend DirectoryBrowsingFixture.() -> Unit) = runBlocking {
    withContext(Dispatchers.Main) {
        val fixture = DirectoryBrowsingFixture(observe)
        try { fixture.scenario() } finally { fixture.close() }
    }
}
